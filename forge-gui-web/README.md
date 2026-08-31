# Forge Web

A browser front end for Forge. The rules engine runs headless in a JVM; the board is
rendered in the browser and driven over a WebSocket.

This is a third client alongside `forge-gui-desktop` (Swing) and `forge-gui-mobile`
(libGDX). It doesn't change either of them — it implements the same `IGuiGame` /
`IGuiBase` interfaces they do, so the engine is unaware it's talking to a web page.

## Running

```sh
mvn -pl forge-gui-web -am -DskipTests package
java -Dforge.assets.dir=forge-gui/ -jar forge-gui-web/target/forge-gui-web-*.jar
```

Then open <http://localhost:7860>. Pick a saved constructed deck or let it generate a
random two-colour one, and play.

| Option | Meaning |
| --- | --- |
| first CLI argument, or `-Dforge.web.port` | Port to listen on (default `7860`) |
| `-Dforge.assets.dir` | Where `res/` lives. `forge-gui/` when running from the source tree |
| `-Dforge.web.static` | Serve the client from a directory instead of the jar, for editing the UI without rebuilding |
| `-Dforge.web.debug=true` | Log every state push |

## How it works

```
 engine (Game, game thread)
      │  IGuiGame calls
      ▼
 WebGuiGame ──► WebSession ──► StateSerializer ──► JSON ──► WebSocket ──► browser
      ▲                                                                     │
      └──────────── IGameController ◄──── actions and dialog answers ◄──────┘
```

**`WebGuiGame`** extends `AbstractGuiGame`, so it inherits the same
controller/selection/auto-yield bookkeeping the desktop and mobile clients use. Update
calls (`updateCards`, `updateZones`, `updatePhase`, …) do nothing but flag the session
dirty. Calls that need an answer (`getChoices`, `confirm`, `assignCombatDamage`, …) park
the game thread on a handoff until the browser replies.

**`WebSession`** owns the socket and the push loop. It coalesces every engine update into
at most one message every 50 ms, so a trigger cascade that fires forty updates produces
one frame rather than forty.

**`StateSerializer`** diffs the board. Each card is serialised on its own and compared
against the fragment last sent; only the ones that changed go on the wire, plus the ids of
cards that left play. A typical mid-game push is a couple of KB rather than the ~50 KB a
full board snapshot would cost. The client keeps its own card map and patches it.

Player input maps onto `IGameController` — the same interface the Swing client drives —
so clicking a card in the browser calls `selectCard` exactly as clicking a card panel
does on the desktop.

Card art comes from `/api/card-image`, which prefers an image the user has already
downloaded through Forge, then its own disk cache, then Scryfall (rate-limited and
cached). Images are served with a long `Cache-Control`, so each printing is fetched once
per install.

## What's implemented

Constructed games against the AI: mulligans, priority, playing lands and spells, paying
mana, the stack, combat (including damage assignment), counters, the graveyard and exile
views, the game log, targeting and all the choice/confirm/amount prompts the engine
raises.

## What isn't, yet

- Sideboarding between games — the main deck is carried over unchanged.
- Conceding or leaving is refused while opening hands are being decided. The engine can't
  end a game mid-mulligan, and the desktop client refuses the same request.
- Limited, Quest, Adventure, Commander and the other game modes; the lobby only builds
  constructed matches.
- Multiplayer and spectating. One browser session per server process.
- The deck editor.

## Client

No build step. `src/main/resources/web` is three files — `index.html`, `app.css`,
`app.js` — served straight to the browser as ES modules. Point `-Dforge.web.static` at
that folder and a refresh picks up edits.
