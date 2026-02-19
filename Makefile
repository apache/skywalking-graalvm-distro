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

.PHONY: all clean build init-submodules build-skywalking build-distro compile test javadoc dist info

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
build-distro:
	$(MVN) clean package -DskipTests $(MVN_ARGS)

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
