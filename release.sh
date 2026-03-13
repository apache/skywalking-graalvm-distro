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
REPO="apache/skywalking-graalvm-distro"

# ─── Usage ───────────────────────────────────────────────────────────────────
usage() {
    cat <<EOF
Usage: $0 <version>

Package an Apache-style release for SkyWalking GraalVM Distro.

Prerequisites:
  - CI release workflow must have completed for tag v<version>
  - Binary tarballs and SHA-512 checksums are on the GitHub Release page
  - GPG key with @apache.org email must be configured

What this script does:
  1. Creates a clean source tarball (git clone with submodules, strip .git/binaries)
  2. Downloads binary tarballs and SHA-512 checksums from GitHub Release page
  3. Signs all tarballs with GPG and generates SHA-512 for the source tarball

Output:
  ${RELEASE_DIR}/
    ${ARTIFACT_PREFIX}-<version>-src.tar.gz{,.asc,.sha512}
    ${ARTIFACT_PREFIX}-<version>-linux-amd64.tar.gz{,.asc,.sha512}
    ${ARTIFACT_PREFIX}-<version>-linux-arm64.tar.gz{,.asc,.sha512}
    ${ARTIFACT_PREFIX}-<version>-macos-amd64.tar.gz{,.asc,.sha512}
    ${ARTIFACT_PREFIX}-<version>-macos-arm64.tar.gz{,.asc,.sha512}
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
    if [[ ! -f "${file}.sha512" ]]; then
        log "Generating SHA-512 checksum for ${file}..."
        shasum -a 512 "${file}" > "${file}.sha512"
    fi
}

# ─── Validate arguments ─────────────────────────────────────────────────────
[[ $# -eq 1 ]] || usage
VERSION="$1"
TAG="v${VERSION}"

# ─── Pre-flight checks ──────────────────────────────────────────────────────
log "Pre-flight checks..."

command -v gpg   >/dev/null 2>&1 || error "gpg is not installed"
command -v git   >/dev/null 2>&1 || error "git is not installed"
command -v tar   >/dev/null 2>&1 || error "tar is not installed"
command -v gh    >/dev/null 2>&1 || error "gh CLI is not installed (needed to download release assets)"

# Verify default GPG key exists and is @apache.org
gpg --list-secret-keys --keyid-format LONG >/dev/null 2>&1 \
    || error "No GPG secret keys found. Import or generate a key first."
GPG_KEY=$(gpg --list-secret-keys --keyid-format LONG 2>/dev/null \
    | grep -A1 "^sec" | head -2)
GPG_EMAIL=$(gpg --list-secret-keys --keyid-format LONG 2>/dev/null \
    | grep "uid" | head -1 | sed 's/.*<\(.*\)>.*/\1/')
log "GPG signing key:"
echo "${GPG_KEY}"
echo "  uid: ${GPG_EMAIL}"
echo ""
[[ "${GPG_EMAIL}" == *@apache.org ]] \
    || error "GPG key email '${GPG_EMAIL}' is not an @apache.org address. Apache releases must be signed with your Apache committer key."

# Verify tag exists locally
git rev-parse "${TAG}" >/dev/null 2>&1 \
    || error "Tag ${TAG} not found locally. Run: git fetch --tags"

# Verify GitHub Release exists
gh release view "${TAG}" --repo "${REPO}" >/dev/null 2>&1 \
    || error "GitHub Release for ${TAG} not found. Run the CI release workflow first."

# ─── Step 1: Prepare release directory ────────────────────────────────────────
log "Preparing release directory..."
rm -rf "${SCRIPT_DIR}/${RELEASE_DIR}"
mkdir -p "${SCRIPT_DIR}/${RELEASE_DIR}"

# ─── Step 2: Source package ───────────────────────────────────────────────────
log "Creating source package..."

SRC_TARBALL="${ARTIFACT_PREFIX}-${VERSION}-src.tar.gz"
SRC_CLONE_DIR="${SCRIPT_DIR}/${RELEASE_DIR}/src-clone"

# Clean clone at the release tag with submodules
git clone --branch "${TAG}" --recurse-submodules "${SCRIPT_DIR}" "${SRC_CLONE_DIR}"

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
rm -rf "${SRC_CLONE_DIR}/${RELEASE_DIR}"

# Create tarball from clean source
tar -czf "${SCRIPT_DIR}/${RELEASE_DIR}/${SRC_TARBALL}" \
    -C "${SCRIPT_DIR}/${RELEASE_DIR}" \
    --exclude="src-clone/${RELEASE_DIR}" \
    -s "/^src-clone/${ARTIFACT_PREFIX}-${VERSION}-src/" \
    src-clone

# Clean up clone
rm -rf "${SRC_CLONE_DIR}"

log "Source package created: ${RELEASE_DIR}/${SRC_TARBALL}"

# ─── Step 3: Download binary tarballs from GitHub Release ─────────────────────
log "Downloading binary tarballs and checksums from GitHub Release ${TAG}..."

cd "${SCRIPT_DIR}/${RELEASE_DIR}"

# Download all tar.gz and sha512 assets
gh release download "${TAG}" --repo "${REPO}" \
    --pattern "*.tar.gz" \
    --pattern "*.sha512" \
    --clobber

# List downloaded files
log "Downloaded assets:"
ls -lh *.tar.gz *.sha512 2>/dev/null || true

# Verify SHA-512 checksums for downloaded binaries
log "Verifying SHA-512 checksums..."
for sha_file in *.sha512; do
    if shasum -a 512 -c "${sha_file}"; then
        log "  OK: ${sha_file}"
    else
        error "SHA-512 verification failed for ${sha_file}"
    fi
done

# ─── Step 4: Sign all tarballs ────────────────────────────────────────────────
log "Signing release artifacts..."

for tarball in *.tar.gz; do
    sign_and_checksum "${tarball}"
done

cd "${SCRIPT_DIR}"

# ─── Summary ─────────────────────────────────────────────────────────────────
echo ""
log "Release ${VERSION} packaging complete!"
echo ""
echo "Release artifacts:"
ls -lh "${SCRIPT_DIR}/${RELEASE_DIR}/"
echo ""
echo "Verification commands:"
echo "  cd ${RELEASE_DIR}"
for tarball in "${SCRIPT_DIR}/${RELEASE_DIR}"/*.tar.gz; do
    f=$(basename "${tarball}")
    echo "  gpg --verify ${f}.asc ${f}"
    echo "  shasum -a 512 -c ${f}.sha512"
done
echo ""
echo "Next steps:"
echo "  1. Upload to Apache SVN dist/dev for voting"
echo "  2. Send [VOTE] email to dev@skywalking.apache.org"
