# GlobalQuakeServer FDSNWS-event HTTP API — practical usage guide

Written 2026-07-09. Everything in this document was verified by reading the actual current source
in this repo (not upstream docs, not memory) and by actually building and running
`GlobalQuakeServer` and hitting its HTTP API with real `curl` requests. Anything labeled "captured
live" below is a real, unedited response from a real running instance on this machine, built with
JDK 21 + Maven 3.9.16 (`GlobalQuakeServer-0.11.0_pre-2-jar-with-dependencies.jar`). Anything labeled
"executed against real code" was produced by directly invoking the real response-building methods
(`EarthquakeDataExport` / `ArchivedQuake`) on manually-constructed `ArchivedQuake` objects, bypassing
the need for a real seismic network — this was necessary to show non-empty output, since a test run
with no real seedlink stations connected produces zero detections.

Audience: you want to write an external script (cron job, systemd timer, etc.) that polls this API
and alerts you when there's a nearby earthquake. This doc is a practical reference, not a spec dump.

---

## 1. What this is

`GlobalQuakeServer` embeds a small HTTP server that implements (a useful subset of) **FDSNWS-event**
— the same catalog-query standard used by real seismological data centers like IRIS/EarthScope,
USGS, EMSC, etc. If you've ever hit `https://earthquake.usgs.gov/fdsnws/event/1/query`, this is the
same URL shape and the same query-parameter vocabulary, just served locally by your own GlobalQuake
instance instead of a public data center. It reports **GlobalQuake's own local detections** —
whatever your instance has itself calculated from your seedlink station feeds — not a mirror of
USGS/EMSC data.

Source files (all read in full for this doc):
- `GlobalQuakeServer/src/main/java/gqserver/fdsnws_event/FdsnwsEventsHTTPServer.java` — server bootstrap
- `GlobalQuakeServer/src/main/java/gqserver/fdsnws_event/EventsV1Handler.java` — request handling, filtering, format switch
- `GlobalQuakeServer/src/main/java/gqserver/fdsnws_event/EventsV1ParamChecks.java` — parameter validation
- `GlobalQuakeCore/src/main/java/globalquake/core/earthquake/EarthquakeDataExport.java` — response body builders
- `GlobalQuakeCore/src/main/java/globalquake/core/archive/ArchivedQuake.java` — the underlying event object and its serializers
- `GlobalQuakeServer/src/main/java/gqserver/main/Main.java` — headless bootstrap
- `GlobalQuakeCore/src/main/java/globalquake/core/Settings.java` — configurable defaults
- `GlobalQuakeServer/src/main/resources/fdsnws_event_application.wadl` — advertised parameter list

---

## 2. How to enable / start it

**It is disabled by default.** In `Settings.java`:

```
loadProperty("FDSNWSEventIP", "localhost");         // localhost only, by design, for security
loadProperty("FDSNWSEventPort", "8080");
loadProperty("autoStartFDSNWSEventServer", "false"); // OFF by default
```

To turn it on, set `autoStartFDSNWSEventServer=true` (and optionally change the IP/port) in
`<data-dir>/globalQuake.properties` (the properties file lives next to wherever GlobalQuake stores
its data — for `GlobalQuakeServer` run from a given working directory, that's
`./.GlobalQuakeServerData/globalQuake.properties`, per `Main.MAIN_FOLDER`). There is currently no
in-app UI toggle exposed in headless mode — you edit the properties file directly (or set it before
first launch so it's picked up on first run; unset properties are written with their defaults, and
this key is later read from file if already present).

Once enabled, headless startup wires it up automatically. In `Main.initAll()`:

```java
updateProgressBar("Starting FDSNWS_Event Server...", ...);
if(Settings.autoStartFDSNWSEventServer){
    try {
        FdsnwsEventsHTTPServer.getInstance().startServer();
    }catch (Exception e){
        getErrorHandler().handleWarning(new RuntimeException("Unable to start FDSNWS EVENT server! Check logs for more info.", e));
    }
}
```

This runs early in the init sequence (before station downloads finish), so the API is reachable well
before the server is "fully ready" — confirmed live: the log line `fdsnws_event Server started on
localhost:8080` appeared while station network downloads were still in progress.

**Real launch command used for this doc** (from repo root, after `mvn -q -pl GlobalQuakeServer -am
package -DskipTests`):

```
java -jar GlobalQuakeServer/target/GlobalQuakeServer-0.11.0_pre-2-jar-with-dependencies.jar --headless
```

`--headless`/`-h` is the only flag that matters for this; `Main.java` also accepts `-c`/`--clients`
(max client count for the separate binary client/server protocol — unrelated to this HTTP API) and
`-g`/`--gpu-max-mem`. No flag exists to set the FDSNWS host/port/enabled state from the command line
— it's properties-file only.

