# Design: detect multiple nearby-in-time earthquakes (and stop M6+ duplicates)

Status: **IMPLEMENTED 2026-07-09** (both A and B). Built clean, 35 Core tests pass. NOT yet
sandbox-verified with two spawned quakes, and NOT yet committed. Original design retained below;
"Implementation notes" at the bottom records what actually shipped and what to watch in testing.
Scope: `GlobalQuakeCore` clustering/association (`core/earthquake/ClusterAnalysis.java`,
`core/earthquake/EarthquakeAnalysis.java`, `core/analysis/*`). This is the most delicate part of the
app; the goal is a *minimal, targeted* change that does not regress normal single-quake behaviour.

---

## 1. Problem

README known issues:
- "Unable to detect multiple earthquakes in the same epicenter in short period of time."
- "Larger earthquakes (M6+) often trigger false detections or show duplicated earthquakes."

These are two faces of the same association logic being tuned hard against duplicates.

## 2. Root cause (verified against code)

Clustering runs every ~300 ms in this fixed order (`ClusterAnalysis.run()`, lines 52-66):
`expandExistingClusters()` → `expandPWaves()` → **`createNewClusters()`** → `stealEvents()` →
`mergeClusters()` → `updateClusters()`. Existing clusters grab picks **before** new clusters can form.

1. A live quake's cluster runs `expandPWaves()` (only once `correctEvents > 7`, line 369) using
   `couldBeArrival(..., increasingPWindow=true)` — a **~10 s** window (`ClusterAnalysis.java:329`,
   `max(10000, 1000 + travel*0.01)`), far wider than the strict `Settings.pWaveInaccuracyThreshold`
   (1800 ms default).
2. A second quake at the same/nearby epicenter has **near-identical arrival times**, so every one of
   its picks satisfies that wide window and is claimed: `event.assignedCluster = cluster1`
   (lines 403-417).
3. `createNewClusters()` only considers picks with `assignedCluster == null` and needs
   ≥ `clusterMinSize` (default 4) of them (lines 447, 471). The second quake is starved → no cluster.
4. The solver then **trims the stolen picks as residual outliers** (`EarthquakeAnalysis.findHypocenter`,
   residual reduction lines ~317-340) — so they are *owned by cluster #1 but excluded from its fit*.
   Invisible and unavailable to anyone else.
5. Even if a second cluster somehow forms, `mergeClusters()`/`canMerge()` (lines 147-208) deletes it
   when > `MERGE_THRESHOLD` (0.54) of its events are consistent (wide window) with the first quake and
   it is within `maxDist = 6000/(1 + correct*0.2)` km — **no origin-time check at all**, so a genuine
   doublet/aftershock at the same spot is merged away regardless of *when* it happened.

Secondary (picker-level, NOT touched by this design — too risky): a station is locked in `EVENT`
status ≥7 s and until shaking decays (`BetterAnalysis.java:140,157-176`), and same-station picks
within `MIN_EVENT_DIFF = 3000 ms` are invalidated (`Event.java:23,102-112`). These reduce the number
of *independent* second-quake picks but are not the primary blocker; leave them for a later phase.

The **M6+ duplicate** is the inverse: one giant quake's teleseismic/PKP picks form a spatially
detached second cluster (`createNewClusters` chains only through `getNearbyStations`, line 452;
PKP/PKIKP allowed in `couldBeArrival` 338-356), and the shrinking `maxDist` then *refuses* to merge it
back (line 194). So the same fix area governs both bugs — good, one lever.

## 3. Goals / non-goals

Goals:
- Detect a genuine second earthquake near in space and within a short time (seconds → a few minutes)
  of a first one.
- Reduce, not increase, M6+ duplicate detections.
- Zero regression to the normal single-quake path (the common case).

Non-goals (explicitly out of scope for this change):
- Touching the station picker (`BetterAnalysis`), `EVENT`-status refractory, or `MIN_EVENT_DIFF`.
- Narrowing the ~10 s `increasingPWindow` (it stabilises the primary solution; high regression risk).
- Any GPU/`GQHypocs` path.

## 4. Proposed changes

Two composable changes. **B is small and enabling; A is the core fix. Ship B first, then A.**

### Change B — merge only when origin TIMES are close (temporal guard)

