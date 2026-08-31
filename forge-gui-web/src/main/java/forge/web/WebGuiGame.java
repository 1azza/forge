package forge.web;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.game.GameEntityView;
import forge.game.GameState;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.AbstractGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.player.PlayerZoneUpdate;
import forge.player.PlayerZoneUpdates;
import forge.trackable.TrackableCollection;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;

/**
 * The engine's view of the browser.
 *
 * <p>Update calls just flag the session dirty — the push loop coalesces them into one
 * message per frame, which is the difference between a smooth board and one that hitches
 * every time a trigger resolves. Calls that need an answer block on the session.
 */
public class WebGuiGame extends AbstractGuiGame {

    private final WebSession session;

    WebGuiGame(final WebSession session) {
        this.session = session;
    }

    // --------------------------------------------------------------- updates

    @Override
    protected void updateCurrentPlayer(final PlayerView player) {
        // The engine calls this on every priority change; the field only feeds the "me"
        // marker, which is constant for the session, so a normal diff push suffices.
        session.markDirty();
    }

    @Override
    public void openView(final TrackableCollection<PlayerView> myPlayers) {
        if (getGameView() != null && getGameView().getGameLog() == null) {
            getGameView().initGameLog();
        }
        session.markFullResync();
    }

    @Override
    public void afterGameEnd() {
        super.afterGameEnd();
        session.markDirty();
    }

    @Override
    public void showCombat() {
        session.markDirty();
    }

    @Override
    public void updateCards(final Iterable<CardView> cards) {
        session.markDirty();
    }

    @Override
    public void updateZones(final Iterable<PlayerZoneUpdate> zonesToUpdate) {
        session.markDirty();
    }

    @Override
    public void updateManaPool(final Iterable<PlayerView> manaPoolUpdate) {
        session.markDirty();
    }

    @Override
    public void updateLives(final Iterable<PlayerView> livesUpdate) {
        session.markDirty();
    }

    @Override
    public void updateShards(final Iterable<PlayerView> shardsUpdate) {
        session.markDirty();
    }

    @Override
    public void updateStack() {
        session.markDirty();
    }

    @Override
    public void updatePhase(final boolean saveState) {
        session.markDirty();
    }

    @Override
    public void updateTurn(final PlayerView player) {
        session.markDirty();
    }

    @Override
    public void updatePlayerControl() {
        session.markDirty();
    }

    @Override
    public void refreshField() {
        session.markDirty();
    }

    @Override
    public void handleGameEvent(final GameEvent event) {
        super.handleGameEvent(event);
        session.markDirty();
    }

    @Override
    public void setSelectables(final Iterable<CardView> cards, final int min, final int max) {
        super.setSelectables(cards, min, max);
        session.markDirty();
    }

    @Override
    public void clearSelectables() {
        super.clearSelectables();
        session.markDirty();
    }

    @Override
    public void setWeaklySelectable(final Iterable<CardView> cards) {
        super.setWeaklySelectable(cards);
        session.markDirty();
    }

    @Override
    public void clearWeaklySelectable() {
        super.clearWeaklySelectable();
        session.markDirty();
    }

    @Override
    public void showPromptMessage(final PlayerView playerView, final String message, final CardView card) {
        session.prompt().message = message;
        session.prompt().card = card;
        session.markDirty();
    }

    @Override
    public void showPromptMessageNoCancel(final PlayerView playerView, final String message) {
        session.prompt().message = message;
        session.prompt().cancelEnabled = false;
        session.markDirty();
    }

    @Override
    public void updateButtons(final PlayerView owner, final String label1, final String label2,
            final boolean enable1, final boolean enable2, final boolean focus1) {
        final StateSerializer.Prompt prompt = session.prompt();
        prompt.okLabel = label1;
        prompt.cancelLabel = label2;
        prompt.okEnabled = enable1;
        prompt.cancelEnabled = enable2;
        session.markDirty();
    }

    @Override
    public void flashIncorrectAction() {
        session.send("{\"t\":\"flash\"}");
    }

    @Override
    public void alertUser() {
        session.send("{\"t\":\"alert\"}");
    }

    @Override
    public void enableOverlay() {
    }

