package forge.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import forge.deck.Deck;
import forge.deck.DeckgenUtil;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

/** Builds the two decks and hands the match to the engine. */
public final class MatchLauncher {

    private MatchLauncher() { }

    /** Constructed deck names the user has saved, for the start screen. */
    public static List<String> deckNames() {
        final List<String> names = new ArrayList<>();
        FModel.getDecks().getConstructed().forEach(deck -> names.add(deck.getName()));
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /**
     * Starts a constructed game against the AI.
     *
     * @param request the browser's start payload: {@code deck} and {@code opponentDeck},
     *                either a saved deck name or blank for a generated one
     * @return an error message, or {@code null} on success
     */
    public static String start(final WebSession session, final Map<String, Object> request) {
        final Deck playerDeck = resolveDeck(Json.str(request, "deck"), false);
        if (playerDeck == null) {
            return "Could not build a deck for you.";
        }
        final Deck aiDeck = resolveDeck(Json.str(request, "opponentDeck"), true);
        if (aiDeck == null) {
            return "Could not build a deck for the AI.";
        }

        final RegisteredPlayer human = new RegisteredPlayer(playerDeck);
        human.setPlayer(GamePlayerUtil.getGuiPlayer());
        final RegisteredPlayer ai = new RegisteredPlayer(aiDeck);
        ai.setPlayer(GamePlayerUtil.createAiPlayer());

        final List<RegisteredPlayer> players = List.of(human, ai);
        final HostedMatch match = GuiBase.getInterface().hostMatch();
        session.markFullResync();

        // The engine expects the match to be kicked off from the GUI thread, exactly as
        // the Swing client does from the EDT; it hands off to its own game thread.
        GuiBase.getInterface().invokeInEdtLater(
                () -> match.startMatch(GameType.Constructed, null, players, human, session.getGuiGame()));
        return null;
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
