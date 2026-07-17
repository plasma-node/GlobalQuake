---
name: seedlink-date-parsing-bug
description: FIXED (2026-07-09) — IRIS/RingServer-4.x seedlink servers were failing station discovery entirely because ISO-8601 timestamps weren't parsed; now handled via java.time
metadata: 
  node_type: memory
  type: project
  originSessionId: 79e25f83-3515-42c6-b4f1-2544ac6f2faf
---

**FIXED 2026-07-09** in `SeedlinkCommunicator.java`: replaced the `ThreadLocal<SimpleDateFormat>`
pair with immutable `java.time` `DateTimeFormatter`s, added a `parseEndTime()` helper that handles
the SeedLink v4 ISO-8601 form (`endDate.indexOf('T') >= 0` → `Instant.parse`) alongside the two
legacy formats, and widened the catch from `NumberFormatException` to `Exception` so one bad
timestamp degrades that single channel to `UNKNOWN_DELAY` instead of aborting the whole network.
Compiled clean (mvn -pl GlobalQuakeCore) and unit-verified all four formats + garbage-throws.
NOTE: this does NOT fix seedlink "timed out" or "connection refused (getsockopt)" statuses — those
are unreachable/dead servers, a separate connectivity concern; nor the FDSN station-source "ERROR!"
statuses (separate HTTP FDSNWS metadata subsystem).

Original diagnosis (kept for reference): `SeedlinkCommunicator.java`
(`GlobalQuakeCore/src/main/java/globalquake/core/database/`) parses seedlink `INFO STREAMS`
`end_time` values with two hardcoded legacy `SimpleDateFormat` patterns, chosen by checking for a
`-` in the string. It only catches `NumberFormatException`, but `SimpleDateFormat.parse()` actually
throws `java.text.ParseException` — uncaught, it propagates up and aborts discovery for the
**entire** seedlink network on the first unparseable timestamp, not just the one bad channel.

**Why this matters:** Servers running RingServer ≥4.0 / SeedLink protocol v4 (confirmed on
`rtserve.iris.washington.edu:18000` and `jamaseis.iris.edu:18000`) emit ISO-8601 timestamps
(`2026-07-09T09:24:18.060000Z`) that match neither legacy format, so these IRIS servers — which
carry a lot of Pacific Northwest station coverage — fail station discovery completely, killing PNW
monitoring. Any other RingServer-v4 source would hit the same failure.

**How to apply:** Recommended fix (still just documented, not implemented):
1. Catch `ParseException` (or `Exception`) around the date parse, not just `NumberFormatException`,
   so one bad timestamp degrades to `SeedlinkCommunicator.UNKNOWN_DELAY` for that channel instead of
   killing the whole network's discovery.
2. Add ISO-8601 parsing (e.g. `DateTimeFormatter.ISO_INSTANT`) alongside the two legacy formats —
   good opportunity to migrate off `SimpleDateFormat`/`ThreadLocal` to `java.time` entirely.
3. Optionally: SAX-parse the `INFO STREAMS` response instead of full DOM for huge catalogs (IRIS DMC
   response was 6.4MB in testing) to reduce timeout risk against the 20s/3-retry budget.

If asked to fix the seedlink connectivity issue, this is the fix — don't re-diagnose from scratch.
Full writeup: `.ai/seedlink-connection-issue.md` in the repo (untracked, see [[untracked-ai-notes]]).
Related: [[project-overview]].
