package forge.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Multiset;

import forge.card.MagicColor;
import forge.game.GameEntityView;
import forge.game.GameLogEntry;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.card.CounterType;
import forge.game.combat.CombatView;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.AbstractGuiGame;

/**
 * Turns the engine's {@link GameView} into the JSON the browser renders.
 *
 * <p>Card objects are diffed between pushes. A busy board is a few hundred cards and the
 * vast majority are unchanged from one priority window to the next, so re-sending all of
 * them on every engine update is what makes a naive bridge feel sluggish. Each card is
 * serialised independently, compared against the fragment last sent, and only the
 * differences go on the wire; the client keeps its own card map and patches it.
 */
final class StateSerializer {

    private static final int LOG_LINES = 60;

    private static final byte[] MANA_COLORS = {
        MagicColor.WHITE, MagicColor.BLUE, MagicColor.BLACK, MagicColor.RED, MagicColor.GREEN, MagicColor.COLORLESS
    };
    private static final String[] MANA_COLOR_NAMES = { "W", "U", "B", "R", "G", "C" };

    private final AbstractGuiGame gui;

    /** Last fragment sent per card id, so we can skip cards that haven't moved. */
    private final Map<Integer, String> sentCards = new HashMap<>();
    /** Card id to view, for resolving clicks coming back from the browser. */
    private final Map<Integer, CardView> cardsById = new HashMap<>();
    private final Map<Integer, PlayerView> playersById = new HashMap<>();

    StateSerializer(final AbstractGuiGame gui) {
        this.gui = gui;
    }

    CardView card(final int id) {
        return cardsById.get(id);
    }

    PlayerView player(final int id) {
        return playersById.get(id);
    }

    /** Drops the diff baseline so the next build sends the full board again. */
    synchronized void reset() {
        sentCards.clear();
        cardsById.clear();
        playersById.clear();
    }

    /**
     * Builds a state message. Returns {@code null} when nothing has changed since the last
     * call, which lets the push loop stay quiet while the AI is thinking.
     */
    synchronized String build(final GameView game, final Prompt prompt, final boolean full) {
        if (game == null) {
            return null;
        }
        if (full) {
            sentCards.clear();
        }

        final Set<CardView> visible = collectCards(game);
        final List<String> changed = new ArrayList<>();
        final Set<Integer> presentIds = new LinkedHashSet<>();

        cardsById.clear();
        for (final CardView card : visible) {
            if (card == null) {
                continue;
            }
            cardsById.put(card.getId(), card);
            presentIds.add(card.getId());
            final String fragment = cardJson(card);
            if (!fragment.equals(sentCards.get(card.getId()))) {
                sentCards.put(card.getId(), fragment);
                changed.add(fragment);
            }
        }

        final List<Integer> gone = new ArrayList<>();
        sentCards.keySet().removeIf(id -> {
            if (presentIds.contains(id)) {
                return false;
            }
            gone.add(id);
            return true;
        });

        final StringBuilder sb = new StringBuilder(4096);
        final Json.Obj root = Json.obj(sb);
        root.put("t", "state");
        root.putIf("full", full);
        root.put("turn", game.getTurn());
        root.put("phase", game.getPhase() == null ? "" : game.getPhase().nameForUi);
        root.put("phaseId", game.getPhase() == null ? "" : game.getPhase().name());
        root.put("gameOver", game.isGameOver());
        root.put("mulligan", game.isMulligan());
        if (game.isGameOver()) {
            root.put("winner", game.getWinningPlayerName());
        }
        final PlayerView turnPlayer = game.getPlayerTurn();
        if (turnPlayer != null) {
            root.put("turnPlayer", turnPlayer.getId());
        }
        final PlayerView local = gui.getCurrentPlayer();
        if (local != null) {
            root.put("me", local.getId());
        }
        root.put("stormCount", game.getStormCount());

        writePlayers(root, game);
        writeCards(root, changed, gone);
        writeStack(root, game);
        writeCombat(root, game.getCombat());
        writeLog(root, game);
        writePrompt(root, prompt);

        root.end();
        return sb.toString();
    }

    // -------------------------------------------------------------- gathering

    /**
     * The zones the client draws. Libraries and hidden hands are sent as counts only, so
     * their contents never reach the browser and can't be inspected from the console.
     */
    private Set<CardView> collectCards(final GameView game) {
        final Set<CardView> cards = new LinkedHashSet<>();
        if (game.getPlayers() != null) {
            for (final PlayerView p : game.getPlayers()) {
                addAll(cards, p.getBattlefield());
                addAll(cards, p.getHand());
                addAll(cards, p.getGraveyard());
                addAll(cards, p.getExile());
                addAll(cards, p.getCommand());
                addAll(cards, p.getFlashback());
                addAll(cards, p.getAnte());
            }
        }
        if (game.getStack() != null) {
            for (final StackItemView si : game.getStack()) {
                if (si.getSourceCard() != null) {
                    cards.add(si.getSourceCard());
                }
            }
        }
        if (game.getRevealedCollection() != null) {
            cards.addAll(game.getRevealedCollection());
        }
        return cards;
    }

