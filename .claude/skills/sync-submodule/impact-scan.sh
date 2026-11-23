#!/usr/bin/env bash
#
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
#

# The review checks of upstream's graalvm-distro-impact skill ("When reviewing a change"), run over a whole sync
# range against this repo's working tree, plus the "GraalVM distro impact" item of every upstream PR in the range.
# Every hit is a question, not a verdict: judge it with that skill's "Deciding the impact" table.
#
# Usage, from the distro root: .claude/skills/sync-submodule/impact-scan.sh <old-commit> <new-commit>

set -euo pipefail

B=${1:?usage: impact-scan.sh <old-commit> <new-commit>}
H=${2:?usage: impact-scan.sh <old-commit> <new-commit>}
ROOT=$(git rev-parse --show-toplevel)
SKILL=.claude/skills/graalvm-distro-impact/SKILL.md
MODULE_CLASSES='extends (ModuleDefine|ModuleProvider|ModuleConfig|AbstractConfigurationProvider)([^[:alnum:]_]|$)'

up() { git -C "$ROOT/skywalking" "$@"; }
# The patches the scan parses: git's own diff, uncolored, with a/ b/ prefixes, whatever the user's git config says.
PATCH=(--no-ext-diff --no-color --src-prefix=a/ --dst-prefix=b/)
section() { printf '\n== %s\n' "$1"; }
fail() { echo "$1" >&2; exit 1; }

up cat-file -e "$H:$SKILL" 2>/dev/null || fail "$SKILL is missing at $H (upstream added it in #14256)"
# The hazard pattern is upstream's, read at the new pin so it tracks the skill: the last grep -E of the one
# `git diff -U0` command in its review steps.
HAZARD_LINES=$(up show "$H:$SKILL" | grep -E "git diff -U0 .*grep -E '" || true)
[ "$(printf '%s' "$HAZARD_LINES" | grep -c '^')" = 1 ] \
  || fail "Expected one 'git diff -U0 ... | grep -E' command in $SKILL at $H: update this script"
HAZARDS=$(printf '%s\n' "$HAZARD_LINES" | sed -E "s/.*grep -E '([^']+)'[[:space:]]*\$/\1/")
case "$HAZARDS" in
  *'git diff'*) fail "Could not cut the hazard pattern out of $SKILL at $H: update this script" ;;
  *'|'*'|'*) ;;
  *) fail "The hazard pattern read from $SKILL at $H looks wrong ($HAZARDS): update this script" ;;
esac
rc=0
printf '' | grep -E "$HAZARDS" >/dev/null 2>&1 || rc=$?
[ "$rc" = 1 ] || fail "The hazard pattern read from $SKILL at $H is not a valid ERE: update this script"

# --no-renames: a renamed file shows its old path too, which is the one a replacement or a scan names.
section "Replaced classes the range edits: diff each into the distro copy, then the javap member check"
comm -12 <(up diff --no-renames --name-only "$B" "$H" | sed -n 's#.*/src/main/java/##p' | sort -u) \
         <(git -C "$ROOT" ls-files -- oap-libs-for-graalvm oap-graalvm-server build-tools/precompiler \
             | sed -n 's#.*/src/main/java/##p' | sort -u)

section "Classes the range deletes or renames that the distro still names"
up diff --name-status -M "$B" "$H" -- '*/src/main/java/*.java' | awk '$1 ~ /^(D|R)/ {print $2}' \
  | sed -n 's#.*/src/main/java/##; s#\.java$##p' \
  | while read -r c; do
      git -C "$ROOT" grep -l -F -e "$(printf '%s' "$c" | tr / .)" -e "$c" -- oap-graalvm-native/src/main/resources \
        oap-graalvm-server/src build-tools oap-libs-for-graalvm | sed "s#^#$c: #" || true
    done

section "DSL engines, the server-starter pom and resources, files the assemblies copy"
up diff --no-renames --name-only "$B" "$H" | grep -E '^oap-server/(oal-rt|oal-grammar|analyzer/(meter|log|trace)-analyzer|analyzer/hierarchy)/src/main/|server-core/src/main/java/.*/core/(analysis/meter|dsl)/|^oap-server/server-starter/(pom\.xml|src/main/resources/)|^dist-material/((log4j2\.xml|alarm-settings\.yml)$|release-docs/)' || true

section "Module, provider and config classes changed, added or deleted"
up diff --name-only --diff-filter=d "$B" "$H" -- '*/src/main/java/*.java' | while read -r f; do
  if up grep -q -E "$MODULE_CLASSES" "$H" -- "$f"; then echo "$f"; fi
done
# Removed declaration lines, of deleted files and of files a rename pairs with an unrelated new one.
up diff "${PATCH[@]}" --no-renames -U0 --diff-filter=DM "$B" "$H" -- '*/src/main/java/*.java' \
  | grep -E "^-.*$MODULE_CLASSES" || true

section "Native-image hazards on added lines"
# One rename-aware diff, so a moved file's unchanged lines are not counted as added; each added line goes to one
# file and its path to another, so the pattern only ever sees code.
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
up diff "${PATCH[@]}" -M -U0 --diff-filter=d "$B" "$H" -- '*/src/main/java/*.java' \
  | awk -v paths="$tmp/paths" -v lines="$tmp/lines" '
      /^\+\+\+ / {path = substr($0, 7); next}
      /^\+/ {print path > paths; print substr($0, 2) > lines}'
touch "$tmp/paths" "$tmp/lines"
rc=0
grep -nE "$HAZARDS" "$tmp/lines" > "$tmp/hits" || rc=$?
[ "$rc" -le 1 ] || fail "grep failed on the hazard pattern"
awk 'NR == FNR {path[NR] = $0; next} {n = $0; sub(/:.*/, "", n); sub(/^[0-9]+:/, ""); print path[n] ": " $0}' \
  "$tmp/paths" "$tmp/hits"

section "Each upstream PR's GraalVM distro impact item (a hint; PRs before #14256 have none)"
if ! command -v gh >/dev/null; then
  echo "gh not found: skipped"
  exit 0
fi
up log --reverse --format='%s' "$B..$H" | sed -n 's/.*(#\([0-9][0-9]*\))$/\1/p' | while read -r n; do
  if ! body=$(gh pr view "$n" --repo apache/skywalking --json body -q .body 2>&1); then
    echo "#$n: gh pr view failed: $(printf '%s\n' "$body" | sed -n 1p)"
    continue
  fi
  # The checked option of the item, with the lines it continues on, up to the next option or the item's end.
  # Options are the checkboxes at the first option's indentation; deeper ones belong to the option above. awk reads
  # to the end, so a long body never breaks the pipe.
  item=$(printf '%s\n' "$body" | tr -d '\r' | awk '
    done {next}
    /^[-*] GraalVM distro impact/ {print "@@item"; p = 1; next}
    !p {next}
    /^[^[:space:]]/ {done = 1; next}
    /^[[:space:]]+[-*] \[[ xX]\]/ {
      match($0, /^[[:space:]]+/)
      if (indent == 0) indent = RLENGTH
      if (RLENGTH == indent) {on = ($0 ~ /^[[:space:]]+[-*] \[[xX]\]/); if (on) print; next}
    }
    on && NF {print}')
  case "$item" in
    "") echo "#$n: no item" ;;
    "@@item") echo "#$n: no box checked" ;;
    *) printf '%s\n' "$item" | sed '1d; s#^  ##' | sed "s#^#\#$n: #" ;;
  esac
done
