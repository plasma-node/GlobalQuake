---
name: headless-ntfy-deployment
description: "Client-side Debian deployment package (2026-07-17) — --headless/--nosound/--sound-strong-only/--autoselect flags + ntfy push notifier + Jarvis JSONL; all additive, uncommitted"
metadata: 
  node_type: memory
  type: project
  originSessionId: 79e25f83-3515-42c6-b4f1-2544ac6f2faf
---

Implemented 2026-07-17 (built clean, 35/35 tests pass, headless smoke-tested on Windows; NOT yet
committed, NOT yet ntfy end-to-end tested). All in GlobalQuakeClient — server module untouched,
GlobalQuakeCore untouched. Plan: `.claude/plans/crystalline-herding-pony.md`. Design/reqs:
`.ai/ntfy-notifier-spec.md`.

**New CLI flags** (`globalquake.main.Main`, parse-FIRST then init error handler with real headless
flag, then prepare, then apply Settings flags): `-h/--headless`, `-n/--nosound` (Settings.enableSound=
false), `-q/--sound-strong-only` (SoundsService.strongShakingSoundOnly — mutes all but felt_strong),
`-a/--autoselect`.

**New files**: `main/ClientBootstrap.java` (asset loads extracted from MainFrame.initAll, shared by
both modes), `main/HeadlessClient.java` (no-frame bootstrap mirroring gqserver Main + MainFrame.finishInit;
shutdown hook replicates the window-close archive save; System.exit(1) on fatal init since headless
error handler doesn't exit), `main/StationAutoSelector.java` (SelectAllAction loop w/o dialog, must run
in runAvailabilityCheck onFinish), `notify/` package (NtfyConfig, NtfyService, QuakeTracker, NotifyTier).

**Modified**: MainFrame (initAll delegates to ClientBootstrap; autoselect hook in finishInit),
GlobalQuakeLocal (clear() null-frame guard; NtfyService constructed in all 3 ctors after soundsService +
destroy()), SoundsService (static strongShakingSoundOnly + private play() gate on all ~13 internal
playSound calls).

**NtfyService**: config `.GlobalQuakeData/ntfy.properties` (separate from Settings; template written
disabled on first run). Tiers NEARBY<SHAKING<STRONG: NEARBY from AlertManager's AlertIssuedEvent (local
bus onWarningIssued) + zone-radius; SHAKING/STRONG from home-PGA formulas copied exactly from
SoundsService (GeoUtils.geologicalDistance/pgaFunction vs IntensityScales shaking/strongShaking
thresholds). Dedupe by PHYSICAL FINGERPRINT (origin ±30s + epicenter ≤100km) not UUID — survives
remove/recreate churn; first-notify after notifyDelayMs(4s); re-notify on tier change or |Δmag|≥0.7,
upgrades bypass 30s rate floor; optional cancel note (archive≠remove). Jarvis feed
`.GlobalQuakeData/nearby_quakes.jsonl` (atomic tmp+move, retention-pruned). Own 1s tick executor for all
HTTP (java.net.http, no new deps) + file I/O; listeners only mutate state; never touches Swing; inert
no-op when disabled. Simulation guard: playground quakes ignored unless allowSimulated=true.

**Deploy**: `deploy/globalquake-client.service` (systemd) + `deploy/README.md`. WorkingDirectory holds
.GlobalQuakeData; `-Djava.awt.headless=true` as canary; SIGTERM+TimeoutStopSec for archive hook.

**2026-07-17 ntfy debug**: first end-to-end attempt got no notification. Cause: user's serverUrl had
a trailing slash (`https://ntfy.sh/`) → app built `https://ntfy.sh//topic` → ntfy returns HTTP 307
redirect → Java HttpClient (default no-follow) dropped it. FIXED: NtfyService now normalizes the
endpoint (strip trailing slash on serverUrl, leading slash on topic) AND uses
followRedirects(NORMAL). Verified pipe works via curl (correct URL=200, double-slash=307). Also
confirmed: `.GlobalQuakeData` IS gitignored (quake data/configs never hit git). NOTE: user runs the
playground from `GlobalQuakeClient/target/` so the ACTIVE config is
`GlobalQuakeClient/target/.GlobalQuakeData/` (home set (PNW); multiQuakeMode=0), NOT the repo-root one
(home=0,0). Config loads once at NtfyService construction — must restart playground after editing
ntfy.properties.

