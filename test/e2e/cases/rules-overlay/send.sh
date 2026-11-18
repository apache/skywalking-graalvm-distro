#!/bin/sh
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

# Posts, every 5 seconds, what each rule of the overlay analyzes.
OAP=http://oap:12800

hex() {
  od -An -tx1 -N"$1" /dev/urandom | tr -d ' \n'
}

post() {
  curl -sS -o /dev/null -w "$1 HTTP %{http_code}\n" -X POST -H 'Content-Type: application/json' \
    --data-binary "$2" "${OAP}$1" || true
}

while true; do
  S=$(date +%s)
  NS="${S}000000000"
  START_NS="$((S - 1))000000000"
  MS="${S}000"

  # MAL: otel-rules/overlay/overlay-service.yaml
  post /v1/metrics '{"resourceMetrics":[{"resource":{"attributes":[
    {"key":"service.name","value":{"stringValue":"overlay-e2e"}}]},
    "scopeMetrics":[{"metrics":[{"name":"overlay_requests","gauge":{"dataPoints":[{"asDouble":5,
    "timeUnixNano":"'"${NS}"'","attributes":[{"key":"service","value":{"stringValue":"overlay-mal-svc"}}]}]}}]}]}]}'

  # TAL: tal/overlay.yaml
  post /v1/traces '{"resourceSpans":[{"resource":{"attributes":[
    {"key":"service.name","value":{"stringValue":"overlay-tal-svc"}},
    {"key":"service.layer","value":{"stringValue":"OVERLAY_E2E"}}]},
    "scopeSpans":[{"spans":[{"traceId":"'"$(hex 16)"'","spanId":"'"$(hex 8)"'","name":"GET /overlay","kind":2,
    "startTimeUnixNano":"'"${START_NS}"'","endTimeUnixNano":"'"${NS}"'","status":{}}]}]}]}'

  # LAL: lal/overlay.yaml
  post /v1/logs '{"resourceLogs":[{"resource":{"attributes":[
    {"key":"service.name","value":{"stringValue":"overlay-tal-svc"}},
    {"key":"service.layer","value":{"stringValue":"OVERLAY_E2E"}}]},
    "scopeLogs":[{"logRecords":[{"timeUnixNano":"'"${NS}"'","body":{"stringValue":"overlay e2e log"}}]}]}]}'

  # OAL: oal/overlay.oal, over the service the bundled native TAL rules find in this segment
  post /v3/segment '{"traceId":"'"$(hex 16)"'","traceSegmentId":"'"$(hex 16)"'","service":"overlay-native-svc",
    "serviceInstance":"overlay-native-1","spans":[{"spanId":0,"parentSpanId":-1,"startTime":'"$((MS - 100))"',
    "endTime":'"${MS}"',"operationName":"/overlay","spanType":"Entry","spanLayer":"Http","componentId":1,
    "isError":false}]}'

  sleep 5
done
