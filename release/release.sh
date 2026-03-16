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

ARTIFACT_PREFIX="apache-skywalking-graalvm-distro"
RELEASE_DIR="release-package"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO="apache/skywalking-graalvm-distro"

# ─── Usage ───────────────────────────────────────────────────────────────────
usage() {
    cat <<EOF
Usage: $0 <version>

Package an Apache-style release for SkyWalking GraalVM Distro.

Prerequisites:
  - CI release workflow must have completed for tag v<version>
    (pushes linux-amd64 and linux-arm64 tarballs to GitHub Release)
  - GraalVM JDK 25 installed (for building darwin-arm64 native image)
  - GPG key with @apache.org email must be configured

What this script does:
  1. Creates a clean source tarball from git tag (not local working tree)
  2. Builds macOS arm64 (Apple Silicon) native binary locally
  3. Uploads darwin-arm64 tarball to GitHub Release page
  4. Downloads Linux binary tarballs from GitHub Release
  5. Signs all tarballs with GPG and generates SHA-512 checksums
  6. Uploads all artifacts to Apache SVN dist/dev for voting

Directory structure:
  ${RELEASE_DIR}/
    tmp/                    Temporary work (cleaned at end)
      src-clone/            Git clone from tag (deleted after source tarball)
      download/             Downloaded CI artifacts
    dist/                   Final release artifacts
      ${ARTIFACT_PREFIX}-<version>-src.tar.gz{,.asc,.sha512}
      ${ARTIFACT_PREFIX}-<version>-linux-amd64.tar.gz{,.asc,.sha512}
      ${ARTIFACT_PREFIX}-<version>-linux-arm64.tar.gz{,.asc,.sha512}
      ${ARTIFACT_PREFIX}-<version>-darwin-arm64.tar.gz{,.asc,.sha512}
EOF
    exit 1
}

# ─── Helpers ─────────────────────────────────────────────────────────────────
log()   { echo "===> $*"; }
error() { echo "ERROR: $*" >&2; exit 1; }

sign_and_checksum() {
    local file="$1"
    log "Signing ${file}..."
    gpg --armor --detach-sign "${file}"
    log "Generating SHA-512 checksum for ${file}..."
    (cd "$(dirname "${file}")" && shasum -a 512 "$(basename "${file}")" > "$(basename "${file}").sha512")
}

