/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ratis.logservice.server;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import com.codahale.metrics.Timer;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.logservice.api.LogInfo;
import org.apache.ratis.logservice.api.LogName;
import org.apache.ratis.logservice.api.LogStream;
import org.apache.ratis.logservice.common.Constants;
import org.apache.ratis.logservice.common.LogAlreadyExistException;
import org.apache.ratis.logservice.common.LogNotFoundException;
import org.apache.ratis.logservice.common.NoEnoughWorkersException;
import org.apache.ratis.logservice.metrics.LogServiceMetricsRegistry;
import org.apache.ratis.logservice.proto.LogServiceProtos;
import org.apache.ratis.logservice.proto.MetaServiceProtos;
import org.apache.ratis.logservice.proto.MetaServiceProtos.CreateLogRequestProto;
import org.apache.ratis.logservice.proto.MetaServiceProtos.DeleteLogRequestProto;
import org.apache.ratis.logservice.proto.MetaServiceProtos.LogServicePingRequestProto;
import org.apache.ratis.logservice.proto.MetaServiceProtos.LogServiceRegisterLogRequestProto;
import org.apache.ratis.logservice.proto.MetaServiceProtos.LogServiceUnregisterLogRequestProto;
import org.apache.ratis.logservice.proto.MetaServiceProtos.MetaSMRequestProto;
import org.apache.ratis.logservice.util.LogServiceProtoUtil;
import org.apache.ratis.logservice.util.MetaServiceProtoUtil;
import org.apache.ratis.metrics.RatisMetricRegistry;
import org.apache.ratis.metrics.impl.RatisMetricRegistryImpl;
import org.apache.ratis.proto.RaftProtos;
import org.apache.ratis.protocol.*;


