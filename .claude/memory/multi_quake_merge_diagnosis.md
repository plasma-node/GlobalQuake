---
name: multi-quake-merge-diagnosis
description: "Multi-quake bug saga — canMerge annihilated newborns (Change C), then Change C alone caused S-as-P ghost-quake storms, fixed by phase-aware Change D (2026-07-16); authoritative detail in .ai/multi-quake-fix-design.md"
metadata: 
  node_type: memory
  type: project
  originSessionId: 79e25f83-3515-42c6-b4f1-2544ac6f2faf
---

Confirmed 2026-07-16 by running the playground with `[MQ]` INFO instrumentation (temporary, in the
working tree — one `logMultiQuakeDebug()` method + `[MQ]`-tagged log lines in `ClusterAnalysis.java`;
strip before final commit) and reading `playground-run.log`.

**CHANGE C ALONE REGRESSED (2026-07-16, later same day):** single-quake control produced a
phantom-quake storm — up to 17 clusters, phantom origins +24–27s (= S−P delay), real quake
repeatedly destroyed ("no earthquake detected" flicker). Cause: stations re-trigger on the S/coda
wavefront ~25s after P; those picks are mutually consistent, so the solver locates "S-as-P ghost"
quakes; the loose merge Change C removed had been the de-facto ghost garbage collector (double
duty: swallowed real doublets AND ghosts). The `markPossibleSWaves` intensity gate
(expectedIntensity < 3.0) is why ghosts weren't already S-marked at an M4.5's regional stations.
**Change D fixed it** (all ClusterAnalysis): (1) `absorbGhostClusters()` after createNewClusters —
dissolves newborns majority-explained by an existing quake under strict-P ∪ S-curve
(intensity-free vote), S-fitting picks setAsSWave(true); runs before EarthquakeAnalysis in the
same tick (GlobalQuakeRuntime:60-61 sequential) so ghosts can't establish; (2) `couldBeSArrival`
gained considerIntensity param — gate kept for global markPossibleSWaves, timing-only for ghost
vote + clearSWaves unmark (else mark/unmark oscillation); (3) expandPWaves exile forgiveness —
rejected pick reclaimable iff strict fit (fixes real-quake starvation from permanent
rejectedByCluster exile); (4) canMerge direction guard — a quake can't absorb a cluster with more
correctEvents than its own (phantom can't eat the real quake; merge direction was iteration-order
luck). Doublet still survives: its picks fit neither strict-P (offset by dtOrigin) nor S-curve
(one thin distance ring, never a majority). Authoritative writeup: `.ai/multi-quake-fix-design.md`.

**CHANGE D ALSO FAILED (2026-07-16 late) — CODA-FRONT GHOSTS + MASTER SWITCH:** third run stormed
again; ghost-DISSOLVE fired ZERO times because phantom dtOrigins spread 20–120s (coda re-triggers
across the whole shaking duration), not the ~25s S ring — the ±6s S window can't cover the smear,
and a coda-wide window would re-swallow real doublets. Established phantoms self-sustain (drift
only ~7s over minutes — stability filters won't kill them). KEY REFRAME: this storm IS upstream's
README known issue #2 ("M6+ false detections/duplicated earthquakes") — same coda-retrigger root;
playground's generator reproduces it maximally even at M4.x. We un-suppressed an existing upstream
bug, didn't create it. RESOLUTION: added `multiQuakeMode` master switch (HypocsSettings,
`.GlobalQuakeData/hypocs.properties`), **default 0 = exact upstream behavior** (safe); =1 enables
the experimental A/B/C/D path (playground only). 35/35 tests. **CHANGE E IMPLEMENTED (2026-07-17): emission quarantine**, user-approved,
research-validated (scanloc S-association, NET-VISA coda suppression "no hard vetoes" lesson,
GaMMA/PyOcto joint P+S). Gate at the single quake-creation chokepoint
(EarthquakeAnalysis.updateHypocenter, eq==null branch): candidate born within (−30s, 240s) and
1500km of an active quake is HELD (no Earthquake object, no QuakeCreateEvent, nothing on screen;
cluster keeps revising) until own-S confirmation: stations with strict-P-fitting pick + later pick
fitting the CANDIDATE's S curve (new public static ClusterAnalysis.couldBeSArrivalTiming), needing
max(4, 30% of correctEvents) pairs. Ghost coda fronts have no secondary S wavefront → never
promote → expire silently. Tunables: quarantineCodaWindowMs/quarantineDistKm/quarantineMinSPairs/
quarantineMinSPairsPct; all inert when multiQuakeMode=0. NOTE: `multiQuakeMode=1` currently
written into `.GlobalQuakeData/hypocs.properties` for playground testing — remove/zero for real
runs. 35/35 tests. **ROUND-1 RESULTS (2026-07-17): control CLEAN (phantom storms GONE — 160 HOLDs, 0
phantoms emitted).** But doublets never promoted: sPairs=0 in ALL holds — same-station P+S pairing
structurally impossible (picker 7s event-lockout swallows S inside the open P event at near
stations). #4 (M4→M9): M9 never promoted; parent M4 absorbed its picks and re-magnituded to M8.6
(identity takeover — works UX-wise for near-simultaneous, wrong origin/ID; dtOrigin<15s ambiguity,
roadmap). REWORKED: sFit = cluster stations with ANY pick riding candidate's S curve (S re-triggers
ARE the signal), promote when sFit ≥ max(3, 25% of pFit); logs "own-S N (P-fit M, need K)" to
measure doublet-vs-ghost separation empirically. Also learned: shakemap display is MODEL-generated
from official mag/depth/loc, never from live station amplitudes; overlapping quake inflates
parent's computed magnitude (assigned-event amplitudes). All uncommitted; retest pending.

