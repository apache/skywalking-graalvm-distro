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

# Post-vote release script. Run AFTER the vote passes.
#
# What this script does:
#   1. Show a full briefing of planned changes and ask for confirmation
#   2. SVN move: dist/dev → dist/release (with version directory)
#   3. Optionally remove releases older than this one from dist/release,
#      each only once archive.apache.org serves every file of it
#
# The apache/skywalking-website update is NOT done here — it is a manual PR that follows
# the website's own release instructions (see docs/release-guide.md). The scripted update
# this replaced edited the website's shared YAML across sibling projects' entries.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DISTRO_REPO="apache/skywalking-graalvm-distro"

SVN_DEV_BASE="https://dist.apache.org/repos/dist/dev/skywalking/graalvm-distro"
SVN_RELEASE_BASE="https://dist.apache.org/repos/dist/release/skywalking/graalvm-distro"
DOWNLOADS_BASE="https://downloads.apache.org/skywalking/graalvm-distro"
ARCHIVE_BASE="https://archive.apache.org/dist/skywalking/graalvm-distro"

# ─── Usage ───────────────────────────────────────────────────────────────────
usage() {
    cat <<EOF
Usage: $0 <version>

Post-vote release tasks for Apache SkyWalking GraalVM Distro.

Run this AFTER the vote passes on dev@skywalking.apache.org.

What this script does:
  1. Brief you on all planned changes and ask for confirmation
  2. SVN move from dist/dev to dist/release/skywalking/graalvm-distro/{version}/
  3. Optionally remove releases older than {version} from dist/release, each only
     once archive.apache.org serves every file of it; run it again after the
     website PR to do this step

What you still do by hand afterwards (see docs/release-guide.md):
  - Merge the release/v{version} PR into main
  - PR on apache/skywalking-website: data/projects.yml and the release event post
  - [ANNOUNCE] email to dev@skywalking.apache.org

Prerequisites:
  - Vote has passed on dev@skywalking.apache.org
  - svn and curl installed
  - Tag v<version> exists in ${DISTRO_REPO}
EOF
    exit 1
}

# ─── Helpers ─────────────────────────────────────────────────────────────────
log()   { echo "===> $*"; }
error() { echo "ERROR: $*" >&2; exit 1; }

# Whether an SVN URL exists. Only SVN's "non-existent" answer means no; any other
# failure (credentials, network) aborts instead of being read as a missing path.
svn_exists() {
    local out
    if out=$(svn info "$1" --username "${SVN_USER}" --password "${SVN_PASS}" --non-interactive 2>&1); then
        return 0
    fi
    grep -q "W170000" <<<"${out}" && return 1
    error "svn info $1 failed:
${out}"
}

# Canonical X.Y.Z (no leading zeros, so equal versions compare equal as strings), optionally -<prerelease>.
VERSION_RE='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.]+)?$'

# Whether release $1 precedes release $2: X.Y.Z numerically, and X.Y.Z-<pre> before X.Y.Z.
# Two prereleases of the same X.Y.Z are not ordered, so neither counts as older.
older_than() {
    local a="${1%%-*}" b="${2%%-*}"
    if [[ "${a}" == "${b}" ]]; then
        [[ "$1" == *-* && "$2" != *-* ]]
        return
    fi
    [[ "$(printf '%s\n%s\n' "${a}" "${b}" | sort -V | head -1)" == "${a}" ]]
}

# Whether archive.apache.org serves every file dist/release holds for release $1.
archived() {
    local files f
    files=$(svn list "${SVN_RELEASE_BASE}/$1" --username "${SVN_USER}" --password "${SVN_PASS}" --non-interactive) \
        || error "svn list ${SVN_RELEASE_BASE}/$1 failed"
    [[ -n "${files}" ]] || return 1
    for f in ${files}; do
        curl -sfIL --connect-timeout 10 --max-time 60 -o /dev/null "${ARCHIVE_BASE}/$1/${f}" || return 1
    done
}

