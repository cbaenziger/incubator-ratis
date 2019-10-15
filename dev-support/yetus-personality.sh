#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# load URLs, checksums of dependencies
# all dependency env. vars. start with ratis_ include them all via --build-arg

personality_plugins "all"

## @description  Globals specific to this personality
## @audience     private
## @stability    evolving
function personality_globals
{
  # shellcheck disable=SC2034
  BUILDTOOL=maven
  #shellcheck disable=SC2034
  PATCH_BRANCH_DEFAULT=master
  #shellcheck disable=SC2034
  JIRA_ISSUE_RE='^RATIS-[0-9]+$'
  #shellcheck disable=SC2034
  GITHUB_REPO="apache/incubator-ratis"
}

function ratis_docker_support
{
  for kv in $(source dev-support/binary_locations.sh && env|awk 'BEGIN{FS="="}; /^ratis_.*/{printf $1 "=" $2 " "}'); do
    add_docker_build_arg "${kv%=*}" "${kv#*=}"
  done
##  export DOCKER_EXTRAENVS+=( $(source dev-support/binary_locations.sh && env|awk 'BEGIN{FS="="}; /^ratis_.*/{printf $1 " "}') )
##  yetus_debug "Using DOCKER_EXTRAENVS: ${DOCKER_EXTRAENVS[*]}"
##  for i in ${DOCKER_EXTRAENVS[*]}; do
##    add_docker_env $i
##  done
  yetus_debug "Using DOCKER_EXTRABUILDARGS: ${DOCKER_EXTRABUILDARGS[*]}"
}

## @description  Queue up modules for this personality
## @audience     private
## @stability    evolving
## @param        repostatus
## @param        testtype
function personality_modules
{
  #Ratis is not a big project, we can always run everything on the whole project.
  #Especially as we need the generated sources and shaded client.
  clear_personality_queue
  personality_enqueue_module .
}
