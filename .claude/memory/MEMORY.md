> ⚠️ PUBLIC REPO — this memory folder and `.ai/` are committed to a PUBLIC fork so work syncs across
> machines. NEVER write secrets, tokens, API keys, ntfy topics, precise home/location coordinates,
> IP addresses, emails, or any personal/user data here. Code and project decisions only. Runtime
> config with secrets lives in `.GlobalQuakeData/` (gitignored) — never mirror its values here.

- [Project overview](project_overview.md) — GlobalQuake architecture; independent revival of archived upstream repo
- [Build setup](build_setup.md) — confirmed JDK 21 + Maven 3.9.16 builds clean, jar locations
- [Seedlink date-parsing bug](seedlink_date_parsing_bug.md) — IRIS/RingServer-4.x discovery; FIXED 2026-07-09 via java.time ISO-8601 parsing
- [Startup gate & FDSN](startup_update_gate_and_fdsn.md) — launch-anytime + fail-fast + no-data persistence + IRIS→EarthScope; committed bad9741b
- [Git remotes](git_remotes.md) — origin=user's fork (plasma-node/GlobalQuake), upstream=archived original; commit via -F file not PS heredoc
- [Codebase facts](codebase_facts.md) — GPU=CUDA/analysis-only, custom sounds, server FDSNWS HTTP API, multi-quake detection root cause + design doc
- [Multi-quake merge diagnosis](multi_quake_merge_diagnosis.md) — full saga: C/D both stormed (coda-front ghosts = upstream README issue #2); now gated behind multiQuakeMode switch, default OFF = exact upstream; quarantine proposed next; + shakemap NPE fix
- [Seedlink parallel connections](seedlink_parallel_connections.md) — slow station count-up = per-station handshake RTTs on big catalogs; fixed with chunked parallel connections 2026-07-17
- [Headless + ntfy deployment](headless_ntfy_deployment.md) — client --headless/--nosound/--autoselect flags + ntfy push notifier + Jarvis JSONL; built+smoke-tested, uncommitted 2026-07-17
- [Untracked .ai/ notes](untracked_ai_notes.md) — repo has valuable but untracked .ai/ notes folder, don't let it get wiped