**Real captured log excerpt** (2026-07-09):
```
[16:25:21] INFO: Initialising... 50%: Starting FDSNWS_Event Server...
[16:25:21] INFO: fdsnws_event Server started on localhost:8080
```

**Port**: `8080` by default, configurable via `FDSNWSEventPort`. **Bind address**: `localhost` by
default (not `0.0.0.0`), configurable via `FDSNWSEventIP` — deliberately chosen "for security" per an
inline comment in `Settings.java`. See the security section below before changing this.

---

## 3. Endpoints

Two routes are registered (`FdsnwsEventsHTTPServer.initRoutes()`):

| Path | Purpose |
|---|---|
| `/fdsnws/event/1/query` | The actual event query endpoint |
| `/fdsnws/event/1/application.wadl` | Serves a static WADL file describing (a subset of) supported parameters |

Everything else falls through to a catch-all logger (`HttpCatchAllLogger`) — there's no other API
surface here (no station endpoint, no `/version`, no health check).

`application.wadl`, captured live:

```xml
<?xml version="1.0"?>
<application xmlns="http://wadl.dev.java.net/2009/02" xmlns:q="http://quakeml.org/xmlns/quakeml/1.2" xmlns:xs="http://www.w3.org/2001/XMLSchema">
  <resources base="fdsnws/event/1/">
    <resource path="application.wadl">
      <method id="application.wadl" name="GET">
      <response status="200">
        <representation mediaType="application/xml"/>
      </response>
      </method>
    </resource>
    <resource path="query">
      <method id="query" name="GET">
      <request>
        <param name="starttime" style="query" type="xs:dateTime"/>
        <param name="endtime" style="query" type="xs:dateTime"/>
        <param name="minlatitude" style="query" type="xs:double" default="-90"/>
        <param name="maxlatitude" style="query" type="xs:double" default="90"/>
        <param name="minlongitude" style="query" type="xs:double" default="-180"/>
        <param name="maxlongitude" style="query" type="xs:double" default="180"/>
        <param name="mindepth" style="query" type="xs:double"/>
        <param name="maxdepth" style="query" type="xs:double"/>
        <param name="minmagnitude" style="query" type="xs:double"/>
        <param name="maxmagnitude" style="query" type="xs:double"/>
        <!-- extensions -->
        <param name="format" style="query" default="quakeml">
          <option value="xml" mediaType="application/xml"/>
          <option value="text" mediaType="text/csv"/>
          <option value="geojson" mediaType="application/json"/>
        </param>
        </request>
      <response status="200">
        <representation mediaType="application/xml" element="q:quakeml"/>
        <representation mediaType="text/csv"/>
        <representation mediaType="text/javascript"/>
        <representation mediaType="application/json"/>
      </response>
      </method>
    </resource>
  </resources>
</application>
```

Note the WADL is *honest* here — it only lists params that are actually wired up (it doesn't
advertise `lat`/`eventid`/etc.), which matches the code. Two small WADL inaccuracies worth knowing:
it claims `format` defaults to `"quakeml"` — the code's actual default is `"xml"` (confirmed: an
unqualified request comes back with `Content-type: application/xml`). Also, passing
`format=quakeml` literally (a value the WADL and `EventsV1ParamChecks.parseFormat` both accept as
"valid") triggers a real bug — see §10.

---

## 4. Full parameter reference

The request parser (`EventsV1Handler.FdsnwsEventsRequest`) declares fields for the full FDSNWS-event
parameter set, but a large fraction are **parsed into local variables and then simply never read** by
the actual filter (`EventsV1Handler.filterEventDataWithRequest`). The class even contains a
commented-out `notImplementedParameters` set that lists exactly this. I independently verified this
against the live `filterEventDataWithRequest` method: it only reads `starttime`, `endtime`,
`minlatitude`, `maxlatitude`, `minlongitude`, `maxlongitude`, `mindepth`, `maxdepth`,
`minmagnitude`, `maxmagnitude` — nothing else.

