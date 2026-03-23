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
#   3. Optionally remove oldest release from dist/release
#   4. Clone apache/skywalking-website
#   5. Update data/releases.yml (add new version, migrate old version URLs to archive)
#   6. Update data/docs.yml (add new version entry)
#   7. Create release event post in content/events/
#   8. Create a PR on apache/skywalking-website combining all changes

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DISTRO_REPO="apache/skywalking-graalvm-distro"
WEBSITE_REPO="apache/skywalking-website"

SVN_DEV_BASE="https://dist.apache.org/repos/dist/dev/skywalking/graalvm-distro"
SVN_RELEASE_BASE="https://dist.apache.org/repos/dist/release/skywalking/graalvm-distro"

# URL patterns:
#   Current release (latest):  https://www.apache.org/dyn/closer.cgi/skywalking/...  (tarballs)
#                               https://downloads.apache.org/skywalking/...           (asc, sha512)
#   Archived releases (old):   https://archive.apache.org/dist/skywalking/...         (all files)

# ─── Usage ───────────────────────────────────────────────────────────────────
usage() {
    cat <<EOF
Usage: $0 <version>

Post-vote release tasks for Apache SkyWalking GraalVM Distro.

Run this AFTER the vote passes on dev@skywalking.apache.org.

What this script does:
  1. Brief you on all planned changes and ask for confirmation
  2. SVN move from dist/dev to dist/release/{version}/
  3. Optionally remove oldest release from dist/release (Apache policy: keep only latest)
  4. Update apache/skywalking-website in a single PR:
     - data/releases.yml  (add new version with closer.cgi URLs,
                            migrate previous version URLs to archive.apache.org)
     - data/docs.yml      (add new version entry, update Latest commitId)
     - content/events/    (create release announcement from changes/changes.md)

Prerequisites:
  - Vote has passed on dev@skywalking.apache.org
  - gh CLI authenticated with permission to create PRs on ${WEBSITE_REPO}
  - svn installed
  - Tag v<version> exists in ${DISTRO_REPO}
EOF
    exit 1
}

# ─── Helpers ─────────────────────────────────────────────────────────────────
log()   { echo "===> $*"; }
error() { echo "ERROR: $*" >&2; exit 1; }

# ─── Validate arguments ─────────────────────────────────────────────────────
[[ $# -eq 1 ]] || usage
VERSION="$1"
TAG="v${VERSION}"

# ─── Pre-flight checks ──────────────────────────────────────────────────────
log "Pre-flight checks..."

for cmd in git gh svn sed date python3; do
    command -v "${cmd}" >/dev/null 2>&1 || error "'${cmd}' is not installed"
done

# Verify tag exists
git rev-parse "${TAG}" >/dev/null 2>&1 \
    || error "Tag ${TAG} not found locally. Run: git fetch --tags"

# Verify changes/changes.md has release notes
CHANGES_FILE="${REPO_ROOT}/changes/changes.md"
[[ -f "${CHANGES_FILE}" ]] || error "changes/changes.md not found"
grep -q "^## ${VERSION}$" "${CHANGES_FILE}" \
    || error "changes/changes.md does not contain a '## ${VERSION}' section"

# GitHub CLI auth
gh auth status --hostname github.com >/dev/null 2>&1 \
    || error "'gh' is not authenticated. Run: gh auth login"

# Get the SkyWalking submodule commit for version display
SW_COMMIT=$(git -C "${REPO_ROOT}" ls-tree "${TAG}" skywalking | awk '{print $3}')
SW_COMMIT_SHORT="${SW_COMMIT:0:7}"

# Get the distro tag commit for docs.yml
TAG_COMMIT=$(git rev-parse "${TAG}")

# Determine the upstream SkyWalking version label used in website entries
# Format: X.Y.Z (10.3.0-dev-<short-commit>)
SW_VERSION_RAW=$(git show "${TAG}:skywalking/pom.xml" 2>/dev/null \
    | sed -n '/<version>/{s/.*<version>\(.*\)<\/version>.*/\1/;p;q;}')
if [[ -n "${SW_VERSION_RAW}" ]]; then
    VERSION_LABEL="${VERSION} (${SW_VERSION_RAW}-${SW_COMMIT_SHORT})"
else
    VERSION_LABEL="${VERSION}"
fi

# Format release date: "Mar. 23rd, 2026"
RELEASE_DATE=$(date -u +"%b. %d, %Y")
DAY=$(date -u +"%d")
DAY_NUM=$((10#${DAY}))
case "${DAY_NUM}" in
    1|21|31) SUFFIX="st" ;;
    2|22)    SUFFIX="nd" ;;
    3|23)    SUFFIX="rd" ;;
    *)       SUFFIX="th" ;;