    @Override
    public void disableOverlay() {
    }

    @Override
    public void finishGame() {
        session.markDirty();
        session.send("{\"t\":\"gameOver\"}");
    }

    @Override
    public void showManaPool(final PlayerView player) {
        session.markDirty();
    }

    @Override
    public void hideManaPool(final PlayerView player) {
        session.markDirty();
    }

    @Override
    public void setPanelSelection(final CardView hostCard) {
    }

    @Override
    public void setCard(final CardView card) {
    }

    @Override
    public void setPlayerAvatar(final LobbyPlayer player, final IHasIcon ihi) {
    }

    @Override
    public GameState getGamestate() {
        return null;
    }

    /**
     * The browser renders every zone at once, so there is nothing to open, temporarily
     * reveal or restore. Returning empty keeps the engine's bookkeeping consistent.
     */
    @Override
    public PlayerZoneUpdates openZones(final PlayerView controller, final Collection<ZoneType> zones,
            final Map<PlayerView, Object> players, final boolean backupLastZones) {
        return new PlayerZoneUpdates();
    }

    @Override
    public void restoreOldZones(final PlayerView playerView, final PlayerZoneUpdates playerZoneUpdates) {
    }

    @Override
    public Iterable<PlayerZoneUpdate> tempShowZones(final PlayerView controller, final Iterable<PlayerZoneUpdate> zonesToUpdate) {
        return Collections.emptyList();
    }

    @Override
    public void hideZones(final PlayerView controller, final Iterable<PlayerZoneUpdate> zonesToUpdate) {
    }

    @Override
    public boolean isUiSetToSkipPhase(final PlayerView playerTurn, final PhaseType phase) {
        return !session.stopsAtPhase(phase);
    }

    // ---------------------------------------------------------------- prompts