# ─── Validate arguments ─────────────────────────────────────────────────────
[[ $# -eq 1 ]] || usage
VERSION="$1"
TAG="v${VERSION}"

# ─── Pre-flight checks ──────────────────────────────────────────────────────
log "Pre-flight checks..."

# Check required tools
for cmd in git gpg tar gh shasum make svn; do
    command -v "${cmd}" >/dev/null 2>&1 || error "'${cmd}' is not installed"
done

# Verify default GPG key exists and is @apache.org
gpg --list-secret-keys --keyid-format LONG >/dev/null 2>&1 \
    || error "No GPG secret keys found. Import or generate a key first."
GPG_KEY=$(gpg --list-secret-keys --keyid-format LONG 2>/dev/null \
    | grep -A1 "^sec" | head -2)
GPG_EMAIL=$(gpg --list-secret-keys --keyid-format LONG 2>/dev/null \
    | grep "uid" | head -1 | sed 's/.*<\(.*\)>.*/\1/')
[[ "${GPG_EMAIL}" == *@apache.org ]] \
    || error "GPG key email '${GPG_EMAIL}' is not an @apache.org address. Apache releases must be signed with your Apache committer key."

echo ""
echo "Release version : ${VERSION}"
echo "Git tag         : ${TAG}"
echo "GPG signing key :"
echo "${GPG_KEY}"
echo "  uid: ${GPG_EMAIL}"
echo ""
read -r -p "Proceed with this GPG key? [y/N] " confirm
[[ "${confirm}" =~ ^[Yy]$ ]] || { echo "Aborted."; exit 0; }
echo ""

# Verify tag exists locally
git rev-parse "${TAG}" >/dev/null 2>&1 \
    || error "Tag ${TAG} not found locally. Run: git fetch --tags"

# Verify GitHub Release exists
gh release view "${TAG}" --repo "${REPO}" >/dev/null 2>&1 \
    || error "GitHub Release for ${TAG} not found. Run the CI release workflow first."

# Verify changes/changes.md contains a section for this version
CHANGES_FILE="${REPO_ROOT}/changes/changes.md"
[[ -f "${CHANGES_FILE}" ]] || error "changes/changes.md not found"
if ! grep -q "^## ${VERSION}$" "${CHANGES_FILE}"; then
    error "changes/changes.md does not contain a '## ${VERSION}' section. Add release notes before releasing."
fi
# Verify the section has content (at least one non-empty line before next ## or EOF)
SECTION_CONTENT=$(sed -n "/^## ${VERSION}$/,/^## /p" "${CHANGES_FILE}" | sed '1d;/^## /d' | grep -v '^$' | head -1)
[[ -n "${SECTION_CONTENT}" ]] \
    || error "changes/changes.md has a '## ${VERSION}' section but it is empty. Add release notes."
log "changes/changes.md: found release notes for ${VERSION}"

# ─── Step 1: Prepare release directory ────────────────────────────────────────
log "Preparing release directory..."
rm -rf "${SCRIPT_DIR}/${RELEASE_DIR}"
mkdir -p "${SCRIPT_DIR}/${RELEASE_DIR}/tmp/download"
mkdir -p "${SCRIPT_DIR}/${RELEASE_DIR}/dist"

TMP_DIR="${SCRIPT_DIR}/${RELEASE_DIR}/tmp"
DIST_DIR="${SCRIPT_DIR}/${RELEASE_DIR}/dist"

# ─── Step 2: Source package ───────────────────────────────────────────────────
log "Creating source package from tag ${TAG}..."

SRC_TARBALL="${ARTIFACT_PREFIX}-${VERSION}-src.tar.gz"
SRC_CLONE_DIR="${TMP_DIR}/src-clone"

# Clone from remote at the release tag (not local working tree)
git clone --branch "${TAG}" --recurse-submodules \
    "https://github.com/${REPO}.git" "${SRC_CLONE_DIR}"

# Pre-generate version.properties before removing .git (no git in source tarball)
SRC_SW_COMMIT=$(git -C "${SRC_CLONE_DIR}/skywalking" rev-parse HEAD)
mkdir -p "${SRC_CLONE_DIR}/oap-graalvm-server/src/main/resources"
cat > "${SRC_CLONE_DIR}/oap-graalvm-server/src/main/resources/version.properties" <<VPEOF
#Generated by release.sh
git.build.version=${VERSION}-graal-distro
git.commit.id=${SRC_SW_COMMIT}
VPEOF
log "Pre-generated version.properties for source tarball"

# Remove all .git directories and files (root + submodules)
# Submodule .git entries are files (not directories), so match both types
find "${SRC_CLONE_DIR}" -name ".git" -exec rm -rf {} + 2>/dev/null || true
find "${SRC_CLONE_DIR}" -name ".gitmodules" -type f -delete 2>/dev/null || true

# Remove build artifacts and unnecessary files
find "${SRC_CLONE_DIR}" -type d -name "target" -exec rm -rf {} + 2>/dev/null || true
find "${SRC_CLONE_DIR}" -name "*.jar" -delete 2>/dev/null || true
find "${SRC_CLONE_DIR}" -name "*.class" -delete 2>/dev/null || true
find "${SRC_CLONE_DIR}" -name ".DS_Store" -delete 2>/dev/null || true
rm -rf "${SRC_CLONE_DIR}/.idea" "${SRC_CLONE_DIR}/.vscode" "${SRC_CLONE_DIR}/.claude"

# Create tarball from clean source
tar -czf "${DIST_DIR}/${SRC_TARBALL}" \
    -C "${TMP_DIR}" \
    -s "/^src-clone/${ARTIFACT_PREFIX}-${VERSION}-src/" \
    src-clone

# Clean up clone
rm -rf "${SRC_CLONE_DIR}"

log "Source package created: dist/${SRC_TARBALL}"

# ─── Step 3: Build darwin-arm64 native binary ─────────────────────────────────
log "Building macOS arm64 (Apple Silicon) native binary..."
(cd "${REPO_ROOT}" && make native-image)

NATIVE_SRC=$(ls "${REPO_ROOT}"/oap-graalvm-native/target/oap-graalvm-native-*-native-dist.tar.gz)
DARWIN_TARBALL="${ARTIFACT_PREFIX}-${VERSION}-darwin-arm64.tar.gz"
cp "${NATIVE_SRC}" "${DIST_DIR}/${DARWIN_TARBALL}"

log "darwin-arm64 tarball created: dist/${DARWIN_TARBALL}"

# ─── Step 4: Upload darwin-arm64 to GitHub Release ────────────────────────────
log "Uploading darwin-arm64 tarball to GitHub Release ${TAG}..."

DARWIN_SHA512="${DARWIN_TARBALL}.sha512"
(cd "${DIST_DIR}" && shasum -a 512 "${DARWIN_TARBALL}" > "${DARWIN_SHA512}")
gh release upload "${TAG}" --repo "${REPO}" \
    "${DIST_DIR}/${DARWIN_TARBALL}" "${DIST_DIR}/${DARWIN_SHA512}" \
    --clobber

log "darwin-arm64 uploaded to GitHub Release"

# ─── Step 5: Download Linux binary tarballs from GitHub Release ───────────────
log "Downloading Linux binary tarballs from GitHub Release ${TAG}..."

DOWNLOAD_DIR="${TMP_DIR}/download"

gh release download "${TAG}" --repo "${REPO}" \
    --pattern "*-linux-*.tar.gz" \
    --pattern "*-linux-*.tar.gz.sha512" \
    --dir "${DOWNLOAD_DIR}" \
    --clobber

# Verify SHA-512 checksums for downloaded binaries
log "Verifying SHA-512 checksums for downloaded binaries..."
for sha_file in "${DOWNLOAD_DIR}"/*.sha512; do
    (cd "${DOWNLOAD_DIR}" && shasum -a 512 -c "$(basename "${sha_file}")") \
        || error "SHA-512 verification failed for $(basename "${sha_file}")"
    log "  OK: $(basename "${sha_file}")"
done

# Move verified tarballs to dist (discard CI sha512 — we regenerate with GPG)
mv "${DOWNLOAD_DIR}"/*.tar.gz "${DIST_DIR}/"

log "Linux tarballs verified and moved to dist/"

# ─── Step 6: Sign all tarballs ────────────────────────────────────────────────
log "Signing release artifacts with GPG..."

# Remove the pre-upload darwin sha512 — will be regenerated alongside GPG signature
rm -f "${DIST_DIR}/${DARWIN_SHA512}"

for tarball in "${DIST_DIR}"/*.tar.gz; do
    sign_and_checksum "${tarball}"
done

# Re-upload darwin sha512 to GitHub Release (regenerated after GPG signing)
log "Uploading darwin SHA-512 checksum to GitHub Release..."
gh release upload "${TAG}" --repo "${REPO}" \
    "${DIST_DIR}/${DARWIN_SHA512}" \
    --clobber

# ─── Step 7: Clean up tmp ────────────────────────────────────────────────────
log "Cleaning up temporary files..."
rm -rf "${TMP_DIR}"

# ─── Step 8: Upload to Apache SVN dist/dev ───────────────────────────────────
SVN_DEV_BASE="https://dist.apache.org/repos/dist/dev/skywalking/graalvm-distro"
SVN_DEV_DIR="${SVN_DEV_BASE}/${VERSION}"

echo ""
log "Uploading release artifacts to Apache SVN dist/dev..."
echo "  SVN target: ${SVN_DEV_DIR}"
echo ""
read -r -p "Apache SVN username (LDAP): " SVN_USER
[[ -n "${SVN_USER}" ]] || error "SVN username is required"
read -r -s -p "Apache SVN password: " SVN_PASS
echo ""
[[ -n "${SVN_PASS}" ]] || error "SVN password is required"

SVN_CHECKOUT_DIR="${SCRIPT_DIR}/${RELEASE_DIR}/svn-checkout"
mkdir -p "${SVN_CHECKOUT_DIR}"

# Create the parent directory in SVN if it does not exist yet
if ! svn info "${SVN_DEV_BASE}" --username "${SVN_USER}" --password "${SVN_PASS}" --non-interactive >/dev/null 2>&1; then
    log "SVN directory ${SVN_DEV_BASE} does not exist, creating..."
    svn mkdir "${SVN_DEV_BASE}" \
        -m "Create graalvm-distro directory for release staging" \
        --username "${SVN_USER}" --password "${SVN_PASS}" --non-interactive
fi

# Checkout the parent directory (sparse — just top level)
svn checkout --depth empty "${SVN_DEV_BASE}" "${SVN_CHECKOUT_DIR}" \
    --username "${SVN_USER}" --password "${SVN_PASS}" --non-interactive

# Create version directory and add all artifacts
mkdir -p "${SVN_CHECKOUT_DIR}/${VERSION}"
cp "${DIST_DIR}"/* "${SVN_CHECKOUT_DIR}/${VERSION}/"
svn add "${SVN_CHECKOUT_DIR}/${VERSION}"

log "Files to upload:"
ls -lh "${SVN_CHECKOUT_DIR}/${VERSION}/"

echo ""
read -r -p "Commit to SVN? [y/N] " svn_confirm
[[ "${svn_confirm}" =~ ^[Yy]$ ]] || { echo "SVN upload aborted. Files remain in ${DIST_DIR}/"; exit 0; }

svn commit "${SVN_CHECKOUT_DIR}" \
    -m "Upload Apache SkyWalking GraalVM Distro ${VERSION} for voting" \
    --username "${SVN_USER}" --password "${SVN_PASS}" --non-interactive

log "Uploaded to ${SVN_DEV_DIR}"

# Clean up SVN checkout
rm -rf "${SVN_CHECKOUT_DIR}"

# ─── Step 9: Generate vote email ─────────────────────────────────────────────
log "Generating vote email..."

COMMIT_ID=$(git rev-parse "${TAG}")

# Collect submodule commit IDs
SKYWALKING_COMMIT=$(git -C "${REPO_ROOT}" ls-tree "${TAG}" skywalking | awk '{print $3}')

# Build sha512 checksums block
SHA512_BLOCK=""
for sha_file in "${DIST_DIR}"/*.sha512; do
    SHA512_BLOCK="${SHA512_BLOCK}   - $(cat "${sha_file}")
"
done

VOTE_DATE=$(date -u +"%B %d, %Y")

MAIL_FILE="${DIST_DIR}/vote-email.txt"
cat > "${MAIL_FILE}" <<MAILEOF
Mail title: [VOTE] Release Apache SkyWalking GraalVM Distro version ${VERSION}

Mail content:
Hi All,
This is a call for vote to release Apache SkyWalking GraalVM Distro version ${VERSION}.

Release notes:

 * https://github.com/apache/skywalking-graalvm-distro/blob/v${VERSION}/changes/changes.md

Release Candidate:

 * ${SVN_DEV_DIR}
 * sha512 checksums
${SHA512_BLOCK}
Release Tag :

 * (Git Tag) v${VERSION}

Release CommitID :

 * https://github.com/apache/skywalking-graalvm-distro/tree/${COMMIT_ID}
 * Git submodule
   * skywalking: https://github.com/apache/skywalking/tree/${SKYWALKING_COMMIT}

Keys to verify the Release Candidate :

 * https://dist.apache.org/repos/dist/release/skywalking/KEYS
 * Signed by ${GPG_EMAIL}

Guide to build the release from source :

 * https://github.com/apache/skywalking-graalvm-distro/blob/v${VERSION}/docs/compiling.md

Voting will start now (${VOTE_DATE}) and will remain open for at least 72 hours, Request all PMC members to give their vote.
[ ] +1 Release this package.
[ ] +0 No opinion.
[ ] -1 Do not release this package because...., if you have any doubt, ask me.
MAILEOF

# ─── Summary ─────────────────────────────────────────────────────────────────
echo ""
log "Release ${VERSION} packaging and upload complete!"
echo ""
echo "Release artifacts in ${RELEASE_DIR}/dist/:"
ls -lh "${DIST_DIR}/"
echo ""
echo "SVN dist/dev: ${SVN_DEV_DIR}"
echo ""
echo "Verification commands:"
echo "  cd ${RELEASE_DIR}/dist"
for tarball in "${DIST_DIR}"/*.tar.gz; do
    f=$(basename "${tarball}")
    echo "  gpg --verify ${f}.asc ${f}"
    echo "  shasum -a 512 -c ${f}.sha512"
done
echo ""
echo "========================================="
echo "          VOTE EMAIL"
echo "========================================="
cat "${MAIL_FILE}"
echo "========================================="
echo ""
echo "Vote email saved to: ${MAIL_FILE}"
echo ""
echo "Next step:"
echo "  Send the above email to dev@skywalking.apache.org"
