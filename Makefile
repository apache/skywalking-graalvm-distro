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

# The GraalVM release the native image is built with. CI pins the same `version` in setup-graalvm.
GRAALVM_VERSION ?= 25.5
# The Linux builder of native-image-macos; `25i5` is the GraalVM Community 25.5 innovation release.
GRAALVM_IMAGE ?= ghcr.io/graalvm/native-image-community:25i5

# The newest sdkman GraalVM $(GRAALVM_VERSION), Oracle before Community, then a GraalVM JAVA_HOME, then the
# newest sdkman GraalVM 25. `make GRAAL_HOME=<dir>` overrides the pick.
SDKMAN_JAVA := $(HOME)/.sdkman/candidates/java
GRAAL_HOME := $(shell \
	for p in '$(GRAALVM_VERSION).*-graal' '$(GRAALVM_VERSION).*-graalce'; do \
		d=$$(ls -d $(SDKMAN_JAVA)/$$p 2>/dev/null | sort -V | tail -1); \
		if [ -n "$$d" ]; then echo "$$d"; exit 0; fi; \
	done; \
	if echo "$$JAVA_HOME" | grep -qi graal; then echo "$$JAVA_HOME"; exit 0; fi; \
	ls -d $(SDKMAN_JAVA)/*graal* 2>/dev/null | grep 25 | sort -V | tail -1)
ifneq ($(GRAAL_HOME),)
  export JAVA_HOME := $(GRAAL_HOME)
  GRAAL_HOME_VERSION := $(shell sed -n 's/^GRAALVM_VERSION="\(.*\)"/\1/p' "$(GRAAL_HOME)/release" 2>/dev/null)
  ifeq ($(filter $(GRAALVM_VERSION).%,$(GRAAL_HOME_VERSION)),)
    $(warning GraalVM at $(GRAAL_HOME) is $(or $(GRAAL_HOME_VERSION),of unknown version), not $(GRAALVM_VERSION). Install GraalVM $(GRAALVM_VERSION), e.g. through sdkman (`sdk list java`).)
  endif
endif

MVN := ./mvnw
# Extra Maven arguments for every build, e.g. MVN_ARGS="-o". Appended to the arguments the targets need.
MVN_ARGS ?=
BASE_MVN_ARGS := -Dskywalking.version=$(SW_VERSION) $(MVN_ARGS)
# The native-image-macos container only packages what the host compiled: no overlay, its own Maven repository mount.
CONTAINER_MVN_ARGS := $(BASE_MVN_ARGS)
HOST_MVN_ARGS := $(BASE_MVN_ARGS)

# A rules overlay compiled into the image: a directory laid out like OAP's config/ with a rules-overlay.yaml
# declaring what it adds, overrides and excludes (docs/rules-overlay.md).
RULES_OVERLAY ?=
ifneq ($(strip $(RULES_OVERLAY)),)
  ifneq ($(words $(RULES_OVERLAY)),1)
    $(error RULES_OVERLAY must be a path without spaces: "$(RULES_OVERLAY)")
  endif
  HOST_MVN_ARGS += -Drules.overlay="$(abspath $(RULES_OVERLAY))"
endif

# A local Maven repository to build with instead of ~/.m2/repository. Host builds get it as maven.repo.local, the
# native-image-macos container mounted over its repository (with ~/.m2 for settings); unset, ~/.m2 is used as before.
M2_REPO ?=
ifneq ($(strip $(M2_REPO)),)
  ifneq ($(words $(M2_REPO)),1)
    $(error M2_REPO must be a path without spaces: "$(M2_REPO)")
  endif
  HOST_MVN_ARGS += -Dmaven.repo.local="$(abspath $(M2_REPO))"
  # ~/.m2 for its settings and the wrapper's cache, the chosen repository over its repository.
  M2_MOUNT := "$(HOME)/.m2":/root/.m2 -v "$(abspath $(M2_REPO))":/root/.m2/repository
  # A localRepository in the mounted settings.xml must not move it elsewhere.
  CONTAINER_MVN_ARGS += -Dmaven.repo.local=/root/.m2/repository
else
  M2_MOUNT := "$(HOME)/.m2":/root/.m2
endif

# The tests verify-rules runs: the precompiler's and the ones that read the compiled rules, not the ones pinned to
# the bundled rule set.
VERIFY_RULES_PRECOMPILER_TESTS := PrecompilerTest,OalPassTest,ClasspathScansTest,PrecompileFailureTest,TALPrecompileTest,RegistryParityTest,RegistryGeneratorTest,RulesStagerTest,LalRegistrationCheckTest,LalConfigDataTest,LalPrecompileTest,TalActivationCheckTest,HierarchyCheckTest,HierarchyPrecompileTest,ManifestEscapeTest
VERIFY_RULES_SERVER_TESTS := TALPrecompiledRuleTest,TalDSLManifestTest,OverlayRuleTest,MalCoordinatesLookupTest,OverlayLalRuleTest,LalRuntimeTypeParityTest,CompiledRuleActivationTest,BundledRuleCatalogParityTest

.PHONY: all clean build init-submodules init-skywalking build-distro compile test javadoc dist info docker-up docker-down boot shutdown native-image native-image-macos native-dist trace-agent docker-native verify-rules

all: build

info:
	@echo "SkyWalking version: $(SW_VERSION)"
	@echo "JAVA_HOME: $(JAVA_HOME)"
	@"$(JAVA_HOME)/bin/native-image" --version 2>/dev/null || echo "native-image: not found under JAVA_HOME"

# Initialize skywalking nested submodules (proto files, query protocol, etc.)
init-submodules:
	cd skywalking && git submodule update --init --recursive

# Init skywalking submodule and install artifacts to local Maven repo
# flatten-maven-plugin resolves ${revision} in installed POMs so external projects can depend on them
init-skywalking: init-submodules
	cd skywalking && ../mvnw flatten:flatten install -DskipTests -Dmaven.javadoc.skip=true -Dcheckstyle.skip=true -Dgpg.skip=true $(if $(strip $(M2_REPO)),-Dmaven.repo.local="$(abspath $(M2_REPO))") $(MVN_ARGS)

# Compile + install to local repo (no tests).
# Install is needed so standalone goals like javadoc:javadoc (which don't trigger the reactor
# lifecycle) can resolve the precompiler-*-generated.jar from the local Maven repo.
compile:
	$(MVN) clean install -DskipTests $(HOST_MVN_ARGS)

# Run tests (includes compile)
test:
	$(MVN) clean test $(HOST_MVN_ARGS)

# Generate javadoc (validates javadoc correctness)
javadoc:
	$(MVN) javadoc:javadoc -DskipTests $(HOST_MVN_ARGS)

# Build the distro modules (package + assembly, no tests)
# Phase 1: install the precompiler, whose generated jar (the compiled rule registry) the *-for-graalvm libs
#           compile against, and the repackaged *-for-graalvm libs, so dependency-reduced POMs
#           are available in the local Maven repo for downstream resolution.
# Phase 2: package oap-graalvm-server and oap-graalvm-native with assembly, cleaned first: the assembly directory
#           would keep the jar of a module the build no longer has, which the launcher still loads.
#           -am (also-make) automatically builds build-common, precompiler,
#           and other dependencies in the reactor.
build-distro:
	$(MVN) clean install -pl build-tools/precompiler,oap-libs-for-graalvm -am -DskipTests $(HOST_MVN_ARGS)
	$(MVN) install -f oap-libs-for-graalvm -DskipTests $(HOST_MVN_ARGS)
	$(MVN) clean -pl oap-graalvm-server,oap-graalvm-native $(HOST_MVN_ARGS)
	$(MVN) package -pl oap-graalvm-server,oap-graalvm-native -am -DskipTests $(HOST_MVN_ARGS)

# Show the distribution directory
dist: build-distro
	@echo "JVM distribution created at:"
	@echo "  oap-graalvm-server/target/oap-graalvm-jvm-distro/oap-graalvm-jvm-distro/"
	@echo "  oap-graalvm-server/target/oap-graalvm-jvm-distro.tar.gz"

# Show the native distribution directory
native-dist: native-image
	@echo "Native distribution created at:"
	@ls -d oap-graalvm-native/target/oap-graalvm-native-*-native-dist/oap-native/ 2>/dev/null
	@ls oap-graalvm-native/target/oap-graalvm-native-*-native-dist.tar.gz 2>/dev/null

# Full build: skywalking first, then distro
build: init-skywalking build-distro

clean:
	$(MVN) clean $(HOST_MVN_ARGS)
	cd skywalking && ../mvnw clean $(if $(strip $(M2_REPO)),-Dmaven.repo.local="$(abspath $(M2_REPO))") $(MVN_ARGS)

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

# Compile the rules, RULES_OVERLAY merged in, and run the tests that check them, without building the native image:
# the fast gate for a rules overlay. The same install order as build-distro, so the *-for-graalvm libs are built
# against this build's rule registry.
verify-rules:
	$(MVN) clean install -pl build-tools/precompiler,oap-libs-for-graalvm -am -DskipTests $(HOST_MVN_ARGS)
	$(MVN) install -f oap-libs-for-graalvm -DskipTests $(HOST_MVN_ARGS)
	$(MVN) test -pl build-tools/precompiler,oap-graalvm-server -Dtest='$(VERIFY_RULES_PRECOMPILER_TESTS),$(VERIFY_RULES_SERVER_TESTS)' -Dsurefire.failIfNoSpecifiedTests=false $(HOST_MVN_ARGS)

# Build native image (requires GraalVM JDK)
native-image: compile
	$(MVN) package -pl oap-graalvm-native -Pnative -DskipTests $(HOST_MVN_ARGS)

# Build Linux native image from macOS using Docker.
# Java compilation is platform-independent (already done on host via `compile`).
# Only the native-image step needs Linux — runs inside a GraalVM container
# with the host .m2 cache and project dir mounted.
native-image-macos: compile
	docker run --rm --entrypoint bash \
		-v $(M2_MOUNT) \
		-v $(CURDIR):/workspace \
		-w /workspace \
		$(GRAALVM_IMAGE) \
		./mvnw package -pl oap-graalvm-native -Pnative -DskipTests $(CONTAINER_MVN_ARGS)

# Run tracing agent to capture supplementary native-image metadata
# Merges with pre-generated reflect-config.json from the precompiler
trace-agent: build-distro docker-up
	SW_STORAGE_BANYANDB_TARGETS=localhost:17912 \
	SW_CLUSTER=standalone \
	JAVA_OPTS="-Xms256M -Xmx4096M -agentlib:native-image-agent=config-merge-dir=oap-graalvm-native/src/main/resources/META-INF/native-image/org.apache.skywalking/oap-graalvm-native" \
	  oap-graalvm-server/target/oap-graalvm-jvm-distro/oap-graalvm-jvm-distro/bin/oapService.sh

# Build Docker image with native binary (requires prior: make native-image)
docker-native:
	docker build -f docker/Dockerfile.native \
		--build-arg DIST=$$(ls oap-graalvm-native/target/oap-graalvm-native-*-native-dist.tar.gz) \
		-t skywalking-oap-native .

# Build distro and boot OAP with BanyanDB
boot: build-distro docker-up
	SW_STORAGE_BANYANDB_TARGETS=localhost:17912 \
	SW_CLUSTER=standalone \
	  oap-graalvm-server/target/oap-graalvm-jvm-distro/oap-graalvm-jvm-distro/bin/oapService.sh
