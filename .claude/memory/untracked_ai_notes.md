---
name: untracked-ai-notes
description: The repo's .ai/ notes folder is now gitignored (2026-07-09) — kept private/local, safe from `git clean -fd`, deliberately excluded from the public fork
metadata: 
  node_type: memory
  type: project
  originSessionId: 79e25f83-3515-42c6-b4f1-2544ac6f2faf
---

The repo has a `.ai/` folder (`project-overview.md`, `vscode-build-setup.md`,
`seedlink-connection-issue.md`, `api-and-server-goals.md`) with detailed investigation notes and
forward goals.

**RESOLVED 2026-07-09:** `.ai/` is now in `.gitignore` (added last line). The user chose to keep it
**private/local**, deliberately excluded from the public fork (`plasma-node/GlobalQuake`), because
a freeform notes dump risks future accidental leaks (IPs, home location, tokens) if published. Being
gitignored ALSO protects it from a plain `git clean -fd` (that skips ignored files; only `-fdx`
would remove it). So it's safer now on both fronts than the old untracked-and-unignored state.

**How to apply:** Still don't run `git clean -fdx` or `reset --hard` carelessly — `.ai/` and the
user's local `.GlobalQuakeData/` are the real state to preserve. For OFF-machine backup the user
may later want a separate PRIVATE repo/gist for `.ai/` (offered, not set up yet). Never add `.ai/`
to a public commit.
