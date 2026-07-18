# GlobalQuake — Project Overview

## What it is

GlobalQuake is a Java desktop/server application for near-real-time, world-wide earthquake
monitoring. It:

- Pulls seismic station metadata from public `fdsnws-station` services (IRIS, TexNet, RESIF, etc.)
- Streams live waveform data from public `seedlink` servers
- Runs its own detection/association/hypocenter-location algorithms on that data
- Visualizes detected quakes on an interactive 3D globe (rendered with plain Java2D/Swing — no
  OpenGL/JavaFX/LWJGL dependency)
- Estimates magnitude, depth, and location, and can issue its own earthquake early warnings (EEW)

It explicitly does not own any seismic data — it depends entirely on third-party seedlink/fdsnws
providers continuing to share data, and the README/help pages warn results can be inaccurate and
that it's "for entertainment purposes."

## Current upstream status (as of this snapshot)

- Upstream repo: `github.com/xspanger3770/GlobalQuake`. **The repository is archived/read-only**
  (archived ~2025-03-28).
- The README banner on the upstream repo states development beyond **v1.0.0** continues privately:
  > "Beginning from release 1.0.0, this repository is discontinued, and further development of the
  > project will continue privately as part of a proprietary initiative."
- Public development moved to `https://globalquake.net/` (hosted releases, help pages, a status
  page at `status.globalquake.net`) and a Discord server. The public GitHub issue tracker/discussions
  are effectively frozen at the archive point.
- **This local repo is a snapshot of the last open-source pre-1.0.0 dev line**: `pom.xml` reports
  version `0.11.0_pre-2`, branch is `announcement` (mirrors the archived state), main branch for
  PRs historically was `develop`. This is the base we're reviving independently — there is no
  expectation of pulling further upstream updates.
- License: MIT (per README), with two bundled sound-effect sets under separate licenses
  (`LICENSE_J` from JQuake, `LICENSE_K` from KiwiMonitor) — check those files before redistributing
  audio assets.

## Module architecture (Maven multi-module, Java 17)

Root `pom.xml` (packaging `pom`) aggregates 4 modules, built in dependency order:

```
GlobalQuakeAPI      -> wire protocol / data model shared between server and clients
      ^
GlobalQuakeCore     -> detection engine, station DB, seedlink ingestion, geo/regions, archive
      ^                (depends on GlobalQuakeAPI)
      |-- GlobalQuakeClient  -> Swing desktop UI, 3D globe rendering, sound alarms
      |                         (depends on Core + API)
      \-- GlobalQuakeServer  -> headless server, Discord bot (JDA), fdsnws-event HTTP server,
                                 socket protocol to remote clients (depends on Core + API)
```

Key packages inside `GlobalQuakeCore/src/main/java/globalquake/core/`:

- `database/` — station database, seedlink network config, `SeedlinkCommunicator` (station/stream
  discovery over seedlink `INFO` command), `FDSNWSDownloader` (station metadata over HTTP)
- `seedlink/` — `SeedlinkNetworksReader`, the long-running per-network threads that stream live
  miniSEED data
- `earthquake/` — detection/association/hypocenter logic
- `analysis/`, `intensity/`, `alert/`, `archive/`, `regions/`, `geo/`, `report/`, `training/`, `lab/`

`GlobalQuakeAPI/src/main/java/gqserver/api/` defines the client↔server wire packets (station,
earthquake, cluster, system data + packet framing) used by `GlobalQuakeServer`'s socket service and
by remote client connections.

## Key third-party dependencies

- **seisFile** (`edu.sc.seis:seisFile:2.1.0-SNAPSHOT`) — miniSEED parsing + SeedLink client
  (`SeedlinkReader`, `DataRecord`, `SeedlinkPacket`). This exact snapshot version isn't on Maven
  Central; it's vendored as a jar under `libs/edu/sc/seis/seisFile/2.1.0-SNAPSHOT/` and exposed to
  Maven via a `file://` repository declared in each module's `pom.xml`. **Don't `mvn clean` the repo
  root in a way that deletes `libs/` — it's not fetchable from a public repo.**
- **seedCodec**, **TauP** (also by Philip Crotwell / USC) — miniSEED decompression and travel-time
  tables for hypocenter search
- **tinylog** — logging (config in root `tinylog.properties`)
- **iirj**, **JTransforms** — signal processing (IIR filters, FFT) for waveform analysis
- **geojson-jackson**, **org.json**, **opencsv**, **h3** (Uber) — geo data / region lookups
- **JDA** (Java Discord API, server module only) — Discord bot integration
- Sound: `mp3spi`, `tritonus-share`, `jlayer` (client module only, for alarm sounds)
- **GQHypocenterSearch** — a separate CUDA/C++ native module (CMake) for GPU-accelerated hypocenter
  search, loaded via JNI (`globalquake_jni_GQNativeFunctions.h`). This is optional GPU acceleration,
  not required to build or run the Java application — see `vscode-build-setup.md`.

## Where things run from

- Desktop client entrypoint: `globalquake.main.Main` (in `GlobalQuakeClient`)
- Server entrypoint: `gqserver.main.Main` (in `GlobalQuakeServer`), supports `--headless`
- `Dockerfile` builds an Ubuntu 22.04 image running the headless server jar with the CUDA `.so`
  optionally mounted at `./lib`
- CI (`.github/workflows/build.yml`) just runs `mvn -B clean install` + `mvn test` on JDK 17
  (Temurin), on every push/PR to `main`/`develop` — no packaging/release automation lives in CI.

See `vscode-build-setup.md` for what's needed to get this building locally, and
`seedlink-connection-issue.md` for the current seedlink connectivity bug investigation.
