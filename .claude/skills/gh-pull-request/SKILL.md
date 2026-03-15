---
name: gh-pull-request
description: Verify, commit, and push changes on a PR branch. Runs pre-flight checks (compile, checkstyle, license headers) before every push. Also creates the PR if one doesn't exist yet.
---

# PR Branch Workflow

Run pre-flight checks, commit, push, and optionally create a PR.

## Pre-flight checks

Run these checks before every commit+push and fix any failures:

### 1. Compile

```bash
make compile
```

### 2. License header check

```bash
license-eye header check
```

If invalid files are found, fix with `license-eye header fix` and re-check.

## Commit and push

After checks pass, commit and push:

```bash
git add <files>
git commit -m "<message>"
git push -u origin <branch-name>
```

### Branch strategy
- **Never work directly on main branch**
- If on main, create a new branch first: `git checkout -b feature/<name>` or `git checkout -b fix/<name>`

## Create PR (if not yet created)

Check whether a PR already exists for the current branch:

```bash
gh pr view --json number 2>/dev/null
```

If no PR exists, create one:

### PR title
Summarize the changes concisely. Examples:
- `Fix native image boot failure for MAL expressions`
- `Add Zipkin e2e test case`
- `Update BanyanDB to latest version`

### PR description

Use this format:

```
### <Summary of changes>

- [ ] Describe what changed and why.
- [ ] Update `changes/changes.md` if this is a user-facing change.
- [ ] Tests pass: `make test`
- [ ] License headers valid: `license-eye header check`
```

### Create command

```bash
gh pr create --title "<title>" --body "$(cat <<'EOF'
<PR body>
EOF
)"
```

### Post-creation
- Do NOT add AI assistant as co-author. Code responsibility is on the committer's hands.
- Return the PR URL when done.
