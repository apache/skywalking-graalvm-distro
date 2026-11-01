# Release Guide

This guide covers the end-to-end release process for Apache SkyWalking GraalVM Distro,
from version bumping through Apache voting.

## Prerequisites

The release manager needs the following tools installed locally:

| Tool | Purpose | Install |
|------|---------|---------|
| GraalVM JDK 25 | Build macOS native binary | [sdkman](https://sdkman.io/): `sdk install java 25-graal` |
| GPG | Sign release artifacts | `brew install gnupg` |
| git | Version control | `brew install git` |
| gh | GitHub CLI (download CI artifacts, create PRs) | `brew install gh` |
| svn | Upload to Apache dist/dev | `brew install svn` |
| curl | Check archive.apache.org before removing an old release | Built-in on macOS |
| make | Build orchestration | Xcode Command Line Tools |
| shasum | SHA-512 checksums | Built-in on macOS |

### GPG Key Setup

Your GPG key must use an `@apache.org` email address. The release scripts verify this.

```bash
# Generate a key (if you don't have one)
gpg --full-generate-key

# Export and publish to Apache KEYS file
gpg --armor --export your-id@apache.org >> KEYS
```

Ensure your public key is in https://dist.apache.org/repos/dist/release/skywalking/KEYS.

## Release Scripts

All scripts are in the `release/` directory:

| Script | Purpose |
|--------|---------|
| `full-release.sh` | Automated end-to-end pipeline (recommended) |
| `pre-release.sh` | Bump version, tag, bump to next SNAPSHOT |
| `release.sh` | Package, sign, SVN upload, vote email |
| `post-vote.sh` | After vote passes: SVN move from dist/dev to dist/release |

## Automated Release (Recommended)

Run a single command to execute the entire release pipeline:

```bash
release/full-release.sh
```

The script orchestrates `pre-release.sh` and `release.sh` with CI polling in between:

1. **Pre-flight checks** — validates tools, GPG key (`@apache.org`), clean `main` branch,
   up-to-date with remote, `gh` authentication
2. **Calls `pre-release.sh`** — prompts for release version and next SNAPSHOT version, and
   commits both `pom.xml` bumps on a new `release/v{version}` branch, tagging the first;
   `main` is left unchanged
3. **Pushes tag** — triggers CI release build (Linux native binaries + Docker + GitHub Release)
4. **Creates PR** — pushes `release/v{version}` and opens its PR to `main`, merged after the
   vote passes
5. **Polls CI** — checks tag build status every 30s until green (~30-45 min)
6. **Calls `release.sh`** — builds macOS native binary, creates source tarball,
   signs all artifacts, uploads to SVN, generates vote email

After the script completes, send the vote email to `dev@skywalking.apache.org`.

## Manual Release (Step by Step)

If you prefer to run each phase separately, use the three individual steps below.

### Step 1: Pre-Release

```bash
release/pre-release.sh
```

The script will:

1. Read the current SNAPSHOT version from `pom.xml` (e.g. `0.1.0-SNAPSHOT`)
2. Ask you to confirm the release version (default: `0.1.0`)
3. Ask you to confirm the next development version (default: the next patch, `0.1.1-SNAPSHOT`; must end with `-SNAPSHOT`)
4. Create branch `release/v0.1.0`, bump all `pom.xml` files to the release version and commit
5. Create git tag `v0.1.0`
6. Bump all `pom.xml` files to the next SNAPSHOT version and commit, on the same branch
7. Switch back to `main`, unchanged

After the script completes, review and push:

```bash
git log --oneline release/v0.1.0 -2
git push origin v0.1.0                                # triggers CI release build
git push -u origin release/v0.1.0
gh pr create --base main --head release/v0.1.0        # merge after the vote passes
```

### Step 2: Wait for CI

Pushing the `v*` tag triggers the CI workflow (`.github/workflows/ci.yml`) in release mode.

The CI automatically:

1. Builds Linux native binaries on both `amd64` and `arm64` runners
2. Pushes Docker images to GHCR and Docker Hub with version and `latest` tags
3. Creates a GitHub Release page with Linux tarballs and SHA-512 checksums

**Wait for CI to complete** before proceeding. Monitor at:
`https://github.com/apache/skywalking-graalvm-distro/actions`

The GitHub Release page will have:
- `apache-skywalking-graalvm-distro-{version}-linux-amd64.tar.gz` + `.sha512`
- `apache-skywalking-graalvm-distro-{version}-linux-arm64.tar.gz` + `.sha512`

### Step 3: Release Packaging

Once CI completes, run:

```bash
release/release.sh 0.1.0
```

The script performs these steps in order:

| Step | What | Notes |
|------|------|-------|
| Pre-flight | Verify GPG key, git tag, GitHub Release, and the tag's `changes/changes.md` section exist | Aborts early if anything is missing |
| Source tarball | Clone from tag, strip `.git`/binaries, create `*-src.tar.gz` | Clean clone from GitHub, not local tree; aborts if the GitHub tag is not the local one |
| macOS build | `make init-skywalking`, then `make native-image`, in a copy of the tag clone | Requires GraalVM JDK 25 on macOS. Builds the tag's code and upstream submodule, as the CI release build does, not your checkout's; it reinstalls the tag's upstream artifacts in `~/.m2` |
| Upload macOS | Push darwin-arm64 tarball to GitHub Release | Via `gh release upload` |
| Download Linux | Pull linux-amd64/arm64 tarballs from GitHub Release | Verifies SHA-512 checksums |
| GPG sign | Sign all 4 tarballs, generate SHA-512 | Uses `@apache.org` GPG key |
| SVN upload | Upload all artifacts to Apache dist/dev | Asks for Apache LDAP credentials |
| Vote email | Generate `[VOTE]` email template | Saved to `release-package/dist/vote-email.txt` |

## Release Artifacts

After completion, `release/release-package/dist/` contains:

```
apache-skywalking-graalvm-distro-0.1.0-src.tar.gz{,.asc,.sha512}
apache-skywalking-graalvm-distro-0.1.0-linux-amd64.tar.gz{,.asc,.sha512}
apache-skywalking-graalvm-distro-0.1.0-linux-arm64.tar.gz{,.asc,.sha512}
apache-skywalking-graalvm-distro-0.1.0-darwin-arm64.tar.gz{,.asc,.sha512}
vote-email.txt
```

These are also uploaded to:
`https://dist.apache.org/repos/dist/dev/skywalking/graalvm-distro/0.1.0/`

## Vote

Send the generated vote email to `dev@skywalking.apache.org`.

The vote remains open for at least 72 hours and requires PMC approval.

## After the Vote Passes

Once the vote passes (72+ hours, PMC approval), run:

```bash
release/post-vote.sh 0.1.0
```

The script first shows a full briefing of all planned changes and asks for confirmation before
executing anything. It then performs:

| Step | What | Notes |
|------|------|-------|
| SVN move | `svn mv` from dist/dev to dist/release | Creates `dist/release/skywalking/graalvm-distro/{version}/`; skipped when it already exists |
| Old release cleanup | Optionally remove releases older than `{version}` from dist/release | ASF policy keeps the latest release of each line under development there; every release stays at archive.apache.org. Only releases archive.apache.org serves in full (every file) are offered, which it does about a day after a release first appears on downloads.apache.org. Decline on the first run: the website still links the previous release through downloads.apache.org until the website PR is live. Two prereleases of the same version are never ordered against each other |

The script stops there and prints the remaining steps with the values they need. It does not
update the website: the scripted update it used to run edited the website's shared YAML across
sibling projects' entries (it rewrote the Horizon UI documentation pin) and tied the listing of the
previous release to the SVN cleanup. The website has since moved every project into
`data/projects.yml`, with its own release instructions and validator.

### Remaining steps (manual)

1. Merge the `release/v{version}` PR into `main`.
2. Prepare the website PR below.
3. Merge it at least an hour after the SVN move (ASF asks for that wait before the download page
   changes), once `https://downloads.apache.org/skywalking/graalvm-distro/{version}/` serves every
   file of the release; they usually appear within 15 minutes.
4. Once the website's downloads page lists the release, send the `[ANNOUNCE]` email to
   `dev@skywalking.apache.org`.
5. Once the website links the previous release to archive.apache.org, run
   `release/post-vote.sh {version}` again: the move is skipped, and it offers the cleanup.

### Website PR

On `apache/skywalking-website`, branch `graalvm-distro-{version}-release`, follow the "Projects and
Documentation", "Downloads" and "Event" sections of the website's `README.md`, copying the shape of
the previous distro release. `{upstream}` is the SkyWalking version `docs/version-mapping.md` maps
the release to.

| File | Change |
|------|--------|
| `data/projects.yml` | In the project with `repo: skywalking-graalvm-distro` only. Insert the release at the top of `releases`: `version: v{version}`, `latest: true`; `docs` with `link: /docs/skywalking-graalvm-distro/v{version}/readme/`, `commitId` the tag commit `post-vote.sh` prints, and `label: {version} (SkyWalking {upstream})`; the four `downloads` (source, Linux AMD64, Linux ARM64, MacOS ARM) through `https://www.apache.org/dyn/closer.cgi/`, with `.asc` and `.sha512` on `https://downloads.apache.org/`; `label: {version} ({upstream})` and `date`. Set the previous release to `latest: false` and move its download, `.asc` and `.sha512` links to `https://archive.apache.org/dist/` once archive.apache.org serves them, keeping its `docs` pin; until then keep its `downloads.apache.org` links (`post-vote.sh` names any older release in dist/release that is not archived yet). |
| `content/events/release-apache-skywalking-graalvm-distro-{version, dots as dashes}/index.md` | The release post, e.g. `release-apache-skywalking-graalvm-distro-0-4-0`: `title: Release Apache SkyWalking GraalVM Distro {version}`, `date`, `author: SkyWalking Team` and `description` as in the previous post. The body opens with the line linking the downloads page, then lists the user-facing changes of the `## {version}` section of `changes/changes.md`, leaving out dependency, build-only and test-only items. |

Run `node scripts/project-config.js` in the website repo before pushing (it needs Node.js and the
website's dependencies: `npm install` there first, as its `README.md` describes), and check that the
diff touches only the distro's entries.

## Quick Reference

```bash
# Automated (recommended) — single command, runs everything
release/full-release.sh

# Manual — three steps
release/pre-release.sh            # 1. release/v0.1.0: release version (tagged), next SNAPSHOT
git push origin v0.1.0            #    push tag (triggers CI)
git push -u origin release/v0.1.0 #    push the branch, open its PR to main
                                  # 2. wait for CI to go green
release/release.sh 0.1.0          # 3. package, sign, SVN upload, vote email

# After vote passes
release/post-vote.sh 0.1.0        # 4. SVN move; then the release PR, website PR, announce
```
