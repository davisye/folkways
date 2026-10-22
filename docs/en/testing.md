# Testing

[Documentation](README.md) · [简体中文](../zh-CN/testing.md)

Complete the [development setup](../../CONTRIBUTING.md#development-setup) first.

## Checks without launching Minecraft

| Command                   | Coverage                                                                                                             |
| ------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| `npm run format:md:check` | Prettier formatting for all project Markdown; also checked before commits and in CI                                  |
| `npm run test:unit`       | Node tests for TypeScript helpers, including i18n                                                                    |
| `npm run test:i18n`       | Language keys, placeholders, notice/refusal coverage and built-in pattern dictionaries; needs no npm dependencies    |
| `npm run typecheck`       | TypeScript checks for every suite                                                                                    |
| `./gradlew check`         | Module checks, game test source compilation, and checks for tabs and trailing whitespace; does not launch game tests |
| `./gradlew build`         | Gradle checks, jar packaging and collection                                                                          |

## Nightly diagnostics

`./gradlew build` produces Release jars. `./gradlew -PbuildChannel=nightly build`
produces jars with a `-nightly` version suffix. CI builds and checks both channels;
its GameTests and client smoke test run the Nightly channel. The Nightly release
publishes only that channel's jars. Switching channels also clears the previous
channel's collected jars from `build/libs/`.

Nightly Core includes the unmodified [Spark NeoForge 1.21.1 build](https://modrinth.com/mod/spark/version/1.10.124-neoforge-1.21.1)
as a Jar-in-Jar mod. It enables background CPU sampling for both the server and
client, at Spark's default 10 ms interval. Planning heartbeat logs default to on.
Release Core does not bundle Spark or enable its profiler, and new Release
server configs default to disabled heartbeat logs. Existing user preferences
take priority in both channels.

After reproducing a slowdown, save the server profile locally with
`/spark profiler stop --save-to-file`; for rendering stalls use
`/sparkc profiler stop --save-to-file`. The files are written under
`config/spark/`. Use `profiler info` on either command to inspect sampling status,
and `profiler start` to begin another recording. Bare `profiler open` or
`profiler stop` uploads profiling data to Spark's viewer; automatic background
sampling does not upload a profile.

To opt out of Nightly background sampling, set `backgroundProfiler` to `false`
in `config/spark/config.json`, or launch Java with `-Dspark.backgroundProfiler=false`
or `SPARK_BACKGROUNDPROFILER=false`. A saved `diagnostics.logPlanHeartbeat`
preference in `folkways_core-server.toml` controls the heartbeat log separately.
Per-player chat diagnostics remain opt-in. A separately installed Spark mod
keeps its own settings when using Release.

Use Java 21's JFR for deeper investigations of allocations, GC pauses or locks;
start a short recording when needed. Nightly does not start a second continuous
JFR recording alongside Spark. Spark's native async profiler supports Linux and
macOS; on Windows its Java sampler provides CPU stack samples. See
[Spark commands](https://spark.lucko.me/docs/Command-Usage) and
[profiler engines](https://spark.lucko.me/docs/misc/Using-async-profiler).

`./gradlew :core:checkDiagnostics :core:verifyDiagnostics` checks the selected
channel's defaults, opt-outs and packaged profiler checksum without launching
Minecraft. Both checks are included in `build`.

## NeoForge GameTests

Choose a test owner and environment:

| Command                                                                 | Scope                                                                        |
| ----------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| `./gradlew runGameTestServer`                                           | Combined Core, Vanilla and Create tests in the base environment              |
| `./gradlew -PcookeryTests=true runGameTestServer`                       | Combined tests with Cookery, including Core and Vanilla's `src/cookeryTest`  |
| `./gradlew -PgameTestMod=core runGameTestServer`                        | Core-owned tests in the base environment; also accepts `vanilla` or `create` |
| `./gradlew -PgameTestMod=core -PcookeryTests=true runGameTestServer`    | Core-owned tests with Cookery; also accepts `vanilla`                        |
| `./gradlew -PgameTestMod=cookery -PcookeryTests=true runGameTestServer` | Cookery-owned tests with Cookery installed                                   |

Ownership is separate from installed mods: selecting Core still loads Vanilla's
residents and the integrations its scenarios require. Modular Golems is not
loaded in these runs and has no GameTests. Core also runs the existing LDLib2
dependency tests; other owners do not repeat them.

Selected game directories are `run/gametest/<mod>/<base|cookery>/`. Combined runs
use `run/gametest/`. Before launching a server directly through Gradle, accept
the [Minecraft EULA](https://www.minecraft.net/eula) in that directory's
`eula.txt`. Only do so if you accept it.

## Blockwright suites

[Blockwright](https://github.com/IzakyL/blockwright) drives real Minecraft clients
and servers. Its source tree includes native Windows support; the pinned npm
release predates it. Use Linux x64 with the published packages, or a source-built
SDK on Windows, as described in [development setup](../../CONTRIBUTING.md#development-setup).
This repository's CI uses Ubuntu 24.04. The suites download Minecraft, NeoForge and fixture mods on first
run. `blockwright.toml` sets `eula = true` for their servers; running them requires
accepting the Minecraft EULA.

| Command                 | Purpose                                                          |
| ----------------------- | ---------------------------------------------------------------- |
| `npm run test:e2e`      | Player-visible behavior on the ground                            |
| `npm run test:sable`    | Aboard variants of twin e2e specs and Sable-only specs           |
| `npm run test:coverage` | Both e2e suites with coverage, followed by a report              |
| `npm run visual:take`   | Screenshots of screens, panels, resident looks and world visuals |
| `npm run ponder:take`   | Recordings of the Colony Book's Ponder scenes                    |
| `npm run pattern:take`  | Building-pattern galleries                                       |
| `npm run bench:take`    | Large-colony performance benchmarks                              |
| `npm run promo:take`    | Promotional footage                                              |

Limit concurrent game instances with `--workers=1`. For a single spec:

```sh
npm run test:e2e -- tests/e2e/founding-smoke.spec.ts --workers=1
```

Each suite under `tests/` has its own `playwright.config.ts` and `tsconfig.json`.
Generated suite output belongs in the Git-ignored `tests/out/`.

### Writing e2e specs

Prefer a black-box e2e spec for behavior a player can observe. Shared helpers
belong in `tests/shared/`; suites may import from there and `tests/ldlib2/`, but
not from another suite.

Specs about colony blocks use twin stages from
[`tests/shared/harness/stage.ts`](../../tests/shared/harness/stage.ts).
The ground variant runs in `test:e2e`; the aboard variant runs in `test:sable`.
Keep the layout in plan coordinates. Use `stage.lay` for the floor,
`stage.at(cell)` for block commands and reads, `stage.world(cell)` for visible
world positions, and `stage.stand(at)` to position a player. Keep ship-only specs
in `tests/sable/`. See
[`founding-smoke.spec.ts`](../../tests/e2e/founding-smoke.spec.ts) for an example.

The repository's `.mcp.json` exposes the same harness to MCP-capable assistants;
the LDLib2 UI adapter lives in `tests/ldlib2/`.

### JEC search compatibility

```sh
npm run test:e2e -- tests/e2e/jech-search.spec.ts --workers=1
```

This spec runs once with JEC absent and once with `blockwright.toml#jech`, which
installs JEC only on the client. Both servers run without it. It covers item,
folder, Workforce and stock matching, existing query operators, and changes to
JEC's fuzzy spelling and keyboard settings while reusing a prepared query.
The default profile keeps JEC absent; CI's founding smoke test does not run this spec.

## Cookery fixtures

The `cookery` and `cookery-sable` profiles in `blockwright.toml` load both
`folkways_cookery` and Kaleidoscope Cookery 1.6.0. `cookery-native` loads only the
native mod to check that the extension stays optional.

Cookery e2e specs cover native production, interruptions and recovery, shared
tools, flooded-rice farming through harvest and replanting, and all thirteen
lessons. `creative-supplies.spec.ts` also runs without Cookery.

NeoForge tests live in `mods/cookery/src/gametest`. Fixtures needing Core or
Vanilla package internals live in `mods/{core,vanilla}/src/cookeryTest` and join
their owning module's run only with `-PcookeryTests=true`.

The native audit checks **612 recipe resources in the pinned Cookery 1.6.0
fixture** and writes `cookery-recipe-audit.json` inside the game directory.
After the selected Cookery run, generate the recipe graph with:

```sh
node tests/shared/cookery/recipe-graph.mjs \
  run/gametest/cookery/cookery/cookery-recipe-audit.json \
  tests/out/cookery-recipe-graph.json
```

For a combined run, the audit input is `run/gametest/cookery-recipe-audit.json`.

## What CI runs

The [build workflow](../../.github/workflows/build.yml) runs on pushes and pull requests:

| Job                     | Checks                                                                                                                                     |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| Build                   | `npm ci`, `npm run format:md:check`, `npm run setup`, `./gradlew build`, `npm run typecheck`, `npm run test:unit`; uploads `folkways-jars` |
| GameTests (core)        | Base and Cookery environments; includes LDLib2 dependency tests                                                                            |
| GameTests (vanilla)     | Base and Cookery environments                                                                                                              |
| GameTests (create)      | Base environment                                                                                                                           |
| GameTests (cookery)     | Cookery environment                                                                                                                        |
| Client smoke (founding) | `founding-smoke.spec.ts` against a real client/server, one worker                                                                          |

Both profiles of a mod are attempted even when the first fails; either failure
fails that mod's check. Each server invocation has a 50-minute process timeout,
and each mod job has a 110-minute limit. Client smoke has a 60-minute job limit.
CI does not run the full e2e, Sable, visual, Ponder, benchmark or promo suites.

The nightly release on pushes to `master` requires Build, all four GameTest
checks and Client smoke to pass.

### Failure artifacts

CI uploads console logs, game logs, crash reports and a JUnit suite-level result
even after test failure. Console logs and JUnit files are written after each
invocation under `run/gametest/ci/<mod>-<profile>.*`; artifact names are
`gametest-<mod>-reports`. The JUnit case reports whether that server invocation
passed. Individual GameTest details remain in the server logs.

Client smoke uploads `tests/out/` as `client-smoke-results`.
