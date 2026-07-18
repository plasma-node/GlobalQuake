# Seedlink connection issue: "Network error: Unparseable date: ..."

## Symptom reported

- Some seedlink networks fail to connect / fail station discovery entirely, especially:
  - `rtserve.iris.washington.edu:18000` ("IRIS DMC")
  - `jamaseis.iris.edu:18000` ("IRIS Jamaseis")
- Error shown is along the lines of "Network error: Unparseable date: ..."
- Some seedlinks also appear to time out.
- This matters because these two servers carry a lot of PNW (Pacific Northwest) station coverage,
  so losing them removes PNW monitoring capability.

## Root cause (confirmed)

`GlobalQuakeCore/src/main/java/globalquake/core/database/SeedlinkCommunicator.java` performs
station/stream discovery by sending the seedlink `INFO STREAMS` command and parsing the returned
XML (`SeedlinkCommunicator.parseAvailability`, lines ~45-92). For each `<stream>` element it reads
the `end_time` attribute and parses it with one of two **hardcoded legacy date formats**, chosen
just by checking whether the string contains a `-`:

```java
FORMAT_UTC_SHORT.set(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
FORMAT_UTC_LONG.set(new SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSSS"));
...
end.setTime(endDate.contains("-") ? FORMAT_UTC_SHORT.get().parse(endDate) : FORMAT_UTC_LONG.get().parse(endDate));
```

This only ever catches `NumberFormatException`:

```java
} catch(NumberFormatException e){
    Logger.warn(...);
}
```

but `SimpleDateFormat.parse()` throws the checked `java.text.ParseException` — whose message is
literally `"Unparseable date: \"" + text + "\""`. That exception is **not caught here**, so it
propagates out of `parseAvailability` → `runAvailabilityCheck` (declared `throws Exception`) → up
to `StationDatabaseManager.runSeedlinkUpdate`, which catches it generically:

```java
try {
    SeedlinkCommunicator.runAvailabilityCheck(seedlinkNetwork, stationDatabase, attempt);
} catch (Exception ce) {
    seedlinkNetwork.setStatus(0, "Network error: " + ce.getMessage());
    return false;
}
```

— producing exactly the "Network error: Unparseable date: ..." status shown in the UI, and
**aborting processing of the entire `INFO STREAMS` response** for that seedlink network the moment
one channel has an unrecognized `end_time` format (not just skipping that one channel — the whole
DOM walk is inside the same try/throws chain, so a single bad timestamp anywhere in a
multi-thousand-station response kills discovery for every station on that server).

### Why IRIS specifically

Empirically confirmed by connecting to both servers directly with the project's own vendored
`seisFile-2.1.0-SNAPSHOT.jar` (`SeedlinkReader.getInfoString(SeedlinkReader.INFO_STREAMS)`):

```
rtserve.iris.washington.edu:18000
  banner: SeedLink v4.0 (RingServer/4.5.4) :: SLPROTO:4.0 SLPROTO:3.1 CAP WS:13
  <stream location="" seedname="HHZ" type="D"
          begin_time="2026-07-09T08:03:02.490000Z"
          end_time="2026-07-09T09:24:18.060000Z"/>

jamaseis.iris.edu:18000
  banner: SeedLink v4.0 (RingServer/4.5.0) :: SLPROTO:4.0 SLPROTO:3.1 CAP WS:13
  <stream location="00" seedname="BHZ" type="D"
          begin_time="2026-07-01T09:22:35.954000Z"
          end_time="2026-07-09T09:24:16.854000Z"/>
```

Both servers now run **RingServer 4.x**, which advertises `SLPROTO:4.0` and formats timestamps per
the SeedLink v4 spec: `%Y-%m-%dT%H:%M:%S.%fZ` (ISO-8601, `T` separator, `Z` suffix, 6-digit
fractional seconds) — per the FDSN SeedLink protocol docs
(https://docs.fdsn.org/projects/seedlink/en/latest/protocol.html). This is neither of
GlobalQuake's two hardcoded legacy formats:

- `yyyy-MM-dd HH:mm:ss` fails because of the `T` separator and fractional seconds/`Z` suffix
- `yyyy/MM/dd HH:mm:ss.SSSS` never even applies here since the string does contain `-`

So *every* stream from these two servers hits `FORMAT_UTC_SHORT`, immediately throws
`ParseException: Unparseable date: "2026-07-09T09:24:18.060000Z"`, and kills discovery.

This is a protocol-version issue, not a flaky-server issue: any seedlink source that has upgraded
to RingServer ≥4.0 / SeedLink protocol v4 will hit this same failure. IRIS's own catalogs happened
to upgrade; other providers (many European SeisComP-based nodes) are apparently still on older
SeedLink v3.x servers using the legacy formats, which is why "some" seedlinks fail and others don't.

## Secondary factor: timeouts

`rtserve.iris.washington.edu`'s `INFO STREAMS` response for the full IRIS DMC catalog is huge — in
the live test it was **6.4 MB of XML** covering the entire global network. `SeedlinkCommunicator`
downloads this fully (`reader.getInfoString(...)`) and then parses it as one in-memory DOM
(`DocumentBuilder.parse`) before touching any of it. Against `SeedlinkNetwork.DEFAULT_TIMEOUT = 20`
seconds (used both as the connect and the socket read timeout) and 3 retry attempts
(`StationDatabaseManager.ATTEMPTS`), a large transfer over a slow/congested link can plausibly
exceed the budget, especially since GlobalQuake's docs/help page ("Getting GlobalQuake running")
already independently note timing/sync sensitivity for seedlink connections. This is likely
contributing to the reported timeouts on the largest catalogs, separately from the date-parsing
crash.

## Recommended fix (not yet implemented — documentation only per current scope)

1. **Catch the exception that's actually thrown.** Change the `catch(NumberFormatException e)`
   around the date parse to also catch `java.text.ParseException` (or catch `Exception` narrowly
   around just the parse call), so one bad/unexpected timestamp degrades gracefully to
   `SeedlinkCommunicator.UNKNOWN_DELAY` for that single channel instead of aborting the whole
   network's discovery. The `UNKNOWN_DELAY` sentinel and the `continue`-based flow already assume
   this is possible — the bug is that the wrong exception type is caught.
2. **Support ISO-8601 timestamps.** Add a third format/parse path for the SeedLink v4
   `%Y-%m-%dT%H:%M:%S.%fZ` style (e.g. `DateTimeFormatter.ISO_INSTANT`, tolerant of the variable
   fractional-second width RingServer emits), tried before/alongside the two legacy
   `SimpleDateFormat` patterns. Since this project targets Java 17, this would be a good place to
   move off `SimpleDateFormat`/`Calendar` entirely to `java.time` (`Instant`/`DateTimeFormatter`),
   which is thread-safe (removing the need for the current `ThreadLocal<SimpleDateFormat>`
   workaround) and gives clearer parse-failure exceptions.
3. Optionally: stream/parse the `INFO STREAMS` response incrementally (SAX rather than full DOM)
   for very large catalogs like IRIS DMC, and/or bump the timeout or make it configurable per
   seedlink network, to reduce the chance of large-catalog timeouts independent of the date bug.

Fixing item 1+2 alone should restore both `rtserve.iris.washington.edu` and `jamaseis.iris.edu` (and
any other RingServer-v4 source) to working station discovery, which directly restores PNW coverage.
