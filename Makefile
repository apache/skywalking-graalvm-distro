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

# Extract SkyWalking version from submodule
SW_VERSION := $(shell grep '<revision>' skywalking/pom.xml | head -1 | sed 's/.*<revision>\(.*\)<\/revision>.*/\1/')

MVN := ./mvnw
MVN_ARGS := -Dskywalking.version=$(SW_VERSION)

.PHONY: all clean build init-submodules build-skywalking build-distro compile test javadoc dist info docker-up docker-down boot shutdown native-image trace-agent

all: build

info:
	@echo "SkyWalking version: $(SW_VERSION)"

# Initialize skywalking nested submodules (proto files, query protocol, etc.)
init-submodules:
	cd skywalking && git submodule update --init --recursive

# Build the skywalking submodule and install artifacts to local Maven repo
# flatten-maven-plugin resolves ${revision} in installed POMs so external projects can depend on them
build-skywalking: init-submodules
	cd skywalking && ../mvnw flatten:flatten install -DskipTests -Dmaven.javadoc.skip=true -Dcheckstyle.skip=true -Dgpg.skip=true

# Compile + install to local repo (no tests).
# Install is needed so standalone goals like javadoc:javadoc (which don't trigger the reactor
# lifecycle) can resolve the precompiler-*-generated.jar from the local Maven repo.
compile:
	$(MVN) clean install -DskipTests $(MVN_ARGS)

# Run tests (includes compile)
test:
	$(MVN) clean test $(MVN_ARGS)

# Generate javadoc (validates javadoc correctness)
javadoc:
	$(MVN) javadoc:javadoc -DskipTests $(MVN_ARGS)

# Build the distro modules (package + assembly, no tests)
# Phase 1: install repackaged *-for-graalvm libs so dependency-reduced POMs
#           are available in the local Maven repo for downstream resolution.
# Phase 2: package oap-graalvm-server and oap-graalvm-native with assembly.
build-distro:
	$(MVN) clean install -pl oap-libs-for-graalvm -am -DskipTests $(MVN_ARGS)
	$(MVN) package -pl oap-graalvm-server,oap-graalvm-native -DskipTests $(MVN_ARGS)

# Show the distribution directory
dist: build-distro
	@echo "Distribution created at:"
	@echo "  oap-graalvm-server/target/oap-graalvm-jvm-distro/oap-graalvm-jvm-distro/"
	@echo "  oap-graalvm-server/target/oap-graalvm-jvm-distro.tar.gz"

# Full build: skywalking first, then distro
build: build-skywalking build-distro

clean:
	$(MVN) clean $(MVN_ARGS)
	cd skywalking && ../mvnw clean

# Start BanyanDB for local development
docker-up:
	docker compose -f docker/docker-compose.yml up -d
	@echo "Waiting for BanyanDB to be ready..."
	@until docker compose -f docker/docker-compose.yml exec banyandb sh -c 'nc -nz 127.0.0.1 17912' 2>/dev/null; do sleep 1; done
	@echo "BanyanDB is ready on localhost:17912"

# Stop BanyanDB
docker-down:
	docker compose -f docker/docker-compose.yml down

# Stop a previously running OAP server
shutdown:
	@oap-graalvm-server/target/oap-graalvm-jvm-distro/oap-graalvm-jvm-distro/bin/oapServiceStop.sh 2>/dev/null || true

# Build native image (requires GraalVM JDK)
native-image: compile
	$(MVN) package -pl oap-graalvm-native -Pnative -DskipTests $(MVN_ARGS)

# Run tracing agent to capture supplementary native-image metadata
# Merges with pre-generated reflect-config.json from the precompiler
trace-agent: build-distro docker-up
	SW_STORAGE_BANYANDB_TARGETS=localhost:17912 \
	SW_CLUSTER=standalone \
	JAVA_OPTS="-Xms256M -Xmx4096M -agentlib:native-image-agent=config-merge-dir=oap-graalvm-native/src/main/resources/META-INF/native-image/org.apache.skywalking/oap-graalvm-native" \
	  oap-graalvm-server/target/oap-graalvm-jvm-distro/oap-graalvm-jvm-distro/bin/oapService.sh

# Build distro and boot OAP with BanyanDB
boot: build-distro docker-up
	SW_STORAGE_BANYANDB_TARGETS=localhost:17912 \
	SW_CLUSTER=standalone \
	  oap-graalvm-server/target/oap-graalvm-jvm-distro/oap-graalvm-jvm-distro/bin/oapService.sh
