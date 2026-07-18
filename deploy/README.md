# Headless / server deployment

Run the GlobalQuake **client** (not the server module — only the client computes home-location
shaking alerts) as a background daemon on a machine with no desktop environment.

## CLI flags

| Flag | Effect |
|------|--------|
| `--headless` / `-h` | Run with no Swing UI at all. |
| `--autoselect-radius <miles>` / `-r` | **Recommended.** Select available stations within N miles of your home, prune the rest. Keeps memory/CPU/threads bounded. Re-run each boot; picks up newly available stations. |
| `--autoselect` / `-a` | Select ALL available stations globally — **thousands of stations; will OOM a server.** Use the radius form instead. |
| `--nosound` / `-n` | Disable all alert sounds (for a box with no audio, or where ntfy is the only alert path). |
| `--sound-strong-only` / `-q` | Play only the strong-shaking alert sound; mute everything else. |
| `--gpu-max-mem <GB>` / `-g` | (existing) cap CUDA GPU memory. |
| `--help` | Print all flags and exit. |

## Resource sizing (avoid OOM / CPU burn)

Each selected station streams live data into an in-memory waveform buffer and runs analysis, so
**station count is the dominant cost.** `--autoselect` (no radius) selects thousands of stations
globally and will exhaust the heap. Always use `--autoselect-radius <miles>` on a server (e.g. `600`
covers a wide regional area). Also set a heap cap with `-Xmx` (the unit uses `-Xmx4G`); the JVM's
default max heap is 1/4 of RAM, which a global station set blows past.

## Common gotchas

- **`javax.net.ssl.SSLHandshakeException: PKIX path building failed`** on station-source updates:
  the JVM can't validate the FDSN servers' TLS certs — the Debian JRE is missing the CA bundle.
  Fix: `sudo apt install ca-certificates-java && sudo update-ca-certificates -f`, or use a full JDK
  (Temurin) whose `cacerts` is populated. Not fatal if you pre-seeded `.GlobalQuakeData` (station
  metadata is already cached and seedlink associations are restored), but new metadata won't update
  until certs work.
- **Nothing listening on the HTTP port / `curl 127.0.0.1:8090` refused:** the local server only
  starts when it's enabled. Set `httpServerEnabled=true` in `.GlobalQuakeData/ntfy.properties`
  (and, if your agent runs on another machine, `httpServerBind=0.0.0.0` + firewall it). Look for the
  log line `Local status server on http://...`; if you instead see `ntfy push AND local status
  server are both OFF`, that's the config.

## First run

A virgin `.GlobalQuakeData` must complete one station-source download + seedlink availability scan
before `--autoselect` has anything to select. Easiest path: run once **on your desktop with the
UI**, set your home location and alert thresholds in Settings, then copy the resulting
`.GlobalQuakeData/` (station database, `globalQuake.properties`) to the server. Also copy/edit
`.GlobalQuakeData/ntfy.properties` (created disabled on first run — set `enabled=true` + a topic).

## systemd (Debian)

```bash
sudo useradd -r -s /usr/sbin/nologin globalquake
sudo mkdir -p /opt/globalquake
sudo cp GlobalQuakeClient/target/GlobalQuake-*-jar-with-dependencies.jar /opt/globalquake/GlobalQuakeClient.jar
# copy your pre-seeded .GlobalQuakeData here too:
sudo cp -r .GlobalQuakeData /opt/globalquake/
sudo chown -R globalquake:globalquake /opt/globalquake

sudo cp deploy/globalquake-client.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now globalquake-client
journalctl -u globalquake-client -f
```

`-Djava.awt.headless=true` in the unit is both correct and a canary — any stray Swing call throws
`HeadlessException` loudly instead of hanging on a missing X server.

## Agent (Jarvis) feed

When `jsonlEnabled=true`, the client keeps `.GlobalQuakeData/nearby_quakes.jsonl` — one JSON object
per line for every quake currently affecting a configured zone (uuid, fingerprint, origin, lat,
lon, depth, mag, zone, distKm, pga, tier, updatedAt). It is rewritten atomically, so a reader never
sees a torn file; entries expire after `jsonlRetentionMinutes`.
