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

# Upstream's cluster-paging case (test/e2e-v2/cases/dsl-debugging/cluster-paging) on the two native OAP replicas of a
# kind cluster instead of two ZooKeeper-coordinated containers. The first pod by name plays upstream's oap1 (the one
# spans reach), the second oap2.
#   emit: run upstream's tal-otlp span emitter against the first pod only.
#   flow: port-forward each pod's admin port and run upstream's flow script. The Kubernetes coordinator registers the
#         nodes by pod IP, so a peer's records name it by its IP where the compose nodes are named oap1 and oap2: the
#         script runs with those two names replaced by the pods' IPs, and nothing else changed.

set -euo pipefail

NS=skywalking
UPSTREAM_FLOW=skywalking/test/e2e-v2/cases/dsl-debugging/cluster-paging/dsl-debug-flow.sh

read -r -a NAMES <<< "$(kubectl -n "$NS" get pods -l app=skywalking,component=oap --field-selector=status.phase=Running \
  --sort-by=.metadata.name -o jsonpath='{.items[*].metadata.name}')"
read -r -a IPS <<< "$(kubectl -n "$NS" get pods -l app=skywalking,component=oap --field-selector=status.phase=Running \
  --sort-by=.metadata.name -o jsonpath='{.items[*].status.podIP}')"
if [ "${#NAMES[@]}" -ne 2 ] || [ "${#IPS[@]}" -ne 2 ]; then
  echo "expected 2 running OAP pods, found ${NAMES[*]:-none} (${IPS[*]:-no IPs})" >&2
  exit 1
fi

case "${1:-}" in
  emit)
    kubectl -n "$NS" delete pod span-emitter --ignore-not-found
    kubectl -n "$NS" run span-emitter --image=dsldbg-span-emitter:e2e --image-pull-policy=Never --restart=Never \
      --env="OTLP_ENDPOINT=http://${IPS[0]}:11800" --env="EMITTER_INTERVAL_S=0.2"
    kubectl -n "$NS" wait --for=condition=Ready pod/span-emitter --timeout=180s
    ;;
  flow)
    PORT1=${OAP1_ADMIN_LOCAL_PORT:-47128}
    PORT2=${OAP2_ADMIN_LOCAL_PORT:-47228}
    kubectl -n "$NS" port-forward "pod/${NAMES[0]}" "${PORT1}:17128" > /tmp/dsldbg-cluster-pf1.log 2>&1 &
    PF1=$!
    kubectl -n "$NS" port-forward "pod/${NAMES[1]}" "${PORT2}:17128" > /tmp/dsldbg-cluster-pf2.log 2>&1 &
    PF2=$!
    trap 'kill ${PF1} ${PF2} 2>/dev/null || true' EXIT
    for port in "${PORT1}" "${PORT2}"; do
      for _ in $(seq 1 30); do
        curl -s -o /dev/null "http://127.0.0.1:${port}/dsl-debugging/status" && break
        sleep 1
      done
    done

    flow=$(mktemp)
    sed -e "s/echo oap2; else echo oap1;/echo ${IPS[1]}; else echo ${IPS[0]};/" \
        -e "s/--arg self oap2 /--arg self ${IPS[1]} /" "${UPSTREAM_FLOW}" > "${flow}"
    # Fail loudly when upstream renames its nodes: the flow would then compare the pods' records under other names.
    if [ "$(grep -c "echo ${IPS[1]}; else echo ${IPS[0]};" "${flow}")" != 1 ] \
        || [ "$(grep -c -- "--arg self ${IPS[1]} " "${flow}")" != 1 ] \
        || grep -v -E '^[[:space:]]*#|log |fail ' "${flow}" | grep -q -w -E 'oap[12]'; then
      echo "upstream's ${UPSTREAM_FLOW} changed how it names its nodes; update ${BASH_SOURCE[0]}" >&2
      exit 1
    fi
    OAP_HOST=127.0.0.1 OAP1_REST_PORT="${PORT1}" OAP2_REST_PORT="${PORT2}" bash "${flow}"
    ;;
  *)
    echo "usage: $0 emit|flow" >&2
    exit 2
    ;;
esac
