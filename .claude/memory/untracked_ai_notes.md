---
name: untracked-ai-notes
description: REVERSED 2026-07-17 — .ai/ and .claude/memory are now PUBLISHED to the public fork (to sync work across machines); strict no-sensitive-data rule applies
metadata:
  node_type: memory
  type: project
  originSessionId: 79e25f83-3515-42c6-b4f1-2544ac6f2faf
---

The repo has a `.ai/` folder (project-overview, vscode-build-setup, seedlink-connection-issue,
api-and-server-goals, api-usage-guide, ntfy-notifier-spec, multi-quake-fix-design) plus a tracked
`.claude/memory/` mirror of this memory system.

**DECISION 2026-07-17 (reverses the 2026-07-09 private-.ai decision):** the user works across
multiple computers, so BOTH `.ai/` and `.claude/memory/` are now COMMITTED to the public fork
(`plasma-node/GlobalQuake`) so context syncs. `.gitignore` line changed from `.ai/` to
`.ai/scratch/` (scratch build artifacts stay out) and `.claude/settings.local.json` added to
gitignore. Verified before publishing: NO secrets/tokens/ntfy-topic/precise-home-coords/IPs/emails
in these files (sanitized `home=48,-121` → "home (PNW)" in headless_ntfy_deployment.md). The real
secret (ntfy topic) and home coords live only in `.GlobalQuakeData/` (gitignored) and are NOT here.

**How to apply — CRITICAL, this is a PUBLIC repo:** NEVER write secrets, tokens, API keys, ntfy
topics, precise home/location coordinates, IPs, emails, or personal/user data into `.claude/memory`
or `.ai/`. Code/architecture/project decisions only. Guard notices are in `.claude/memory/MEMORY.md`
(loaded each session) and `.ai/README.md`. Runtime config with secrets stays in `.GlobalQuakeData/`
(gitignored) — never mirror its values into notes. Still avoid `git clean -fdx` (would wipe
`.GlobalQuakeData/` and `.ai/scratch/`). Related: [[headless-ntfy-deployment]].
