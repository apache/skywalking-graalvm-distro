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

# Verify docs/version-mapping.md is consistent with actual git state.
#
# Checks:
# 1. The dev row's upstream version matches the current submodule commit.
# 2. Each released version row matches the submodule commit at that git tag.
#
# Exit 0 on success, 1 on any mismatch.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DOC="$REPO_ROOT/docs/version-mapping.md"

if [ ! -f "$DOC" ]; then
    echo "ERROR: $DOC not found"
    exit 1
fi

errors=0

# Resolve upstream version string for a submodule commit.
# If the commit has an upstream tag (vX.Y.Z), return that version.
# Otherwise, return "{short-commit}-SNAPSHOT".
resolve_upstream_version() {
    local commit="$1"
    local short
    short=$(echo "$commit" | cut -c1-12)
    local tag
    tag=$(cd "$REPO_ROOT/skywalking" && git tag --points-at "$commit" 2>/dev/null | grep "^v" | head -1)
    if [ -n "$tag" ]; then
        # Strip leading 'v' from tag
        echo "${tag#v}"
    else
        echo "\`${short}\`-SNAPSHOT"
    fi
}

# Parse table rows between DOC-CHECK markers.
# Expected format: | Distro Version | Apache SkyWalking Version |
in_table=0
while IFS= read -r line; do
    # Detect marker boundaries
    if echo "$line" | grep -q "DOC-CHECK: version-mapping-table"; then
        in_table=1
        continue
    fi
    if echo "$line" | grep -q "END DOC-CHECK"; then
        in_table=0
        continue
    fi
    [ "$in_table" -eq 0 ] && continue

    # Skip header and separator rows
    echo "$line" | grep -qE "^\|.*\|$" || continue
    echo "$line" | grep -q "\-\-\-" && continue
    echo "$line" | grep -q "Distro Version" && continue

    # Extract columns
    distro_ver=$(echo "$line" | awk -F'|' '{print $2}' | xargs)
    doc_upstream=$(echo "$line" | awk -F'|' '{print $3}' | xargs)

    if echo "$distro_ver" | grep -q "(dev)"; then
        # Dev row: verify against current submodule commit
        actual_commit=$(git -C "$REPO_ROOT" ls-tree HEAD skywalking | awk '{print $3}')
        expected=$(resolve_upstream_version "$actual_commit")

        if [ "$doc_upstream" != "$expected" ]; then
            echo "MISMATCH [dev]: doc says '$doc_upstream' but submodule is at '$expected'"
            errors=$((errors + 1))
        else
            echo "OK [dev]: $distro_ver -> $doc_upstream"
        fi
    else
        # Released version: verify against git tag
        tag="v$distro_ver"
        if ! git -C "$REPO_ROOT" rev-parse "$tag" >/dev/null 2>&1; then
            echo "WARN [$distro_ver]: tag $tag not found (skipping — may not exist in shallow clone)"
            continue
        fi

        actual_commit=$(git -C "$REPO_ROOT" ls-tree "$tag" skywalking | awk '{print $3}')
        if [ -z "$actual_commit" ]; then
            echo "WARN [$distro_ver]: no submodule entry at tag $tag"
            continue
        fi

        expected=$(resolve_upstream_version "$actual_commit")
        if [ "$doc_upstream" != "$expected" ]; then
            echo "MISMATCH [$distro_ver]: doc says '$doc_upstream' but tag $tag has '$expected'"
            errors=$((errors + 1))
        else
            echo "OK [$distro_ver]: $tag -> $doc_upstream"
        fi
    fi
done < "$DOC"

if [ "$errors" -gt 0 ]; then
    echo ""
    echo "FAILED: $errors version mapping mismatch(es) found."
    echo "Update docs/version-mapping.md to match actual submodule state."
    exit 1
fi

echo ""
echo "All version mappings verified."
