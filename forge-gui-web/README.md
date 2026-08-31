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
random two-colour one, and play — either against the AI, or against a second human on
your LAN (see [LAN multiplayer](#lan-multiplayer)).

| Option | Meaning |
| --- | --- |
| first CLI argument, or `-Dforge.web.port` | Port to listen on (default `7860`) |
| `-Dforge.assets.dir` | Where `res/` lives. `forge-gui/` when running from the source tree |
| `-Dforge.web.static` | Serve the client from a directory instead of the jar, for editing the UI without rebuilding |
| `-Dforge.web.debug=true` | Log every state push |
| `-Dforge.web.lan.grace.seconds` | How long a disconnected LAN seat stays reserved (default `90`) |

## How it works

```
 engine (Game, game thread)
      │  IGuiGame calls
      ▼
 WebGuiGame ──► WebSession ──► StateSerializer ──► JSON ──► WebSocket ──► browser
      ▲                                                                     │
      └──────────── IGameController ◄──── actions and dialog answers ◄──────┘
```

In LAN mode this pipeline is per seat: a `LanRoomManager` owns one `WebSession` (and thus one
`WebGuiGame`, `StateSerializer`, prompt, pending-request map and transport set) for the host
seat, one for the opponent seat, and a third for solo play. `StateSerializer` reads its local
player from its own `WebGuiGame`, so the same `GameView` serialises differently for each seat
without any shared state. The manager authenticates every socket and HTTP call against the
seat's capability token, so one seat can never answer or invoke the other's controller.

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
cached). Board and hand thumbnails are served small (`size=small`, Scryfall's 146px
printing or a downscaled copy of the local art, cached separately); the hover inspector
asks for `size=normal` full art. Images are served with a long `Cache-Control`, so each
printing at each size is fetched once per install.

## Match lifecycle

Starting a match ends the previous one. `MatchLauncher` concedes the running game —
`PlayerControllerHuman.concede()` releases the input latches the parked game thread waits
on — waits for it to unwind, then calls `endCurrentGame()` and `resetForNewMatch()` on the
GUI thread. The finished game's view stays in place: clearing it makes the old thread NPE
in `awaitNextInput`, and `startMatch` swaps the view anyway.

The engine cannot end a game before the first turn begins (coin toss, opening-hand
prompts) — the desktop client refuses the same concede, so the client disables
concede/leave until `turn > 0`. `GameView.isMulligan()` is not usable as that signal: it
is only true during the London return-cards step, never during the initial keep/mulligan
prompt.

## LAN multiplayer

A second mode on the same URL: one constructed 1v1 room per server. The first browser to
join claims the **host** seat (and gets the lobby's rule/start controls); the second claims
the **opponent** seat. Both players pick a name and a saved/random constructed deck, ready
up, and the host starts the match. The root URL and `/ws` are unchanged; there is no room
code or LAN discovery yet.

**Reaching the room over the LAN.** Start the server as usual and connect from another
machine with `http://<host-ip>:7860` (e.g. `http://192.168.1.20:7860`). The server binds
all interfaces by default. You will need to allow inbound TCP on the configured port in the
host's firewall.

**Identity and reconnection.** Claiming a seat returns an opaque random capability token,
stored in the browser tab's `sessionStorage`; the server keeps only its SHA-256 hash. The token is
sent on the WebSocket's first frame and on every lobby HTTP mutation, and is never echoed in
a room broadcast. A refresh reconnects with the stored token and restores the same seat; a
disconnected seat stays reserved for `-Dforge.web.lan.grace.seconds` (default 90 s). During
a live game a disconnect parks that seat's input rather than auto-answering, so the other
player's pending decision is untouched; reconnect before the grace expires resumes with a
full snapshot and the pending prompt re-raised. On expiry the match is safely conceded and
the room returns to the lobby.

**Scope and limits.** LAN play is constructed 1v1 only, with a host-controlled starting-life
setting. Variants (Commander, Planechase, …), spectators, teams, sideboarding and the rest of
the desktop lobby are out of scope, as is multi-room routing. There is **no TLS by default** —
tokens and game state travel in plaintext, so only use LAN mode on a network you trust. The
token handshake authenticates a seat's identity but is not a substitute for transport
encryption or player authentication.

## What's implemented

Constructed games against the AI, and constructed 1v1 between two humans on a LAN:
mulligans, priority, playing lands and spells, paying mana, the stack, combat (including
damage assignment), counters, the graveyard and exile views, the game log, targeting and
all the choice/confirm/amount prompts the engine raises. Each human seat gets its own
visibility (you see your hand, the opponent sees only counts) and its own prompt/action
routing, so neither player ever receives the other's private cards or prompts.

## What isn't, yet

- Sideboarding between games — the main deck is carried over unchanged.
- Conceding or leaving is refused while opening hands are being decided. The engine can't
  end a game mid-mulligan, and the desktop client refuses the same request.
- Limited, Quest, Adventure, Commander and the other game modes; the lobby only builds
  constructed matches (and LAN is constructed 1v1 only).
- LAN spectators, teams, multi-room routing and non-constructed LAN variants. Solo still
  broadcasts one game to any number of tabs; LAN seats are strictly one browser each.
- The deck editor.

## Client

No build step. `src/main/resources/web` is three files — `index.html`, `app.css`,
`app.js` — served straight to the browser as ES modules. Point `-Dforge.web.static` at
that folder and a refresh picks up edits.
