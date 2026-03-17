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

# Full automated release pipeline. Orchestrates pre-release.sh and release.sh
# with CI polling in between.
#
# Flow:
#   1. Validate prerequisites (tools, GPG, branch, working tree)
#   2. Run pre-release.sh (bump version, tag, bump to next SNAPSHOT)
#   3. Move snapshot commit to a PR branch, push tag + PR
#   4. Poll CI until tag build is green
#   5. Run release.sh (macOS build, sign, SVN upload, vote email)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO="apache/skywalking-graalvm-distro"

# ─── Helpers ─────────────────────────────────────────────────────────────────
log()   { echo "===> $*"; }
error() { echo "ERROR: $*" >&2; exit 1; }

# ─── Step 1: Pre-flight checks ──────────────────────────────────────────────
log "Pre-flight checks..."

for cmd in git gpg tar gh shasum make svn; do
    command -v "${cmd}" >/dev/null 2>&1 || error "'${cmd}' is not installed"
done
log "  Tools: OK"

cd "${REPO_ROOT}"

CURRENT_BRANCH=$(git branch --show-current)
[[ "${CURRENT_BRANCH}" == "main" ]] \
    || error "Must be on 'main' branch (currently on '${CURRENT_BRANCH}')"

[[ -z "$(git status --porcelain)" ]] \
    || error "Working tree is not clean. Commit or stash changes first."

git fetch origin main --quiet
LOCAL_SHA=$(git rev-parse HEAD)
REMOTE_SHA=$(git rev-parse origin/main)
[[ "${LOCAL_SHA}" == "${REMOTE_SHA}" ]] \
    || error "Local main (${LOCAL_SHA:0:8}) differs from origin/main (${REMOTE_SHA:0:8}). Pull or push first."
log "  Branch: main, clean, up to date"

GPG_EMAIL=$(gpg --list-secret-keys --keyid-format LONG 2>/dev/null \
    | grep "uid" | head -1 | sed 's/.*<\(.*\)>.*/\1/')
[[ "${GPG_EMAIL}" == *@apache.org ]] \
    || error "GPG key email '${GPG_EMAIL}' is not an @apache.org address."
log "  GPG signer: ${GPG_EMAIL}"

gh auth status --hostname github.com >/dev/null 2>&1 \
    || error "'gh' is not authenticated. Run: gh auth login"
log "  GitHub CLI: authenticated"

# ─── Step 2: Run pre-release.sh ──────────────────────────────────────────────
log "Running pre-release.sh..."
echo ""

"${SCRIPT_DIR}/pre-release.sh"