    private static void addAll(final Set<CardView> target, final Iterable<CardView> source) {
        if (source == null) {
            return;
        }
        for (final CardView c : source) {
            if (c != null) {
                target.add(c);
            }
        }
    }

    // ------------------------------------------------------------- fragments

    private String cardJson(final CardView card) {
        final StringBuilder sb = new StringBuilder(320);
        final Json.Obj o = Json.obj(sb);
        o.put("id", card.getId());

        final ZoneType zone = card.getZone();
        o.put("zone", zone == null ? "None" : zone.name());
        if (card.getController() != null) {
            o.put("ctrl", card.getController().getId());
        }
        if (card.getOwner() != null) {
            o.put("owner", card.getOwner().getId());
        }

        o.putIf("tapped", card.isTapped());
        o.putIf("sick", card.isSick());
        o.putIf("attacking", card.isAttacking());
        o.putIf("blocking", card.isBlocking());
        o.putIf("phased", card.isPhasedOut());
        o.putIf("token", card.isToken());
        o.putIf("commander", card.isCommander());
        o.putIf("sel", gui.isSelectable(card));
        o.putIf("hl", gui.isHighlighted(card));
        o.putIf("weak", gui.isWeaklySelectable(card));

        final boolean visible = gui.mayView(card);
        if (!visible) {
            o.put("hidden", true);
            o.put("name", "");
            return o.end().toString();
        }

        final CardStateView state = card.getCurrentState();
        if (state != null) {
            o.put("name", state.getName());
            o.put("img", state.getImageKey());
            o.put("set", state.getSetCode());
            o.put("type", state.getType() == null ? "" : state.getType().toString());
            if (state.getManaCost() != null && !state.getManaCost().isNoCost()) {
                o.put("cost", state.getManaCost().toString());
            }
            o.put("text", state.getAbilityText());
            if (state.isCreature() || card.getCurrentState().hasPrintedPT()) {
                o.put("power", state.getPower());
                o.put("toughness", state.getToughness());
            }
            if (state.isPlaneswalker()) {
                o.put("loyalty", state.getLoyalty());
            }
            if (state.isBattle()) {
                o.put("defense", state.getDefense());
            }
        }
        if (card.hasAlternateState()) {
            final CardStateView alt = card.getAlternateState();
            if (alt != null) {
                o.put("altName", alt.getName());
                o.put("altImg", alt.getImageKey());
            }
        }

        if (card.getDamage() > 0) {
            o.put("damage", card.getDamage());
        }
        if (card.getShieldCount() > 0) {
            o.put("shields", card.getShieldCount());
        }
        writeCounters(o, card.getCounters());

        final CardView attachedTo = card.getAttachedTo();
        if (attachedTo != null) {
            o.put("attachedTo", attachedTo.getId());
        }
        final List<CardView> attachments = card.getAttachedCards();
        if (attachments != null && !attachments.isEmpty()) {
            final Json.Arr arr = o.arr("attached");
            for (final CardView a : attachments) {
                arr.add(a.getId());
            }
            arr.end();
        }
        return o.end().toString();
    }

    private static void writeCounters(final Json.Obj o, final Multiset<CounterType> counters) {
        if (counters == null || counters.isEmpty()) {
            return;
        }
        final Json.Obj c = o.obj("counters");
        for (final Multiset.Entry<CounterType> entry : counters.entrySet()) {
            c.put(String.valueOf(entry.getElement()), entry.getCount());
        }
        c.end();
    }

    private void writePlayers(final Json.Obj root, final GameView game) {
        playersById.clear();
        final Json.Arr players = root.arr("players");
        if (game.getPlayers() == null) {
            players.end();
            return;
        }
        for (final PlayerView p : game.getPlayers()) {
            playersById.put(p.getId(), p);
            final Json.Obj o = players.obj();
            o.put("id", p.getId());
            o.put("name", p.getName());
            o.put("life", p.getLife());
            o.put("ai", p.isAI());
            o.putIf("priority", p.getHasPriority());
            o.putIf("lost", p.getHasLost());
            o.put("hand", p.getZoneSize(ZoneType.Hand));
            o.put("library", p.getZoneSize(ZoneType.Library));
            o.put("maxHand", p.getMaxHandSize());
            o.put("landsPlayed", p.getNumLandThisTurn());
            o.putIf("local", gui.isLocalPlayer(p));
            o.putIf("hl", gui.isHighlighted(p));

            writeIds(o, "bf", p.getBattlefield());
            writeIds(o, "gy", p.getGraveyard());
            writeIds(o, "exile", p.getExile());
            writeIds(o, "cmd", p.getCommand());
            if (gui.isLocalPlayer(p)) {
                writeIds(o, "handCards", p.getHand());
            }

            final Json.Obj mana = o.obj("mana");
            for (int i = 0; i < MANA_COLORS.length; i++) {
                final int amount = p.getMana(MANA_COLORS[i]);
                if (amount > 0) {
                    mana.put(MANA_COLOR_NAMES[i], amount);
                }
            }
            mana.end();
            writeCounters(o, p.getCounters());
            o.end();
        }
        players.end();
    }

