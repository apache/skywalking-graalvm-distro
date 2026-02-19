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

OAP_LOG_DIR="${OAP_LOG_DIR:-${OAP_HOME}/logs}"
JAVA_OPTS="${JAVA_OPTS:- -Xms256M -Xmx4096M}"

if [ ! -d "${OAP_LOG_DIR}" ]; then
    mkdir -p "${OAP_LOG_DIR}"
fi

_RUNJAVA=${JAVA_HOME}/bin/java
[ -z "$JAVA_HOME" ] && _RUNJAVA=java

# Build classpath: config directory first, then libs.
# IMPORTANT: oap-graalvm-server-*.jar MUST appear on the classpath BEFORE
# upstream JARs (server-core, meter-analyzer, log-analyzer) to ensure
# same-FQCN replacement classes shadow the upstream originals.
# See DISTRO-POLICY.md Challenge 5 for details.
CLASSPATH="$OAP_HOME/config"

# Add oap-graalvm-server JAR first (contains same-FQCN override classes)
for i in "$OAP_HOME"/libs/oap-graalvm-server-*.jar
do
    CLASSPATH="$CLASSPATH:$i"
done

# Add precompiler-generated JAR second (contains pre-compiled OAL/MAL/LAL classes + manifests)
for i in "$OAP_HOME"/libs/precompiler-*-generated.jar
do
    CLASSPATH="$CLASSPATH:$i"
done

# Add all remaining JARs
for i in "$OAP_HOME"/libs/*.jar
do
    case "$i" in
        */oap-graalvm-server-*) continue ;;
        *precompiler-*-generated*) continue ;;
    esac
    CLASSPATH="$CLASSPATH:$i"
done

OAP_OPTIONS=" -Doap.logDir=${OAP_LOG_DIR}"

eval exec "\"$_RUNJAVA\" ${JAVA_OPTS} ${OAP_OPTIONS} -classpath $CLASSPATH org.apache.skywalking.oap.server.graalvm.GraalVMOAPServerStartUp \
        2>${OAP_LOG_DIR}/oap.log"
