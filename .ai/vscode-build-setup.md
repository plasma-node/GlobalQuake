# Building GlobalQuake in VS Code

The upstream project was developed in IntelliJ (`.iml` files are checked in), but the build is
plain Maven, so VS Code works fine. Nothing here requires the CUDA native module — see the note at
the bottom.

## Prerequisites

1. **JDK 17** — this is a hard requirement, pinned in every module's `pom.xml`
   (`maven.compiler.source/target = 17`) and in CI (`actions/setup-java@v3`, `java-version: 17`).
   You already have JDK 21 installed at `C:\Program Files\Java\jdk-21` — Java 17 *source/target*
   compiles fine under a JDK 21 toolchain (verified: `javac`/`java` from that install work), so a
   separate JDK 17 install is not strictly required. If you hit any toolchain friction, install
   Temurin 17 to match CI exactly.
2. **Maven** (3.6+) — either install it standalone, or rely on the VS Code Maven extension's
   bundled Maven runtime (no separate install needed).
3. **VS Code extensions**:
   - `vscjava.vscode-java-pack` (Extension Pack for Java — includes language support, debugger,
     test runner, project manager)
   - `vscjava.vscode-maven` (Maven for Java — lifecycle commands, dependency tree, run configs)
4. **The vendored `libs/` directory must stay in place.** `seisFile-2.1.0-SNAPSHOT` is not on Maven
   Central; every module's `pom.xml` adds a `file://${project.basedir}/../libs` repository pointing
   at `libs/edu/sc/seis/seisFile/2.1.0-SNAPSHOT/seisFile-2.1.0-SNAPSHOT.jar`. If this ever needs
   reinstalling into a fresh local `~/.m2` cache, use `mvn install:install-file` with that jar
   (group `edu.sc.seis`, artifact `seisFile`, version `2.1.0-SNAPSHOT`) rather than trying to fetch
   it remotely.

## Getting a first build

From the repo root:

```
mvn -B clean install
```

This builds modules in the correct dependency order (`GlobalQuakeAPI` → `GlobalQuakeCore` →
`GlobalQuakeClient`/`GlobalQuakeServer`) because they're listed in that order in the root
`pom.xml`'s `<modules>` and reference each other via `xspanger.GlobalQuake:GlobalQuakeAPI` /
`GlobalQuakeCore` dependencies. Maven's reactor resolves this automatically — no manual per-module
build order needed.

`mvn test` runs the JUnit tests (there are a handful under `GlobalQuakeCore/src/test` and
`GlobalQuakeServer/src/test`, e.g. `StationDatabaseManagerTest`).

In VS Code, once the Maven extension picks up the multi-module `pom.xml`, you get this for free via
the "Maven" side panel (per-module lifecycle goals) and the Java Projects panel picks up all 4
modules for navigation/refactoring/debugging.

## Running it

Both runnable modules use the `maven-assembly-plugin` to produce a `jar-with-dependencies` on
`mvn package`:

- **Desktop client**: `GlobalQuakeClient/target/GlobalQuake-0.11.0_pre-2-jar-with-dependencies.jar`,
  main class `globalquake.main.Main`
- **Headless server**: `GlobalQuakeServer/target/GlobalQuakeServer-0.11.0_pre-2-jar-with-dependencies.jar`,
  main class `gqserver.main.Main` (accepts `--headless`)

For iterative dev, it's easier to just run the main class directly from VS Code (Run/Debug codelens
on the `main` method, or add a `launch.json` entry with `mainClass` set to one of the above) instead
of rebuilding the assembly jar every time.

Runtime data (station database, settings, sound files) lives under a `GlobalQuake`/
`.GlobalQuakeServerData`-style folder relative to the working directory
(`GlobalQuake.mainFolder`, see `StationDatabaseManager.getStationsFolder()`) — first run will create
it.

## The CUDA native module (GQHypocenterSearch) — optional, out of scope for now

`GQHypocenterSearch/` is a separate CMake/C++/CUDA project (`gq_hypocs` shared lib + JNI header
`globalquake_jni_GQNativeFunctions.h`) providing GPU-accelerated hypocenter search. It:

- Requires an NVIDIA CUDA toolkit + `cuda_add_library`/`find_package(CUDA REQUIRED)` from CMake
- Hardcodes Linux JDK include paths (`/usr/lib/jvm/java-17-openjdk-amd64/include`), so it won't
  configure as-is on Windows without editing `CMakeLists.txt`
- Is only consumed by the `Dockerfile`, which copies a prebuilt `.so` into the server's
  `-Djava.library.path`

This is not needed to build or run the Java application — treat it as a future/optional GPU
acceleration path, not part of the day-to-day VS Code dev loop. If it's ever wanted, it'll need a
CUDA toolkit install, a CMake generator that works on Windows (e.g. Ninja + MSVC or WSL), and the
include paths in `GQHypocenterSearch/CMakeLists.txt` fixed up for the target OS.