    private static void writeIds(final Json.Obj o, final String name, final Iterable<CardView> cards) {
        if (cards == null) {
            return;
        }
        final Json.Arr arr = o.arr(name);
        for (final CardView c : cards) {
            if (c != null) {
                arr.add(c.getId());
            }
        }
        arr.end();
    }

    private static void writeCards(final Json.Obj root, final List<String> changed, final List<Integer> gone) {
        final Json.Arr cards = root.arr("cards");
        for (final String fragment : changed) {
            cards.addRaw(fragment);
        }
        cards.end();
        if (!gone.isEmpty()) {
            final Json.Arr removed = root.arr("removed");
            for (final Integer id : gone) {
                removed.add(id);
            }
            removed.end();
        }
    }

    private void writeStack(final Json.Obj root, final GameView game) {
        final Json.Arr stack = root.arr("stack");
        if (game.getStack() != null) {
            for (final StackItemView si : game.getStack()) {
                final Json.Obj o = stack.obj();
                o.put("id", si.getId());
                o.put("text", si.getText());
                o.putIf("trigger", si.isTrigger());
                o.putIf("ability", si.isAbility());
                if (si.getSourceCard() != null) {
                    o.put("src", si.getSourceCard().getId());
                    o.put("srcName", si.getSourceCard().getCurrentState() == null
                            ? "" : si.getSourceCard().getCurrentState().getName());
                }
                if (si.getActivatingPlayer() != null) {
                    o.put("by", si.getActivatingPlayer().getId());
                }
                final Json.Arr targets = o.arr("targets");
                if (si.getTargetCards() != null) {
                    for (final CardView c : si.getTargetCards()) {
                        targets.add(c.getId());
                    }
                }
                targets.end();
                o.end();
            }
        }
        stack.end();
    }

    private static void writeCombat(final Json.Obj root, final CombatView combat) {
        final Json.Obj o = root.obj("combat");
        if (combat != null && combat.getNumAttackers() > 0) {
            final Json.Arr bands = o.arr("bands");
            for (final CardView attacker : combat.getAttackers()) {
                final Json.Obj band = bands.obj();
                band.put("attacker", attacker.getId());
                final GameEntityView defender = combat.getDefender(attacker);
                if (defender != null) {
                    band.put("defender", defender.getId());
                    band.put("defenderName", defender.getName());
                }
                final Json.Arr blockers = band.arr("blockers");
                if (combat.getBlockers(attacker) != null) {
                    for (final CardView b : combat.getBlockers(attacker)) {
                        blockers.add(b.getId());
                    }
                }
                blockers.end();
                final Json.Arr planned = band.arr("planned");
                if (combat.getPlannedBlockers(attacker) != null) {
                    for (final CardView b : combat.getPlannedBlockers(attacker)) {
                        planned.add(b.getId());
                    }
                }
                planned.end();
                band.end();
            }
            bands.end();
        }
        o.end();
    }

    private static void writeLog(final Json.Obj root, final GameView game) {
        final Json.Arr arr = root.arr("log");
        if (game.getGameLog() != null) {
            final List<GameLogEntry> entries = game.getGameLog().getLogEntries(null);
            // getLogEntries returns newest first; send the most recent LOG_LINES oldest-first.
            for (int i = Math.min(entries.size(), LOG_LINES) - 1; i >= 0; i--) {
                final GameLogEntry e = entries.get(i);
                if (e != null && e.message() != null) {
                    arr.add(e.message());
                }
            }
        }
        arr.end();
    }

    private static void writePrompt(final Json.Obj root, final Prompt prompt) {
        if (prompt == null) {
            return;
        }
        final Json.Obj o = root.obj("prompt");
        o.put("message", prompt.message == null ? "" : prompt.message);
        o.put("ok", prompt.okLabel == null ? "OK" : prompt.okLabel);
        o.put("cancel", prompt.cancelLabel == null ? "Cancel" : prompt.cancelLabel);
        o.put("okEnabled", prompt.okEnabled);
        o.put("cancelEnabled", prompt.cancelEnabled);
        if (prompt.card != null) {
            o.put("card", prompt.card.getId());
        }
        o.end();
    }

    /** The button and message strip the engine drives through {@code IGuiGame}. */
    static final class Prompt {
        volatile String message = "";
        volatile String okLabel = "OK";
        volatile String cancelLabel = "Cancel";
        volatile boolean okEnabled;
        volatile boolean cancelEnabled;
        volatile CardView card;
    }
}