# ─── Validate arguments ─────────────────────────────────────────────────────
[[ $# -eq 1 ]] || usage
VERSION="$1"
[[ "${VERSION}" =~ ${VERSION_RE} ]] || error "Version '${VERSION}' is not X.Y.Z or X.Y.Z-<prerelease>"
TAG="v${VERSION}"

# ─── Pre-flight checks ──────────────────────────────────────────────────────
log "Pre-flight checks..."

for cmd in git svn curl sed sort xargs; do
    command -v "${cmd}" >/dev/null 2>&1 || error "'${cmd}' is not installed"
done

# Verify tag exists
git -C "${REPO_ROOT}" rev-parse "${TAG}" >/dev/null 2>&1 \
    || error "Tag ${TAG} not found locally. Run: git fetch --tags"

TAG_COMMIT=$(git -C "${REPO_ROOT}" rev-parse "${TAG}^{commit}")

# ─── Briefing: show all planned changes ─────────────────────────────────────
SVN_DEV_DIR="${SVN_DEV_BASE}/${VERSION}"
SVN_RELEASE_DIR="${SVN_RELEASE_BASE}/${VERSION}"

echo ""
echo "═══════════════════════════════════════════════════════════════════════════"
echo "  POST-VOTE RELEASE PLAN — SkyWalking GraalVM Distro ${VERSION}"
echo "═══════════════════════════════════════════════════════════════════════════"
echo ""
echo "  Release version : ${VERSION}"
echo "  Git tag         : ${TAG}"
echo "  Tag commit      : ${TAG_COMMIT}"
echo ""
echo "─── 1. SVN: Move from dist/dev to dist/release ──────────────────────────"
echo ""
echo "  FROM: ${SVN_DEV_DIR}"
echo "  TO:   ${SVN_RELEASE_DIR}"
echo ""
echo "─── 2. SVN: Optionally drop older releases from dist/release ────────────"
echo ""
echo "  You will be asked. Only releases older than ${VERSION} that archive.apache.org"
echo "  serves in full are offered."
echo ""
echo "  The website update is NOT done by this script — see docs/release-guide.md."
echo ""
echo "═══════════════════════════════════════════════════════════════════════════"
echo ""
read -r -p "Proceed with all the above? [y/N] " confirm
[[ "${confirm}" =~ ^[Yy]$ ]] || { echo "Aborted."; exit 0; }
echo ""

# ─── Step 1: SVN credentials ────────────────────────────────────────────────
read -r -p "Apache SVN username (LDAP): " SVN_USER
[[ -n "${SVN_USER}" ]] || error "SVN username is required"
read -r -s -p "Apache SVN password: " SVN_PASS
echo ""
[[ -n "${SVN_PASS}" ]] || error "SVN password is required"

# ─── Step 2: SVN move from dist/dev to dist/release ─────────────────────────
# Check if already moved (idempotent)
if svn_exists "${SVN_RELEASE_DIR}"; then
    log "SVN: ${SVN_RELEASE_DIR} already exists — skipping move (already done)."
else
    log "SVN: Verifying source exists in dist/dev..."
    svn_exists "${SVN_DEV_DIR}" || error "SVN source not found: ${SVN_DEV_DIR}"

    # Create parent directory in dist/release if needed
    if ! svn_exists "${SVN_RELEASE_BASE}"; then
        log "Creating ${SVN_RELEASE_BASE}..."
        svn mkdir "${SVN_RELEASE_BASE}" \
            -m "Create graalvm-distro directory in dist/release" \
            --username "${SVN_USER}" --password "${SVN_PASS}" --non-interactive
    fi

    log "SVN: Moving dist/dev/${VERSION} → dist/release/${VERSION}..."
    svn mv "${SVN_DEV_DIR}" "${SVN_RELEASE_DIR}" \
        -m "Release Apache SkyWalking GraalVM Distro ${VERSION}" \
        --username "${SVN_USER}" --password "${SVN_PASS}" --non-interactive

    log "SVN move complete: ${SVN_RELEASE_DIR}"
fi

# ─── Step 3: Ask whether to remove older releases from dist/release ─────────
# ASF policy: dist/release holds the latest release of each line under development;
# every release stays available at archive.apache.org.
echo ""
log "Checking for older releases in dist/release..."

# Version directories in dist/release
EXISTING_VERSIONS=$(svn list "${SVN_RELEASE_BASE}" \
    --username "${SVN_USER}" --password "${SVN_PASS}" --non-interactive \
    | sed -n 's|/$||p' | sort -V)

ARCHIVED_OLD=""
UNARCHIVED_OLD=""
for v in ${EXISTING_VERSIONS}; do
    if ! [[ "${v}" =~ ${VERSION_RE} ]] || ! older_than "${v}" "${VERSION}"; then
        continue
    fi
    if archived "${v}"; then
        ARCHIVED_OLD="${ARCHIVED_OLD}${v} "
    else
        UNARCHIVED_OLD="${UNARCHIVED_OLD}${v} "
    fi
done
ARCHIVED_OLD=$(echo "${ARCHIVED_OLD}" | xargs)  # trim
UNARCHIVED_OLD=$(echo "${UNARCHIVED_OLD}" | xargs)

REMOVED_VERSIONS=""
if [[ -n "${UNARCHIVED_OLD}" ]]; then
    echo ""
    echo "  Older than ${VERSION}, not served in full by archive.apache.org yet (kept): ${UNARCHIVED_OLD}"
    echo "  archive.apache.org takes about a day after a release first appears; re-run later."
fi
if [[ -n "${ARCHIVED_OLD}" ]]; then
    echo ""
    echo "  Older than ${VERSION}, served in full by archive.apache.org: ${ARCHIVED_OLD}"
    echo "  ASF policy keeps only the latest release of each line under development in"
    echo "  dist/release; every release stays available at archive.apache.org."
    echo "  If the website PR moving their links to archive.apache.org is not live yet,"
    echo "  answer N and re-run this script once it is: removing them now breaks the"
    echo "  website's downloads.apache.org links to them."
    echo ""
    read -r -p "  Remove ${ARCHIVED_OLD} from dist/release? [y/N] " remove_old
    if [[ "${remove_old}" =~ ^[Yy]$ ]]; then
        for old_ver in ${ARCHIVED_OLD}; do
            log "SVN: Removing ${SVN_RELEASE_BASE}/${old_ver}..."
            svn rm "${SVN_RELEASE_BASE}/${old_ver}" \
                -m "Remove old GraalVM Distro ${old_ver} from dist/release (archived at archive.apache.org)" \
                --username "${SVN_USER}" --password "${SVN_PASS}" --non-interactive
            log "Removed: ${old_ver}"
            REMOVED_VERSIONS="${REMOVED_VERSIONS}${old_ver} "
        done
        REMOVED_VERSIONS=$(echo "${REMOVED_VERSIONS}" | xargs)
    else
        log "Keeping older versions in dist/release."
    fi
elif [[ -z "${UNARCHIVED_OLD}" ]]; then
    log "No releases older than ${VERSION} in dist/release."
fi

# ─── Summary ─────────────────────────────────────────────────────────────────
echo ""
log "Post-vote SVN tasks complete!"
echo ""
echo "  SVN release : ${SVN_RELEASE_DIR}"
if [[ -n "${REMOVED_VERSIONS}" ]]; then
    echo "  Removed     : ${REMOVED_VERSIONS}"
fi
if [[ -n "${UNARCHIVED_OLD}" ]]; then
    echo "  Not on archive.apache.org yet, kept : ${UNARCHIVED_OLD}"
fi
echo ""
echo "Next steps (manual — see docs/release-guide.md):"
echo "  1. Merge the release/v${VERSION} PR into main."
echo "  2. Prepare a PR on apache/skywalking-website, following its README.md:"
echo "     - data/projects.yml, project 'repo: skywalking-graalvm-distro': add v${VERSION}"
echo "       on top with 'latest: true' and docs commitId ${TAG_COMMIT};"
echo "       set the previous release to 'latest: false' and move its download,"
echo "       .asc and .sha512 links to archive.apache.org once it serves them"
if [[ -n "${UNARCHIVED_OLD}" ]]; then
    echo "       (not yet for ${UNARCHIVED_OLD}: keep their downloads.apache.org links)"
fi
echo "     - content/events/release-apache-skywalking-graalvm-distro-${VERSION//./-}/index.md"
echo "     - run 'node scripts/project-config.js' there before pushing"
echo "  3. Merge it at least an hour after the SVN move, once every file of ${VERSION}"
echo "     is served at ${DOWNLOADS_BASE}/${VERSION}/."
echo "  4. Once the downloads page lists ${VERSION}, send [ANNOUNCE] to dev@skywalking.apache.org."
if [[ -n "${ARCHIVED_OLD}" && -z "${REMOVED_VERSIONS}" ]]; then
    echo "  5. Once the website links ${ARCHIVED_OLD} to archive.apache.org, re-run"
    echo "     $0 ${VERSION} to remove them from dist/release."
fi
