# AGENTS.md

Guide for AI agents working in this repo. Human contributor docs: [CONTRIBUTING.md](CONTRIBUTING.md).

## What this fork is

Fork of [Card-Forge/forge](https://github.com/Card-Forge/forge) — a Magic: The Gathering
rules engine in Java (~1M lines, GPL-3.0). Upstream ships desktop (Swing) and mobile
(libGDX) clients; both are untouched here. This fork adds **`forge-gui-web`**: the engine
runs headless behind a Netty HTTP/WebSocket server and the board renders in the browser.

**Before changing `forge-gui-web` — the bridge, the server, or the browser client — read
its [README](forge-gui-web/README.md)**: module architecture, the threading contract, the
wire format, and match-lifecycle gotchas (teardown, turn-0 rule) are documented there.

## Build

Maven multi-module, `--release 17` (JDK 21+ compiles it; JDK 25 tested).

```
mvn -B -pl forge-gui-web -am -DskipTests package
```

- `-am` is mandatory with `-pl`: the parent pom uses CI-friendly `${revision}` versioning,
  and a single-module build without the reactor fails with
  `forge:forge:pom:${revision} not found`. Same pattern for any other module.
- While a server is running from `target/`, rebuilds fail on Windows file locks in
  `target/lib` — stop the server first.

## Run the web client

From the repo root:

```
java -Dforge.assets.dir=forge-gui/ -Dforge.web.static=forge-gui-web/src/main/resources/web -jar forge-gui-web/target/forge-gui-web-*.jar 7860
```

- `-Dforge.assets.dir` points at the `res/` assets. Without it the card database is empty.
  In this repo it is always `forge-gui/`.
- `-Dforge.web.static` serves the client from source, so edits to
  `forge-gui-web/src/main/resources/web/{app.js,app.css,index.html}` need only a browser
  refresh — no rebuild.
- `-Dforge.web.debug=true` logs every state push to the server console.
- First start loads the card database (~20 s), then serves http://localhost:7860.

## Verifying changes

The web client has no unit test suite. Verify by playing: start a game, keep
a hand, play a land, end turn, concede, start a new game — then read the server console
for stack traces. CI (`.github/workflows/forge-gui-web.yml`) builds the module and
smoke-tests the packaged jar on every push to `master`/`web-client`. Engine or AI
changes: `mvn test` on the touched module (the full-suite run lives in CI,
`.github/workflows/test-build.yaml`).

## Module map

| Module | Contents |
| --- | --- |
| `forge-core` | card definitions, static data, image keys |
| `forge-game` | the rules engine: `Game`, phases, zones, combat; `Trackable` view objects (`CardView`, `PlayerView`, `GameView`) |
| `forge-ai` | the AI opponent |
| `forge-gui` | GUI-shared code: `IGuiGame`/`IGuiBase` interfaces, `AbstractGuiGame`, `HostedMatch`, player controllers, network protocol |
| `forge-gui-desktop` | Swing client (upstream) |
| `forge-gui-mobile`, `forge-gui-android`, `forge-gui-ios` | libGDX clients (upstream) |
| `forge-gui-web` | this fork's browser client: Java bridge under `src/main/java`, client under `src/main/resources/web` |

Card data lives in `forge-gui/res/` (`cardsfolder/`, `editions/`). User data (decks,
preferences, image cache) lives outside the repo under `%APPDATA%\Forge` and
`%LOCALAPPDATA%\Forge` on Windows.

## Where to make a change

- Rules bugs, card implementations → `forge-game` + `forge-gui/res/cardsfolder/`
- AI behaviour → `forge-ai`
- Code shared by multiple front ends → `forge-gui`
- Browser UI (layout, styling, interactions) → `forge-gui-web/src/main/resources/web`
- State serialisation, prompts, server routes → `forge-gui-web/src/main/java/forge/web`

## Branch etiquette

GPL-3.0; keep new work compatible. `master` carries this fork's state (upstream plus
`forge-gui-web`). Changes that would benefit upstream (engine, AI, desktop) should stay
rebase-able against `Card-Forge/forge:master` — avoid edits inside upstream modules
unless that's where the fix belongs.