`canMerge(earthquake, cluster)` (`ClusterAnalysis.java:190`) currently merges on spatial overlap +
>54% arrival consistency, with **no time check**. Add: when *both* the primary `earthquake` and the
candidate `cluster.getEarthquake()` exist, compute `dtOrigin = |earthquake.origin - cluster.getEarthquake().origin|`
(origin time is available: `Hypocenter.origin`, used in `couldBeArrival` line 304/312). 

- If `dtOrigin > ORIGIN_TIME_MERGE_SEPARATION` → **return false** (distinct events in time; never merge).
- Else fall through to the existing spatial/overlap logic (still dedups a true duplicate of the *same*
  quake, whose origin time matches).

Proposed default `ORIGIN_TIME_MERGE_SEPARATION ≈ 15 s` (configurable via `HypocsSettings`). Rationale:
two solutions of the *same* rupture land within a few seconds of origin time; a real doublet/aftershock
differs by more. Tunable during testing.

Edge case: candidate cluster has **no** earthquake yet (`getEarthquake() == null`) → no origin time →
keep current behaviour (allow merge) so half-formed noise clusters still get absorbed. Only *established*
second quakes are protected.

Why safe: strictly *reduces* merging, and only when there is a real, well-defined time gap. On its own
B also directly helps the M6+ case is NOT its target (that's spatial) — B is specifically the
"same-place, different-time" protector. B alone does nothing without A (nothing forms a 2nd cluster to
protect), which is why order is B then A but both are needed.

### Change A — let residual (unexplained) picks form a second cluster

Core idea: picks that a well-established quake **cannot explain** (fail the *strict* window and are
being outlier-trimmed anyway) should be released so `createNewClusters` can build a second cluster from
them — **but** we must stop `expandPWaves` from immediately re-stealing them (the oscillation trap,
below), and we must not destabilise quake #1.

**The oscillation trap (critical):** `updateClusters` (release, line 488-496) runs *last*; next cycle
`expandPWaves` runs *first* and, using the wide window, re-grabs any pick that fits wide-but-not-strict —
i.e. exactly the picks we want to free. So a naive "set `assignedCluster = null`" never lets
`createNewClusters` see them. Must be prevented.

**Chosen mechanism — per-event rejection memory:**
1. Add to `Event` a lightweight marker of the cluster(s) that have rejected it, e.g.
   `transient Cluster rejectedBy` (or a small `Set<Integer> rejectedClusterIds`). Transient is fine —
   this is live-analysis state, never persisted.
2. New pass `releaseMisfitEvents()` (call it inside `updateClusters`, extending the existing
   release loop at lines 488-496, or as its own step right *before* `createNewClusters`). For each
   cluster whose earthquake is **well established** (`earthquake != null` and
   `previousHypocenter.correctEvents >= RELEASE_MIN_CORRECT`, propose 8, matching the `>7` gate that
   turns on `expandPWaves`): for each assigned event that **fails the strict test**
   `couldBeArrival(event, earthquake, considerIntensity=false, increasingPWindow=false, pWaveOnly=true)`
   → set `event.rejectedBy = cluster`, `event.assignedCluster = null`, remove from cluster.
3. In `expandPWaves` (and `expandCluster`/`expandPWaves` steal points, lines 394/403-417) skip any
   event where `event.rejectedBy == thatCluster`. Other clusters and `createNewClusters` may still use
   it. This breaks the oscillation while keeping the pick available to form a *distinct* cluster.
4. `createNewClusters` is unchanged: it will build a second cluster only if ≥ `clusterMinSize` (4)
   released picks are **mutually consistent at a distinct origin** (its own 5 km/s moveout test,
   lines 457-460). This ≥4-consistent gate is the natural safety valve — scattered noise picks won't
   form a cluster; only a real coherent second source will.
5. If the picks that got released actually *were* quake #1 (edge jitter) and re-form a cluster at the
   same origin *time*, Change B lets that re-merge (times match). A genuine second quake (different
   origin time) is protected by B. A and B interlock.

Clear `rejectedBy` when the event is removed/reset (it already gets removed via `EVENT_STORE_TIME` /
invalidation in `BetterAnalysis.second()`), so the marker cannot leak across unrelated future quakes.

**Why this won't worsen M6+ duplicates:** A only *releases* picks a quake cannot explain; the M6+
duplicate problem is picks that DO fit (teleseismic PKP) forming a detached cluster that then fails to
merge. A doesn't create those. And B's time guard actually *helps* the M6+ case: a teleseismic
duplicate of the same rupture shares the origin time, so B keeps it mergeable (does not block merge),
while the existing `maxDist` is what wrongly blocks it — so as a *stretch* we may also relax the M6+
`maxDist` rule, but that is a SEPARATE, later change, tracked in §9, not part of A/B.

## 5. New parameters (all configurable, conservative defaults)

| Param | Proposed default | Where | Meaning |
|---|---|---|---|
| `ORIGIN_TIME_MERGE_SEPARATION` | 15 s | HypocsSettings | min origin-time gap to treat clusters as distinct in `canMerge` |
| `RELEASE_MIN_CORRECT` | 8 | HypocsSettings | only release misfit picks from quakes at least this well-constrained |
| (reuse) strict window | `Settings.pWaveInaccuracyThreshold` (1800 ms) | Settings | the "does this pick fit quake #1" test used for release |

Keeping them in `HypocsSettings.getOrDefaultInt/Double` lets us tune without recompiling and lets a
user disable the feature (e.g. `RELEASE_MIN_CORRECT` huge → A off).

## 6. Edge cases

- **Aftershock during coda:** near-source stations may be picker-blind (EVENT lock) — expected partial
  loss; distant stations still pick the second P and can form the cluster. Acceptable; documents the
  known picker limitation.
- **Two quakes < ~3 s apart at one station:** `MIN_EVENT_DIFF` kills the second same-station pick —
  still relies on *other* stations. Not solved here (picker scope).
- **Released pick re-forms duplicate of #1:** handled by B (same origin time → allowed to merge).
- **Candidate cluster without a quake yet:** B keeps legacy merge behaviour (no time to compare).
- **Rejection memory leak:** cleared on event removal; transient (never serialized).
- **Very large quake:** more events, more `expandPWaves` churn — verify no perf regression from the
  extra strict-test loop (it is O(assigned events) per cluster per cycle, same order as existing loops).

## 7. Test matrix (sandbox — user spawns quakes manually)

Baseline first: **regression** — one normal M4–M5 quake still detected identically (location, mag,
single detection, no duplicate). This is the gate that must stay green after every step.

| # | Scenario | Expect |
|---|---|---|
| R | Single M4.5 (control) | unchanged behaviour, exactly one earthquake |
| 1 | Two M4.5, same epicenter, ~30 s apart | two distinct earthquakes (was: one) |
| 2 | Two M4.5, same epicenter, ~5 s apart | ideally two; acceptable degraded = one (picker limit) — must NOT crash/duplicate-storm |
| 3 | Two M4.5, ~100 km apart, ~20 s apart | two distinct earthquakes |
| 4 | One M6.5 teleseismic | **one** earthquake, no duplicate (must not regress) |
| 5 | One M4.5 then aftershock M3 ~60 s later | two earthquakes |
| 6 | Rapid noise / many false triggers | no spurious clusters (≥4-consistent gate holds) |

For each: record earthquake count, origin times, locations, and whether any `QuakeRemoveEvent` storm
occurs. Compare against a build of current `main` for the same spawn (A/B off vs on).

## 8. Rollback plan

- All changes are guarded by the new HypocsSettings params. Setting `RELEASE_MIN_CORRECT` very high and
  `ORIGIN_TIME_MERGE_SEPARATION` to 0 restores current behaviour without reverting code.
- Changes are isolated to `ClusterAnalysis` (+ one transient field on `Event`); no serialization/format
  change, so reverting the commit is clean and cannot corrupt a saved database.
- Implement incrementally on a branch, one change per commit (B, then A), running scenario R after each.

## 9. Open questions / verify at implementation time

- Confirm exact residual-trim lines in `EarthquakeAnalysis.findHypocenter` and that trimmed picks truly
  retain `assignedCluster` (root-cause step 4) — read 300-345 before coding A.
- Confirm `Hypocenter.origin` units (ms epoch) for B's `dtOrigin`.
- Decide `rejectedBy` as single `Cluster` vs `Set` (can one pick be legitimately near two distinct
  quakes? rare; start with single, revisit if scenario 3 needs it).
- Perf check on 1000-station M6 spawn (extra per-cycle strict-test loop).
- STRETCH (separate change, not A/B): relax `canMerge` `maxDist` shrink for the M6+ teleseismic
  duplicate, OR add PKP-aware same-event detection. Do only after A/B are proven.

## 10. Implementation order

1. Branch `fix/multi-quake-detection`.
2. Commit 1: **B** (temporal merge guard) + param. Run scenario R + 4.
3. Commit 2: **A** (rejection memory + `releaseMisfitEvents` + expand skip) + params. Run full matrix.
4. Tune defaults against the matrix; document final values here.
5. Only then consider the §9 stretch for M6+ `maxDist`.

---

## Implementation notes (what actually shipped, 2026-07-09)

Files changed:
- `core/analysis/Event.java`: added `public transient Cluster rejectedByCluster;`.
- `core/earthquake/ClusterAnalysis.java`:
  - `run()`: inserted `releaseMisfitEvents()` between `expandExistingClusters()` and `createNewClusters()`.
  - New `releaseMisfitEvents()`: for each cluster with an established quake
    (`correctEvents >= releaseMinCorrectEvents`, default 8), release each assigned pick that fails the
    strict test `couldBeArrival(event, eq, considerIntensity=false, increasingPWindow=false,
    pWaveOnly=false)`; sets `assignedCluster=null`, `rejectedByCluster=cluster`, removes from cluster.
  - `expandCluster` and `expandPWaves`: skip picks where `rejectedByCluster == thatCluster`
    (breaks the re-steal oscillation).
  - `canMerge` (Change B): after the distance cap, if both clusters have quakes and
    `|origin_a - origin_b| > originTimeMergeSeparationMs` (default 15000) → `return false`.

New tunables (HypocsSettings, no recompile needed):
- `releaseMinCorrectEvents` = 8  → higher disables Change A.
- `originTimeMergeSeparationMs` = 15000 → 0 disables Change B (restores old merge behaviour).

Deliberate conservative choices / watch-items for sandbox testing:
- **S-wave-marked misfits are NOT released** (release loop skips `isSWave`). If cluster #1 marks a
  second quake's P picks as S-waves, those won't free up and the 2nd cluster may stay under
  `clusterMinSize`. If scenario 1/5 still shows the 2nd quake missed, this is the first knob to revisit
  (consider clearing the S flag on release, carefully).
- Used `pWaveOnly=false` in the release test so legit teleseismic PKP/PKIKP of a big quake are kept
  (guards against worsening M6+ duplicates). Trade-off: a 2nd quake pick that coincidentally fits #1's
  wide PKP window (6 s) won't be released — acceptable (under-release beats duplicate-storm).
- `rejectedByCluster` is transient and dies with the Event (`EVENT_STORE_TIME`); a stale ref to a
  removed cluster never matches a live cluster, so it's harmless.

Still TODO: run the §7 test matrix in sandbox (esp. R and #4 regression gates), tune the two params,
then commit. The §9 M6+ `maxDist` stretch was intentionally NOT done.

---

## Playground findings + Change C (2026-07-16)

First live two-quake playground run with `[MQ]` instrumentation (INFO-level census in
`ClusterAnalysis`; keep until final commit) produced `playground-run.log` and **overturned the
assumed blocker**. Evidence:

- `[MQ] createNew: unassignedSeeds=12 bestCorroboration=9 (need>=4) newClusters=7` — the second
  quake's picks DO exist and DO form candidate clusters. A+B work: `releaseMisfitEvents` freed
  30–74 picks/cycle, seeds formed clusters every cycle.
- `[MQ] cycle: clusters=1` — **335 cycles, never 2+**. Newborn clusters were annihilated within the
  same cycle they formed. Zero `merge-BLOCK` lines: Change B never even ran.

**Corrected root cause:** `canMerge`'s guards (distance cap AND Change B's origin-time check) sit
inside `if (cluster.getEarthquake() != null && cluster.getPreviousHypocenter() != null)`. A newborn
cluster hasn't run the solver yet → `getEarthquake() == null` → both guards skipped → fall through
to the LOOSE fit test (`couldBeArrival(..., increasingPWindow=true)`, ±10 s min window) vs
`MERGE_THRESHOLD=0.54`. The wide window re-matches the very picks `releaseMisfitEvents` just
released for failing the 1800 ms strict window → newborn swallowed → picks released again next
cycle → infinite release/reform/swallow oscillation (visible in the log). Change A/B guarded the
wrong direction: they protect established quakes, but the 2nd cluster dies before establishing.
`EarthquakeAnalysis.checkConditions` verified to have NO cross-cluster duplicate suppression — the
solver only compares a cluster to its own previous hypocenter — so `canMerge` was the sole
annihilation point.

**Change C (implemented 2026-07-16):** in `canMerge`, judge a newborn (earthquake-less) cluster by
the SAME strict arrival test used by `releaseMisfitEvents` — `couldBeArrival(event, eq, false,
false, false)` — instead of the loose test (which established clusters keep). Same
`MERGE_THRESHOLD`. Invariant: release and merge now agree on what "belongs to quake #1" means, so
the oscillation is structurally impossible; a released pick can never count toward re-merging its
newborn back into the cluster that released it. Genuine stragglers of quake #1 fit strictly and
still merge back. A distinct quake's newborn survives → solver establishes its origin → Change B's
origin-time guard governs from then on. No new tunables.

Expected lifecycle for a doublet now: release/starve → newborn forms → strict test keeps it
separate → solver locates eq2 → dtOrigin > 15 s blocks established-established merge → two quakes.
Consolidation of same-quake duplicates preserved: sibling solves to ~same origin (dtOrigin ≤ 15 s)
→ loose pct → merged.

Accepted risks / watch in testing:
- Newborn phantom clusters now persist up to 2 min (`updateClusters` tooOld) instead of being
  instantly swallowed → possible transient cluster-level alerts near a big quake. If phantom
  QUAKES (not just clusters) appear, first check `[MQ] merge-eval newborn` lines and the solver's
  quality gates; tuning lives in release strictness, not in re-loosening merge.
- While eq1 has ≤7 correctEvents, `expandPWaves` doesn't run, so eq1's own fresh picks can form a
  sibling newborn that fails strict fit vs a rough early hypocenter → transient duplicate that
  must converge via the established-established path once both solve. Watch scenario R.
- S-wave misclassification ring (unchanged watch-item from A): quake #2 picks landing in eq1's
  S window at the matching distance ring are invisible to clustering. Partial suppressor only.

Also fixed 2026-07-16 (unrelated to clustering): playground never called `ShakeMap.init()`
(`GlobalQuakePlayground.main`) → `ShakeMap.h3` null → NPE every shakemap cycle → no shake zones /
intensity display in playground. One-line fix + import, mirrors `MainFrame.init`.

Status: Change C built clean, 35/35 Core tests pass, client jar packaged. NOT yet
playground-verified (§7 matrix pending on this build), NOT committed. `[MQ]` logging stays in
until final commit per user.

---

## Change C regression + Change D (2026-07-16, same day)

**Scenario R (single M4.5 control) FAILED on Change C alone.** Run log (playground-run.log,
overwritten, 23:09–23:14): cluster storm up to `clusters=17`, 430 origin-time merge-BLOCKs between
ESTABLISHED quakes, phantom origins consistently +24–27 s after the real quake (= the S−P delay),
real quake repeatedly destroyed (`clusters=0` cycles mid-quake; user saw "no earthquake detected"
flicker + quakes jumping to other areas). 1600/1725 newborn merge evals said "kept separate".

**Diagnosis — S-as-P ghost events:** stations end their P event (≥7 s), re-trigger when the S/coda
wavefront arrives ~25 s later; those re-trigger picks are mutually consistent (real physical
wavefront) so the solver locates phantom "second quakes" from them. The loose merge removed by
Change C had been doing double duty: it swallowed genuine doublets (bug) AND garbage-collected
S/coda ghosts (feature). Strict-P-only was the wrong discriminator — the system needs a
PHASE-AWARE test. Why ghosts weren't already suppressed: `markPossibleSWaves`' intensity gate
(`expectedIntensity < 3.0 → not S`) fails at an M4.5's regional stations, so the ghost picks were
never S-marked. Secondary finding: `rejectedByCluster` exile was permanent — release against a
wandering early hypocenter permanently starved the real quake of its own picks (the flicker).
Third: merge direction is earthquake-list iteration order, letting a young phantom absorb (delete)
the real quake when dtOrigin ≤ 15 s.

**Change D (implemented, replaces nothing — layers on A/B/C):** all in `ClusterAnalysis`:
1. `absorbGhostClusters()` — runs after `createNewClusters()`, before `mergeClusters()` (and
   before `EarthquakeAnalysis.run()` in the same tick — verified sequential in
   `GlobalQuakeRuntime:60-61` — so a dissolved ghost can NEVER establish). Dissolves a newborn
   whose picks are majority-explained (> MERGE_THRESHOLD) by an existing quake under strict-P ∪
   S-curve (intensity-free), with sFit > pFit. S-fitting picks → `setAsSWave(true)` (stops
   refueling ghosts, stays out of P solutions); rest released with `rejectedByCluster` memory.
   Straggler-dominated newborns (pFit ≥ sFit) are left for canMerge's Change C path to fold back.
2. `couldBeSArrival(event, eq, considerIntensity)` refactor — 2-arg keeps the gate (global marking
   stays conservative); ghost vote + `clearSWaves`' unmark test use `false` (pure timing; the
   gated unmark would strip ghost-marks every cycle → mark/unmark oscillation).
