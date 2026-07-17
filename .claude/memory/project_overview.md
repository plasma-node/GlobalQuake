---
name: project-overview
description: "What GlobalQuake is, its architecture, and the fact this local repo is an independent revival of an archived upstream project"
metadata: 
  node_type: memory
  type: project
  originSessionId: 79e25f83-3515-42c6-b4f1-2544ac6f2faf
---

GlobalQuake is a Java 17 / Maven multi-module desktop+server app for near-real-time earthquake
monitoring: pulls station metadata from fdsnws-station services, streams live waveform data from
seedlink servers, runs its own detection/hypocenter algorithms, and visualizes quakes on a
Java2D/Swing-rendered 3D globe.

Modules (build order): `GlobalQuakeAPI` (wire protocol) → `GlobalQuakeCore` (detection engine,
seedlink ingestion, station DB) → `GlobalQuakeClient` (Swing desktop UI) / `GlobalQuakeServer`
(headless server + Discord bot). There's also an optional `GQHypocenterSearch` CUDA/C++ module for
GPU-accelerated hypocenter search — not required for the Java build.

**Why:** Upstream `github.com/xspanger3770/GlobalQuake` is archived (~2025-03-28); further
development continues privately. This local repo (`0.11.0_pre-2`, branch `announcement`) is being
revived/continued independently — there is no expectation of pulling further upstream updates.

**How to apply:** Treat this as a standalone codebase going forward, not a fork to keep in sync
with upstream. See [[build-setup]] for getting it running and [[seedlink-date-parsing-bug]] for the
known seedlink discovery bug.