esac
RELEASE_DATE=$(echo "${RELEASE_DATE}" | sed "s/ ${DAY},/ ${DAY_NUM}${SUFFIX},/")

TODAY=$(date -u +"%Y-%m-%d")

# ─── Briefing: show all planned changes ─────────────────────────────────────
SVN_DEV_DIR="${SVN_DEV_BASE}/${VERSION}"
SVN_RELEASE_DIR="${SVN_RELEASE_BASE}/${VERSION}"

ARTIFACT_PREFIX="apache-skywalking-graalvm-distro-${VERSION}"
CLOSER_BASE="https://www.apache.org/dyn/closer.cgi/skywalking/graalvm-distro/${VERSION}"
DOWNLOADS_BASE="https://downloads.apache.org/skywalking/graalvm-distro/${VERSION}"
ARCHIVE_NEW_BASE="https://archive.apache.org/dist/skywalking/graalvm-distro/${VERSION}"

EVENT_DIR_NAME="release-apache-skywalking-graalvm-distro-${VERSION}"

echo ""
echo "═══════════════════════════════════════════════════════════════════════════"
echo "  POST-VOTE RELEASE PLAN — SkyWalking GraalVM Distro ${VERSION}"
echo "═══════════════════════════════════════════════════════════════════════════"
echo ""
echo "  Release version : ${VERSION}"
echo "  Version label   : ${VERSION_LABEL}"
echo "  Git tag         : ${TAG}"
echo "  Tag commit      : ${TAG_COMMIT}"
echo "  SW submodule    : ${SW_COMMIT_SHORT}"
echo "  Release date    : ${RELEASE_DATE}"
echo ""
echo "─── 1. SVN: Move from dist/dev to dist/release ──────────────────────────"
echo ""
echo "  FROM: ${SVN_DEV_DIR}"
echo "  TO:   ${SVN_RELEASE_DIR}"
echo ""
echo "─── 2. Website: data/releases.yml ───────────────────────────────────────"
echo ""
echo "  ADD new version (${VERSION_LABEL}) with download URLs:"
echo "    Source tarball : ${CLOSER_BASE}/${ARTIFACT_PREFIX}-src.tar.gz"
echo "    Linux AMD64   : ${CLOSER_BASE}/${ARTIFACT_PREFIX}-linux-amd64.tar.gz"
echo "    Linux ARM64   : ${CLOSER_BASE}/${ARTIFACT_PREFIX}-linux-arm64.tar.gz"
echo "    MacOS ARM     : ${CLOSER_BASE}/${ARTIFACT_PREFIX}-darwin-arm64.tar.gz"
echo "    (asc/sha512 via downloads.apache.org)"
echo ""
echo "  MIGRATE previous version URLs:"
echo "    closer.cgi/skywalking/...      → archive.apache.org/dist/skywalking/..."
echo "    downloads.apache.org/skywalking/... → archive.apache.org/dist/skywalking/..."
echo ""
echo "─── 3. Website: data/docs.yml ───────────────────────────────────────────"
echo ""
echo "  UPDATE 'Latest' commitId → ${TAG_COMMIT}"
echo "  ADD new version entry: ${VERSION_LABEL}"
echo "    link: /docs/skywalking-graalvm-distro/v${VERSION}/readme/"
echo ""
echo "─── 4. Website: content/events/ ─────────────────────────────────────────"
echo ""
echo "  CREATE ${EVENT_DIR_NAME}/index.md"
echo "  Content from: changes/changes.md ## ${VERSION} section"
echo ""
echo "─── 5. Website PR ───────────────────────────────────────────────────────"
echo ""
echo "  Repository : ${WEBSITE_REPO}"
echo "  Branch     : graalvm-distro-${VERSION}-release"
echo "  All website changes combined in a single PR."
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
log "SVN: Verifying source exists in dist/dev..."
svn info "${SVN_DEV_DIR}" --username "${SVN_USER}" --password "${SVN_PASS}" --non-interactive >/dev/null 2>&1 \
    || error "SVN source not found: ${SVN_DEV_DIR}"

