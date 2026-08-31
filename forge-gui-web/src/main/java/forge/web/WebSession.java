package forge.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.interfaces.IGameController;

/**
 * One browser tab's worth of state: the socket it talks over, the GUI the engine drives,
 * and the request/reply plumbing between the two.
 *
 * <p>The engine's GUI contract is synchronous — {@code getChoices}, {@code confirm} and
 * friends block the game thread until the player answers. The browser is asynchronous, so
 * every blocking call parks on a {@link Pending} handoff and the socket reader hands the
 * answer back. If the socket drops while a call is parked we release it with a safe
 * default rather than leaving the game thread wedged forever.
 */
public class WebSession {

    /** Whatever can push text at the browser. Keeps the Netty types out of the game code. */
    public interface Transport {
        void send(String text);
        boolean isOpen();
    }

    /** Set {@code -Dforge.web.debug=true} to log every state push. */
    private static final boolean DEBUG = Boolean.getBoolean("forge.web.debug");

    /** How often the coalescing push loop looks for changes. */
    private static final long PUSH_INTERVAL_MS = 50;
    /** How long a blocked engine call waits for a reply before giving up on the player. */
    private static final long ANSWER_TIMEOUT_MINUTES = 30;
    /**
     * Phases the client stops at by default; everything else is auto-passed. Stored as
     * names rather than {@link PhaseType} values so constructing a session doesn't
     * initialise the engine's {@code PhaseType} enum (which needs {@code Localizer}, only
     * available after {@code FModel.initialize()}).
     */
    private static final Set<String> DEFAULT_STOPS = Set.of(
            "MAIN1",
            "COMBAT_DECLARE_ATTACKERS",
            "COMBAT_DECLARE_BLOCKERS",
            "MAIN2",
            "END_OF_TURN");

    private final WebGuiGame guiGame;
    private final StateSerializer serializer;
    private final StateSerializer.Prompt prompt = new StateSerializer.Prompt();

    /**
     * Every live browser connection. Any number of tabs may watch the same game; when a
     * dialog is up, whichever tab answers first wins (the second answer finds no pending
     * request and is ignored). Before this was a single field, a second tab silently
     * froze the first one — easy to hit when playing in a background tab.
     */
    private final Set<Transport> transports = ConcurrentHashMap.newKeySet();

    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicInteger nextRequestId = new AtomicInteger(1);
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AtomicBoolean needsFullState = new AtomicBoolean(true);
    private final AtomicBoolean reportedPushFailure = new AtomicBoolean();

    /**
     * Whether the last open transport leaving should release parked engine calls. True for
     * the solo session (a closed tab shouldn't wedge the game); false for a LAN seat, which
     * keeps its prompt parked so the game pauses until reconnect or expiry.
     */
    private volatile boolean abandonOnDisconnect = true;

    /** The last {@code ask} message sent, re-sent on reconnect so a paused dialog survives. */
    private volatile String pendingAskJson;
    private final AtomicBoolean resendAsk = new AtomicBoolean();