**The decisive evidence:** in a two-quake test, `[MQ] createNew` logged up to `newClusters=7` per
cycle (the 2nd quake's picks DO exist, DO form candidate clusters — `bestCorroboration` reached 9),
yet `[MQ] cycle` NEVER showed more than `clusters=1` (335 cycles at 1, never 2). Candidate 2nd
clusters are born and destroyed within the same analysis cycle. Zero `merge-BLOCK` lines = the
origin-time guard never even ran.

**Root cause (revised — this SUPERSEDES the earlier "expandPWaves steals picks" / "station-level
one-event-per-station saturation" hypotheses as the PRIMARY blocker):** `ClusterAnalysis.canMerge`
puts BOTH its distance guard and the added origin-time separation guard inside
`if (cluster.getEarthquake() != null && cluster.getPreviousHypocenter() != null)`. A newborn cluster
from `createNewClusters()` hasn't run the hypocenter solver yet → `getEarthquake() == null` → that
whole guard block is SKIPPED. `canMerge` then falls through to a "could these picks be arrivals of
the existing quake?" test, which quake #1's wide (~10s) arrival window almost always passes, so the
newborn is swallowed into quake #1 every cycle. **The existing fix (`releaseMisfitEvents` +
origin-time guard) guards the wrong direction** — it protects an already-established quake, but the
2nd cluster is killed BEFORE it can establish an earthquake/origin-time.

What DOES work (confirmed from logs): `releaseMisfitEvents()` frees misfit picks correctly (up to 74
released), and `createNewClusters()` forms candidate clusters correctly. The single broken link is
the merge lifecycle.

**FIX IMPLEMENTED (Change C, 2026-07-16, uncommitted):** `canMerge` now judges a newborn
(earthquake-less) cluster with the SAME strict arrival test `releaseMisfitEvents` uses
(`couldBeArrival(event, eq, false, false, false)`, 1800ms window) instead of the loose ±10s test
(which established clusters keep, post-guards). Invariant: release and merge agree on pick
ownership → the release/reform/swallow oscillation is structurally impossible; a distinct quake's
newborn survives until the solver gives it an origin, then the origin-time guard (dtOrigin >
originTimeMergeSeparationMs=15000 → no merge) governs. Same MERGE_THRESHOLD=0.54, no new tunables.
Verified `EarthquakeAnalysis.checkConditions` has NO cross-cluster duplicate suppression (only
compares a cluster to its own previous hypocenter), so canMerge was the sole annihilation point.
Built clean, 35/35 tests pass. NOT yet playground-verified — test matrix in
`.ai/multi-quake-fix-design.md` §7 (regression scenario R + doublet scenarios) pending. Watch-items:
transient phantom clusters (persist ≤2min now vs instantly swallowed), early sibling duplicates
while eq1 ≤7 correctEvents (must converge via established-established merge), S-wave
misclassification ring. `[MQ]` logging stays in until final commit per user.

**Also fixed 2026-07-16 (separate bug, same session):** playground showed no shake zones / intensity
because `GlobalQuakePlayground.main()` never called `ShakeMap.init()` (normal client does it in
`MainFrame`/`ServerSelectionFrame`), so `ShakeMap.h3` was null and shakemap generation NPE'd every
cycle. Added `ShakeMap.init()` to playground main. Uncommitted, built clean.

Related: [[codebase-facts]] (has the earlier/original multi-quake diagnosis — now partly superseded),
[[build-setup]].