# Create parent directory in dist/release if needed
if ! svn info "${SVN_RELEASE_BASE}" --username "${SVN_USER}" --password "${SVN_PASS}" --non-interactive >/dev/null 2>&1; then
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

# ─── Step 3: Ask whether to remove oldest release from dist/release ─────────
# Apache policy: only the latest release should remain in dist/release.
# Older releases are auto-archived to archive.apache.org.
echo ""
log "Checking for older releases in dist/release..."

# List existing version directories in dist/release
EXISTING_VERSIONS=$(svn list "${SVN_RELEASE_BASE}" \
    --username "${SVN_USER}" --password "${SVN_PASS}" --non-interactive 2>/dev/null \
    | sed 's|/$||' | sort -V)

# Filter out the version we just released
OLD_VERSIONS=""
for v in ${EXISTING_VERSIONS}; do
    if [[ "${v}" != "${VERSION}" ]]; then
        OLD_VERSIONS="${OLD_VERSIONS}${v} "
    fi
done
OLD_VERSIONS=$(echo "${OLD_VERSIONS}" | xargs)  # trim

REMOVED_VERSIONS=""
if [[ -n "${OLD_VERSIONS}" ]]; then
    echo ""
    echo "  Found older version(s) in dist/release: ${OLD_VERSIONS}"
    echo "  Apache policy: only keep the latest release in dist/release."
    echo "  Older versions are automatically archived at archive.apache.org."
    echo ""
    echo "  If removed, the website download links and doc links for these"
    echo "  versions will also be removed from releases.yml and docs.yml."
    echo ""
    read -r -p "  Remove older version(s) from dist/release? [y/N] " remove_old
    if [[ "${remove_old}" =~ ^[Yy]$ ]]; then
        for old_ver in ${OLD_VERSIONS}; do
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
else
    log "No older versions found in dist/release."
fi

# ─── Step 4: Clone skywalking-website and create branch ─────────────────────
WORK_DIR="${SCRIPT_DIR}/release-package/website-pr"
rm -rf "${WORK_DIR}"
mkdir -p "${WORK_DIR}"

log "Cloning ${WEBSITE_REPO}..."
gh repo clone "${WEBSITE_REPO}" "${WORK_DIR}/skywalking-website" -- --depth 1

cd "${WORK_DIR}/skywalking-website"

BRANCH_NAME="graalvm-distro-${VERSION}-release"
git checkout -b "${BRANCH_NAME}"

# ─── Step 5: Update data/releases.yml ────────────────────────────────────────
log "Updating data/releases.yml..."

RELEASES_FILE="data/releases.yml"

# Python script to:
#   1. Add new version entry with closer.cgi / downloads.apache.org URLs
#   2. For kept old versions: migrate URLs to archive.apache.org
#   3. For removed versions: delete their entries entirely
python3 <<'PYEOF'
import sys

releases_file = sys.argv[1]
version = sys.argv[2]
version_label = sys.argv[3]
release_date = sys.argv[4]
removed_versions = set(sys.argv[5].split()) if len(sys.argv) > 5 and sys.argv[5] else set()

with open(releases_file, "r") as f:
    content = f.read()

prefix = f"apache-skywalking-graalvm-distro-{version}"
closer_base = f"https://www.apache.org/dyn/closer.cgi/skywalking/graalvm-distro/{version}"
downloads_base = f"https://downloads.apache.org/skywalking/graalvm-distro/{version}"

