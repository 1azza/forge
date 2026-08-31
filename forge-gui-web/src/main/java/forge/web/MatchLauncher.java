package forge.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import forge.deck.Deck;
import forge.deck.DeckgenUtil;
import forge.game.Game;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.player.PlayerControllerHuman;

/**
 * Builds decks and hands matches to the engine.
 *
 * <p>One instance owns the process's single active {@link HostedMatch}. The room
 * coordinator (see {@link LanRoomManager}) is the only caller, so a solo start and a LAN
 * start can never race over the same match: starting one always tears the other down first.
 */
public class MatchLauncher {

    /** How long to wait for a conceded game's thread to unwind before starting the next. */
    private static final long TEARDOWN_TIMEOUT_MS = 3000;
    private static final long TEARDOWN_POLL_MS = 25;

    /** The match currently owned by this launcher. One per server process. */
    private volatile HostedMatch current;

    /** Constructed deck names the user has saved, for the start/lobby screens. */
    public static List<String> deckNames() {
        final List<String> names = new ArrayList<>();
        FModel.getDecks().getConstructed().forEach(deck -> names.add(deck.getName()));
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /**
     * Starts a constructed game against the AI, ending whatever was running first.
     *
     * @param request the browser's start payload: {@code deck} and {@code opponentDeck},
     *                either a saved deck name or blank for a generated one
     * @return an error message, or {@code null} on success
     */
    public synchronized String startSolo(final WebSession session, final Map<String, Object> request) {
        final Deck playerDeck = resolveDeck(Json.str(request, "deck"), false);
        if (playerDeck == null) {
            return "Could not build a deck for you.";
        }
        final Deck aiDeck = resolveDeck(Json.str(request, "opponentDeck"), true);
        if (aiDeck == null) {
            return "Could not build a deck for the AI.";
        }

        if (!endActiveMatch(List.of(session))) {
            return "The previous game is still shutting down. Try again in a moment.";
        }

        final RegisteredPlayer human = new RegisteredPlayer(playerDeck);
        human.setPlayer(GamePlayerUtil.getGuiPlayer());
        final RegisteredPlayer ai = new RegisteredPlayer(aiDeck);
        ai.setPlayer(GamePlayerUtil.createAiPlayer());

        final List<RegisteredPlayer> players = List.of(human, ai);
        final HostedMatch match = GuiBase.getInterface().hostMatch();
        current = match;
        session.markFullResync();

        // The engine expects the match to be kicked off from the GUI thread, exactly as
        // the Swing client does from the EDT; it hands off to its own game thread.
        GuiBase.getInterface().invokeInEdtLater(
                () -> match.startMatch(GameType.Constructed, null, players, human, session.getGuiGame()));
        return null;
    }

    /**
     * Starts a constructed 1v1 between the room's two human seats. Each {@link RegisteredPlayer}
     * maps to its own {@link IGuiGame}, so the engine drives two independent controllers over
     * one {@link HostedMatch}.
     *
     * @param onGameOver invoked on the game thread once the game finishes (natural end or
     *                   teardown), so the room can leave the PLAYING state
     * @return an error message, or {@code null} on success
     */
    public synchronized String startLan(final LanRoom room, final List<WebSession> sessions,
            final Runnable onGameOver) {
        final Deck hostDeck = resolveDeck(room.host().deckName(), false);
        if (hostDeck == null) {
            return "Could not build a deck for the host.";
        }
        final Deck opponentDeck = resolveDeck(room.opponent().deckName(), false);
        if (opponentDeck == null) {
            return "Could not build a deck for the opponent.";
        }

        if (!endActiveMatch(sessions)) {
            return "The previous game is still shutting down. Try again in a moment.";
        }

        final RegisteredPlayer host = new RegisteredPlayer(hostDeck);
        host.setPlayer(GamePlayerUtil.getGuiPlayer(room.host().displayName(), -1, -1, false));
        host.setStartingLife(room.startingLife());
        final RegisteredPlayer opponent = new RegisteredPlayer(opponentDeck);
        opponent.setPlayer(GamePlayerUtil.getGuiPlayer(room.opponent().displayName(), -1, -1, false));
        opponent.setStartingLife(room.startingLife());

        final List<RegisteredPlayer> players = List.of(host, opponent);
        final Map<RegisteredPlayer, IGuiGame> guis = Map.of(
                host, room.host().session.getGuiGame(),
                opponent, room.opponent().session.getGuiGame());

        final HostedMatch match = GuiBase.getInterface().hostMatch();
        if (onGameOver != null) {
            match.setEndGameHook(onGameOver);
        }
        current = match;
        for (final WebSession session : sessions) {
            session.markFullResync();
        }

        GuiBase.getInterface().invokeInEdtLater(
                () -> match.startMatch(GameType.Constructed, null, players, guis));
        return null;
    }

    /**
     * Concedes and disposes the running match, if any.
     *
     * <p>A game thread parked on player input won't exit on its own. Conceding is what
     * releases it — {@link PlayerControllerHuman#concede()} trips the input queues once
     * the game is over — so we cancel any parked waits, concede, wait for the game to
     * actually finish, and only then clear each GUI's per-match state.
     *
     * @return true once nothing is left running
     */
    public synchronized boolean endActiveMatch(final List<WebSession> sessions) {
        final HostedMatch match = current;
        current = null;
        if (match == null) {
            return true;
        }

        // Release anything blocked on a dialog first, or its thread will never reach the
        // point where conceding can take effect.
        for (final WebSession session : sessions) {
            session.abandonPendingRequests();
        }

        final Game game = match.getGame();
        if (game != null && !game.isGameOver()) {
            for (final PlayerControllerHuman controller : match.getHumanControllers()) {
                try {
                    controller.concede();
                } catch (final RuntimeException e) {
                    System.err.println("Error conceding the previous game: " + e);
                }
            }
        }

        if (!awaitGameOver(match)) {
            return false;
        }

        GuiBase.getInterface().invokeInEdtAndWait(() -> {
            match.endCurrentGame();
            // Drops the previous match's controllers and current player. Without this the
            // stale PlayerViews survive and the next match never sets a current player.
            for (final WebSession session : sessions) {
                session.getGuiGame().resetForNewMatch();
            }
        });
        // Deliberately leave the game view in place. The finished board is harmless for
        // the few milliseconds before startMatch swaps it, whereas clearing it makes the
        // old game thread's trailing awaitNextInput dereference a null view.
        return true;
    }

    private boolean awaitGameOver(final HostedMatch match) {
        final long deadline = System.currentTimeMillis() + TEARDOWN_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            final Game game = match.getGame();
            if (game == null || game.isGameOver()) {
                return true;
            }
            try {
                Thread.sleep(TEARDOWN_POLL_MS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    static Deck resolveDeck(final String name, final boolean forAi) {
        if (name != null && !name.isBlank() && !"random".equalsIgnoreCase(name)) {
            final Deck saved = FModel.getDecks().getConstructed().get(name);
            if (saved != null) {
                return saved;
            }
        }
        return DeckgenUtil.getRandomColorDeck(forAi);
    }
}
