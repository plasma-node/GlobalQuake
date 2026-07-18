---
name: seedlink-parallel-connections
description: Slow post-launch station count-up root-caused to per-station seedlink handshake RTTs on big catalogs; fixed 2026-07-17 with parallel chunked connections per network
metadata: 
  node_type: memory
  type: project
  originSessionId: 79e25f83-3515-42c6-b4f1-2544ac6f2faf
---

User reported (2026-07-17) the bottom-left "Stations: x/y" counter crawling after launch (fast at
first, then slow, region-clustered; both boots). Root cause: `SeedlinkNetworksReader` used ONE
connection per seedlink network, and seisFile's `selectData()` costs two blocking round-trips per
station (STATION + SELECT), with **no data flowing until the entire handshake ends**. The
ISO-8601 discovery fix ([[seedlink-date-parsing-bug]]) restored the giant catalogs (IRIS rtserve
etc.), so hundreds of stations (incl. PNW) now handshake sequentially on one connection → minutes
before first packet. The regression was CAUSED by our coverage win, not by launch-anytime.

**Fix (2026-07-17, in `SeedlinkNetworksReader`):** split each network's stations round-robin
across up to `MAX_CONNECTIONS_PER_NETWORK=6` parallel connections (`MAX_STATIONS_PER_CONNECTION=64`
per chunk). Per-chunk reconnect with its own backoff; `connectedStations` updated via synchronized
+= / -= per chunk (no more per-thread stomping); network status flips DISCONNECTED only when the
LAST live connection dies (tracked via activeConnections AtomicInteger map). Small networks (≤64
stations) still use exactly one connection — behavior unchanged for them.

**Why:** public seedlink servers tolerate a few connections per client; 6 × 64 covers ~400
stations per server with ~6× faster handshake. If a server complains (connection-refused on extra
connections), lower MAX_CONNECTIONS_PER_NETWORK.

**How to apply:** if the user still reports slow station attach, next steps are: measure per-select
RTT in logs, consider batching SELECT patterns per STATION, or SeedLink v4 batch handshake.
Related: [[startup-update-gate-and-fdsn]] (launch-anytime = stations attach visibly post-launch,
which is expected and separate from this slowness).