# New version source block
new_source_entry = f"""        - version: {version_label}
          date: {release_date}
          downloadLink:
            - name: src
              link: {closer_base}/{prefix}-src.tar.gz
            - name: asc
              link: {downloads_base}/{prefix}-src.tar.gz.asc
            - name: sha512
              link: {downloads_base}/{prefix}-src.tar.gz.sha512"""

# New version distribution block
new_dist_entry = f"""        - version: {version_label}
          date: {release_date}
          downloadLink:
            - name: Linux AMD64
              link: {closer_base}/{prefix}-linux-amd64.tar.gz
            - name: asc
              link: {downloads_base}/{prefix}-linux-amd64.tar.gz.asc
            - name: sha512
              link: {downloads_base}/{prefix}-linux-amd64.tar.gz.sha512
            - name: "|"
            - name: Linux ARM64
              link: {closer_base}/{prefix}-linux-arm64.tar.gz
            - name: asc
              link: {downloads_base}/{prefix}-linux-arm64.tar.gz.asc
            - name: sha512
              link: {downloads_base}/{prefix}-linux-arm64.tar.gz.sha512
            - name: "|"
            - name: MacOS ARM
              link: {closer_base}/{prefix}-darwin-arm64.tar.gz
            - name: asc
              link: {downloads_base}/{prefix}-darwin-arm64.tar.gz.asc
            - name: sha512
              link: {downloads_base}/{prefix}-darwin-arm64.tar.gz.sha512"""

graalvm_marker = "A re-distribution of the Apache SkyWalking OAP server"

def should_remove_entry(version_line):
    """Check if a '- version: ...' line belongs to a removed version."""
    for rv in removed_versions:
        # Match version lines like "- version: 0.2.1 (10.3.0-dev-64a1795)"
        # The version number appears right after "version: "
        if f"- version: {rv} " in version_line or f"- version: {rv}" == version_line.strip():
            return True
    return False

def process_old_entries(lines, i, stop_condition):
    """Process old version entries: remove entries for removed versions,
    migrate URLs to archive.apache.org for kept versions."""
    kept = []
    while i < len(lines):
        old_line = lines[i]
        old_stripped = old_line.lstrip()
        old_indent = len(old_line) - len(old_stripped)

        if stop_condition(old_stripped, old_indent):
            break

        # Check if this is a version entry that should be removed
        if old_stripped.startswith("- version:") and should_remove_entry(old_stripped):
            # Skip this entire version entry (version, date, downloadLink, and all items)
            i += 1
            while i < len(lines):
                next_stripped = lines[i].lstrip()
                next_indent = len(lines[i]) - len(next_stripped)
                # Stop at the next version entry or section boundary
                if next_stripped.startswith("- version:") or stop_condition(next_stripped, next_indent):
                    break
                i += 1
            continue

        # Keep the entry but migrate URLs to archive.apache.org
        migrated = old_line
        migrated = migrated.replace("https://www.apache.org/dyn/closer.cgi/skywalking/", "https://archive.apache.org/dist/skywalking/")
        migrated = migrated.replace("https://downloads.apache.org/skywalking/", "https://archive.apache.org/dist/skywalking/")
        kept.append(migrated)
        i += 1

    return kept, i

lines = content.split("\n")
result = []
i = 0
in_graalvm = False
inserted_source = False
inserted_dist = False

