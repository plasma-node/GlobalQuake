---
name: codebase-facts
description: "Non-obvious GlobalQuake facts — GPU accel scope, custom sound mechanism, server FDSNWS HTTP API, multi-quake detection root cause"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 019e6e39-6584-4730-a395-746a872c2fd1
---

Verified 2026-07-09 while answering the user's questions.

**GPU acceleration** = CUDA only (NVIDIA), via `GQHypocs` → `GQNativeFunctions` JNI
(`GlobalQuakeCore/.../core/earthquake/GQHypocs.java`). It accelerates **analysis** (hypocenter
location search), NOT rendering. So AMD/Intel/iGPUs get no acceleration and fall back to CPU;
supporting them would need an OpenCL/Vulkan port (big, separate job).

**Rendering is 100% CPU** — pure Java2D `Graphics2D` (`GlobalQuakeCore/.../ui/globe/GlobeRenderer.render`,
`GlobePanel`). No OpenGL/LWJGL/JOGL/Vulkan anywhere in the poms. The 3D globe is software-rasterized.
So GPU is used ONLY for hypocenter analysis, never anything visual.

**Custom sounds**: files in `GlobalQuakeCore/src/main/resources/sounds/*.wav`; loaded by
`globalquake.sounds.GQSound.load()` which prefers `.GlobalQuakeData/sounds/<name>.wav` (user override)
over the bundled classpath resource. `felt_strong.wav` (and `eew_warning.wav`, `level_4.wav`) ship
BLANK by default. Must be PCM WAV (javax.sound won't play MP3). Two ways to customize: drop into
`.GlobalQuakeData/sounds/` for personal use (delete the blank one first — export only copies if absent),
or replace the resource file to ship in builds. Sounds list: `globalquake.sounds.Sounds`.

**Server API** (`GlobalQuakeServer`): already exposes an **FDSNWS-event HTTP API**
(`gqserver.fdsnws_event.FdsnwsEventsHTTPServer` + `EventsV1Handler` + `EventsV1ParamChecks`) — the
standard earthquake-catalog query API (supports lat/lon/maxradius/time/magnitude). Also has a custom
TCP packet protocol (`gqserver.server.GQServerSocket`/`DataService`) for the desktop client, and a
Discord bot (`gqserver.bot.DiscordBot`, JDA) for proactive push. Headless is first-class:
`gqserver.main.Main --headless`. For the user's alerting goal ([[api-and-server-goals]] in .ai/), an
OpenClaw cron polling the FDSNWS endpoint works with NO new code; "set location" is just a query param.
FULLY VERIFIED 2026-07-09 by build+run+curl+standalone harness → **`.ai/api-usage-guide.md`** (681 lines,
the authoritative reference). Key confirmed facts:
- **Disabled by default + localhost-only**: `autoStartFDSNWSEventServer=false`, `FDSNWSEventIP=localhost`,
  port `8080` — all in `Settings.java`, only settable via properties file (no CLI flag). Must enable it.
- Data source `EarthquakeDataExport.getArchivedAndLiveEvents()` = archived + live union, so polling late
  STILL returns the quake. Default window = last 1 hour. Live events appended unsorted after archived.
- Filters that ACTUALLY work: time (start/end), bbox (minlat/maxlat/minlon/maxlon), depth, magnitude only.
- **Stable event ID = UUID**: GeoJSON `id`/`properties.unid`/`properties.source_id`, text `EventID`,
  QuakeML `publicID`. Use it to dedupe alerts across polls.
- **Intensity/shaking is NOT exposed by the API at all** — `ArchivedQuake` computes `maxPGA` internally
  but no serializer writes it. External caller must compute shaking themselves from mag/depth/distance
  using `GeoUtils.pgaFunction` + `getDepthCorrection` + `MMIIntensityScale` (the same math the desktop
  client uses for its own felt/felt_strong alerts). Worked example in the doc.
- **No auth, CORS `*` wide open** → do NOT port-forward to public internet.
- BUGS confirmed: radius params (`lat`/`lon` parsed-but-unused; `minradius`/`maxradius` not even parsed)
  → use a bbox instead. `format=quakeml` passes validation but CRASHES the handler switch → HTTP 500;
  use `format=xml`. Many params are no-ops: eventtype, includeall*, includearrivals, eventid, limit,
  offset, orderby, catalog, contributor, updatedafter, magnitudetype. Candidate fix tasks for later.

**Multi-quake detection** (README known issue): root cause diagnosed in DEPTH — `ClusterAnalysis`
runs `expandPWaves()` (wide ~10s window) BEFORE `createNewClusters()`, so a 2nd nearby-in-time quake's
picks are stolen into cluster #1 (`assignedCluster` set), starving the 2nd cluster (needs 4 unassigned
picks); the solver then trims them as outliers. `mergeClusters`/`canMerge` had NO origin-time check so
doublets got merged away. Full design + implementation notes: `.ai/multi-quake-fix-design.md`.
**IMPLEMENTED 2026-07-09** (built clean, 35 tests pass, NOT yet sandbox-verified or committed):
approach A = `ClusterAnalysis.releaseMisfitEvents()` releases picks a well-established quake can't fit
(strict `couldBeArrival`) + `Event.rejectedByCluster` marker so `expandCluster`/`expandPWaves` won't
re-steal (avoids oscillation); approach B = origin-time separation guard in `canMerge`. Two tunables in
HypocsSettings: `releaseMinCorrectEvents`=8, `originTimeMergeSeparationMs`=15000 (set the latter to 0 to
disable B / the former huge to disable A). Watch-item: S-wave-marked misfits are NOT released (may limit
effectiveness; first knob to revisit if sandbox 2-quake tests still miss the 2nd). Related: [[build-setup]].
