---
name: git-remotes
description: "Git remote layout for this clone — origin is the user's fork, upstream is the archived original"
metadata: 
  node_type: memory
  type: project
  originSessionId: 019e6e39-6584-4730-a395-746a872c2fd1
---

Set up 2026-07-09. This local clone was originally cloned from the archived upstream; remotes were
repointed to the user's own fork:

- **`origin`** → `https://github.com/plasma-node/GlobalQuake` (the user's fork — PUBLIC; push here)
- **`upstream`** → `https://github.com/xspanger3770/GlobalQuake` (archived original; read-only reference)

The fork originally had only an `announcement` branch (upstream's default). Our tested work is based
on `main` (base `00d63afa`, the actual GlobalQuake code — `announcement` is a separate/announcement
branch). First push created `main` on the fork; local `main` now tracks `origin/main`.

The user commits straight to their fork's `main` (chose no PR/feature-branch flow for their own
revival repo). First batch pushed as commit `bad9741b` (seedlink ISO fix, launch-anytime/fail-fast,
no-data persistence, IRIS→EarthScope migration, button unlock; `.ai/` excluded via gitignore).

Gotcha for commits on Windows here: use the **Bash** tool with a real message file
(`git commit -F <file>`), NOT PowerShell `@'...'@` here-string syntax in the Bash tool — that leaks
literal `@` into the message (happened once, was amended). Related: [[build-setup]],
[[untracked-ai-notes]].
