---
title: Getting started
---
<!---
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License. See accompanying LICENSE file.
-->

Ratis is a [Raft](https://raft.github.io/") protocol *library* in Java. It's not a standalone server application like Zookeeper or Consul.

### Examples

To demonstrate how to use Ratis from the code, Please look at the following examples.

 * [Arithmetic example](https://github.com/apache/incubator-ratis/tree/master/ratis-examples/src/main/java/org/apache/ratis/examples/arithmetic): This is a simple distributed calculator that replicates the values defined and allows user to perform arithmetic operations on these replicated values.

 * [FileStore example](https://github.com/apache/incubator-ratis/tree/master/ratis-examples/src/main/java/org/apache/ratis/examples/filestore): This is an example of using Ratis for reading and writing files.

<!-- TODO: We should have the following as documentation in the github.  -->

The source code of the examples could be found in the
[ratis-examples](https://github.com/apache/incubator-ratis/blob/master/ratis-examples/) sub-project.

### Maven usage

To use in our project you can access the latest binaries from maven central:


{{< highlight xml>}}
<dependency>
   <artifactId>ratis-server</artifactId>
   <groupId>org.apache.ratis</groupId>
</dependency>
{{< /highlight >}}


You also need to include *one* of the transports:

{{< highlight xml>}}
<dependency>
   <artifactId>ratis-grpc</artifactId>
   <groupId>org.apache.ratis</groupId>
</dependency>
{{< /highlight >}}

{{< highlight xml>}}
 <dependency>
   <artifactId>ratis-netty</artifactId>
   <groupId>org.apache.ratis</groupId>
</dependency>
{{< /highlight >}}

{{< highlight xml>}}
<dependency>
   <artifactId>ratis-hadoop</artifactId>
   <groupId>org.apache.ratis</groupId>
</dependency>
{{< /highlight >}}

Please note that Apache Hadoop dependencies are shaded, so it's safe to use hadoop transport with different versions of Hadoop.

### Example Cluster

### Monitoring

The metrics [provided](https://issues.apache.org/jira/browse/RATIS-651) for operators looking to understand the behavior of their clusters can be seen via the Hadoop [metrics2 API](https://hadoop.apache.org/docs/current/api/org/apache/hadoop/metrics2/package-summary.html) and thus JMX as well.

## Showing when followers last heart-beat (in nanoseconds):

`object -> ratis_core:name=ratis_core.heartbeat.n1@group-6F7570313233.follower_n0_last_heartbeat_elapsed_time`
If a follower node is down, this metric will monotonically increase for a follower. A useful measure may be an alarm if it is over a concerning value.

## Showing when an election is in progress:

When an election is in progress, one will see the following metrics increasing from those nodes participating:
`object -> ratis_core:name=ratis_core.leader_election.n2@group-6F7570313233.leader_election_timeout_count`
Here the timeout count will increment for every failed (timed out) election.

## Showing how stable the last leader was:

If one has a flapping cluster, it can be instructive to watch how long this leader has been the leader via:
`ratis_core:name=ratis_core.leader_election.n2@group-6F7570313233.last_leader_elapsed_time`

