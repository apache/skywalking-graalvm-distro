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

# Verify GPG signing works before starting the release process.
# The user must sign a test file to cache the passphrase in gpg-agent.
export GPG_TTY=$(tty)
echo ""
echo "Please sign a test file to verify GPG and cache your passphrase."
echo "Run this command now:"
echo ""
echo "    echo test | gpg -s > /dev/null"
echo ""
read -r -p "Press Enter after signing succeeds... "

GPG_TEST_FILE=$(mktemp)
echo "release-preflight-check" > "${GPG_TEST_FILE}"
if ! gpg --armor --detach-sign "${GPG_TEST_FILE}" 2>/dev/null; then
    rm -f "${GPG_TEST_FILE}" "${GPG_TEST_FILE}.asc"
    error "GPG signing still fails. Check: export GPG_TTY=\$(tty) and try again."
fi
rm -f "${GPG_TEST_FILE}" "${GPG_TEST_FILE}.asc"
log "  GPG signer: ${GPG_EMAIL} (signing verified)"

gh auth status --hostname github.com >/dev/null 2>&1 \
    || error "'gh' is not authenticated. Run: gh auth login"
log "  GitHub CLI: authenticated"

# ─── Step 2: Run pre-release.sh ──────────────────────────────────────────────
log "Running pre-release.sh..."
echo ""

"${SCRIPT_DIR}/pre-release.sh"

# ─── Step 3: Extract versions from what pre-release.sh created ───────────────
# After pre-release.sh, we're back on main (unchanged). The release branch has
# two commits: release version + next SNAPSHOT, with the tag on the first.
RELEASE_BRANCH=$(git branch --list 'release/v*' --sort=-creatordate | head -1 | tr -d ' ')
TAG=$(git describe --tags --abbrev=0 "${RELEASE_BRANCH}~1" 2>/dev/null \
    || git describe --tags --abbrev=0 "${RELEASE_BRANCH}")
RELEASE_VERSION="${TAG#v}"

log "Detected: release=${RELEASE_VERSION}, tag=${TAG}, branch=${RELEASE_BRANCH}"

# ─── Step 4: Push tag and release branch, create PR ─────────────────────────
log "Pushing tag ${TAG}..."
git push origin "${TAG}"

log "Pushing release branch ${RELEASE_BRANCH}..."
git push -u origin "${RELEASE_BRANCH}"

log "Creating PR for release branch..."
PR_URL=$(gh pr create \
    --repo "${REPO}" \
    --title "Release ${RELEASE_VERSION}: bump version to next SNAPSHOT" \
    --body "$(cat <<EOF
Release branch for ${RELEASE_VERSION}.

- Commit 1: Bump version to \`${RELEASE_VERSION}\` (tagged \`v${RELEASE_VERSION}\`)
- Commit 2: Bump version to next SNAPSHOT

Merge after release vote passes to update main to the next development version.
Created by \`release/full-release.sh\`.
EOF
)" \
    --base main \
    --head "${RELEASE_BRANCH}" \
    2>&1)
log "PR created: ${PR_URL}"

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
