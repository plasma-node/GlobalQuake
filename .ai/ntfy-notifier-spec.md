# ntfy notifier + Jarvis/OpenClaw integration — requirements & proposed design

Captured 2026-07-17 from user request (verbatim intent), plus proposed architecture. Not built yet.

## User requirements (as stated)

- Send notifications via **ntfy** from the **client** (client is the detecting instance; it computes
  local shaking). Config lives on the client.
- Ideally a way to run the **client headless** (detection without UI).
- Real config sets the **home location** at boot; a separate **notifier config** specifies the
  area/areas for which alerts are sent.
- When the software intrinsically decides "shaking expected", "strong shaking", or "earthquake
  nearby", route that through the notification service with the appropriate tier.
- User will supply the meat later: endpoint/topic to POST to, priority levels, message wording.
- **Jarvis (OpenClaw agent) integration**: agent needs to see what earthquakes happened recently —
  filter by preconfigured home coords+radius, or ideally pass coords in a query. Also: client logs
  nearby shaking-detected quakes to a file, cleared after a few minutes, so the agent can read it.
- **Debounce**: don't spam. Wait a moment (~seconds) for magnitude to settle before first send.
  Re-notify only on significant intensity upgrade/downgrade or genuinely new quake. Must NOT
  re-alert when the same physical quake is deleted and re-created under a new UUID ("thinks the
  other one died").

## ntfy API facts (verified via docs.ntfy.sh 2026-07-17)

- POST/PUT to `https://ntfy.sh/<topic>` (topic = de-facto password; self-hosting supported).
- Headers: `Title`, `Priority` (1=min, 2=low, 3=default, 4=high w/ pop-over, 5=max/urgent),
  `Tags` (comma emoji shortcodes e.g. `warning,rotating_light`), `Click` (URL incl. `geo:`),
  `Markdown: yes`. Auth: Basic or Bearer token. Body ≤ 4096 bytes as text.

## Proposed architecture

New client-side package `globalquake.notify`:
- `NtfyService` subscribes to the existing GlobalQuake event bus (QuakeCreateEvent /
  QuakeUpdateEvent / QuakeRemoveEvent) + reuses the client's existing home-location alert math
  (same PGA/MMI path the felt/felt_strong sounds use — GeoUtils.pgaFunction + intensity scales).
- Config `.GlobalQuakeData/ntfy.properties`: enabled, serverUrl, topic, authToken?, zones
  (name:lat:lon:radiusKm, multiple), tier thresholds (MMI/PGA per tier), priorities per tier,
  message templates, debounce params.
- Tiers → ntfy mapping (proposal): quake-nearby-no-shaking = P2/low; light shaking expected =
  P3 + `warning`; strong shaking = P5 + `rotating_light` (bypasses DND on phones); optional
  all-clear/downgrade = P1.

### Debounce / dedupe state machine (per physical event, not per UUID)
- **Fingerprint**: origin time ±30s AND epicenter within ~100km ⇒ same physical quake, regardless
  of UUID (survives remove/re-create churn, quarantine promotions, identity takeovers).
- First notify: after quake survives `notifyDelayMs` (~3–5s, a few revisions) so magnitude settles.
- Re-notify only if: tier changed (up OR down by ≥1 tier), or |Δmag| ≥ ~0.7, or distance-to-zone
  classification changed. Rate-floor between sends (~30s) except tier UPGRADES send immediately.
- QuakeRemove without replacement fingerprint within ~60s → optional low-priority cancel note.

### Jarvis/OpenClaw access
- Phase 1 (trivial, agent-friendly): `.GlobalQuakeData/nearby_quakes.jsonl` — one JSON line per
  quake currently/recently affecting configured zones: {uuid, fingerprint, origin, lat, lon, depth,
  mag, distKm, expectedMMI, tier, updatedAt}. Pruned on a timer (user said clear after a few
  minutes; make retention configurable, e.g. 10–60 min).
- Phase 2 (optional): tiny localhost HTTP endpoint on the client (GET /nearby?lat=..&lon=..&radius=..)
  mirroring the server module's FDSNWS style — gives the agent arbitrary-coords queries.
- Alternative considered: run GlobalQuakeServer alongside (it already has FDSNWS-event HTTP API,
  see .ai/api-usage-guide.md) — rejected as primary because user wants the CLIENT (single instance)
  to own detection + alerting.

### Headless client
- Stretch goal: `--headless` flag for the client entrypoint skipping Swing frame creation (keep
  runtime + notifier). Needs an audit of UI-touching listeners (alerts/sounds reference Swing?).
  Until then: client runs with UI on the monitoring box.

## Deployment requirements (added 2026-07-17, user request)

- Run detection instance **headless as a daemon**: Linux (systemd unit) or Windows (service, e.g.
  NSSM/sc.exe). Note `GlobalQuakeServer` ALREADY supports `--headless` — likely the right vehicle
  for the server computer; the desktop client currently cannot run headless.
- **Automated station selection** at boot: config-driven — "select all available" or a subset
  spec, no UI clicking. Today selection lives in the saved station database edited via UI.
- Server deployment implies the notifier should ALSO work from GlobalQuakeServer eventually
  (same core event bus), even though user wants client-first.