    @Override
    public SpellAbilityView getAbilityToPlay(final CardView hostCard, final List<SpellAbilityView> abilities,
            final ITriggerEvent triggerEvent) {
        if (abilities.isEmpty()) {
            return null;
        }
        if (abilities.size() == 1) {
            return abilities.get(0);
        }
        final List<WebSession.Choice> options = WebSession.choices(abilities,
                o -> ((SpellAbilityView) o).getDescription());
        final List<Integer> picked = session.askChoice("Choose an ability", 1, 1, options, null, false);
        return picked.isEmpty() ? null : abilities.get(clamp(picked.get(0), abilities.size()));
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(final CardView attacker, final List<CardView> blockers,
            final int damage, final GameEntityView defender, final boolean overrideOrder, final boolean maySkip) {
        if (damage <= 0 || blockers.isEmpty()) {
            return Collections.emptyMap();
        }
        // Same shortcut the Swing client takes: with a single ordering and a blocker that
        // soaks everything, there's nothing for the player to decide.
        final CardView firstBlocker = blockers.get(0);
        if (!overrideOrder && !attacker.getCurrentState().hasDeathtouch() && firstBlocker.getLethalDamage() >= damage) {
            return Map.of(firstBlocker, damage);
        }

        final List<WebSession.Choice> options = WebSession.choices(blockers,
                o -> ((CardView) o).getCurrentState().getName());
        final String title = "Assign " + damage + " damage from " + attacker.getCurrentState().getName();
        final Map<Integer, Integer> assigned = session.askAmounts(title, options, damage, false, "damage", maySkip);

        final Map<CardView, Integer> result = new LinkedHashMap<>();
        if (assigned.isEmpty()) {
            result.put(firstBlocker, damage);
            return result;
        }
        for (final Map.Entry<Integer, Integer> e : assigned.entrySet()) {
            final int index = e.getKey();
            if (index >= 0 && index < blockers.size() && e.getValue() > 0) {
                result.put(blockers.get(index), e.getValue());
            }
        }
        return result;
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(final CardView effectSource, final Map<Object, Integer> target,
            final int amount, final boolean atLeastOne, final String amountLabel) {
        if (amount <= 0) {
            return Collections.emptyMap();
        }
        final List<Object> keys = new ArrayList<>(target.keySet());
        final List<WebSession.Choice> options = WebSession.choices(keys, WebGuiGame::label);
        final String title = (effectSource == null ? "" : effectSource.getCurrentState().getName() + " — ")
                + "Assign " + amount + " " + (amountLabel == null ? "" : amountLabel);
        final Map<Integer, Integer> assigned = session.askAmounts(title, options, amount, atLeastOne, amountLabel, false);

        final Map<Object, Integer> result = new LinkedHashMap<>();
        for (final Map.Entry<Integer, Integer> e : assigned.entrySet()) {
            final int index = e.getKey();
            if (index >= 0 && index < keys.size() && e.getValue() > 0) {
                result.put(keys.get(index), e.getValue());
            }
        }
        if (result.isEmpty() && !keys.isEmpty()) {
            result.put(keys.get(0), amount);
        }
        return result;
    }

    @Override
    public void message(final String message, final String title) {
        final StringBuilder sb = new StringBuilder(128);
        sb.append("{\"t\":\"toast\",\"title\":");
        Json.escape(sb, title == null ? "" : title);
        sb.append(",\"message\":");
        Json.escape(sb, message == null ? "" : message);
        sb.append('}');
        session.send(sb.toString());
    }

    @Override
    public void showErrorDialog(final String message, final String title) {
        final StringBuilder sb = new StringBuilder(128);
        sb.append("{\"t\":\"toast\",\"error\":true,\"title\":");
        Json.escape(sb, title == null ? "Error" : title);
        sb.append(",\"message\":");
        Json.escape(sb, message == null ? "" : message);
        sb.append('}');
        session.send(sb.toString());
    }

    @Override
    public boolean showConfirmDialog(final String message, final String title, final String yesButtonText,
            final String noButtonText, final boolean defaultYes) {
        return session.askConfirm(message, title, List.of(yesButtonText, noButtonText), defaultYes);
    }

    @Override
    public int showOptionDialog(final String message, final String title, final FSkinProp icon,
            final List<String> options, final int defaultOption) {
        return session.showOptionDialog(message, title, options, defaultOption);
    }

    @Override
    public String showInputDialog(final String message, final String title, final FSkinProp icon,
            final String initialInput, final List<String> inputOptions, final boolean isNumeric) {
        return session.showInputDialog(message, title, initialInput, inputOptions, isNumeric);
    }

    @Override
    public boolean confirm(final CardView c, final String question, final boolean defaultIsYes, final List<String> options) {
        final String title = c == null ? "Forge" : c.getCurrentState().getName();
        return session.askConfirm(question, title, options, defaultIsYes);
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max, final List<T> choices,
            final List<T> selected, final FSerializableFunction<T, String> display) {
        if (choices == null || choices.isEmpty()) {
            return new ArrayList<>();
        }
        // min/max of -1 is the engine's "just show these to the player" signal.
        final boolean revealOnly = min < 0 && max < 0;
        final List<WebSession.Choice> options = new ArrayList<>(choices.size());
        for (final T choice : choices) {
            options.add(WebSession.Choice.of(choice, display == null ? label(choice) : display.apply(choice)));
        }
        final List<Integer> preselected = new ArrayList<>();
        if (selected != null) {
            for (final T s : selected) {
                final int index = choices.indexOf(s);
                if (index >= 0) {
                    preselected.add(index);
                }
            }
        }

        final List<Integer> picked = session.askChoice(message,
                revealOnly ? 0 : min, revealOnly ? 0 : max, options, preselected, false);

        final List<T> result = new ArrayList<>();
        if (revealOnly) {
            return result;
        }
        for (final Integer index : picked) {
            if (index != null && index >= 0 && index < choices.size()) {
                result.add(choices.get(index));
            }
        }
        // The engine treats a too-small answer as a protocol error, so fall back to the
        // first legal selection if the player closed the dialog without choosing.
        while (result.size() < min && result.size() < choices.size()) {
            for (final T choice : choices) {
                if (!result.contains(choice)) {
                    result.add(choice);
                    break;
                }
            }
        }
        return result;
    }

    @Override
    public <T> OrderResult<T> order(final String title, final String top, final int remainingObjectsMin,
            final int remainingObjectsMax, final List<T> sourceChoices, final List<T> destChoices,
            final CardView referenceCard, final boolean sideboardingMode, final boolean showRememberCheckbox) {
        final List<T> pool = new ArrayList<>(sourceChoices);
        final List<WebSession.Choice> options = new ArrayList<>(pool.size());
        for (final T choice : pool) {
            options.add(WebSession.Choice.of(choice, label(choice)));
        }

        // remainingObjectsMin/Max count what may be left behind, so the number of items the
        // player must actually place is the complement.
        final int mustPlaceMin = remainingObjectsMax >= 0 ? Math.max(0, pool.size() - remainingObjectsMax) : 0;
        final int mustPlaceMax = remainingObjectsMin >= 0 ? Math.max(0, pool.size() - remainingObjectsMin) : pool.size();

        final List<Integer> picked = session.askChoice(
                title + (top == null || top.isEmpty() ? "" : " — " + top),
                mustPlaceMin, mustPlaceMax, options, null, true);

        final List<T> ordered = new ArrayList<>();
        if (destChoices != null) {
            ordered.addAll(destChoices);
        }
        for (final Integer index : picked) {
            if (index != null && index >= 0 && index < pool.size()) {
                ordered.add(pool.get(index));
            }
        }
        for (final T choice : pool) {
            if (!ordered.contains(choice)) {
                ordered.add(choice);
            }
        }
        return new OrderResult<>(ordered, false);
    }

    /**
     * Sideboarding is not exposed in the web client yet, so keep the main deck as it is
     * rather than blocking the match on a screen that doesn't exist.
     */
    @Override
    public List<PaperCard> sideboard(final CardPool sideboard, final CardPool main, final String message) {
        return null;
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(final String title, final List<? extends GameEntityView> optionList,
            final DelayedReveal delayedReveal, final boolean isOptional) {
        if (optionList == null || optionList.isEmpty()) {
            return null;
        }
        final List<WebSession.Choice> options = new ArrayList<>(optionList.size());
        for (final GameEntityView entity : optionList) {
            options.add(WebSession.Choice.of(entity, label(entity)));
        }
        final List<Integer> picked = session.askChoice(title, isOptional ? 0 : 1, 1, options, null, false);
        if (picked.isEmpty()) {
            return isOptional ? null : optionList.get(0);
        }
        return optionList.get(clamp(picked.get(0), optionList.size()));
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(final String title, final List<? extends GameEntityView> optionList,
            final int min, final int max, final DelayedReveal delayedReveal) {
        final List<GameEntityView> result = new ArrayList<>();
        if (optionList == null || optionList.isEmpty()) {
            return result;
        }
        final List<WebSession.Choice> options = new ArrayList<>(optionList.size());
        for (final GameEntityView entity : optionList) {
            options.add(WebSession.Choice.of(entity, label(entity)));
        }
        final List<Integer> picked = session.askChoice(title, min, max, options, null, false);
        for (final Integer index : picked) {
            if (index != null && index >= 0 && index < optionList.size()) {
                result.add(optionList.get(index));
            }
        }
        while (result.size() < min && result.size() < optionList.size()) {
            for (final GameEntityView entity : optionList) {
                if (!result.contains(entity)) {
                    result.add(entity);
                    break;
                }
            }
        }
        return result;
    }

    @Override
    public List<CardView> manipulateCardList(final String title, final Iterable<CardView> cards,
            final Iterable<CardView> manipulable, final boolean toTop, final boolean toBottom, final boolean toAnywhere) {
        final List<CardView> result = new ArrayList<>();
        for (final CardView card : cards) {
            result.add(card);
        }
        return result;
    }

    // ----------------------------------------------------------------- helpers

    private static int clamp(final int index, final int size) {
        return index < 0 ? 0 : Math.min(index, size - 1);
    }

    private static String label(final Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof CardView card) {
            return card.getCurrentState() == null ? card.toString() : card.getCurrentState().getName();
        }
        if (value instanceof SpellAbilityView sa) {
            return sa.getDescription();
        }
        if (value instanceof GameEntityView entity) {
            return entity.getName();
        }
        return String.valueOf(value);
    }
}