    private final ScheduledExecutorService pusher = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "Forge Web state push");
        t.setDaemon(true);
        return t;
    });

    private final Set<String> stopAtPhases = new java.util.HashSet<>(DEFAULT_STOPS);

    public WebSession() {
        guiGame = new WebGuiGame(this);
        serializer = new StateSerializer(guiGame);
        pusher.scheduleWithFixedDelay(this::pushIfDirty, PUSH_INTERVAL_MS, PUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public WebGuiGame getGuiGame() {
        return guiGame;
    }

    StateSerializer.Prompt prompt() {
        return prompt;
    }

    boolean stopsAtPhase(final PhaseType phase) {
        return phase == null || stopAtPhases.contains(phase.name());
    }

    // ------------------------------------------------------------- transport

    /** Registers a socket and gives it a full board. Safe to call for many sockets. */
    public void attach(final Transport transport) {
        transports.add(transport);
        System.out.println("Browser connected (" + transports.size() + " open)");
        // Never serialise live state from the Netty callback: just flag the resync and let
        // the push loop do the work on its own thread, then re-raise any parked prompt.
        resendAsk.set(true);
        markFullResync();
    }

    public void detach(final Transport transport) {
        transports.remove(transport);
        // Only release parked engine calls when the last transport goes — another open
        // transport can still answer a pending dialog — and only when this session wants
        // to abandon on disconnect (LAN seats pause instead; the coordinator decides).
        if (transports.isEmpty() && abandonOnDisconnect) {
            abandonPendingRequests();
        }
    }

    /** Releases every parked engine call so the game thread can unwind on disconnect. */
    public void abandonPendingRequests() {
        for (final Pending p : pending.values()) {
            p.cancel();
        }
        pending.clear();
    }

    public void setAbandonOnDisconnect(final boolean abandonOnDisconnect) {
        this.abandonOnDisconnect = abandonOnDisconnect;
    }

    /** Package-private for tests: true while the next push will be a full snapshot. */
    boolean needsFullState() {
        return needsFullState.get();
    }

    void send(final String json) {
        transports.removeIf(t -> !t.isOpen());
        for (final Transport t : transports) {
            t.send(json);
        }
    }

    void markDirty() {
        dirty.set(true);
    }

    void markFullResync() {
        needsFullState.set(true);
        serializer.reset();
        markDirty();
    }

    private void pushIfDirty() {
        if (transports.isEmpty()) {
            // Keep the dirty flag set: whatever changed still needs sending once a
            // browser is listening again, and nothing else will re-flag it.
            return;
        }
        if (!dirty.getAndSet(false)) {
            return;
        }
        try {
            final GameView game = guiGame.getGameView();
            final boolean full = needsFullState.getAndSet(false);
            final String json = serializer.build(game, prompt, full);
            if (json != null) {
                if (DEBUG) {
                    System.out.println("push " + json.length() + "B: "
                            + json.substring(0, Math.min(400, json.length())));
                }
                send(json);
            } else if (DEBUG) {
                System.out.println("push skipped, gameView=" + (game == null ? "null" : "present"));
            }
            // A reconnect needs the parked prompt re-raised even when the state snapshot
            // was skipped (e.g. no game view yet) — the client still needs the dialog.
            if (resendAsk.getAndSet(false)) {
                final String ask = pendingAskJson;
                if (ask != null) {
                    send(ask);
                }
            }
        } catch (final RuntimeException e) {
            // A state snapshot taken mid-mutation can trip over a collection being rebuilt.
            // Retry on the next tick rather than killing the push loop, and only report the
            // first failure so a persistent one doesn't flood the console every 50ms.
            if (reportedPushFailure.compareAndSet(false, true)) {
                System.err.println("Skipping a state push: " + e);
                e.printStackTrace();
            }
            needsFullState.set(true);
            dirty.set(true);
        }
    }

    // -------------------------------------------------------------- requests

    /** A parked engine call waiting on the browser. */
    private static final class Pending {
        private final SynchronousQueue<Object> handoff = new SynchronousQueue<>();
        private static final Object CANCELLED = new Object();

        void cancel() {
            handoff.offer(CANCELLED);
        }

        void answer(final Map<String, Object> reply) {
            handoff.offer(reply);
        }

        Map<String, Object> await() {
            try {
                final Object result = handoff.poll(ANSWER_TIMEOUT_MINUTES, TimeUnit.MINUTES);
                if (result == CANCELLED || result == null) {
                    return null;
                }
                @SuppressWarnings("unchecked")
                final Map<String, Object> reply = (Map<String, Object>) result;
                return reply;
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /**
     * Sends a question to the browser and blocks until it answers.
     *
     * @param body a JSON object fragment, already serialised, minus the enclosing braces
     * @return the reply object, or {@code null} if the player went away
     */
    Map<String, Object> ask(final String kind, final String body) {
        final int rid = nextRequestId.getAndIncrement();
        final Pending waiter = new Pending();
        pending.put(rid, waiter);
        try {
            final StringBuilder sb = new StringBuilder(256);
            sb.append("{\"t\":\"ask\",\"rid\":").append(rid).append(",\"kind\":");
            Json.escape(sb, kind);
            if (body != null && !body.isEmpty()) {
                sb.append(',').append(body);
            }
            sb.append('}');
            pendingAskJson = sb.toString();
            // Flush the board first, so the dialog is answered against what's on screen.
            pushIfDirty();
            send(sb.toString());
            return waiter.await();
        } finally {
            pending.remove(rid);
            pendingAskJson = null;
        }
    }

    // --------------------------------------------------------- inbound events

    /** Entry point for everything the browser sends over the socket. */
    public void onMessage(final String text) {
        final Map<String, Object> msg = Json.parseObject(text);
        final String type = Json.str(msg, "t");
        if (type == null) {
            return;
        }
        switch (type) {
            case "answer" -> {
                final Pending waiter = pending.remove(Json.integer(msg, "rid", -1));
                if (waiter != null) {
                    waiter.answer(msg);
                }
            }
            case "action" -> handleAction(msg);
            case "resync" -> markFullResync();
            case "stops" -> setStops(msg);
            default -> { }
        }
    }

    private void setStops(final Map<String, Object> msg) {
        final Object raw = msg.get("phases");
        if (!(raw instanceof List<?> list)) {
            return;
        }
        stopAtPhases.clear();
        for (final Object o : list) {
            final String name = String.valueOf(o);
            try {
                // Validate against the engine's enum, but store the name so the default
                // set doesn't need the enum loaded at construction time.
                stopAtPhases.add(PhaseType.valueOf(name).name());
            } catch (final IllegalArgumentException ignored) {
                // client sent a phase we don't know; ignore rather than reject the batch
            }
        }
    }

    /**
     * Player input that isn't answering a specific question: clicking the board, the
     * priority buttons, the mana pool. These map onto {@link IGameController}, the same
     * interface the Swing client drives.
     */
    private void handleAction(final Map<String, Object> msg) {
        final IGameController controller = guiGame.getGameController();
        if (controller == null) {
            return;
        }
        final String action = Json.str(msg, "action");
        if (action == null) {
            return;
        }
        // Engine calls made from here can block, so never run them on the socket thread.
        runOffSocket(() -> {
            switch (action) {
                case "ok" -> controller.selectButtonOk();
                case "cancel" -> controller.selectButtonCancel();
                case "card" -> {
                    final CardView card = serializer.card(Json.integer(msg, "id", -1));
                    if (card != null) {
                        controller.selectCard(card, Collections.emptyList(), null);
                    }
                }
                case "player" -> {
                    final PlayerView player = serializer.player(Json.integer(msg, "id", -1));
                    if (player != null) {
                        controller.selectPlayer(player, null);
                    }
                }
                case "mana" -> {
                    final String color = Json.str(msg, "color");
                    if (color != null && !color.isEmpty()) {
                        controller.useMana(manaColor(color.charAt(0)));
                    }
                }
                case "undo" -> controller.undoLastAction();
                case "alphaStrike" -> controller.alphaStrike();
                case "concede" -> controller.concede();
                case "reorderHand" -> {
                    final CardView card = serializer.card(Json.integer(msg, "id", -1));
                    if (card != null) {
                        controller.reorderHand(card, Json.integer(msg, "index", 0));
                    }
                }
                default -> { }
            }
        });
    }

    private static byte manaColor(final char c) {
        return switch (Character.toUpperCase(c)) {
            case 'W' -> forge.card.MagicColor.WHITE;
            case 'U' -> forge.card.MagicColor.BLUE;
            case 'B' -> forge.card.MagicColor.BLACK;
            case 'R' -> forge.card.MagicColor.RED;
            case 'G' -> forge.card.MagicColor.GREEN;
            default -> forge.card.MagicColor.COLORLESS;
        };
    }

    private void runOffSocket(final Runnable r) {
        final Thread t = new Thread(() -> {
            try {
                r.run();
            } catch (final RuntimeException e) {
                System.err.println("Error handling player action: " + e);
                e.printStackTrace();
            }
        }, "Forge Web action");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- prompts
    // Everything below turns a blocking engine question into a dialog message and back.

    /** Generic pick-from-a-list. Returns the chosen indices into {@code options}. */
    List<Integer> askChoice(final String message, final int min, final int max,
            final List<Choice> options, final List<Integer> preselected, final boolean ordered) {
        final StringBuilder sb = new StringBuilder(512);
        sb.append("\"message\":");
        Json.escape(sb, message == null ? "" : message);
        sb.append(",\"min\":").append(min).append(",\"max\":").append(max);
        sb.append(",\"ordered\":").append(ordered);
        if (preselected != null && !preselected.isEmpty()) {
            sb.append(",\"selected\":[");
            for (int i = 0; i < preselected.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(preselected.get(i));
            }
            sb.append(']');
        }
        sb.append(",\"options\":[");
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            options.get(i).write(sb, i);
        }
        sb.append(']');

        final Map<String, Object> reply = ask("choice", sb.toString());
        if (reply == null) {
            return List.of();
        }
        return Json.intList(reply, "picked");
    }

    boolean askConfirm(final String message, final String title, final List<String> options, final boolean defaultYes) {
        final StringBuilder sb = new StringBuilder(256);
        sb.append("\"message\":");
        Json.escape(sb, message == null ? "" : message);
        sb.append(",\"title\":");
        Json.escape(sb, title == null ? "" : title);
        sb.append(",\"defaultYes\":").append(defaultYes);
        sb.append(",\"options\":[");
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Json.escape(sb, options.get(i));
        }
        sb.append(']');

        final Map<String, Object> reply = ask("confirm", sb.toString());
        if (reply == null) {
            return defaultYes;
        }
        return Json.integer(reply, "picked", defaultYes ? 0 : 1) == 0;
    }

    int showOptionDialog(final String message, final String title, final List<String> options, final int defaultOption) {
        final StringBuilder sb = new StringBuilder(256);
        sb.append("\"message\":");
        Json.escape(sb, message == null ? "" : message);
        sb.append(",\"title\":");
        Json.escape(sb, title == null ? "" : title);
        sb.append(",\"default\":").append(defaultOption);
        sb.append(",\"options\":[");
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Json.escape(sb, options.get(i));
        }
        sb.append(']');

        final Map<String, Object> reply = ask("option", sb.toString());
        return reply == null ? defaultOption : Json.integer(reply, "picked", defaultOption);
    }

    String showInputDialog(final String message, final String title, final String initialInput,
            final List<String> inputOptions, final boolean isNumeric) {
        final StringBuilder sb = new StringBuilder(256);
        sb.append("\"message\":");
        Json.escape(sb, message == null ? "" : message);
        sb.append(",\"title\":");
        Json.escape(sb, title == null ? "" : title);
        sb.append(",\"numeric\":").append(isNumeric);
        sb.append(",\"initial\":");
        Json.escape(sb, initialInput == null ? "" : initialInput);
        if (inputOptions != null && !inputOptions.isEmpty()) {
            sb.append(",\"options\":[");
            for (int i = 0; i < inputOptions.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                Json.escape(sb, inputOptions.get(i));
            }
            sb.append(']');
        }

        final Map<String, Object> reply = ask("input", sb.toString());
        return reply == null ? null : Json.str(reply, "text");
    }

    /** Splits {@code amount} across {@code targets}. Returns index to amount. */
    Map<Integer, Integer> askAmounts(final String title, final List<Choice> targets, final int amount,
            final boolean atLeastOne, final String amountLabel, final boolean maySkip) {
        final StringBuilder sb = new StringBuilder(512);
        sb.append("\"message\":");
        Json.escape(sb, title == null ? "" : title);
        sb.append(",\"amount\":").append(amount);
        sb.append(",\"atLeastOne\":").append(atLeastOne);
        sb.append(",\"maySkip\":").append(maySkip);
        sb.append(",\"label\":");
        Json.escape(sb, amountLabel == null ? "" : amountLabel);
        sb.append(",\"options\":[");
        for (int i = 0; i < targets.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            targets.get(i).write(sb, i);
        }
        sb.append(']');

        final Map<String, Object> reply = ask("amounts", sb.toString());
        final Map<Integer, Integer> result = new LinkedHashMap<>();
        if (reply == null) {
            return result;
        }
        if (reply.get("amounts") instanceof Map<?, ?> map) {
            for (final Map.Entry<?, ?> e : map.entrySet()) {
                try {
                    final int index = Integer.parseInt(String.valueOf(e.getKey()));
                    final int value = e.getValue() instanceof Number n
                            ? n.intValue() : Integer.parseInt(String.valueOf(e.getValue()));
                    result.put(index, value);
                } catch (final NumberFormatException ignored) {
                    // ignore malformed entries; the caller validates the total
                }
            }
        }
        return result;
    }

    /** One selectable item in a dialog, with enough detail for the client to draw a card. */
    static final class Choice {
        private final String label;
        private final CardView card;

        Choice(final String label, final CardView card) {
            this.label = label;
            this.card = card;
        }

        static Choice of(final Object value, final String label) {
            if (value instanceof CardView cv) {
                return new Choice(label, cv);
            }
            if (value instanceof SpellAbilityView sa) {
                return new Choice(label, sa.getHostCard());
            }
            if (value instanceof GameEntityView) {
                return new Choice(label, null);
            }
            return new Choice(label, null);
        }

        void write(final StringBuilder sb, final int index) {
            sb.append("{\"i\":").append(index).append(",\"label\":");
            Json.escape(sb, label == null ? "" : label);
            if (card != null) {
                sb.append(",\"card\":").append(card.getId());
                final CardView.CardStateView state = card.getCurrentState();
                if (state != null) {
                    sb.append(",\"img\":");
                    Json.escape(sb, state.getImageKey());
                    sb.append(",\"name\":");
                    Json.escape(sb, state.getName());
                }
            }
            sb.append('}');
        }
    }

    static List<Choice> choices(final Iterable<?> values, final java.util.function.Function<Object, String> display) {
        final List<Choice> list = new ArrayList<>();
        for (final Object value : values) {
            list.add(Choice.of(value, display.apply(value)));
        }
        return list;
    }

    public void shutdown() {
        abandonPendingRequests();
        pusher.shutdownNow();
    }
}