# ─── Step 3: Extract versions from what pre-release.sh created ───────────────
# After pre-release.sh, find the tag that was just created.
# If main already had the next SNAPSHOT version, HEAD was not changed;
# otherwise HEAD is the "Bump version to X.Y.Z-SNAPSHOT" commit.
NEXT_VERSION=$(sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' "${REPO_ROOT}/pom.xml" | head -1)

# Find the most recent release tag reachable from the release branch
# The tag is always on the release branch, not necessarily on main
LATEST_RELEASE_BRANCH=$(git branch --list 'release/v*' --sort=-creatordate | head -1 | tr -d ' ')
TAG=$(git describe --tags --abbrev=0 "${LATEST_RELEASE_BRANCH}")
RELEASE_VERSION="${TAG#v}"

log "Detected: release=${RELEASE_VERSION}, tag=${TAG}, next=${NEXT_VERSION}"

# ─── Step 4: Handle snapshot version bump on main ────────────────────────────
# Check if pre-release.sh created a snapshot bump commit on main
HEAD_MSG=$(git log -1 --format=%s)

if [[ "${HEAD_MSG}" == "Bump version to ${NEXT_VERSION}" ]]; then
    # Main has a new snapshot bump commit — move it to a PR branch
    SNAPSHOT_BRANCH="version/${NEXT_VERSION}"
    log "Moving snapshot commit to branch ${SNAPSHOT_BRANCH}..."

    git checkout -b "${SNAPSHOT_BRANCH}"
    git checkout main
    git reset --hard "${TAG}"

    NEEDS_SNAPSHOT_PR=true
else
    # Main already had the correct SNAPSHOT version — no bump needed
    log "Main already at ${NEXT_VERSION} — no version bump PR needed"
    NEEDS_SNAPSHOT_PR=false
fi

# ─── Step 5: Push tag (and PR branch if needed) ─────────────────────────────
log "Pushing tag ${TAG}..."
git push origin "${TAG}"

log "Pushing release branch ${LATEST_RELEASE_BRANCH}..."
git push origin "${LATEST_RELEASE_BRANCH}"

if [[ "${NEEDS_SNAPSHOT_PR}" == "true" ]]; then
    log "Pushing branch ${SNAPSHOT_BRANCH}..."
    git push -u origin "${SNAPSHOT_BRANCH}"

    log "Creating PR for version bump..."
    PR_URL=$(gh pr create \
        --repo "${REPO}" \
        --title "Bump version to ${NEXT_VERSION}" \
        --body "$(cat <<EOF
Automated version bump after release ${RELEASE_VERSION}.

- Bumps all pom.xml from \`${RELEASE_VERSION}\` to \`${NEXT_VERSION}\`
- Created by \`release/full-release.sh\`
EOF
)" \
        --base main \
        --head "${SNAPSHOT_BRANCH}" \
        2>&1)
    log "PR created: ${PR_URL}"
fi

# ─── Step 6: Wait for CI on tag ──────────────────────────────────────────────
log "Waiting for CI workflow on tag ${TAG}..."
echo "  Polling every 30s. This typically takes 30-45 minutes."
echo ""

# Wait for the workflow run to appear
WORKFLOW_RUN_ID=""
for attempt in $(seq 1 20); do
    WORKFLOW_RUN_ID=$(gh run list --repo "${REPO}" \
        --branch "${TAG}" --workflow ci.yml \
        --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)
    if [[ -n "${WORKFLOW_RUN_ID}" && "${WORKFLOW_RUN_ID}" != "null" ]]; then
        break
    fi
    echo "  [$(date +%H:%M:%S)] Waiting for workflow to start... (${attempt}/20)"
    sleep 15
done

[[ -n "${WORKFLOW_RUN_ID}" && "${WORKFLOW_RUN_ID}" != "null" ]] \
    || error "Could not find CI workflow run for tag ${TAG} after 5 minutes."

log "Found workflow run: ${WORKFLOW_RUN_ID}"
echo "  https://github.com/${REPO}/actions/runs/${WORKFLOW_RUN_ID}"
echo ""

# Poll until complete
while true; do
    RUN_STATUS=$(gh run view "${WORKFLOW_RUN_ID}" --repo "${REPO}" \
        --json status,conclusion --jq '.status + ":" + (.conclusion // "pending")' 2>/dev/null || echo "unknown:unknown")

    STATUS="${RUN_STATUS%%:*}"
    CONCLUSION="${RUN_STATUS##*:}"

    echo "  [$(date +%H:%M:%S)] Status: ${STATUS}, Conclusion: ${CONCLUSION}"

    if [[ "${STATUS}" == "completed" ]]; then
        if [[ "${CONCLUSION}" == "success" ]]; then
            log "CI passed!"
            break
        else
            error "CI failed with conclusion: ${CONCLUSION}. Check: https://github.com/${REPO}/actions/runs/${WORKFLOW_RUN_ID}"
        fi
    fi

    sleep 30
done

# ─── Step 7: Verify GitHub Release ──────────────────────────────────────────
log "Verifying GitHub Release for ${TAG}..."

gh release view "${TAG}" --repo "${REPO}" >/dev/null 2>&1 \
    || error "GitHub Release for ${TAG} not found despite CI success."

ASSET_COUNT=$(gh release view "${TAG}" --repo "${REPO}" --json assets --jq '.assets | length')
log "GitHub Release has ${ASSET_COUNT} assets"

# ─── Step 8: Run release.sh ─────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════"
echo "  CI complete. Starting release packaging..."
echo "═══════════════════════════════════════════════════════"
echo ""

exec "${SCRIPT_DIR}/release.sh" "${RELEASE_VERSION}"
