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

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ─── Helpers ─────────────────────────────────────────────────────────────────
log()   { echo "===> $*"; }
error() { echo "ERROR: $*" >&2; exit 1; }

# ─── Read current version from root pom.xml ──────────────────────────────────
CURRENT_VERSION=$(sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' "${REPO_ROOT}/pom.xml" | head -1)
[[ -n "${CURRENT_VERSION}" ]] || error "Could not read version from pom.xml"
[[ "${CURRENT_VERSION}" == *-SNAPSHOT ]] || error "Current version '${CURRENT_VERSION}' is not a SNAPSHOT version"

log "Current version: ${CURRENT_VERSION}"

# ─── Step 1: Determine release version ───────────────────────────────────────
DEFAULT_RELEASE_VERSION="${CURRENT_VERSION%-SNAPSHOT}"

echo ""
read -r -p "Release version [${DEFAULT_RELEASE_VERSION}]: " RELEASE_VERSION
RELEASE_VERSION="${RELEASE_VERSION:-${DEFAULT_RELEASE_VERSION}}"
[[ "${RELEASE_VERSION}" != *-SNAPSHOT ]] || error "Release version must not contain -SNAPSHOT"

# ─── Step 2: Determine next development version ─────────────────────────────
# Default: bump minor version (0.1.0 -> 0.2.0-SNAPSHOT)
IFS='.' read -r MAJOR MINOR PATCH <<< "${RELEASE_VERSION}"
DEFAULT_NEXT_VERSION="${MAJOR}.$((MINOR + 1)).0-SNAPSHOT"

echo ""
read -r -p "Next development version [${DEFAULT_NEXT_VERSION}]: " NEXT_VERSION
NEXT_VERSION="${NEXT_VERSION:-${DEFAULT_NEXT_VERSION}}"
[[ "${NEXT_VERSION}" == *-SNAPSHOT ]] || error "Next development version must end with -SNAPSHOT"

# ─── Confirm ─────────────────────────────────────────────────────────────────
echo ""
echo "Summary:"
echo "  Current version      : ${CURRENT_VERSION}"
echo "  Release version      : ${RELEASE_VERSION}"
echo "  Next dev version     : ${NEXT_VERSION}"
echo "  Tag                  : v${RELEASE_VERSION}"
echo ""
read -r -p "Proceed? [y/N] " confirm
[[ "${confirm}" =~ ^[Yy]$ ]] || { echo "Aborted."; exit 0; }

# ─── Step 3: Bump to release version ────────────────────────────────────────
log "Bumping version to ${RELEASE_VERSION}..."

find "${REPO_ROOT}" -name pom.xml -not -path '*/skywalking/*' \
    -exec sed -i '' "s/${CURRENT_VERSION}/${RELEASE_VERSION}/g" {} \;

# Verify the change
VERIFY_VERSION=$(sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' "${REPO_ROOT}/pom.xml" | head -1)
[[ "${VERIFY_VERSION}" == "${RELEASE_VERSION}" ]] || error "Version bump failed. pom.xml shows '${VERIFY_VERSION}'"

log "Committing release version..."
cd "${REPO_ROOT}"
git add -A '*.pom.xml' 2>/dev/null || true
git add $(find . -name pom.xml -not -path '*/skywalking/*')
git commit -m "Release ${RELEASE_VERSION}"

log "Creating tag v${RELEASE_VERSION}..."
git tag "v${RELEASE_VERSION}"

# ─── Step 4: Bump to next development version ───────────────────────────────
log "Bumping version to ${NEXT_VERSION}..."

find "${REPO_ROOT}" -name pom.xml -not -path '*/skywalking/*' \
    -exec sed -i '' "s/${RELEASE_VERSION}/${NEXT_VERSION}/g" {} \;

VERIFY_VERSION=$(sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' "${REPO_ROOT}/pom.xml" | head -1)
[[ "${VERIFY_VERSION}" == "${NEXT_VERSION}" ]] || error "Version bump failed. pom.xml shows '${VERIFY_VERSION}'"

log "Committing next development version..."
git add $(find . -name pom.xml -not -path '*/skywalking/*')
git commit -m "Bump version to ${NEXT_VERSION}"

# ─── Summary ─────────────────────────────────────────────────────────────────
echo ""
log "Pre-release complete!"
echo ""
echo "Created:"
echo "  - Commit: Release ${RELEASE_VERSION}"
echo "  - Tag:    v${RELEASE_VERSION}"
echo "  - Commit: Bump version to ${NEXT_VERSION}"
echo ""
echo "Next steps:"
echo "  1. Review commits:  git log --oneline -3"
echo "  2. Push tag:        git push origin v${RELEASE_VERSION}"
echo "  3. Push branch:     git push origin main"
echo "  4. Wait for CI release workflow to complete"
echo "  5. Run:             release/release.sh ${RELEASE_VERSION}"