| Param | Aliases | Meaning | Default | Status |
|---|---|---|---|---|
| `starttime` | `start` | Only events with origin time ≥ this | now − 1 hour | **Works** |
| `endtime` | `end` | Only events with origin time ≤ this | now | **Works** |
| `minlatitude` | `minlat` | Min latitude (degrees) | -90 | **Works** |
| `maxlatitude` | `maxlat` | Max latitude (degrees) | 90 | **Works** |
| `minlongitude` | `minlon` | Min longitude (degrees) | -180 | **Works** |
| `maxlongitude` | `maxlon` | Max longitude (degrees) | 180 | **Works** |
| `mindepth` | — | Min depth (km) | -6371 | **Works** |
| `maxdepth` | — | Max depth (km) | 6371 | **Works** |
| `minmagnitude` | `minmag` | Min magnitude | -10 | **Works** |
| `maxmagnitude` | `maxmag` | Max magnitude | 10 | **Works** |
| `latitude` | `lat` | Center point for radius search | — | **Parsed and validated (400 if invalid), then NEVER used to filter.** No-op. |
| `longitude` | `lon` | Center point for radius search | — | **Parsed and validated, then never used.** No-op. |
| `minradius` | — | Min degrees from lat/lon | — | **Not even parsed** (field exists, never read from the query string at all). Complete no-op. |
| `maxradius` | — | Max degrees from lat/lon | — | **Not even parsed.** Complete no-op. |
| `magnitudetype` | `magtype` | Which magnitude type to test min/max against | — | **Field declared, never parsed, never used.** No-op (all magnitudes are treated as one undifferentiated number; output is always labeled `gqm`, see §5). |
| `eventtype` | — | Filter by QuakeML event type | — | **Declared, never parsed/used.** No-op (GlobalQuake only reports earthquakes anyway; `evtype` in GeoJSON output is hardcoded to `"earthquake"`). |
| `includeallorigins` | — | Include all origins vs. preferred only | — | **Declared, never parsed/used.** No-op — there's only ever one origin per event in this implementation. |
| `includeallmagnitudes` | — | Include all magnitudes vs. preferred only | — | **Declared, never parsed/used.** No-op, same reason. |
| `includearrivals` | — | Include phase arrivals | — | **Declared, never parsed/used.** No-op. (Arrival/station data does exist internally on `ArchivedQuake.archivedEvents` but is never serialized by any output format.) |
| `eventid` | — | Fetch one specific event by ID | — | **Declared, never parsed/used.** No-op — there is no way to query a single event by ID; you must always fetch a time/location/magnitude range and filter client-side. |
| `limit` | — | Max number of results | — | **Declared, never parsed/used.** No-op — the server returns *everything* matching the other filters, unbounded. |
| `offset` | — | Pagination offset | — | **Declared, never parsed/used.** No-op — no pagination support at all. |
| `orderby` | — | Sort order (`time`, `time-asc`, `magnitude`, `magnitude-asc`) | — | **Declared, never parsed/used.** No-op. Practical ordering you'll actually observe: archived quakes come back sorted newest-origin-first (an internal invariant of `EarthquakeArchive`, not something the API guarantees), but any currently-in-progress ("live") quakes are appended to the end of that list *without* being merged into the sort — so a live quake can appear after older archived ones in the response. Don't rely on response order; sort client-side by the `time`/origin field if you care. |
| `catalog` | — | Filter by catalog name | — | **Declared, never parsed/used.** No-op — there's only one catalog (`"GlobalQuake"`, hardcoded in every output format). |
| `contributor` | — | Filter by contributor | — | **Declared, never parsed/used.** No-op — hardcoded to `"GlobalQuake"` everywhere. |
| `updatedafter` | — | Only events updated after this time | — | **Declared, never parsed/used.** No-op — there's no way to poll "just what changed"; you must diff full result sets yourself (see §8). |
| `format` | — | `xml` (default) \| `json` \| `geojson` \| `text` | `xml` | **Works.** (`quakeml` is also accepted by the validator but crashes the handler — see §10 — don't use it; use `xml` instead, which produces the same QuakeML-flavored XML body.) |
| `nodata` | — | HTTP status to return when there are zero matching events | `204` | **Works.** Must be an integer 1–999 or you get a 400. |

**Bottom line for your polling script:** the only filters you can actually rely on server-side are
time range, a lat/lon **bounding box**, depth range, and magnitude range. True radius/point-distance
filtering, single-event lookup by ID, result limiting/pagination, sort order, and "give me only what
changed" are all either silently ignored or literally not implemented. Build your own bounding box
(§8) and do your own dedup/change-detection (§8) client-side.

---

## 5. Response formats

Set with `format=xml|json|geojson|text`. `json` and `geojson` are handled identically (same code
path in `EventsV1Handler`, both call `EarthquakeDataExport.getGeoJSON(...)`). `xml` and the
(broken) `quakeml` both are meant to produce a QuakeML-ish XML body via
`EarthquakeDataExport.getQuakeMl(...)`, but only `xml` actually reaches that code path without
crashing.

### GeoJSON (`format=geojson` or `format=json`)

Built field-by-field in `ArchivedQuake.getGeoJSON()`. Exact keys, **verified by executing the real
method** against manually-constructed `ArchivedQuake` objects (constructed 2026-07-09; not from a
real seismic network — labeled clearly since this test run had zero real detections):

```json
{
  "features": [
    {
      "geometry": {
        "coordinates": [
          -122.3456,
          47.8123,
          -52.4
        ],
        "type": "Point"
      },
      "id": "11111111-2222-3333-4444-555555555555",
      "type": "Feature",
      "properties": {
        "magtype": "gqm",
        "unid": "11111111-2222-3333-4444-555555555555",
        "evtype": "earthquake",
        "auth": "GlobalQuake",
        "lon": -122.3456,
        "mag": 4.7,
        "depth": 52.4,
        "source_catalog": "GlobalQuake",
        "flynn_region": "6 km W of Quilcene, Washington",
        "lastupdate": 1751000045000,
        "time": "2025-06-27T04:53:20.000Z",
        "source_id": "GlobalQuake_11111111-2222-3333-4444-555555555555",
        "lat": 47.8123
      }
    }
  ],
  "type": "FeatureCollection"
}
```

Field notes:
- `id` (top-level, GeoJSON `Feature.id`) and `properties.unid` — **both the event's UUID**, identical value, just present in two places.
- `properties.source_id` — `"GlobalQuake_" + uuid` (a third, string-prefixed representation of the same ID).
- `geometry.coordinates` — `[lon, lat, elevation]` per GeoJSON convention; elevation is **negative depth in km** (so a 52.4 km deep quake shows as `-52.4`), rounded to 3 decimal places.
- `properties.depth` — depth in km, positive, rounded to 3 decimal places (independent copy of the same info as coordinates[2], just positive and separately rounded).
- `properties.mag` — magnitude rounded to 1 decimal place.
- `properties.magtype` — **always the literal string `"gqm"`** ("GlobalQuake magnitude"), regardless of anything; there is no real magnitude-type differentiation.
- `properties.evtype` — always `"earthquake"` (hardcoded, marked `TODO` in source for future event types).
- `properties.auth`, `properties.source_catalog` — always `"GlobalQuake"` (hardcoded, marked `TODO` "allow user to set this").
- `properties.flynn_region` — human-readable region/place name (e.g. `"6 km W of Quilcene, Washington"`), from GlobalQuake's own region-name resolver.
- `properties.time` — origin time, UTC, format `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`.
- `properties.lastupdate` — epoch **milliseconds** (not seconds, not ISO) of the last update to this event.
- No shaking/intensity/PGA field anywhere in this output — see §6.

### Text/CSV (`format=text`)

Built in `ArchivedQuake.getFdsnText()` / `EarthquakeDataExport.getText()`. Captured live (empty-data
header row only, since the test run had zero detections) and reproduced with populated rows via the
same real code:

Header (always present, even with zero rows — real live-captured 204 responses have this as their
*would-be* header per the source, though the actual 204 response for this API sends an empty body,
see §9):
```
#EventID|Time|Latitude|Longitude|Depth/km|Author|Catalog|Contributor|ContributorID|MagType|Magnitude|MagAuthor|EventLocationName
```

Populated example row (executed against real code):
```
GlobalQuake_11111111-2222-3333-4444-555555555555|2025-06-27T04:53:20.000Z|47.8123|-122.3456|52.4|GlobalQuake|GlobalQuake|GlobalQuake|GlobalQuake_11111111-2222-3333-4444-555555555555|gqm|4.7|GlobalQuake|6 km W of Quilcene, Washington
```

Pipe-delimited, one line per event, no CSV quoting/escaping logic at all (a region name containing
`|` would break parsing — not something you're likely to hit, but there's no defensive escaping).
`EventID` here is `"GlobalQuake_" + uuid` (string form only, no separate raw-UUID column in this
format).

### XML / "QuakeML" (`format=xml`)

Built in `ArchivedQuake.getQuakeML()` / `EarthquakeDataExport.getQuakeMl()`. This is a **minimal,
non-conformant subset** of real QuakeML — it only emits `description`, `origin` (time/lat/lon/depth),
and `magnitude`/`mag`. No `creationInfo`, no proper `eventParameters` attributes beyond the bare
element, no arrivals, no picks, no uncertainty/quality blocks. Captured live (empty database):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<q:quakeml xmlns="http://quakeml.org/xmlns/bed/1.2" xmlns:q="http://quakeml.org/xmlns/quakeml/1.2">
<eventParameters>
</eventParameters>
</q:quakeml>
```

Populated example (executed against real code):
```xml
<event publicID="quakeml:GlobalQuake:11111111-2222-3333-4444-555555555555"><description><type>Flinn-Engdahl region</type><text>6 km W of Quilcene, Washington</text></description><origin><time><value>2025-06-27T04:53:20.000Z</value></time><latitude><value>47.8123</value></latitude><longitude><value>-122.3456</value></longitude><depth><value>52.4</value></depth></origin><magnitude><mag><value>4.7</value></mag></magnitude></event>
```

The event's ID here only appears embedded in the `publicID` attribute as
`quakeml:GlobalQuake:<uuid>` — there's no standalone `<eventID>` element you can pluck out with a
naive tag-name XML query; you'll want a real XML parser or a regex on `publicID="quakeml:GlobalQuake:([^"]+)"`.

