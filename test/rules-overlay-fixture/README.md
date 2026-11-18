# Rules overlay fixture

The overlay CI's `Rules overlay` job builds the distro with (`make verify-rules RULES_OVERLAY=test/rules-overlay-fixture`).
`rules-overlay.yaml` declares one of each change an overlay makes; this file is not a rule file, so the build
ignores it.
