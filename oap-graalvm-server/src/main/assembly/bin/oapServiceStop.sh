#!/usr/bin/env sh
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

PRG="$0"
PRGDIR=$(dirname "$PRG")
[ -z "$OAP_HOME" ] && OAP_HOME=$(cd "$PRGDIR/.." > /dev/null || exit 1; pwd)

PID=$(ps -ef | grep GraalVMOAPServerStartUp | grep -v grep | awk '{print $2}')

if [ -z "$PID" ]; then
    echo "No SkyWalking GraalVM OAP server is running."
    exit 0
fi

echo "Stopping SkyWalking GraalVM OAP server (PID: $PID)..."
kill "$PID"

# Wait for process to stop
TIMEOUT=30
while [ $TIMEOUT -gt 0 ]; do
    if ! kill -0 "$PID" 2>/dev/null; then
        echo "SkyWalking GraalVM OAP server stopped."
        exit 0
    fi
    sleep 1
    TIMEOUT=$((TIMEOUT - 1))
done

echo "Force killing SkyWalking GraalVM OAP server (PID: $PID)..."
kill -9 "$PID" 2>/dev/null
echo "SkyWalking GraalVM OAP server stopped."