**Recommendation:** unless you have a specific reason to want XML, use `format=geojson` — it's by
far the richest and easiest-to-parse format (has the region name, the update timestamp, and three
different representations of the ID, none of which XML or text give you all of).

---

## 6. Does it return an earthquake ID?

**Yes.** Every event has a stable UUID (`ArchivedQuake.uuid`, a `java.util.UUID`, assigned once at
detection time and stable through the quake's whole lifecycle — including as it's revised from a
"live"/in-progress `Earthquake` into an archived `ArchivedQuake`, since `ArchivedQuake(Earthquake)`
just copies `earthquake.getUuid()`).

Where to find it per format:
- **GeoJSON**: `id` (top level) and `properties.unid` — the raw UUID string. Also `properties.source_id` = `"GlobalQuake_" + uuid`.
- **Text**: the `EventID` column = `"GlobalQuake_" + uuid` (string-prefixed only, no bare-UUID column).
- **XML**: embedded inside `<event publicID="quakeml:GlobalQuake:<uuid>">` — you have to extract it from the attribute.

Use the GeoJSON `id`/`unid` field as your canonical dedup key — it's the cleanest, most direct
representation.

---

## 7. Can it tell me if there's shaking / strong shaking near my location?

**No — the API itself returns none of this.** No response format (GeoJSON, text, or XML) includes
any PGA, intensity, MMI/Shindo level, or shakemap reference. I confirmed this by reading every field
written in `ArchivedQuake.getGeoJSON()`, `getFdsnText()`, and `getQuakeML()` — none of them touch
`maxPGA`. I also confirmed this empirically: `ArchivedQuake` does compute a `maxPGA` field internally
(assigned asynchronously by a background executor at construction time via
`calculatePGA()` → `GeoUtils.getMaxPGA(lat, lon, depth, mag)`), but it is **never serialized** by any
of the three output-building methods. `ArchivedQuake.getMaxPGA()` exists as a Java getter but nothing
in the FDSNWS code path calls it. (A test run confirmed the field exists and is queryable in-process,
but again — not exposed over HTTP.)

