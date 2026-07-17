# Headless / server deployment

Run the GlobalQuake **client** (not the server module — only the client computes home-location
shaking alerts) as a background daemon on a machine with no desktop environment.

## CLI flags

| Flag | Effect |
|------|--------|
| `--headless` / `-h` | Run with no Swing UI at all. |
| `--autoselect` / `-a` | After the seedlink availability scan, select every available station (no UI clicking). Re-run each boot; picks up newly available stations. |
| `--nosound` / `-n` | Disable all alert sounds (for a box with no audio, or where ntfy is the only alert path). |
| `--sound-strong-only` / `-q` | Play only the strong-shaking alert sound; mute everything else. |
| `--gpu-max-mem <GB>` / `-g` | (existing) cap CUDA GPU memory. |

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
