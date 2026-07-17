---
name: startup-update-gate-and-fdsn
description: Local-mode startup no longer blocks Launch on station/seedlink updates; FDSN fail-fast + IRIS→EarthScope URL fix; existing-DB IRIS migration still pending
metadata: 
  node_type: memory
  type: project
  originSessionId: 019e6e39-6584-4730-a395-746a872c2fd1
---

Work done 2026-07-09 (built clean, not yet committed) to stop local-mode startup from pausing on
broken/slow station sources. The updates were never on the EDT — the real block was the **"Launch
GlobalQuake" button being disabled until the whole update+availability chain finished**
(`launchButton.setEnabled(!manager.isUpdating())` in `StationSourcesPanel`/`SeedlinkServersPanel`,
plus `btnLaunch` created disabled in the client `DatabaseMonitorFrame`; chain driven by
`MainFrame.finishInit()` → `runUpdate` → `runAvailabilityCheck`).

Changes made:
- **Launch anytime**: `btnLaunch` created enabled; both panels now `launchButton.setEnabled(true)`.
  Updates keep running in the background after launch. `StationDatabaseManager.save()` made
  `synchronized` because launching mid-update makes the window's dispose→save race the chain's save.
- **Fail-fast FDSN**: `FDSNWSDownloader` timeout split 120s → `CONNECT_TIMEOUT_SECONDS=10` /
  `READ_TIMEOUT_SECONDS=30`.
- **Fail-fast seedlink**: `StationDatabaseManager.ATTEMPTS` 3 → 2 (now `public`, referenced by
  `SeedlinkCommunicator` status text).
- **IRIS default URL**: `StationDatabase.addDefaults` IRIS DMC `http://service.iris.edu/...` →
  `https://service.earthscope.org/fdsnws/station/1/`.

Why each FDSN source "broke" (live-probed): IRIS = http→https 301 to EarthScope that
`HttpURLConnection` won't follow (cross-protocol); AusPass = server unreachable, was hanging 120s;
BGR + Haiti = servers are actually UP (200/204) — their in-app failure is a parse/413-split
robustness issue, NOT dead servers (still needs the app log to pin the exact exception).

**PNW station shortage** is the IRIS problem: EarthScope PNW-band query (lon -125..-120) returns
~7.6MB of channels, so fixing IRIS restores the ~1000 PNW stations newer builds show. User asked to
only "gently look into" PNW for now — deeper PNW work deferred.

**"No data" after launch — ROOT-CAUSED + FIXED (2026-07-09):** `Channel.seedlinkNetworks`
(which seedlink server feeds each channel) is `transient`, rebuilt empty every launch ONLY by the
availability scan. `GlobalStationManager.initStations` drops any selected station whose
`selectBestSeedlinkNetwork()` is null, so launching before the scan finishes = no GlobalStation =
gray "No data". This was WORSENED by our launch-anytime change. Fix (user chose "persist mapping"):
- `Channel` now has persisted `Set<String> knownSeedlinkKeys` (host:port), populated in
  `SeedlinkCommunicator.addAvailableChannel` via `channel.rememberSeedlink(...)`.
- `StationDatabase.restoreSeedlinkAssociations()` warm-starts the transient map from those keys on
  load (resolving against the current seedlink list, delay=UNKNOWN_DELAY); called in
  `StationDatabaseManager.load()`. So relaunch streams from last-known servers immediately; the
  background scan refreshes. FIRST-ever run still needs one scan to populate+save the keys.
- `Station.selectedChannel` IS persisted (not transient); `selectBestAvailableChannel()` (which
  nulls unavailable selections) only runs from remove/select UI actions, not load — safe because
  restore runs first in load().

**IRIS existing-DB migration — DONE (2026-07-09):** `StationDatabase.migrateMovedStationSources()`
(map old→new URL, currently just IRIS→EarthScope) called in `load()`; non-destructive (keeps
networks + selections, just resets that source's lastUpdate so it re-downloads from EarthScope).

**Edit/remove/add/select during update — UNLOCKED (2026-07-09):** dropped the `!isUpdating()` guard
in both panels (kept the *update* action gated to avoid re-entrant updates). Made
`runUpdate`/`runAvailabilityCheck` iterate `new ArrayList<>(toBeUpdated)` snapshots so removing a
source/seedlink mid-scan can't CME.

**Build workflow (told user):** `mvn clean` only wipes `target/`, NEVER `.GlobalQuakeData/`
(`MAIN_FOLDER = ./.GlobalQuakeData/`, relative to launch cwd; both gitignored). Building never
deletes GlobalQuake data. `clean` not needed each time — incremental
`mvn -pl GlobalQuakeClient -am package -DskipTests` ≈16s vs clean install ≈36s. Only real friction
is the jar lock when the app runs from target/ (close it, or run from IDE).

**STILL PENDING / caveats:**
- BGR/Haiti in-app parse failure not yet root-caused (need the running app's Logger.error output;
  servers themselves are UP — confirmed via curl).
- Duplicate "Iris DMC" station source lives in the user's saved DB, not in defaults (user can now
  delete it via the unlocked Remove button).
- Custom `felt_strong` sound swap requested — deferred to last, NOT yet done.
- All above built clean (35 tests pass); COMMITTED + pushed to the fork as `bad9741b`
  (origin = plasma-node/GlobalQuake, see [[git-remotes]]).

Related: [[seedlink-date-parsing-bug]] (the ISO-8601 fix, done), [[build-setup]], [[project-overview]].
