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

# Sends eight calls `caller-i -> callee-i` over OTLP/HTTP, each split across the two OAP replicas: the caller's
# spans (a root SERVER span and the CLIENT span it dials callee-i:8080 with) to one pod, the callee's SERVER span to
# the other. The trace's owner is `abs(traceId.hashCode() % 2)`, and the ids below alternate between the two, so half
# the calls need the callee half moved to the client side's pod and half the caller half moved the other way; calls
# 0-3 send the caller half first, 4-7 the callee half. Every alias the topology then shows crossed nodes.

set -euo pipefail

NS=skywalking
CURL_IMAGE=curlimages/curl:latest

read -r -a PODS <<< "$(kubectl -n "$NS" get pods -l app=skywalking,component=oap \
  --field-selector=status.phase=Running -o jsonpath='{.items[*].status.podIP}')"
if [ "${#PODS[@]}" -ne 2 ]; then
  echo "expected 2 running OAP pods, found ${#PODS[@]}: ${PODS[*]}"
  exit 1
fi
CALLER_SIDE=${PODS[0]}
CALLEE_SIDE=${PODS[1]}

read -r -d '' SCRIPT <<EOF || true
set -e
# Both replicas must see the cluster before any half is sent: a replica that knows no peer yet pairs locally.
for pod in $CALLER_SIDE $CALLEE_SIDE; do
  for _ in \$(seq 1 60); do
    n=\$(curl -s http://\$pod:17128/status/cluster/nodes | grep -o '"host"' | wc -l)
    [ "\$n" -eq 2 ] && break
    sleep 5
  done
  echo "\$pod sees \$n nodes"
  [ "\$n" -eq 2 ] || exit 1
done

post() {
  code=\$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
    "http://\$1:12800/v1/traces" --data "\$2")
  echo "POST \$1 \$3 -> \$code"
  [ "\$code" = 200 ]
}
res() {
  echo '{"attributes":[{"key":"service.name","value":{"stringValue":"'\$1'"}},{"key":"service.layer","value":{"stringValue":"OTLP_GENERAL"}}]}'
}

now=\$(date +%s)
for i in 0 1 2 3 4 5 6 7; do
  trace=c1a50\${i}00000000000000000000000000
  root=a00000000000000\$i; client=b00000000000000\$i; server=c00000000000000\$i
  callerHalf='{"resourceSpans":[{"resource":'\$(res caller-\$i)',"scopeSpans":[{"scope":{"name":"e2e"},"spans":[
    {"traceId":"'\$trace'","spanId":"'\$root'","name":"GET /checkout","kind":2,"startTimeUnixNano":"'\$now'000000000","endTimeUnixNano":"'\$now'080000000","attributes":[{"key":"http.request.method","value":{"stringValue":"GET"}},{"key":"http.route","value":{"stringValue":"/checkout"}}],"status":{}},
    {"traceId":"'\$trace'","spanId":"'\$client'","parentSpanId":"'\$root'","flags":256,"name":"GET","kind":3,"startTimeUnixNano":"'\$now'010000000","endTimeUnixNano":"'\$now'060000000","attributes":[{"key":"http.request.method","value":{"stringValue":"GET"}},{"key":"server.address","value":{"stringValue":"callee-'\$i'"}},{"key":"server.port","value":{"intValue":"8080"}}],"status":{}}]}]}]}'
  calleeHalf='{"resourceSpans":[{"resource":'\$(res callee-\$i)',"scopeSpans":[{"scope":{"name":"e2e"},"spans":[
    {"traceId":"'\$trace'","spanId":"'\$server'","parentSpanId":"'\$client'","flags":768,"name":"GET /items","kind":2,"startTimeUnixNano":"'\$now'020000000","endTimeUnixNano":"'\$now'050000000","attributes":[{"key":"http.request.method","value":{"stringValue":"GET"}},{"key":"http.route","value":{"stringValue":"/items"}},{"key":"server.address","value":{"stringValue":"callee-'\$i'"}},{"key":"server.port","value":{"intValue":"8080"}}],"status":{}}]}]}]}'
  if [ \$i -lt 4 ]; then
    post $CALLER_SIDE "\$callerHalf" "caller-\$i"
    post $CALLEE_SIDE "\$calleeHalf" "callee-\$i"
  else
    post $CALLEE_SIDE "\$calleeHalf" "callee-\$i"
    post $CALLER_SIDE "\$callerHalf" "caller-\$i"
  fi
done
EOF

kubectl -n "$NS" delete pod span-sender --ignore-not-found
kubectl -n "$NS" run span-sender --image="$CURL_IMAGE" --image-pull-policy=IfNotPresent --restart=Never \
  --command -- sh -c "$SCRIPT"
if ! kubectl -n "$NS" wait --for=jsonpath='{.status.phase}'=Succeeded pod/span-sender --timeout=420s; then
  kubectl -n "$NS" logs span-sender || true
  exit 1
fi
kubectl -n "$NS" logs span-sender