while i < len(lines):
    line = lines[i]

    if graalvm_marker in line:
        in_graalvm = True
        result.append(line)
        i += 1
        continue

    if in_graalvm:
        stripped = line.lstrip()
        indent = len(line) - len(stripped)

        # Found "source:" key — prepend new entry, process old entries
        if not inserted_source and stripped == "source:" and indent == 6:
            result.append(line)  # keep "      source:"
            result.append(new_source_entry)
            inserted_source = True
            i += 1

            def source_stop(s, ind):
                if ind == 6 and s.startswith("distribution:"):
                    return True
                if ind <= 6 and s and s not in ("") \
                   and not s.startswith("-") and not s.startswith("version:") \
                   and not s.startswith("date:") and not s.startswith("downloadLink:") \
                   and not s.startswith("name:") and not s.startswith("link:"):
                    return True
                return False

            kept, i = process_old_entries(lines, i, source_stop)
            result.extend(kept)
            continue

        # Found "distribution:" key — prepend new entry, process old entries
        if not inserted_dist and stripped == "distribution:" and indent == 6:
            result.append(line)  # keep "      distribution:"
            result.append(new_dist_entry)
            inserted_dist = True
            i += 1

            def dist_stop(s, ind):
                return ind <= 4 and bool(s)

            kept, i = process_old_entries(lines, i, dist_stop)
            result.extend(kept)
            in_graalvm = False
            continue

    result.append(line)
    i += 1

with open(releases_file, "w") as f:
    f.write("\n".join(result))

actions = [f"added {version_label}"]
if removed_versions:
    actions.append(f"removed {', '.join(sorted(removed_versions))}")
actions.append("migrated kept old version URLs to archive.apache.org")
print(f"releases.yml: {'; '.join(actions)}")
PYEOF
"${RELEASES_FILE}" "${VERSION}" "${VERSION_LABEL}" "${RELEASE_DATE}" "${REMOVED_VERSIONS}"

# ─── Step 6: Update data/docs.yml ────────────────────────────────────────────
log "Updating data/docs.yml..."

DOCS_FILE="data/docs.yml"

python3 <<'PYEOF'
import sys

docs_file = sys.argv[1]
version = sys.argv[2]
version_label = sys.argv[3]
tag_commit = sys.argv[4]
removed_versions = set(sys.argv[5].split()) if len(sys.argv) > 5 and sys.argv[5] else set()

with open(docs_file, "r") as f:
    content = f.read()

def is_removed_version(version_line):
    """Check if a '- version: ...' line belongs to a removed version."""
    for rv in removed_versions:
        if f"- version: v{rv} " in version_line or f"- version: v{rv}" == version_line.strip():
            return True
        if f"- version: {rv} " in version_line or f"- version: {rv}" == version_line.strip():
            return True
    return False

lines = content.split("\n")
result = []
i = 0
graalvm_marker = "A re-distribution of the Apache SkyWalking OAP server"
in_graalvm = False
in_docs = False
found_latest = False
inserted_new = False

while i < len(lines):
    line = lines[i]

    if graalvm_marker in line:
        in_graalvm = True
        result.append(line)
        i += 1
        continue

    if in_graalvm and "docs:" in line and not in_docs:
        in_docs = True
        result.append(line)
        i += 1
        continue

    if in_graalvm and in_docs:
        stripped = line.lstrip()

        # Found "- version: Latest" — update its commitId
        if stripped.startswith("- version: Latest"):
            result.append(line)
            i += 1
            # Next line should be link
            if i < len(lines) and "link:" in lines[i]:
                result.append(lines[i])
                i += 1
            # Next line should be commitId — replace it
            if i < len(lines) and "commitId:" in lines[i]:
                indent = len(lines[i]) - len(lines[i].lstrip())
                result.append(" " * indent + "commitId: " + tag_commit)
                i += 1
            found_latest = True
            continue

        # After Latest, before the first versioned entry, insert new version
        if found_latest and not inserted_new and stripped.startswith("- version:"):
            indent = len(line) - len(stripped)
            result.append(" " * indent + f"- version: {version_label}")
            result.append(" " * indent + f"  link: /docs/skywalking-graalvm-distro/v{version}/readme/")
            result.append(" " * indent + f"  commitId: {tag_commit}")
            inserted_new = True
            # Fall through to check if the current entry should be removed

        # Skip version entries for removed versions
        if stripped.startswith("- version:") and is_removed_version(stripped):
            i += 1
            # Skip the link and commitId lines that follow
            while i < len(lines):
                next_stripped = lines[i].lstrip()
                if next_stripped.startswith("- version:") or \
                   (next_stripped and not next_stripped.startswith("link:") \
                    and not next_stripped.startswith("commitId:")):
                    break
                i += 1
            continue

        # Detect end of docs section
        if inserted_new and stripped and not stripped.startswith("-") \
           and not stripped.startswith("version:") and not stripped.startswith("link:") \
           and not stripped.startswith("commitId:"):
            indent_level = len(line) - len(stripped)
            if indent_level <= 4:
                in_graalvm = False
                in_docs = False

    result.append(line)
    i += 1