3. `expandPWaves` exile forgiveness — a rejected pick may be re-claimed by the rejecting cluster
   iff it fits STRICTLY now (claim-back test == release test ⇒ no oscillation). Fixes real-quake
   starvation/flicker.
4. `canMerge` direction guard (established-established): a quake may not absorb a cluster whose
   hypocenter has MORE correctEvents than its own — the reverse merge remains allowed, so
   consolidation still converges, just never backwards (phantom can't eat the real quake).

Discriminator logic for the doublet (why D doesn't reintroduce the swallow): a genuine 2nd quake
30 s later fits neither eq1's strict P (offset ≈ dtOrigin) nor eq1's S curve (S(dist) is
distance-dependent, coincides with "P + constant offset" only on one thin distance ring — never a
majority of a dispersed newborn; near stations, which seed the newborn first, are far off eq1's S
curve). Ghost: ALL its picks ride the S/coda wavefront → majority S-fit → dissolved.

Status: built clean, 35/35 tests. Re-run §7 matrix from scenario R (control MUST be clean before
doublet scenarios). Escape between scenarios is fine. Watch: `[MQ] ghost-DISSOLVE` lines during
the control's S arrival window, `clusters=` census staying ≤2, no merge-BLOCK spam, real quake
stable (no QuakeRemove flicker). `[MQ]` logging stays until final commit.

---

## Change D also failed in playground; coda-front diagnosis + master switch (2026-07-16 late)

Third run: storm again (clusters up to 17, cluster IDs past #1350). **`ghost-DISSOLVE` fired ZERO
times** — phantom dtOrigins spread 20s–120s (peak 40–70s), NOT the ~25s S−P ring. These are
**coda-front ghosts**: playground stations re-trigger for the entire shaking duration, producing a
continuously expanding junk-pick front. The ±6s S window structurally cannot cover a 100s smear;
a coda-wide window would swallow genuine doublets again (their picks sit inside eq1's coda window
at most distances). Established phantoms self-sustain for minutes (C#532: 652 block-samples,
origin drift only ~7s — a solution-stability filter would NOT cleanly kill them, verified from
log). What DID work: 975 straggler newborns merged back at 82–100% strictFit; no release/merge
oscillation; direction guard fired 411×.

**Key reframe:** this storm is upstream's README known issue #2 — "Larger earthquakes (M6+) often
trigger false detections or show duplicated earthquakes" — the same coda-retrigger phenomenon.
Upstream's loose merge was only a partial accidental suppressor; the playground's synthetic
waveform generator (retriggers throughout shaking) reproduces the bug maximally even at M4.x.
We did not create this failure mode; we un-suppressed it. Both README issues share one root:
locally, a coda re-trigger pick is indistinguishable from a new quake's P pick — only ensemble
behaviour over time separates them.

**Master switch added (implemented, tested 35/35):** `multiQuakeMode` in
`.GlobalQuakeData/hypocs.properties` (HypocsSettings), **default 0 = OFF = byte-for-byte upstream
behaviour** (no release, no absorb, loose newborn merge, no origin-time/direction guards, gated
clearSWaves). ON (=1) enables the full experimental A/B/C/D path — playground only for now.
Exile-forgiveness in expandPWaves needs no gate (vacuous when release never runs). `[MQ]` census
logging active in both modes (strip at final).

**Proposed next direction (NOT implemented) — solution-level quarantine ("has its own S-waves"):**
stop fighting at the cluster level; gate EMISSION instead. Newborn clusters near an active quake
solve in quarantine (no UI/alerts/merge-eater rights). Promote only when the candidate develops
its OWN coherent S arrivals (couldBeSArrival vs the candidate's hypocenter — a real quake produces
its own S wavefront; a coda front does not), plus min correctEvents and a survival window; expire
unpromoted quarantine after ~90–120s. Physically principled, reuses existing machinery, costs the
2nd quake ~20–40s of detection latency (acceptable vs phantom storms). Touches EarthquakeAnalysis
emission points — bigger surgery; do only with user buy-in.

---

## Change E: emission quarantine (2026-07-17, research-informed)

Web research sanity check before building (user request):
- Bayesian coda suppression (NET-VISA lineage, Pure&Applied Geophysics 2024): coda ghosts are a
  known hard problem in production global monitoring; their model "virtually eliminated coda
  events" — and they explicitly warn naive coda-pick dropping MISSES REAL EVENTS → no hard vetoes,
  prefer delayed confirmation. https://link.springer.com/article/10.1007/s00024-024-03574-1
- scanloc (gempa/SeisComP commercial associator): DBSCAN cluster search on P, then S association
  "better constrains the event"; eventAssociation.maxTimeSpan=60s, maxDist=500km; teleseismic
  fakes suppressed via zero-weight arrivals (not deletion). https://docs.gempa.de/scanloc/current/
- Modern associators (GaMMA, PyOcto, FastLink) associate P+S JOINTLY so single-phase junk can't
  form events. Upstream GlobalQuake 1.0 (private) also claims improved multi-event detection.

**Change E (implemented):** EMISSION gating at the single earthquake-creation chokepoint
(`EarthquakeAnalysis.updateHypocenter`, the `cluster.getEarthquake()==null` branch; verified the
only place quakes are born). Multi-quake mode only, `!testing` guarded. Logic:
- `isQuarantined()`: candidate is SUSPECT if another active quake exists with
  `dtOrigin ∈ (−30s, quarantineCodaWindowMs=240s)` and dist < `quarantineDistKm=1500`. Not suspect
  → instant creation as always (first-quake latency unchanged).
- Suspect candidates promote only via OWN-S CONFIRMATION: `countOwnSPairConfirmations()` counts
  stations whose cluster-assigned pick strict-P-fits the CANDIDATE hypocenter AND that have a
  second, later pick fitting the candidate's S curve (`ClusterAnalysis.couldBeSArrivalTiming`, new
  public static timing-only helper refactored out of couldBeSArrival). Threshold:
  `max(quarantineMinSPairs=4, ceil(correctEvents × quarantineMinSPairsPct=30 %))`.
- HOLD = return before `new Earthquake(...)`: no object, no QuakeCreateEvent, no UI/alerts/sounds,
  nothing to merge-eat; cluster keeps revising (previousHypocenter still set) and re-asks every
  revision; unpromoted ghosts expire via updateClusters (tooOld/notEnoughEvents). Nothing deleted.
Physics: a real quake sweeps stations with P then S → pairs accumulate; a coda-retrigger front has
no secondary wavefront → pairs only by coincidence, stays under the fraction threshold.
Known residual risks: (a) coincidence S-pairs in dense retrigger fields — the 30% fraction rule is
the defense, first knob to tune; (b) genuine doublet promotion delay ≈ S−P time at confirming
stations (~20–40s); (c) held clusters treated as newborns by canMerge (eq==null) — strict test
keeps them alive, intended (they sequester junk picks).
New tunables: quarantineCodaWindowMs=240000, quarantineDistKm=1500, quarantineMinSPairs=4,
quarantineMinSPairsPct=30. All inert when multiQuakeMode=0.
`multiQuakeMode=1` written to `.GlobalQuakeData/hypocs.properties` for playground testing —
REMEMBER: remove/zero it for real-data runs until proven.
Status: built clean, 35/35 tests. Playground matrix pending: scenario R (control M4.5 — watch
quarantine-HOLD lines during coda, expect ZERO phantom quakes on screen), then doublets (#2/#3 —
expect quarantine-PROMOTE within ~30–60s of the 2nd quake).

### Change E test round 1 + S-evidence rework (2026-07-17)

Playground matrix on quarantine build: #1 control CLEAN (no phantom storm — quarantine firewall
works; 160 HOLDs, zero phantoms emitted). #2 (M4→+30s M4.7) and #3 (same-spot doublet): second
quake NEVER promoted. #4 (M4→M9 immediately): M9 candidate never promoted either — instead the
PARENT M4 absorbed the M9's picks and re-magnituded M4.3→M8.6 ("identity takeover"; user saw
delayed catch + shaking update; origin/ID wrong — roadmap item, dtOrigin<15s ambiguity zone).

