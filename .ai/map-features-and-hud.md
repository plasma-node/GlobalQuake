# Map features & HUD — fault lines, regional capitals, active-quakes list, crosshair, scale bar

Status: **implemented** (client v0.11.0_pre-3). Landed across commits `64bb6178` → `83cd6545`.
This doc records what was built, why, and the non-obvious decisions, so the context survives.

> ⚠️ PUBLIC REPO — no secrets/coords here. Code + design only.

## 1. `--fastscan` / `-f` flag  (commit 64bb6178)
Warm-boot optimisation: probe only the seedlink networks that already carry a *selected* station
(fast restart, fewer public-server queries), falling back to a full scan if nothing is selected yet.
- `StationAutoSelector.seedlinkNetworksForScan(dbm)` is the single decision point; wired into both
  `HeadlessClient.run` and `MainFrame.finishInit`.
- **Travel gotcha:** don't bake `--fastscan` into a *travelling* daemon's systemd unit — on a
  `/sethome`+`/restart` it would only re-probe the old area's networks, never discovering the new
  area's stations. Use it only on a fixed-location box.

## 2. Active-quakes side list  (64bb6178, refined later)
Clickable list of currently-detected quakes, sorted by the **same significance cinema uses**
(`mag*100` + alert boost `+10000` + `pgaHome*2000`). Each row: magnitude chip, region (WRAPPED to 2
lines, not truncated), "time ago", and a red **!** badge when the quake is expected to be felt at
home (≥ MMI II). Click a row → pin it (camera jumps, cinema pauses, top-left box sticks to it);
click again → unpin/resume. Pins self-clear if the quake leaves the live list (no stuck modes).
- Hook added: `GlobePanel.overlayClicked(x,y)` — called first in `handleClick`, lets 2D overlays
  consume a click before the globe-feature hit test.
- **Position:** far-left, anchored at `leftPanelBottom` (computed in `drawEarthquakesBox` = below the
  132px detail box + 200px magnitude histogram) so it sits *under* the magnitude bar, and lifts the
  bottom-centre scale bar clear of the "shaking expected" banner via `alertBoxTop`.

## 3. Fault-lines overlay (F key)  (48b2e0eb → 2c59c96a)
GEM Global Active Faults DB bundled at
`GlobalQuakeCore/src/main/resources/faults/gem_active_faults.geojson` (12 MB, 16,195 traces,
**CC-BY-SA 4.0** — ATTRIBUTION.txt beside it; keep the license if the data file is modified). Parse
adds ~180 ms to startup.
- New `GQFault` (open polyline + slip-type byte + `lengthKm` + `major` flag); `Regions.parseFaults`
  handles `LineString`/`MultiLineString` (existing `parseGeoJson` only did polygons).
- **slip_type gotcha:** GEM values use UNDERSCORES (`Subduction_Thrust`, `Spreading_Ridge`,
  `Sinistral_Transform`, …). `GQFault.categorize`/`isPlateBoundary` tokenise on `-`, `_`, whitespace.
- **Open-line projection:** `GlobeRenderer.project3D` closes rings; added a `close` param — 4-arg
  delegates `close=true` (rings unchanged), `FeatureFaults` uses `close=false`. Reusable hook for any
  future open-line overlay (e.g. tsunami coastlines).
- **LOD:** `FeatureFaults` culls minor faults when zoomed out (threshold `(scroll-0.05)*faultLodFactor`,
  capped so majors never hide). **Major** = plate boundary (subduction/spreading) OR `lengthKm≥180`;
  majors ALWAYS render and draw considerably thicker (2.2×+). Round caps/joins close small gaps.
- **Colour:** by slip type. FULL = dextral(blue)/sinistral(purple)/strike-slip(green)/normal(red)/
  reverse(orange)/other(gray). BASIC = transform/extensional/compressional + other. **F cycles**
  Shown(Full) → Shown(Basic) → Hidden; state shown in the bottom-left keybind HUD; small legend
  bottom-right reflects the mode. Alpha 165 for less visual noise. (Playground's old F binding for
  `displayPlaygroundQuakes` was remapped to **G**.)
- Settings: `displayFaultLines`, `faultColorSimple`, `faultLineThickness`, `faultLodFactor` — all in
  the Graphics settings panel (checkboxes + sliders).

## 4. US/Canada regional capitals  (48b2e0eb → 2c59c96a)
`FeatureRegionalCapitals` from the already-bundled `worldcities.csv` (`iso2 ∈ {US,CA}` and
`capital==admin`), smaller than national capitals. Live: hidden at `scroll ≥ 0.5` (matches the US/CA
border LOD band = "when state borders disappear"). Screenshots: `FeatureRegionalCapitals(true)` =
always shown.

## 5. Screenshot HUD  (1333ce25 → 83cd6545)
- Region the camera is centred on (`Regions.getRegion(lat,lon)`) above the timestamp.
- Recent-quakes list shows right-aligned **T+4m** elapsed-since-origin age (T+ = not an ETA).
- Text-backing boxes less transparent (alpha ~210).
- Station-dot size is now `NtfyConfig.screenshotStationSizeMul` (default 1.0), not a hardcoded 0.5.
- `?faults=0|1` per-shot override on `/screenshot`.

## 6. Home crosshair + scale bar  (e6cfbd55 → 83cd6545)
Both are screen-space overlays in `MapOverlays` (shared by live map + screenshot), drawn last so they
sit on top of everything.
- **Crosshair:** projected home point → 4px `+` with a thick black outline + magenta core (reliable;
  the earlier XOR approach rendered green in this pipeline). Hidden when home is behind the globe.
  `FeatureHomeLoc` is now unused (dropped from both stacks).
- **Scale bar (miles):** `MapOverlays.milesPerPixel` MEASURES the local scale empirically — projects
  two points a known latitude apart near centre and divides real ground distance by pixel separation
  (0.05/1/10° baselines). `pxToDeg` is NOT a linear ground-distance measure on a perspective globe
  (it read 57,000 mi / 1,000 mi for small views); the empirical measure is correct at any zoom.
  Classic bar with end ticks + round 1/2/5×10ⁿ label, bottom-centre, lifted above the alert banner.

## 7. `/version` endpoint  (e6cfbd55)
`GET /version` → `{version, uptimeSeconds, stations, faultTraces, simulation}` — verify the running
build after a deploy/restart.

## Deferred (researched, NOT built)
- **Tsunami risk:** honest proximity/energy heuristic — ship Natural Earth `ne_50m_coastline`
  (public domain), gate on Mw≳7 + shallow + offshore (point-not-in-land via existing polygons; bonus
  = near a subduction GEM fault), radius-of-concern R(Mw), highlight coastline segments in range.
  Reuse the `close=false` projection hook. Explicit "proximity heuristic, not wave propagation" UI
  caveat. No bathymetry.
- **Historical replay (`?replay=uuid`):** would need per-quake shakemap/hexagon state persisted in the
  archive + a lookup + render path. Deferred (user leaning toward a separate GPU-accelerated
  dashboard from saved data later).
- **Fault plane solutions / beach balls:** needs upstream's CNN first-motion data. Out of scope.
- **Fault-name hover tooltip:** easy stretch (mirror the archived-quake hover).

## Open pre-deploy nice-to-haves
- `/all` JSON enrichment (`ageSeconds` + `felt`).
- systemd EnvironmentFile for home/radius/fastscan (travel).