import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.thirdparty.com.google.protobuf.InvalidProtocolBufferException;
import org.apache.ratis.util.AutoCloseableLock;
import org.apache.ratis.util.Daemon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * State Machine serving meta data for LogService. It persists the pairs 'log name' -> RaftGroup
 * During the start basing on the persisted data it would be able to build a list of the existing servers.
 * Requests from clients for DDL operations are handled by query mechanism (so only the leader accept that.
 * It performs the operation (such as Log creation) and sends a message with the log -> group pair to itself
 * to persis this data internally and on followers.
 */

public class MetaStateMachine extends BaseStateMachine {

    Logger LOG = LoggerFactory.getLogger(MetaStateMachine.class);


    //Persisted map between log and RaftGroup
    private Map<LogName, RaftGroup> map = new ConcurrentHashMap<>();
    // List of the currently known peers.
    private final Set<RaftPeer> peers = new HashSet<>();

    // keep a copy of raftServer to get group information.
    private RaftServer raftServer;

    private Map<RaftPeer, Set<LogName>> peerLogs = new ConcurrentHashMap<>();

    private Map<RaftPeer, Long> heartbeatInfo = new ConcurrentHashMap<>();

    private RaftGroup currentGroup = null;

    private Daemon peerHealthChecker = null;
    // MinHeap queue for load balancing groups across the peers
    private long failureDetectionPeriod = Constants.DEFAULT_PEER_FAILURE_DETECTION_PERIOD;
    private PriorityBlockingQueue<PeerGroups> avail = new PriorityBlockingQueue<PeerGroups>();

    //Properties
    private RaftProperties properties = new RaftProperties();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    private RaftGroupId metadataGroupId;
    private RaftGroupId logServerGroupId;
    private RatisMetricRegistry metricRegistry;

    public MetaStateMachine(RaftGroupId metadataGroupId, RaftGroupId logServerGroupId,
                            long failureDetectionPeriod) {
      this.metadataGroupId = metadataGroupId;
      this.logServerGroupId = logServerGroupId;
      this.failureDetectionPeriod = failureDetectionPeriod;
    }

    @Override
    public void initialize(RaftServer server, RaftGroupId groupId, RaftStorage storage) throws IOException {
        this.raftServer = server;
        this.metricRegistry = LogServiceMetricsRegistry
            .createMetricRegistryForLogServiceMetaData(server.getId().toString());
        super.initialize(server, groupId, storage);
        peerHealthChecker = new Daemon(new PeerHealthChecker(),"peer-Health-Checker");
        peerHealthChecker.start();
    }

    @Override
    public TransactionContext applyTransactionSerial(TransactionContext trx) {
        RaftProtos.LogEntryProto x = trx.getLogEntry();
        MetaSMRequestProto req = null;
        try {
            req = MetaSMRequestProto.parseFrom(x.getStateMachineLogEntry().getLogData());
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
        }
        switch (req.getTypeCase()) {
            case REGISTERREQUEST:
                LogServiceRegisterLogRequestProto r = req.getRegisterRequest();
                LogName logname = LogServiceProtoUtil.toLogName(r.getLogname());
                RaftGroup rg = MetaServiceProtoUtil.toRaftGroup(r.getRaftGroup());
                rg.getPeers().stream().forEach(raftPeer -> {
                    Set<LogName> logNames;
                    if(!peerLogs.containsKey(raftPeer)) {
                        logNames = new HashSet<>();
                        peerLogs.put(raftPeer, logNames);
                    } else {
                        logNames = peerLogs.get(raftPeer);
                    }
                    logNames.add(logname);

                });
                map.put(logname, rg);

                LOG.info("Log {} registered at {} with group {} ", logname, getId(), rg );
                break;
            case UNREGISTERREQUEST:
                LogServiceUnregisterLogRequestProto unregReq = req.getUnregisterRequest();
                logname = LogServiceProtoUtil.toLogName(unregReq.getLogname());
                map.remove(logname);
                break;
            case PINGREQUEST:
                LogServicePingRequestProto pingRequest = req.getPingRequest();
                RaftPeer peer = MetaServiceProtoUtil.toRaftPeer(pingRequest.getPeer());
                if (peers.contains(peer)) {
                    //Do Nothing, that's just heartbeat
                } else {
                    peers.add(peer);
                    avail.add(new PeerGroups(peer));
                    heartbeatInfo.put(peer,  System.currentTimeMillis());
                }
                break;
            case HEARTBEATREQUEST:
                MetaServiceProtos.LogServiceHeartbeatRequestProto heartbeatRequest = req.getHeartbeatRequest();
                RaftPeer heartbeatPeer = MetaServiceProtoUtil.toRaftPeer(heartbeatRequest.getPeer());
                heartbeatInfo.put(heartbeatPeer,  System.currentTimeMillis());
                break;
            default:
        }
        return super.applyTransactionSerial(trx);
    }

    @Override
    public TransactionContext startTransaction(RaftClientRequest request) throws IOException {
        return super.startTransaction(request);
    }

    @Override
    public TransactionContext preAppendTransaction(TransactionContext trx) throws IOException {
        return super.preAppendTransaction(trx);
    }

    @Override
    public CompletableFuture<Message> queryStale(Message request, long minIndex) {
        return super.queryStale(request, minIndex);
    }

    @Override
    public CompletableFuture<Message> applyTransaction(TransactionContext trx) {
        return super.applyTransaction(trx);
    }

    @Override
    public CompletableFuture<Message> query(Message request) {
        Timer.Context timerContext = null;
        MetaServiceProtos.MetaServiceRequestProto.TypeCase type = null;
        try {
            if (currentGroup == null) {
                try {
                    List<RaftGroup> x =
                        StreamSupport.stream(raftServer.getGroups().spliterator(), false)
                            .filter(group -> group.getGroupId().equals(metadataGroupId)).collect(Collectors.toList());
                    if (x.size() == 1) {
                        currentGroup = x.get(0);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            RaftProperties properties = new RaftProperties();
            MetaServiceProtos.MetaServiceRequestProto req = null;
            try {
                req = MetaServiceProtos.MetaServiceRequestProto.parseFrom(request.getContent());
            } catch (InvalidProtocolBufferException e) {
                e.printStackTrace();
            }
            type = req.getTypeCase();
            timerContext = metricRegistry.timer(type.name()).time();
            switch (type) {

            case CREATELOG:
                return processCreateLogRequest(req);
            case LISTLOGS:
                return processListLogsRequest();
            case GETLOG:
                return processGetLogRequest(req);
            case DELETELOG:
                return processDeleteLog(req);
            default:
            }
            CompletableFuture<Message> reply = super.query(request);
            return reply;
        }finally{
            if (timerContext != null) {
                timerContext.stop();
            }
        }
    }



    private CompletableFuture<Message>
    processDeleteLog(MetaServiceProtos.MetaServiceRequestProto logServiceRequestProto) {
        DeleteLogRequestProto deleteLog = logServiceRequestProto.getDeleteLog();
        LogName logName = LogServiceProtoUtil.toLogName(deleteLog.getLogName());
        RaftGroup raftGroup = map.get(logName);
        if (raftGroup == null) {
            return CompletableFuture.completedFuture(Message.valueOf(
                    MetaServiceProtoUtil.toDeleteLogExceptionReplyProto(
                            new LogNotFoundException(logName.getName())).build().toByteString()));
        } else {
            Collection<RaftPeer> peers = raftGroup.getPeers();
            peers.stream().forEach(peer -> {
                RaftClient client = RaftClient.newBuilder()
                        .setProperties(properties)
                        .setClientId(ClientId.randomId())
                        .setRaftGroup(RaftGroup.valueOf(logServerGroupId, peer))
                        .build();
                try {
                    client.groupRemove(raftGroup.getGroupId(), true, peer.getId());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            RaftClient client = RaftClient.newBuilder()
                    .setRaftGroup(currentGroup)
                    .setClientId(ClientId.randomId())
                    .setProperties(properties)
                    .build();
            try {
                client.send(() -> MetaServiceProtos.MetaSMRequestProto.newBuilder()
                        .setUnregisterRequest(
                                LogServiceUnregisterLogRequestProto.newBuilder()
                                        .setLogname(LogServiceProtoUtil.toLogNameProto(logName)))
                        .build().toByteString());
            } catch (IOException e) {
                LOG.error(
                    "Exception while unregistring raft group with Metadata Service during deletion of log");
                e.printStackTrace();
            }
        }
        return CompletableFuture.completedFuture(Message.valueOf(
                MetaServiceProtoUtil.toDeleteLogReplyProto().toByteString()));
    }

//    private CompletableFuture<Message> processCloseLog(MetaServiceProtos.MetaServiceRequestProto logServiceRequestProto) {
//        CloseLogRequestProto closeLog = logServiceRequestProto.getCloseLog();
//        LogName logName = LogServiceProtoUtil.toLogName(closeLog.getLogName());
//        // Need to check whether the file is opened if opened close it.
//        // TODO need to handle exceptions while operating with files.
//        return CompletableFuture.completedFuture(Message
//                .valueOf(CloseLogReplyProto.newBuilder().build().toByteString()));
//    }


//    private CompletableFuture<Message> processGetStateRequest(
//            MetaServiceProtos.MetaServiceRequestProto logServiceRequestProto) {
//        MetaServiceProtos.GetStateRequestProto getState = logServiceRequestProto.getGetState();
//        LogName logName = LogServiceProtoUtil.toLogName(getState.getLogName());
//        return CompletableFuture.completedFuture(Message.valueOf(LogServiceProtoUtil
//                .toGetStateReplyProto(true).toByteString()));
//    }
//
    private CompletableFuture<Message> processCreateLogRequest(
            MetaServiceProtos.MetaServiceRequestProto logServiceRequestProto) {
        LogName name;
        try (final AutoCloseableLock writeLock = writeLock()) {
            CreateLogRequestProto createLog = logServiceRequestProto.getCreateLog();
            name = LogServiceProtoUtil.toLogName(createLog.getLogName());
            if(map.containsKey(name)) {
                return CompletableFuture.completedFuture(Message.valueOf(MetaServiceProtoUtil
                        .toCreateLogExceptionReplyProto(new LogAlreadyExistException(name.getName()))
                        .build()
                        .toByteString()));
            }
            // Check that we have at least 3 nodes
            if (avail.size() < 3) {
                return CompletableFuture.completedFuture(Message.valueOf(MetaServiceProtoUtil
                .toCreateLogExceptionReplyProto(new NoEnoughWorkersException(avail.size()))
                        .build()
                        .toByteString()));
            } else {
                List<PeerGroups> peerGroup = IntStream.range(0, 3).mapToObj(i -> avail.poll()).collect(Collectors.toList());
                List<RaftPeer> peers = peerGroup.stream().map(obj -> obj.getPeer()).collect(Collectors.toList());
                RaftGroup raftGroup = RaftGroup.valueOf(RaftGroupId.randomId(), peers);
                peerGroup.stream().forEach(pg -> {
                    pg.getGroups().add(raftGroup);
                    avail.add(pg);
                });
                peers.forEach(i -> {
                    RaftClient client = RaftClient.newBuilder().setProperties(properties)
                      .setRaftGroup(RaftGroup.valueOf(logServerGroupId, i)).build();
                    try {
                        client.groupAdd(raftGroup, i.getId());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
                RaftClient client = RaftClient.newBuilder()
                        .setRaftGroup(currentGroup)
                        .setClientId(ClientId.randomId())
                        .setProperties(properties)
                        .build();
                try {
                    client.send(() -> MetaServiceProtos.MetaSMRequestProto.newBuilder()
                            .setRegisterRequest(LogServiceRegisterLogRequestProto.newBuilder()
                                    .setLogname(LogServiceProtoUtil.toLogNameProto(name))
                                    .setRaftGroup(MetaServiceProtoUtil
                                            .toRaftGroupProto(raftGroup)))
                            .build().toByteString());
                } catch (IOException e) {
                    LOG.error(
                        "Exception while registering raft group with Metadata Service during creation of log");
                    e.printStackTrace();
                }
                return CompletableFuture.completedFuture(Message.valueOf(MetaServiceProtoUtil
                        .toCreateLogReplyProto(new LogInfo((name), raftGroup)).build().toByteString()));
            }
        }
    }


    private AutoCloseableLock writeLock() {
        return AutoCloseableLock.acquire(lock.writeLock());
    }

    private CompletableFuture<Message> processListLogsRequest() {
        return CompletableFuture.completedFuture(Message.valueOf(MetaServiceProtoUtil
                .toListLogLogsReplyProto(
                        map.entrySet()
                                .stream()
                                .map(log -> new LogInfo(log.getKey(), log.getValue()))
                                .collect(Collectors.toList())).toByteString()));
    }

    private CompletableFuture<Message> processGetLogRequest(
            MetaServiceProtos.MetaServiceRequestProto logServiceRequestProto) {
        MetaServiceProtos.GetLogRequestProto getLog = logServiceRequestProto.getGetLog();
        LogName logName = LogServiceProtoUtil.toLogName(getLog.getLogName());
        RaftGroup raftGroup = map.get(logName);
        if (raftGroup != null) {
            return CompletableFuture.completedFuture(Message.valueOf(
                    MetaServiceProtoUtil.toGetLogReplyProto(new LogInfo(logName, raftGroup))
                            .toByteString()));
        } else {
            return CompletableFuture.completedFuture(Message.valueOf(
                    MetaServiceProtoUtil.toGetLogExceptionReplyProto(
                            new LogNotFoundException(logName.getName())).build().toByteString()));
        }
    }


    class PeerGroups implements Comparable{
        RaftPeer peer;
        Set<RaftGroup> groups = new HashSet<>();

        public PeerGroups(RaftPeer peer) {
            this.peer = peer;
        }

        public Set<RaftGroup> getGroups () {
            return groups;
        }

        public RaftPeer getPeer() {
            return peer;
        }

        @Override
        public int compareTo(Object o) {
            return groups.size() - ((PeerGroups) o).groups.size();
        }
    }

    private class PeerHealthChecker implements Runnable {
        @Override
        public void run() {
            while(true) {
                try {
                    Thread.sleep(1000);
                    long now = System.currentTimeMillis();
                    heartbeatInfo.keySet().stream().forEach(raftPeer -> {
                        Long heartbeatTimestamp = heartbeatInfo.get(raftPeer);
                        // Introduce configuration for period to detect the failure.
                        if((now - heartbeatTimestamp) > failureDetectionPeriod) {
                            // Close the logs serve by peer if any.
                            if (peerLogs.containsKey(raftPeer)) {
                                LOG.warn("Closing all logs hosted by peer {} because last heartbeat" +
                                                " ({}ms) exceeds the threshold ({}ms)", raftPeer, now - heartbeatTimestamp,
                                        failureDetectionPeriod);
                                peers.remove(raftPeer);
                                Set<LogName> logNames = peerLogs.get(raftPeer);
                                Iterator<LogName> itr = logNames.iterator();
                                while(itr.hasNext()) {
                                    LogName logName = itr.next();
                                    RaftGroup group = map.get(logName);
                                    RaftClient client = RaftClient.newBuilder().
                                            setRaftGroup(group).setProperties(properties).build();
                                    try {
                                        LOG.warn(String.format("Peer %s in the group %s went down." +
                                                        " Hence closing the log %s serve by the group.",
                                                raftPeer.toString(), group.toString(), logName.toString()));
                                        RaftClientReply reply = client.send(
                                                () -> LogServiceProtoUtil.
                                                        toChangeStateRequestProto(logName, LogStream.State.CLOSED, true)
                                                        .toByteString());
                                        LogServiceProtos.ChangeStateReplyProto message =
                                                LogServiceProtos.ChangeStateReplyProto.parseFrom(reply.getMessage().getContent());
                                        if(message.hasException()) {
                                            throw new IOException(message.getException().getErrorMsg());
                                        }
                                        itr.remove();
                                        client.close();
                                    } catch (IOException e) {
                                        LOG.warn(String.format("Failed to close log %s on peer %s failure.",
                                                logName, raftPeer.toString()), e);
                                    }
                                }
                                if(logNames.isEmpty()) {
                                    peerLogs.remove(raftPeer);
                                    heartbeatInfo.remove(raftPeer);
                                } // else retry closing failed logs on next period.
                            }
                            final List<PeerGroups> peerGroupsToRemove = new ArrayList<>();
                            // remove peer groups from avail.
                            avail.stream().forEach(peerGroup -> {
                                if(peerGroup.getPeer().equals(raftPeer)) {
                                    peerGroupsToRemove.add(peerGroup);
                                }
                            });
                            for(PeerGroups peerGroups: peerGroupsToRemove) {
                                avail.remove(peerGroups);
                            }
                        }
                    });
                } catch (Exception e) {
                    LOG.error(
                            "Exception while closing logs and removing peer" +
                                    " from raft groups with Metadata Service on node failure", e);
                }
            }
        }
    }


    // This method need to be used for testing only.
    public boolean checkPeersAreSame() {
        if(!peers.equals(peerLogs.keySet())) return false;
            if(!peers.equals(heartbeatInfo.keySet())) return false;
        Set<RaftPeer> availPeers = new HashSet<>();
        avail.stream().forEach(peerGroups -> {
            availPeers.add(peerGroups.getPeer());
        });
        if(!peers.equals(availPeers)) return false;
        return true;
    }
}
