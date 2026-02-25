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

# E2E commands run from repo root so that setup step paths
# (skywalking/test/e2e-v2/script/...) resolve correctly for both
# local runs and CI (apache/skywalking-infra-e2e action).
ROOT := ../..

.PHONY: simple-java-agent

# Run simple-java-agent e2e test
# Prerequisites: make docker-native (from root)
simple-java-agent:
	cd $(ROOT) && SW_AGENT_JDK_VERSION=$${SW_AGENT_JDK_VERSION:-17} \
	e2e run -c test/e2e/cases/simple-java-agent/e2e.yaml
