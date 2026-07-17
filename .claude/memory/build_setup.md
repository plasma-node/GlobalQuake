---
name: build-setup
description: "How to build/run GlobalQuake locally on Windows, and confirmed-working toolchain versions"
metadata: 
  node_type: memory
  type: project
  originSessionId: 79e25f83-3515-42c6-b4f1-2544ac6f2faf
---

Confirmed working build as of 2026-07-09: JDK 21 (Oracle, `C:\Program Files\Java\jdk-21`) +
Maven 3.9.16 (installed via chocolatey). `mvn -B clean install` from repo root builds all 5 modules
successfully (`GlobalQuake`, `GlobalQuakeAPI`, `GlobalQuakeCore`, `GlobalQuakeClient`,
`GlobalQuakeServer`) — no toolchain friction despite pom.xml pinning Java 17 source/target.

**Why:** User was setting up local dev for the first time; confirmed a separate JDK 17 install
is NOT required.

**How to apply:** Don't suggest installing JDK 17 or troubleshooting the toolchain — the JDK 21 +
Maven 3.9.16 combo already builds clean. Runnable jars after `mvn package`/`install`:
- Desktop client: `GlobalQuakeClient/target/GlobalQuake-0.11.0_pre-2-jar-with-dependencies.jar`
  (main class `globalquake.main.Main`)
- Headless server: `GlobalQuakeServer/target/GlobalQuakeServer-0.11.0_pre-2-jar-with-dependencies.jar`
  (main class `gqserver.main.Main`, supports `--headless`)

Key gotcha: `seisFile-2.1.0-SNAPSHOT` is vendored under `libs/` (not on Maven Central) and exposed
via a `file://` repo in each module's pom.xml — never let `libs/` get deleted or `mvn clean`d away
at the repo-root filesystem level.

Notes from prior session also live in the repo itself at `.ai/vscode-build-setup.md` — currently
**untracked** in git, so it only persists as long as no one runs `git clean`. Related:
[[project-overview]], [[seedlink-date-parsing-bug]], [[untracked-ai-notes]].