with open(docs_file, "w") as f:
    f.write("\n".join(result))

actions = [f"added {version_label}", "updated Latest commitId"]
if removed_versions:
    actions.append(f"removed doc entries for {', '.join(sorted(removed_versions))}")
print(f"docs.yml: {'; '.join(actions)}")
PYEOF
"${DOCS_FILE}" "${VERSION}" "${VERSION_LABEL}" "${TAG_COMMIT}" "${REMOVED_VERSIONS}"

# ─── Step 7: Create release event post ──────────────────────────────────────
log "Creating release event post..."

EVENT_DIR="content/events/${EVENT_DIR_NAME}"
mkdir -p "${EVENT_DIR}"

# Extract release notes from changes/changes.md (the section for this version)
RELEASE_NOTES=$(sed -n "/^## ${VERSION}$/,/^## /p" "${CHANGES_FILE}" | sed '1d;/^## /d')

# Calculate end time (~2 months from now)
if date -v+2m >/dev/null 2>&1; then
    END_DATE=$(date -v+2m -u +"%Y-%m-%dT00:00:00Z")
else
    END_DATE=$(date -u -d "+2 months" +"%Y-%m-%dT00:00:00Z")
fi

cat > "${EVENT_DIR}/index.md" <<EVENTEOF
---
title: Release Apache SkyWalking GraalVM Distro version ${VERSION}
date: ${TODAY}
author: SkyWalking Team
description: "Release Apache SkyWalking GraalVM Distro ${VERSION}"
endTime: ${END_DATE}
---

SkyWalking GraalVM Distro ${VERSION} is released. Go to [downloads](https://skywalking.apache.org/downloads/#SkyWalkingGraalVMDistro) page to find release tars.

${RELEASE_NOTES}
EVENTEOF

log "Event post created: ${EVENT_DIR}/index.md"

# ─── Step 8: Commit and create PR ───────────────────────────────────────────
log "Creating PR on ${WEBSITE_REPO}..."

git add data/releases.yml data/docs.yml "${EVENT_DIR}/index.md"
git commit -m "Add SkyWalking GraalVM Distro ${VERSION} release"

git push -u origin "${BRANCH_NAME}"

PR_URL=$(gh pr create \
    --repo "${WEBSITE_REPO}" \
    --title "Add SkyWalking GraalVM Distro ${VERSION} release" \
    --body "$(cat <<EOF
## Summary

- Update \`data/releases.yml\`: add ${VERSION} download links (closer.cgi), migrate previous version URLs to archive.apache.org${REMOVED_VERSIONS:+, remove entries for ${REMOVED_VERSIONS}}
- Update \`data/docs.yml\`: add ${VERSION} documentation version, update Latest commitId${REMOVED_VERSIONS:+, remove doc entries for ${REMOVED_VERSIONS}}
- Add release event post for ${VERSION}

Release notes: https://github.com/${DISTRO_REPO}/blob/v${VERSION}/changes/changes.md
EOF
)" \
    --base master \
    --head "${BRANCH_NAME}" \
    2>&1)

log "PR created: ${PR_URL}"

# ─── Summary ─────────────────────────────────────────────────────────────────
echo ""
log "Post-vote release tasks complete!"
echo ""
echo "  SVN release : ${SVN_RELEASE_DIR}"
echo "  Website PR  : ${PR_URL}"
echo ""
echo "Next steps:"
echo "  1. Review and merge the website PR"
echo "  2. Send [ANNOUNCE] email to dev@skywalking.apache.org"
