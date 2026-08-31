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
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.player.PlayerControllerHuman;

/** Builds the two decks and hands the match to the engine. */
public final class MatchLauncher {

    /** How long to wait for a conceded game's thread to unwind before starting the next. */
    private static final long TEARDOWN_TIMEOUT_MS = 3000;
    private static final long TEARDOWN_POLL_MS = 25;

    /**
     * The match currently owned by this process. One session per server, so a second
     * start has to take the first one down rather than leave two games running against
     * the same GUI.
     */
    private static volatile HostedMatch current;

    private MatchLauncher() { }

    /** Constructed deck names the user has saved, for the start screen. */
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
    public static synchronized String start(final WebSession session, final Map<String, Object> request) {
        final Deck playerDeck = resolveDeck(Json.str(request, "deck"), false);
        if (playerDeck == null) {
            return "Could not build a deck for you.";
        }
        final Deck aiDeck = resolveDeck(Json.str(request, "opponentDeck"), true);
        if (aiDeck == null) {
            return "Could not build a deck for the AI.";
        }

        if (!endCurrentMatch(session)) {
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
     * Concedes and disposes the running match, if any.
     *
     * <p>A game thread parked on player input won't exit on its own. Conceding is what
     * releases it — {@link PlayerControllerHuman#concede()} trips the input queues once
     * the game is over — so we concede, wait for the game to actually finish, and only
     * then clear the GUI's per-match state.
     *
     * @return true once nothing is left running
     */
    private static boolean endCurrentMatch(final WebSession session) {
        final HostedMatch match = current;
        current = null;
        if (match == null) {
            return true;
        }

        // Release anything blocked on a dialog first, or its thread will never reach the
        // point where conceding can take effect.
        session.abandonPendingRequests();

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
            session.getGuiGame().resetForNewMatch();
        });
        // Deliberately leave the game view in place. The finished board is harmless for
        // the few milliseconds before startMatch swaps it, whereas clearing it makes the
        // old game thread's trailing awaitNextInput dereference a null view.
        return true;
    }

    private static boolean awaitGameOver(final HostedMatch match) {
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

    private static Deck resolveDeck(final String name, final boolean forAi) {
        if (name != null && !name.isBlank() && !"random".equalsIgnoreCase(name)) {
            final Deck saved = FModel.getDecks().getConstructed().get(name);
            if (saved != null) {
                return saved;
            }
        }
        return DeckgenUtil.getRandomColorDeck(forAi);
    }
}
