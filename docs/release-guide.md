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

## Automated Release (Recommended)

Run a single command to execute the entire release pipeline:

```bash
release/full-release.sh
```

The script orchestrates `pre-release.sh` and `release.sh` with CI polling in between:

1. **Pre-flight checks** — validates tools, GPG key (`@apache.org`), clean `main` branch,
   up-to-date with remote, `gh` authentication
2. **Calls `pre-release.sh`** — prompts for release version and next SNAPSHOT version,
   bumps all `pom.xml` files, commits, creates tag
3. **Pushes tag** — triggers CI release build (Linux native binaries + Docker + GitHub Release)
4. **Creates PR** — moves the SNAPSHOT bump commit to a branch (`version/{next}-SNAPSHOT`),
   resets `main` to the tagged release commit, creates a PR for the version bump
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
3. Ask you to confirm the next development version (default: `0.2.0-SNAPSHOT`, must end with `-SNAPSHOT`)
4. Bump all `pom.xml` files to the release version and commit
5. Create git tag `v0.1.0`
6. Bump all `pom.xml` files to the next SNAPSHOT version and commit

After the script completes, review and push:

```bash
git log --oneline -3
git push origin v0.1.0            # triggers CI release build
git push origin main              # push next SNAPSHOT version
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
| Pre-flight | Verify GPG key, git tag, GitHub Release exist | Aborts early if anything is missing |
| Source tarball | Clone from tag, strip `.git`/binaries, create `*-src.tar.gz` | Clean clone from GitHub, not local tree |
| macOS build | `make native-image` locally | Requires GraalVM JDK 25 on macOS |
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

## Quick Reference

```bash
# Automated (recommended) — single command, runs everything
release/full-release.sh

# Manual — three steps
release/pre-release.sh            # 1. bump version, tag, next SNAPSHOT
git push origin v0.1.0            #    push tag (triggers CI)
git push origin main              #    push next SNAPSHOT
                                  # 2. wait for CI to go green
release/release.sh 0.1.0          # 3. package, sign, SVN upload, vote email
```
