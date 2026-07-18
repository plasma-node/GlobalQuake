# Goals: GlobalQuakeServer API, headless mode, alerting

Captured 2026-07-09. These are forward-looking goals for this fork — **not yet implemented**, to be
investigated/built later. Recorded at the user's request.

## Background / lead to chase
- Someone mentioned **GlobalQuakeServer** already has "something like an API". We have NOT yet looked
  into it. First step later: read `GlobalQuakeServer` (and `GlobalQuakeAPI`) to find out what
  API/protocol it exposes, whether it's a real HTTP/REST API or the custom binary client/server
  packet protocol (`gqserver.api.packets.*`), and how a headless server is launched
  (`gqserver.main.Main`, `--headless` flag was noted in build-setup).

## What the user wants to end up with
1. **An API to retrieve from** — query current/recent earthquake detections and station data on
   demand.
2. **Push / self-reporting** — ideally the server can also *send data out on its own* when it detects
   something (webhook/alert push), not only respond to polls. (User unsure of exact shape — "idk".)
3. **Alerting pipeline**: the user will run an **OpenClaw** instance with a **cron job** that
   periodically checks the API; when it finds a quake of interest it requests more detail and
   **sends an alert to the user**.
4. **Set location via the API** — the user sets their home/monitoring location through the API, and
   the rest of the API is used to check for quakes near it and drive alerts. *This is the main
   thing.*
5. **Headless mode** — run the whole thing without a GUI (server-style) for the always-on alerting
   box.
6. **Auto-restart capability** — the process should recover/restart itself (resilient always-on
   operation). Investigate what GlobalQuakeServer already does here and what's missing.
7. **Screenshot (non-headless mode)** — if running with a GUI, be able to capture a screenshot of
   the map/view and save it to a file (and/or send it) — e.g. attach a visual to an alert.

## Priority notes
- The **core deliverable** is: set my location via the API + poll the API for nearby quakes to fire
  alerts. Everything else (self-push, screenshots, auto-restart polish) is bonus / later.
- Sequence: first audit what `GlobalQuakeServer`/`GlobalQuakeAPI` already provide, THEN decide what
  to add (REST layer? location endpoint? alert webhook?).

Related repo notes: `project-overview.md`, `vscode-build-setup.md`, `seedlink-connection-issue.md`.