So: **you must replicate the shaking estimate yourself** from `lat`/`lon`/`depth`/`mag`, which the
API does give you. Here's the real formula GlobalQuake itself uses, straight from the source
(this is the same computation the desktop client uses for its own home-location "felt"/"felt
strong" sound alerts, in `GlobalQuakeClient/.../sounds/SoundsService.java`, and the same one
`ArchivedQuake` uses internally for its own never-exposed `maxPGA`):

**Step 1 — hypocentral distance.** `GeoUtils.geologicalDistance(quakeLat, quakeLon, -depthKm, yourLat, yourLon, 0.0)` — a 3D chord distance (km) between the hypocenter and your location, accounting for the quake's depth (treats the epicenter as being `depthKm` below the sphere's surface and your location as being at the surface). A close, much simpler approximation if you don't want to reimplement the exact spherical-chord math: `sqrt(greatCircleDistanceKm^2 + depthKm^2)`.

**Step 2 — depth correction.** From `EarthquakeAnalysis.getDepthCorrection(depth)`:
```
depthCorrection = log10(depth + 160.0) - log10(160.0)
```

**Step 3 — PGA estimate.** From `GeoUtils.pgaFunction(mag, distKm, depth)`:
```
adjMag  = mag + 0.4 * depthCorrection
adjDist = distKm / (1.0 + 0.75 * depthCorrection)
pga     = 10^(adjMag * 0.575) / (0.36 * adjDist^(1.25 + adjMag/22.0) + 10)
```
(`pga` comes out in **gal**, i.e. cm/s².)

**Step 4 — compare against an intensity scale threshold.** GlobalQuake's built-in MMI thresholds
(`MMIIntensityScale.java`, PGA in gal at which each level *starts*):

