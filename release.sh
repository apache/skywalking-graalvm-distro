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

# ─── Usage ───────────────────────────────────────────────────────────────────
usage() {
    cat <<EOF
Usage: $0 <version>

Create an Apache-style release for SkyWalking GraalVM Distro.

Arguments:
  version    Release version (e.g. 1.0.0)

What this script does:
  1. Generates a changelog template in changes/<version>.md
  2. Creates a git tag v<version> and pushes it
  3. Packages a clean source tarball (with submodule, excluding .git/binaries)
  4. Builds the Linux native binary via Docker cross-compilation
  5. Packages the native binary tarball with configuration
  6. Signs all tarballs with GPG (default key) and generates SHA-512 checksums

Output:
  ${RELEASE_DIR}/
    ${ARTIFACT_PREFIX}-<version>-src.tar.gz{,.asc,.sha512}
    ${ARTIFACT_PREFIX}-<version>-bin.tar.gz{,.asc,.sha512}
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
    shasum -a 512 "${file}" > "${file}.sha512"
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
command -v docker >/dev/null 2>&1 || error "docker is not installed (needed for native build)"

# Verify default GPG key exists and print it
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

# Verify working directory is clean
if [[ -n "$(git status --porcelain)" ]]; then
    error "Working directory is not clean. Commit or stash changes first."
fi

# Verify tag does not already exist
if git rev-parse "${TAG}" >/dev/null 2>&1; then
    error "Tag ${TAG} already exists. Delete it first if re-releasing."
fi

# ─── Step 1: Changelog ──────────────────────────────────────────────────────
log "Creating changelog for ${VERSION}..."

mkdir -p "${SCRIPT_DIR}/changes"

CHANGELOG="${SCRIPT_DIR}/changes/${VERSION}.md"
{
    echo "# ${VERSION} Release"
    echo ""
    echo "## Highlights"
    echo ""
    echo "<!-- Fill in the highlights of this release -->"
    echo ""
    echo "## Changes"
    echo ""
    # Collect commits since last tag, or all if no prior tag
    PREV_TAG=$(git describe --tags --abbrev=0 2>/dev/null || echo "")
    if [[ -n "${PREV_TAG}" ]]; then
        git log --oneline "${PREV_TAG}..HEAD" | sed 's/^/- /'
    else
        git log --oneline | sed 's/^/- /'
    fi
} > "${CHANGELOG}"

log "Changelog written to ${CHANGELOG}"
log "Please review and edit the changelog before continuing."
echo ""
read -r -p "Press ENTER to continue after editing the changelog (or Ctrl+C to abort)..."

# ─── Step 2: Git tag ────────────────────────────────────────────────────────
log "Creating git tag ${TAG}..."
git tag -a "${TAG}" -m "Release ${VERSION}"
log "Pushing tag ${TAG} to origin..."
git push origin "${TAG}"

# ─── Step 3: Prepare release directory ───────────────────────────────────────
log "Preparing release directory..."
rm -rf "${SCRIPT_DIR}/${RELEASE_DIR}"
mkdir -p "${SCRIPT_DIR}/${RELEASE_DIR}"

# ─── Step 4: Source package ──────────────────────────────────────────────────
log "Creating source package..."

SRC_TARBALL="${ARTIFACT_PREFIX}-${VERSION}-src.tar.gz"
SRC_CLONE_DIR="${SCRIPT_DIR}/${RELEASE_DIR}/src-clone"

# Clean clone with submodules
git clone --recurse-submodules "${SCRIPT_DIR}" "${SRC_CLONE_DIR}"

# Remove all .git directories (root + submodules)
find "${SRC_CLONE_DIR}" -name ".git" -type d -exec rm -rf {} + 2>/dev/null || true
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

# ─── Step 5: Binary package (Linux native) ───────────────────────────────────
log "Building Linux native binary via Docker cross-compilation..."
log "(This may take a while — native-image compilation is resource-intensive)"

make native-image-macos

# Locate the native dist tarball produced by Maven assembly
NATIVE_TARBALL=$(ls "${SCRIPT_DIR}"/oap-graalvm-native/target/oap-graalvm-native-*-native-dist.tar.gz 2>/dev/null | head -1)
[[ -n "${NATIVE_TARBALL}" ]] || error "Native dist tarball not found. Build may have failed."

BIN_TARBALL="${ARTIFACT_PREFIX}-${VERSION}-bin.tar.gz"

# Repackage with Apache naming: extract, rename root dir, re-tar
REPACK_DIR="${SCRIPT_DIR}/${RELEASE_DIR}/repack-tmp"
mkdir -p "${REPACK_DIR}"
tar -xzf "${NATIVE_TARBALL}" -C "${REPACK_DIR}"

# The native assembly uses base directory "oap-native"
mv "${REPACK_DIR}/oap-native" "${REPACK_DIR}/${ARTIFACT_PREFIX}-${VERSION}"

tar -czf "${SCRIPT_DIR}/${RELEASE_DIR}/${BIN_TARBALL}" \
    -C "${REPACK_DIR}" \
    "${ARTIFACT_PREFIX}-${VERSION}"

rm -rf "${REPACK_DIR}"

log "Binary package created: ${RELEASE_DIR}/${BIN_TARBALL}"

# ─── Step 6: Sign and checksum ──────────────────────────────────────────────
log "Signing and checksumming release artifacts..."

cd "${SCRIPT_DIR}/${RELEASE_DIR}"
sign_and_checksum "${SRC_TARBALL}"
sign_and_checksum "${BIN_TARBALL}"
cd "${SCRIPT_DIR}"

# ─── Summary ────────────────────────────────────────────────────────────────
echo ""
log "Release ${VERSION} complete!"
echo ""
echo "Release artifacts:"
ls -lh "${SCRIPT_DIR}/${RELEASE_DIR}/"
echo ""
echo "Verification commands:"
echo "  cd ${RELEASE_DIR}"
echo "  gpg --verify ${SRC_TARBALL}.asc ${SRC_TARBALL}"
echo "  gpg --verify ${BIN_TARBALL}.asc ${BIN_TARBALL}"
echo "  shasum -a 512 -c ${SRC_TARBALL}.sha512"
echo "  shasum -a 512 -c ${BIN_TARBALL}.sha512"
echo ""
echo "Changelog: changes/${VERSION}.md"
echo "Git tag:   ${TAG}"
