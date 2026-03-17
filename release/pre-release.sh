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

# ─── Pre-flight checks ──────────────────────────────────────────────────────
cd "${REPO_ROOT}"

CURRENT_BRANCH=$(git branch --show-current)
[[ "${CURRENT_BRANCH}" == "main" ]] || error "Must be on main branch (currently on '${CURRENT_BRANCH}')"

# Check for uncommitted changes
[[ -z "$(git status --porcelain)" ]] || error "Working tree is not clean. Commit or stash changes first."

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
# Default: bump patch version (0.2.0 -> 0.2.1-SNAPSHOT)
IFS='.' read -r MAJOR MINOR PATCH <<< "${RELEASE_VERSION}"
DEFAULT_NEXT_VERSION="${MAJOR}.${MINOR}.$((PATCH + 1))-SNAPSHOT"

echo ""
read -r -p "Next development version [${DEFAULT_NEXT_VERSION}]: " NEXT_VERSION
NEXT_VERSION="${NEXT_VERSION:-${DEFAULT_NEXT_VERSION}}"
[[ "${NEXT_VERSION}" == *-SNAPSHOT ]] || error "Next development version must end with -SNAPSHOT"

RELEASE_BRANCH="release/v${RELEASE_VERSION}"

# ─── Verify changes.md ──────────────────────────────────────────────────────
CHANGES_FILE="${REPO_ROOT}/changes/changes.md"
[[ -f "${CHANGES_FILE}" ]] || error "changes/changes.md not found"
if ! grep -q "^## ${RELEASE_VERSION}$" "${CHANGES_FILE}"; then
    error "changes/changes.md does not contain a '## ${RELEASE_VERSION}' section. Add release notes before releasing."
fi
SECTION_CONTENT=$(sed -n "/^## ${RELEASE_VERSION}$/,/^## /p" "${CHANGES_FILE}" | sed '1d;/^## /d' | grep -v '^$' | head -1)
[[ -n "${SECTION_CONTENT}" ]] \
    || error "changes/changes.md has a '## ${RELEASE_VERSION}' section but it is empty. Add release notes."
log "changes/changes.md: found release notes for ${RELEASE_VERSION}"

# ─── Confirm ─────────────────────────────────────────────────────────────────
echo ""
echo "Summary:"
echo "  Current version      : ${CURRENT_VERSION}"
echo "  Release version      : ${RELEASE_VERSION}"
echo "  Release branch       : ${RELEASE_BRANCH}"
echo "  Next dev version     : ${NEXT_VERSION}"
echo "  Tag                  : v${RELEASE_VERSION}"
echo ""
read -r -p "Proceed? [y/N] " confirm
[[ "${confirm}" =~ ^[Yy]$ ]] || { echo "Aborted."; exit 0; }

# ─── Step 3: Create release branch and bump to release version ───────────────
log "Creating release branch ${RELEASE_BRANCH}..."
git checkout -b "${RELEASE_BRANCH}"

log "Bumping version to ${RELEASE_VERSION}..."

find "${REPO_ROOT}" -name pom.xml -not -path '*/skywalking/*' \
    -exec sed -i '' "s/${CURRENT_VERSION}/${RELEASE_VERSION}/g" {} \;

# Verify the change
VERIFY_VERSION=$(sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' "${REPO_ROOT}/pom.xml" | head -1)
[[ "${VERIFY_VERSION}" == "${RELEASE_VERSION}" ]] || error "Version bump failed. pom.xml shows '${VERIFY_VERSION}'"

log "Committing release version..."
git add $(find . -name pom.xml -not -path '*/skywalking/*')
git commit -m "Release ${RELEASE_VERSION}"

log "Creating tag v${RELEASE_VERSION}..."
git tag "v${RELEASE_VERSION}"

# ─── Step 4: Switch back to main and bump to next development version ───────
log "Switching back to main..."
git checkout main

MAIN_NEEDS_BUMP=true
if [[ "${CURRENT_VERSION}" == "${NEXT_VERSION}" ]]; then
    log "Main already at ${NEXT_VERSION} — skipping version bump"
    MAIN_NEEDS_BUMP=false
else
    log "Bumping version to ${NEXT_VERSION}..."

    find "${REPO_ROOT}" -name pom.xml -not -path '*/skywalking/*' \
        -exec sed -i '' "s/${CURRENT_VERSION}/${NEXT_VERSION}/g" {} \;

    VERIFY_VERSION=$(sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' "${REPO_ROOT}/pom.xml" | head -1)
    [[ "${VERIFY_VERSION}" == "${NEXT_VERSION}" ]] || error "Version bump failed. pom.xml shows '${VERIFY_VERSION}'"

    log "Committing next development version..."
    git add $(find . -name pom.xml -not -path '*/skywalking/*')
    git commit -m "Bump version to ${NEXT_VERSION}"
fi

# ─── Summary ─────────────────────────────────────────────────────────────────
echo ""
log "Pre-release complete!"
echo ""
echo "Created:"
echo "  - Branch: ${RELEASE_BRANCH}"
echo "    - Commit: Release ${RELEASE_VERSION}"
echo "    - Tag:    v${RELEASE_VERSION}"
if [[ "${MAIN_NEEDS_BUMP}" == "true" ]]; then
    echo "  - On main:"
    echo "    - Commit: Bump version to ${NEXT_VERSION}"
else
    echo "  - Main: already at ${NEXT_VERSION} (no changes)"
fi
echo ""
echo "Next steps:"
echo "  1. Review:            git log --oneline ${RELEASE_BRANCH} -1"
echo "  2. Push tag:          git push origin v${RELEASE_VERSION}"
echo "  3. Push release:      git push origin ${RELEASE_BRANCH}"
if [[ "${MAIN_NEEDS_BUMP}" == "true" ]]; then
    echo "  4. Push main:         git push origin main"
fi
echo "  5. Wait for CI release workflow to complete"
echo "  6. Run:               release/release.sh ${RELEASE_VERSION}"