| MMI | PGA (gal) | | MMI | PGA (gal) |
|---|---|---|---|---|
| I | 0.5 | | VII | 60.0 |
| II | 1.0 | | VIII | 140.0 |
| III | 2.1 | | IX | 321.8 |
| IV | 5.0 | | X | 740.0 |
| V | 11.0 | | XI | 1702.0 |
| VI | 26.0 | | XII | 3000.0 |

The desktop client's default "felt" sound threshold is MMI level index 0 (level **I**, i.e. ≥0.5 gal
— extremely sensitive/"any perceptible motion at all"), and its default "strong shaking" threshold is
level index 5 (level **VI**, ≥26 gal) — these are the `shakingLevelIndex`/`strongShakingLevelIndex`
defaults in `Settings.java`, but they're just UI preferences; you can pick your own thresholds for
your alerting script (e.g. "notify only if estimated PGA ≥ MMI IV, ~5 gal, at my home coordinates").

**Worked example:** M6.0 at 10 km depth, 100 km away.
```
depthCorrection = log10(170) - log10(160) ≈ 0.0263
adjMag  = 6.0 + 0.4*0.0263 ≈ 6.011
adjDist = 100 / (1 + 0.75*0.0263) ≈ 98.07 km
pga     = 10^(6.011*0.575) / (0.36 * 98.07^(1.25 + 6.011/22) + 10)
        = 10^3.456 / (0.36 * 98.07^1.523 + 10)
        ≈ 2858 / (0.36*1083 + 10)
        ≈ 2858 / 400
        ≈ 7.1 gal
```
7.1 gal falls between MMI IV (5.0) and MMI V (11.0) → **estimated MMI IV** ("light shaking, felt by
most people indoors").

This is an estimate GlobalQuake itself uses for its own alert sounds, not a scientifically
authoritative shakemap — treat it as a reasonable heuristic, same caveat that applies to the
in-app feature it's copied from.

---

## 8. Will polling late still catch it? (archived vs. live data)

**Yes.** `EarthquakeDataExport.getArchivedAndLiveEvents()` explicitly unions two sources:

```java
List<ArchivedQuake> archivedQuakes = new ArrayList<>(GlobalQuake.instance.getArchive().getArchivedQuakes());
List<Earthquake> currentEarthquakes = GlobalQuake.instance.getEarthquakeAnalysis().getEarthquakes().stream().toList();
// ...append any current quake not already in the archived list...
```

So the query endpoint always searches **archived (finalized) history** *plus* **currently-in-progress
detections** — not just a rolling live buffer. A quake that finished being detected 10 minutes ago is
in the permanent archive by the time you poll; the API will find it as long as your `starttime`
covers it. Polling 30 seconds late, or even much later, is fine — it isn't a "you had to be watching
at the exact moment" stream.

The practical thing to get right is the **time window**, not the timing of your poll:
- Default window if you don't pass `starttime`/`endtime` is **the last 1 hour up to now**
  (`initDefaultParameters()`: `starttime = now - 3600s`, `endtime = now`).
- If your cron job runs every 5 minutes, don't rely on the 1-hour default — explicitly pass
  `starttime`/`endtime` covering (at least) since your last successful poll, with some overlap
  margin, and dedup by ID (§9) to avoid double-alerting on events you already reported.
- Because there is no `updatedafter` support (no-op, §4), you cannot ask "just what changed since
  last time" — you always get the full set matching your filters and must diff it yourself.

---

## 9. Practical polling recipe

### Building a bounding box from center + radius (since true radius filtering is broken)

Since `lat`/`lon`/`minradius`/`maxradius` are parsed-but-ignored, do the radius→box conversion
yourself. Simple equirectangular approximation (fine for tens–hundreds of km):

```
deltaLat = radius_km / 111.32
deltaLon = radius_km / (111.32 * cos(radians(center_lat)))

minlat = center_lat - deltaLat
maxlat = center_lat + deltaLat
minlon = center_lon - deltaLon
maxlon = center_lon + deltaLon
```

This gives you a box that circumscribes your radius (a bit larger than the true circle near the
corners) — good enough for "did anything happen near me", especially since you'll want to
recompute a real geodesic distance for the actual alert decision anyway (§7).

### curl one-liner

```bash
curl -s "http://localhost:8080/fdsnws/event/1/query?starttime=2026-07-09T00:00:00&endtime=2026-07-09T23:59:59&minlatitude=46.5&maxlatitude=49.5&minlongitude=-124.0&maxlongitude=-120.0&minmagnitude=3.0&format=geojson"
```

Remember: `starttime`/`endtime` are UTC and must be `YYYY-MM-DDTHH:MM:SS` (no timezone suffix, no
fractional seconds — `EventsV1ParamChecks.parseDate` uses a strict `SimpleDateFormat` with that exact
pattern; anything else, including a trailing `Z`, fails with a 400).

### Python polling script

```python
import json
import math
import time
import urllib.request
import urllib.parse
from datetime import datetime, timedelta, timezone

SERVER = "http://localhost:8080"
HOME_LAT, HOME_LON = 47.6062, -122.3321   # Seattle, as an example
RADIUS_KM = 300
MIN_MAG = 3.0
STATE_FILE = "seen_quake_ids.txt"

def bbox(lat, lon, radius_km):
    dlat = radius_km / 111.32
    dlon = radius_km / (111.32 * math.cos(math.radians(lat)))
    return lat - dlat, lat + dlat, lon - dlon, lon + dlon

def fdsn_time(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%S")

def poll(since_minutes=10):
    minlat, maxlat, minlon, maxlon = bbox(HOME_LAT, HOME_LON, RADIUS_KM)
    now = datetime.now(timezone.utc)
    start = now - timedelta(minutes=since_minutes)
    params = {
        "starttime": fdsn_time(start),
        "endtime": fdsn_time(now),
        "minlatitude": minlat, "maxlatitude": maxlat,
        "minlongitude": minlon, "maxlongitude": maxlon,
        "minmagnitude": MIN_MAG,
        "format": "geojson",
    }
    url = f"{SERVER}/fdsnws/event/1/query?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            if resp.status == 204:
                return []
            body = resp.read().decode("utf-8")
            return json.loads(body)["features"]
    except urllib.error.HTTPError as e:
        if e.code == 204:
            return []
        raise

def load_seen():
    try:
        with open(STATE_FILE) as f:
            return set(line.strip() for line in f)
    except FileNotFoundError:
        return set()

def save_seen(ids):
    with open(STATE_FILE, "w") as f:
        f.write("\n".join(sorted(ids)))

def dedup_key(feature):
    # Prefer the real ID. Fall back to a composite key only if you ever
    # hit a response shape without one (shouldn't happen with geojson).
    props = feature.get("properties", {})
    return props.get("unid") or feature.get("id") or \
        f"{props.get('time')}|{props.get('lat')}|{props.get('lon')}|{props.get('mag')}"

def main():
    seen = load_seen()
    features = poll(since_minutes=10)
    new_ids = set(seen)
    for f in features:
        key = dedup_key(f)
        if key in seen:
            continue
        p = f["properties"]
        print(f"NEW EVENT: M{p['mag']} {p['flynn_region']} at {p['time']} (id={key})")
        # ... send your notification here (pushover/ntfy/email/etc.) ...
        new_ids.add(key)
    save_seen(new_ids)

if __name__ == "__main__":
    main()
```

Run this from cron every N minutes with `since_minutes` set comfortably larger than your cron
interval (e.g. 3x it) so a slow poll or a missed run doesn't create a gap, then rely on the ID-based
dedup file to avoid re-alerting on events you've already reported.

---

## 10. Security / exposure note

**There is no authentication of any kind** on this API — no API key, no basic auth, no token, no
IP allowlist beyond the bind address itself (confirmed: nothing in `gqserver.fdsnws_event.*`
references `Authorization`, tokens, or passwords). Every response also sets
`Access-Control-Allow-Origin: *` (confirmed live in every captured response header above), meaning
any web page in any browser tab, from anywhere, can fetch it cross-origin with no restriction — the
only thing currently protecting you is the `localhost`-only default bind address.

**Do not port-forward this to the public internet as-is.** If you need remote access (e.g. your
alerting box is a home server, and you want to poll it from a phone or a cloud cron job), put it
behind something that adds auth and TLS yourself — an SSH tunnel, a WireGuard/Tailscale overlay
network, or a reverse proxy (nginx/Caddy) with its own auth in front of it. Don't just flip
`FDSNWSEventIP` to `0.0.0.0` and open the port on your router. There's nothing secret in the data
itself (it's just earthquake detections), but an unauthenticated, wide-open HTTP server on your home
network is still not something to expose directly, and there's no rate limiting either — worth
keeping in mind if you're worried about someone hammering it.

---

## 11. Known limitations (summary)

- **`lat`/`longitude`/`minradius`/`maxradius` are parsed (lat/lon) or not even parsed (radius) and
  never used for filtering** — build a bounding box yourself (§8) and do exact-distance math
  client-side if you need a true radius.
- **No single-event lookup**: `eventid` is a declared-but-unused parameter; there is no way to fetch
  one specific event by ID. You always query a range and filter.
- **No `limit`/`offset`/pagination**: every matching event is returned, always.
- **No `orderby`**: response order isn't guaranteed; archived events tend to come back newest-first
  among themselves, but any live/in-progress events are tacked onto the end unsorted relative to
  that. Sort client-side by `time`/origin if order matters to you.
- **No `updatedafter`**: no incremental/"what changed" queries; diff full result sets yourself.
- **`magnitudetype`/`eventtype`/`catalog`/`contributor`/`includeallorigins`/`includeallmagnitudes`/
  `includearrivals` are all declared and silently ignored** — output always reports a single
  magnitude typed `"gqm"`, a single event type `"earthquake"`, and a single hardcoded catalog/
  contributor/author of `"GlobalQuake"`.
- **`format=quakeml` (the literal string, distinct from `format=xml`) crashes the handler with a real
  HTTP 500** — confirmed live. The parameter validator accepts `"quakeml"` as a valid format string,
  but the handler's format-dispatch `switch` only has cases for `"xml"`, `"json"`, `"geojson"`,
  `"text"` — anything else falls to a `default` branch that always returns 500. Use `format=xml`
  instead; it produces the same (minimal) QuakeML-flavored XML.
- **No shaking/intensity/PGA field in any response** — `ArchivedQuake` computes a `maxPGA`
  internally but nothing serializes it into GeoJSON/XML/text. Compute your own estimate from
  lat/lon/depth/mag using the formula in §7 if you want a "will this be felt at my location" signal.
- **No authentication, CORS wide open (`*`), no rate limiting** — see §10.
- **The `xml`/QuakeML output is not a conformant QuakeML document** — it's missing most of the
  schema (no arrivals, no creation info, no proper attribution blocks); treat it as "XML that looks
  QuakeML-ish", not something you can validate against the real QuakeML XSD.
- **Text/CSV output has no field-escaping** — a region name containing a literal `|` would corrupt a
  row (unlikely in practice, but there's no defensive quoting in `getFdsnText()`).

---

## Appendix: raw captured responses (2026-07-09, empty database, zero real detections)

For completeness, the actual raw byte-for-byte responses from a real running instance with no real
seismic data (fresh headless start, no seedlink stations actually reporting):

```
$ curl -s -i http://localhost:8080/fdsnws/event/1/query
HTTP/1.1 204 No Content
Date: Thu, 09 Jul 2026 23:25:54 GMT
Content-type: application/xml
Access-control-allow-origin: *

$ curl -s -i "http://localhost:8080/fdsnws/event/1/query?format=geojson"
HTTP/1.1 204 No Content
Date: Thu, 09 Jul 2026 23:25:55 GMT
Content-type: application/json
Access-control-allow-origin: *

$ curl -s -i "http://localhost:8080/fdsnws/event/1/query?format=text"
HTTP/1.1 204 No Content
Date: Thu, 09 Jul 2026 23:25:55 GMT
Content-type: text/plain
Access-control-allow-origin: *

$ curl -s -i "http://localhost:8080/fdsnws/event/1/query?starttime=notadate"
HTTP/1.1 400 Bad Request
Content-type: text/plain
Content-length: 71

Issue parsing start time. Use the format "YYYY-MM-DDTHH:MM:SS" UTC time

$ curl -s -i "http://localhost:8080/fdsnws/event/1/query?format=bogus"
HTTP/1.1 400 Bad Request
Content-type: text/plain
Content-length: 80

Issue parsing format. Make sure it is one of "xml", "json", "geojson", or "text"

$ curl -s -i "http://localhost:8080/fdsnws/event/1/query?format=jsonp"
HTTP/1.1 400 Bad Request
Content-type: text/plain
Content-length: 70

Invalid format. The format jsonp are not the droids you're looking for

$ curl -s -i "http://localhost:8080/fdsnws/event/1/query?format=quakeml"
HTTP/1.1 500 Internal Server Error
Content-type: text/plain
Content-length: 21

Internal Server Error
```

Note on the empty-`format=text` case specifically: reading `EarthquakeDataExport.getText()` alone
would suggest the `#EventID|...` header line is always present in the body, even with zero matching
events (it's unconditionally prepended before the per-event loop). I checked this empirically to be
sure, since it matters for a parser: `curl -sv "http://localhost:8080/fdsnws/event/1/query?format=text"`
against the empty test database returned `HTTP/1.1 204 No Content` with a genuinely **0-byte body**
(verified via `curl -o file` + `wc -c` → `0`). So in practice, whatever body Java's built-in
`HttpServer` would otherwise have sent is suppressed once the response code is 204 — the header line
is generated server-side but never reaches the client on an empty result. Don't special-case "204 but
still try to parse a header row" in your script; on 204, there is no body at all, for every format.