Root cause of non-promotion: **sPairs=0 in ALL 160 HOLD lines** — same-station P+S pairing is
structurally impossible: picker holds a station in EVENT ≥7s, so S arrives inside the open P event
at near stations; distinct S picks exist only where S−P > lockout. REWORK (implemented): count
sFit = cluster stations with ANY valid pick riding the candidate's S curve (the S re-trigger picks
that caused the ghost storms ARE the confirmation signal), pFit = strict-P fits; promote when
sFit >= max(quarantineMinSPairs=3, 25% of pFit). Log shows "own-S N (P-fit M, need K)" — next run
measures real-doublet vs ghost separation empirically. If ghost sFit coincidence overlaps real
doublets, next knobs: capable-distance scoping (only stations with S−P>12s in denominator),
require sFit stations at ≥2 distinct distance rings.

Shakemap notes for user Qs: displayed shaking is MODEL-generated from official quake mag/depth/loc
(GeoUtils.pgaFunction), NOT live station amplitudes — heavy station shaking never directly changes
the map or spawns quakes; only detection/magnitude revisions do. Overlap contamination: parent's
magnitude computed from assigned events' amplitudes → a bigger overlapping quake inflates it
(that's the takeover mechanism).

### Round 2 results — the final boss is the PICKER (2026-07-17)

S-evidence build retest: own-S=0 on every eval — but ALL 126 quarantine evals were dtOrigin=0–7s
SIBLINGS of the parent (correctly held; young near-field clusters legitimately have no separable
S picks — the quarantine now works as a clean duplicate suppressor). The same-spot doublet's
second quake (dtOrigin≈30s) NEVER produced a candidate hypocenter. Root cause: STATION PICKER
LOCKOUT — near stations sit in the parent's still-open events (≥7s + slow synthetic coda decay),
so quake2 generates almost no independent picks near-field; no cluster → no candidate → nothing
to promote. The cluster/solver-level machinery cannot detect what the picker never picks. (This
was the ORIGINAL session-1 hypothesis; the merge-annihilation bug simply sat in front of it. Onion
fully peeled: merge bug → ghost storm → coda smear → picker lockout.)

Scoreboard after this iteration (multiQuakeMode=1):
- Phantom storms: ELIMINATED (the critical win; upstream README issue #2 tamed in playground)
- Far-apart / simultaneous quakes: both detected ✓
- Big-on-small same spot (M9 on M4): caught via parent "identity takeover" re-magnituding
  M4→M8.6 — delayed, wrong origin/ID, but functional alert
- Same-spot small doublet (M4 → +30s M4.x): NOT detected — picker-limited, unchanged from upstream
  (their README issue #1). Playground's sustained synthetic coda exaggerates this vs real STA/LTA.

Remaining root fix (OPTIONAL, riskiest tier — BetterAnalysis surgery): allow a station in EVENT
state to emit a NEW pick on a sharp fresh onset (STA spike re-trigger while locked). Everything
downstream (release → newborn survival → quarantine → own-S promotion) is now in place to receive
those picks. Deferred pending user decision; current state is a fair stopping point (matches
upstream's known limitation, minus the storms).

### Change F: mode tiers + picker re-triggering (2026-07-17)

`multiQuakeMode` is now a TIER: 0=exact upstream, 1=cluster/quarantine layer (all of A–E),
2=level 1 + PICKER RE-TRIGGERING (Change F). Strictly additive → regressions bisect by stepping
the number down.

Change F (`BetterAnalysis.nextSample`, EVENT branch): when a station is locked in an open event
and a genuinely new onset arrives — `shortAverage > mediumAverage × retriggerRatioPct(300)/100`
(medium tracks the current decaying shaking level; coda decay can't spike short over medium) —
and the open event is ≥ `retriggerMinGapMs`(10s) old: close the current event at that instant,
open a fresh pick, stay in EVENT state (one-open-event invariant preserved; end-detection restarts
on the new event). This is the root enabler for same-epicenter doublets: the downstream
release→newborn→quarantine→own-S pipeline was already built and waiting for these picks. Logs
`[MQ] picker-RETRIGGER` per fire (INFO, can be spammy at level 2 during big quakes — playground
only for now). Tunables: retriggerMinGapMs=10000, retriggerRatioPct=300.

Also: seedlink parallelism bumped (MAX_CONNECTIONS_PER_NETWORK 6→8, MAX_STATIONS_PER_CONNECTION
64→32) after user confirmed the first fix visibly sped up station attach; CPU headroom plentiful.
hypocs.properties set to multiQuakeMode=2 for the next playground round. Test focus: #1 control
REGRESSION FIRST at level 2 (retrigger must not cause storms — the quarantine should absorb the
extra picks), then same-spot doublet (#3) — the scenario levels 0 and 1 cannot detect.