**2026-07-17 ntfy bug #2**: after enabling, sends failed with `IllegalArgumentException: invalid
header value` — the em dash `—` in the notification Title. HTTP headers must be ASCII (java.net.http
enforces it). FIXED: title uses `-` not `—`, plus an `asciiHeader()` sanitiser on Title+Tags (body
stays UTF-8 so accented region names survive). Rebuilt. Tier computation, enable-load, fingerprint,
4s-delay all confirmed working from the log — this was the last blocker. Also clarified to user:
`.GlobalQuakeData` is relative to the launch CWD (no fixed location); dev confusion = launching from
repo root vs GlobalQuakeClient/target/ (latter is wiped by `mvn clean` — use repo root). Prod = systemd
WorkingDirectory pins it. Repo-root config now set up test-ready (enabled, topic, home (PNW),
allowSimulated=true).

**2026-07-17 ntfy WORKS + tier model v2**: end-to-end confirmed (notification landed on phone).
Then reworked to a 4-tier priority model per user: NEARBY(low/prio2, gated by nearbyMinMagnitude=2.5,
"in area not felt") < SHAKING(default/3, felt threshold) < STRONG(high/4, strong threshold) <
IMMINENT(max/5). IMMINENT is TIME-BASED (EEW-style): fires once per imminentDebounceMs(60s) when
S-wave ETA at best zone <= imminentSeconds(10) AND shaking there >= imminentMinTier(SHAKING default;
set STRONG for strong-only), independent of the 4s first-notify delay. Body now includes P/S wave
ETA to the best zone ("P wave in 12s, S wave in 21s") via TauPTravelTimeCalculator. New config keys:
priorityImminent, tagsImminent, nearbyMinMagnitude, imminentEnabled/Seconds/DebounceMs/MinTier. Tier
upgrades still bypass the 30s rate floor. Updated NotifyTier(+IMMINENT,+parse), NtfyConfig, QuakeTracker
(+bestZoneLat/Lon,+lastImminentAt), NtfyService (computeTier mag-gate+best-zone coords, checkImminent,
etaLine). Repo-root ntfy.properties migrated to new priority numbers. Built, 35/35 tests.

**2026-07-17 batch 3 (sounds + ETA/miles + local HTTP)**: (1) ETA header on ntfy body ("ETA 21s" or
"Nm" if ≥100s, S-wave time until shaking) + exact P/S at end unchanged; (2) front-facing distances
now MILES (ntfy body "X mi from zone"; JSONL field renamed distKm→distMi) — internals stay km
(toAngle etc.), depth stays km (standard). (3) `shaking_imminent` sound added: new GQSound
(shaking_imminent.mp3), plays client-side once when STRONG shaking expected at home AND S-wave <=10s;
also plays under --sound-strong-only. (4) GQSound.load() now transcodes non-PCM→PCM so MP3 plays
directly (client bundles mp3spi/jlayer SPI, verified in shaded jar + decode-tested: MPEG1L3→PCM16 OK).
(5) Replaced the BLANK shipped felt_strong.wav with the user's real 2.9MB one (was at repo
`resources/felt_strong.wav`; they kept re-pasting it) — now baked into GlobalQuakeCore resources.
Copied user's EarthquakeShakingImminentSHORT.mp3 → resources/sounds/shaking_imminent.mp3. Both sounds
appear in SoundsSettingsPanel automatically (iterates ALL_ACTUAL_SOUNDS). (6) LocalStatusServer: JDK
com.sun.net.httpserver on 127.0.0.1:8090 (configurable), GET /nearby serves the JSONL; runs even if
push disabled (NtfyService active = enabled || httpServerEnabled; POSTs gated on enabled). New config
keys: httpServerEnabled/Bind/Port. HTTPS is out of scope — loopback only, front with reverse proxy if
remote. User set imminentMinTier=STRONG. Built, 35/35 tests, sounds bundled+decodable. Live repo-root
config updated (httpServerEnabled=true).

**2026-07-17 batch 4**: ntfy body now shows expected shaking intensity right after ETA
("Est. intensity IV MMI") via `IntensityScales.getIntensityScale().getLevel(bestPga)` +
`Level.getFullName()`/`scale.getNameShort()` — uses the user's selected scale (MMI/Shindo), omitted
when below the lowest level. Body order now: ETA → Est. intensity → region → mag/depth → mi-from-zone
→ P/S waves. Built, jar packaged.

**PENDING**: user to test the new tiers/ETA/miles/intensity/imminent-sound/http end-to-end; windowed regression +
Debian systemd. First-run needs a completed availability scan before --autoselect yields stations
(pre-seed .GlobalQuakeData from a desktop run). Reminder: user's real .GlobalQuakeData/hypocs.properties
still has multiQuakeMode=2 from picker testing — zero it for real-data monitoring. Future TODOs (noted,
not built): faultline overlay (F-toggle, GEM faults DB), localhost HTTP query endpoint for Jarvis,
headless-client on the actual Debian box. Related: [[multi-quake-merge-diagnosis]], [[build-setup]].
