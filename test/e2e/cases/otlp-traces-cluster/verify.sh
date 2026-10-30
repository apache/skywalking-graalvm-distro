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

set -euo pipefail

NS=skywalking

case "$1" in
  cluster)
    # How many nodes each replica lists, and how many of them are itself.
    i=0
    for pod in $(kubectl -n "$NS" get pods -l app=skywalking,component=oap -o jsonpath='{.items[*].metadata.name}'); do
      body=$(kubectl -n "$NS" exec "$pod" -- sh -c \
        "printf 'GET /status/cluster/nodes HTTP/1.0\r\n\r\n' | nc 127.0.0.1 17128" | sed '1,/^\r*$/d')
      echo "oap-$i: {nodes: $(echo "$body" | yq -p json '.nodes | length'), self: $(echo "$body" | yq -p json '[.nodes[] | select(.self)] | length')}"
      i=$((i + 1))
    done
    ;;
  aliases)
    # Where each caller's edge lands: `callee-i` once the pair is matched, `callee-i:8080` until then.
    for i in 0 1 2 3 4 5 6 7; do
      targets=$(swctl --display yaml --base-url="http://$2:$3/graphql" dependency service --service-name="caller-$i" \
        | yq '. as $t | [.calls[] | select(.source == ($t.nodes[] | select(.name == "caller-'"$i"'") | .id)) | .target as $id | $t.nodes[] | select(.id == $id) | .name] | sort | join(",")')
      echo "caller-$i: $targets"
    done
    ;;
esac
