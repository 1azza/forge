package forge.web;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates the single LAN room plus the solo session, and owns the process's one active
 * match (via {@link MatchLauncher}).
 *
 * <p>The first client to claim gets the host seat, the second the opponent seat. Every HTTP
 * and WebSocket operation is authenticated against the claimed seat's capability token; the
 * room never stores the raw token, only its hash (see {@link CapabilityTokens}). All
 * mutations are serialized through {@code synchronized} blocks so a third client can't slip
 * into a seat and a wrong-seat action can't reach a controller.
 *
 * <p>Disconnected seats are reserved for {@link #graceSeconds()}; on expiry a lobby seat is
 * freed, and a seat in a live match tears the match down so no engine thread stays wedged.
 * The surviving (still-connected) seat is preserved with its token and transport intact so
 * it is never dumped into a broken lobby with a dead identity.
 */
public class LanRoomManager {

    // Error codes carried in JSON responses.
    public static final String ERR_INVALID_TOKEN = "INVALID_TOKEN";
    public static final String ERR_NOT_HOST = "NOT_HOST";
    public static final String ERR_ROOM_FULL = "ROOM_FULL";
    public static final String ERR_SEAT_TAKEN = "SEAT_TAKEN";
    public static final String ERR_NOT_LOBBY = "NOT_LOBBY";
    public static final String ERR_ALREADY_STARTED = "ALREADY_STARTED";
    public static final String ERR_MATCH_FINISHED = "MATCH_FINISHED";
    public static final String ERR_WAITING_FOR_PLAYER = "WAITING_FOR_PLAYER";
    public static final String ERR_NOT_READY = "NOT_READY";
    public static final String ERR_PLAYER_DISCONNECTED = "PLAYER_DISCONNECTED";
    public static final String ERR_MISSING_DECKS = "MISSING_DECKS";
    public static final String ERR_UNKNOWN_DECK = "UNKNOWN_DECK";

    /** {@code -Dforge.web.lan.grace.seconds} — how long a disconnected seat stays reserved. */
    private static final String GRACE_PROPERTY = "forge.web.lan.grace.seconds";
    private static final int DEFAULT_GRACE_SECONDS = 90;

    private final MatchLauncher launcher;
    private final LanRoom room;
    private final WebSession soloSession;

    private final ScheduledExecutorService expiryExecutor;
    private final Map<SeatRole, ScheduledFuture<?>> expiryTasks = new ConcurrentHashMap<>();
    private final Map<SeatRole, WebSession.Transport> currentTransports = new ConcurrentHashMap<>();

    private final int graceSeconds;

    public LanRoomManager() {
        this(new MatchLauncher());
    }

    LanRoomManager(final MatchLauncher launcher) {
        this.launcher = launcher;
        this.soloSession = new WebSession();
        final LanSeat host = new LanSeat(SeatRole.HOST, newSeatSession());
        final LanSeat opponent = new LanSeat(SeatRole.OPPONENT, newSeatSession());
        this.room = new LanRoom(host, opponent);
        this.expiryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "Forge Web LAN expiry");
            t.setDaemon(true);
            return t;
        });
        this.graceSeconds = readGraceSeconds();
    }

    private static WebSession newSeatSession() {
        final WebSession session = new WebSession();
        // A LAN seat keeps its pending prompt parked when its socket drops, so the game
        // thread pauses rather than auto-answering; expiry decides when to release it.
        session.setAbandonOnDisconnect(false);
        return session;
    }

    private static int readGraceSeconds() {
        final String value = System.getProperty(GRACE_PROPERTY);
        if (value != null) {
            try {
                return Math.max(1, Integer.parseInt(value.trim()));
            } catch (final NumberFormatException ignored) {
                System.err.println("Ignoring unparseable " + GRACE_PROPERTY + " '" + value + "'");
            }
        }
        return DEFAULT_GRACE_SECONDS;
    }

    public int graceSeconds() {
        return graceSeconds;
    }

    public LanRoom room() {
        return room;
    }

    public WebSession soloSession() {
        return soloSession;
    }

    /** Result of an authenticated room mutation, serialized by the HTTP layer. */
    public record LanResult(boolean ok, String error, String token, SeatRole role) {
        static LanResult ok(final String token, final SeatRole role) {
            return new LanResult(true, null, token, role);
        }
        static LanResult ok(final SeatRole role) {
            return new LanResult(true, null, null, role);
        }
        static LanResult error(final String error) {
            return new LanResult(false, error, null, null);
        }
    }

    /** A WebSocket channel that passed the token handshake. */
    public record SocketAuth(WebSession session, SeatRole role, String welcomeJson) { }

    // ------------------------------------------------------------------- solo

    /** Starts a solo-vs-AI match; returns an error message or {@code null} on success. */
    public String startSolo(final Map<String, Object> request) {
        return launcher.startSolo(soloSession, request);
    }

    // ------------------------------------------------------------------- lobby

    /** Claims the first free seat (host, then opponent) for {@code name}. */
    public LanResult claim(final String name) {
        synchronized (this) {
            if (!room.host().occupied()) {
                return claimSeat(SeatRole.HOST, name);
            }
            if (!room.opponent().occupied()) {
                return claimSeat(SeatRole.OPPONENT, name);
            }
            return LanResult.error(ERR_ROOM_FULL);
        }
    }

    private LanResult claimSeat(final SeatRole role, final String name) {
        final LanSeat seat = room.seat(role);
        if (seat.occupied()) {
            return LanResult.error(ERR_SEAT_TAKEN);
        }
        if (room.state() != LanRoom.State.WAITING) {
            return LanResult.error(ERR_NOT_LOBBY);
        }
        final String token = CapabilityTokens.issue();
        seat.setTokenHash(CapabilityTokens.hash(token));
        seat.setDisplayName(sanitizeName(name));
        seat.setDeckName("random");
        seat.setReady(false);
        seat.setConnected(false);
        broadcastRoom();
        return LanResult.ok(token, role);
    }

    /** Validates a reconnect token without changing seat state; the socket hello does the rest. */
    public LanResult reconnect(final String token) {
        final LanSeat seat = seatByToken(token);
        if (seat == null) {
            return LanResult.error(ERR_INVALID_TOKEN);
        }
        return LanResult.ok(seat.role);
    }

    public LanResult leave(final String token) {
        final LanSeat seat = seatByToken(token);
        if (seat == null) {
            return LanResult.error(ERR_INVALID_TOKEN);
        }
        if (room.state() == LanRoom.State.WAITING) {
            // Leaving the lobby frees just this seat.
            synchronized (this) {
                seat.clear();
                cancelExpiry(seat.role);
                if (room.isEmpty()) {
                    room.setState(LanRoom.State.WAITING);
                }
            }
            broadcastRoom();
            return LanResult.ok(seat.role);
        }
        // Leaving a live or finished match ends it and returns both seats to the lobby
        // (keeping them, so the pair can rematch without rejoining).
        runTeardown(null);
        return LanResult.ok(seat.role);
    }

    /**
     * Ends the active match and returns the room to the lobby.
     *
     * @param expiredSeat the seat whose disconnect grace elapsed mid-match — that seat alone
     *        is freed while the surviving seat keeps its token and transport — or
     *        {@code null} for a deliberate leave, which keeps both seats for a rematch.
     */
    private void runTeardown(final SeatRole expiredSeat) {
        final WebSession host = room.host().session;
        final WebSession opponent = room.opponent().session;
        final Thread t = new Thread(() -> {
            host.abandonPendingRequests();
            opponent.abandonPendingRequests();
            launcher.endActiveMatch(List.of(host, opponent));
            synchronized (this) {
                room.setState(LanRoom.State.WAITING);
                if (expiredSeat != null) {
                    // Free only the expired seat. The survivor stays occupied, connected and
                    // keeps its token/transport so it can leave or wait for a new opponent
                    // instead of being stranded with a now-invalid identity.
                    room.seat(expiredSeat).clear();
                    room.seat(expiredSeat.other()).setReady(false);
                    currentTransports.remove(expiredSeat);
                } else {
                    room.host().setReady(false);
                    room.opponent().setReady(false);
                }
            }
            broadcastRoom();
        }, "Forge Web LAN teardown");
        t.setDaemon(true);
        t.start();
    }

    public LanResult setName(final String token, final String name) {
        final LanSeat seat = seatByToken(token);
        if (seat == null) {
            return LanResult.error(ERR_INVALID_TOKEN);
        }
        synchronized (this) {
            if (room.state() != LanRoom.State.WAITING) {
                return LanResult.error(ERR_NOT_LOBBY);
            }
            seat.setDisplayName(sanitizeName(name));
        }
        broadcastRoom();
        return LanResult.ok(seat.role);
    }

    public LanResult setDeck(final String token, final String deck) {
        final LanSeat seat = seatByToken(token);
        if (seat == null) {
            return LanResult.error(ERR_INVALID_TOKEN);
        }
        final String normalized = deck == null ? "" : deck.trim();
        if (normalized.isEmpty()) {
            return LanResult.error(ERR_UNKNOWN_DECK);
        }
        synchronized (this) {
            if (room.state() != LanRoom.State.WAITING) {
                return LanResult.error(ERR_NOT_LOBBY);
            }
            seat.setDeckName(normalized);
        }
        broadcastRoom();
        return LanResult.ok(seat.role);
    }

    public LanResult setReady(final String token, final boolean ready) {
        final LanSeat seat = seatByToken(token);
        if (seat == null) {
            return LanResult.error(ERR_INVALID_TOKEN);
        }
        synchronized (this) {
            if (room.state() != LanRoom.State.WAITING) {
                return LanResult.error(ERR_NOT_LOBBY);
            }
            seat.setReady(ready);
        }
        broadcastRoom();
        return LanResult.ok(seat.role);
    }

    /** Host-only rule change, only while still in the lobby. */
    public LanResult setRule(final String token, final int startingLife) {
        final LanSeat seat = seatByToken(token);
        if (seat == null) {
            return LanResult.error(ERR_INVALID_TOKEN);
        }
        if (seat.role != SeatRole.HOST) {
            return LanResult.error(ERR_NOT_HOST);
        }
        synchronized (this) {
            if (room.state() != LanRoom.State.WAITING) {
                return LanResult.error(ERR_NOT_LOBBY);
            }
            room.setStartingLife(Math.max(LanRoom.MIN_STARTING_LIFE,
                    Math.min(LanRoom.MAX_STARTING_LIFE, startingLife)));
        }
        broadcastRoom();
        return LanResult.ok(seat.role);
    }

    /** Host-only match start. Requires two ready, connected seats with decks. */
    public LanResult startLan(final String token) {
        final LanSeat seat = seatByToken(token);
        if (seat == null) {
            return LanResult.error(ERR_INVALID_TOKEN);
        }
        if (seat.role != SeatRole.HOST) {
            return LanResult.error(ERR_NOT_HOST);
        }
        synchronized (this) {
            final LanRoom.State st = room.state();
            if (st == LanRoom.State.PLAYING || st == LanRoom.State.STARTING) {
                return LanResult.error(ERR_ALREADY_STARTED);
            }
            if (st == LanRoom.State.FINISHED) {
                return LanResult.error(ERR_MATCH_FINISHED);
            }
            if (!room.isFull()) {
                return LanResult.error(ERR_WAITING_FOR_PLAYER);
            }
            if (!room.host().ready() || !room.opponent().ready()) {
                return LanResult.error(ERR_NOT_READY);
            }
            if (!room.host().connected() || !room.opponent().connected()) {
                return LanResult.error(ERR_PLAYER_DISCONNECTED);
            }
            if (isBlank(room.host().deckName()) || isBlank(room.opponent().deckName())) {
                return LanResult.error(ERR_MISSING_DECKS);
            }
            room.setState(LanRoom.State.STARTING);
        }
        broadcastRoom();

        final String err = launcher.startLan(room,
                List.of(room.host().session, room.opponent().session), this::onLanGameOver);
        if (err != null) {
            synchronized (this) {
                room.setState(LanRoom.State.WAITING);
            }
            broadcastRoom();
            return LanResult.error(err);
        }
        synchronized (this) {
            room.setState(LanRoom.State.PLAYING);
        }
        broadcastRoom();
        return LanResult.ok(SeatRole.HOST);
    }

    // ---------------------------------------------------------------- sockets

    /**
     * Authenticates a WebSocket channel's hello. A blank token binds to the solo session;
     * a LAN token binds to its seat. Returns {@code null} for an invalid token so the
     * caller can reject the socket.
     */
    public SocketAuth authenticateSocket(final String token, final WebSession.Transport transport) {
        if (token == null || token.isBlank()) {
            soloSession.attach(transport);
            return new SocketAuth(soloSession, null, "{\"t\":\"welcome\",\"mode\":\"solo\"}");
        }
        final LanSeat seat = seatByToken(token);
        if (seat == null) {
            return null;
        }
        synchronized (this) {
            final WebSession.Transport old = currentTransports.remove(seat.role);
            if (old != null) {
                seat.session.detach(old);
            }
            seat.session.attach(transport);
            currentTransports.put(seat.role, transport);
            seat.setConnected(true);
            seat.setReconnectDeadlineMs(0);
            cancelExpiry(seat.role);
        }
        broadcastRoom();
        return new SocketAuth(seat.session, seat.role, welcomeJson(seat.role));
    }

    /** A bound transport closed: reserve the seat and start the grace clock. */
    public void onSocketClosed(final WebSession session, final WebSession.Transport transport) {
        if (session == soloSession) {
            soloSession.detach(transport);
            return;
        }
        final LanSeat seat = seatBySession(session);
        if (seat == null) {
            return;
        }
        seat.session.detach(transport);
        synchronized (this) {
            // Only treat the close as a disconnect if it was the seat's *current* socket.
            // A replaced socket's channelInactive can fire after a newer hello was bound,
            // and must not mark a reconnected seat as gone.
            final boolean wasCurrent = currentTransports.remove(seat.role, transport);
            if (!wasCurrent || !seat.occupied()) {
                return;
            }
            seat.setConnected(false);
            scheduleExpiry(seat.role);
        }
        broadcastRoom();
    }

    private void scheduleExpiry(final SeatRole role) {
        cancelExpiry(role);
        final ScheduledFuture<?> task = expiryExecutor.schedule(
                () -> onSeatExpired(role), graceSeconds, TimeUnit.SECONDS);
        expiryTasks.put(role, task);
        room.seat(role).setReconnectDeadlineMs(System.currentTimeMillis() + graceSeconds * 1000L);
    }

    private void cancelExpiry(final SeatRole role) {
        final ScheduledFuture<?> task = expiryTasks.remove(role);
        if (task != null) {
            task.cancel(false);
        }
    }

    private void onSeatExpired(final SeatRole role) {
        final boolean tearDown;
        synchronized (this) {
            final LanSeat seat = room.seat(role);
            if (!seat.occupied() || seat.connected()) {
                return; // reconnected or released in the meantime
            }
            expiryTasks.remove(role);
            tearDown = room.state() == LanRoom.State.PLAYING || room.state() == LanRoom.State.STARTING;
            if (!tearDown) {
                seat.clear();
            }
        }
        if (tearDown) {
            runTeardown(role);
        } else {
            broadcastRoom();
        }
    }

    /** Invoked by the launcher on the game thread once the LAN game finishes. */
    private void onLanGameOver() {
        synchronized (this) {
            // Only a live match's natural end flips the room to FINISHED; a teardown that
            // already reset it to WAITING must not re-stamp it.
            final LanRoom.State st = room.state();
            if (st != LanRoom.State.PLAYING && st != LanRoom.State.STARTING) {
                return;
            }
            room.setState(LanRoom.State.FINISHED);
        }
        broadcastRoom();
    }

    // ------------------------------------------------------------------ util

    private LanSeat seatByToken(final String token) {
        if (token == null) {
            return null;
        }
        final String hash = CapabilityTokens.hash(token);
        if (hash.equals(room.host().tokenHash())) {
            return room.host();
        }
        if (hash.equals(room.opponent().tokenHash())) {
            return room.opponent();
        }
        return null;
    }

    private LanSeat seatBySession(final WebSession session) {
        if (session == room.host().session) {
            return room.host();
        }
        if (session == room.opponent().session) {
            return room.opponent();
        }
        return null;
    }

    private void broadcastRoom() {
        room.host().session.send(room.roomMessage(SeatRole.HOST));
        room.opponent().session.send(room.roomMessage(SeatRole.OPPONENT));
    }

    private static String welcomeJson(final SeatRole role) {
        final StringBuilder sb = new StringBuilder(96);
        final Json.Obj o = Json.obj(sb);
        o.put("t", "welcome");
        o.put("role", role.name());
        o.end();
        return sb.toString();
    }

    private static String sanitizeName(final String name) {
        if (name == null) {
            return "Player";
        }
        String s = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (s.isEmpty()) {
            s = "Player";
        }
        return s.length() > 32 ? s.substring(0, 32) : s;
    }

    private static boolean isBlank(final String s) {
        return s == null || s.isBlank();
    }

    /** Cancels expiry tasks and releases every parked wait; called from the JVM shutdown hook. */
    public void shutdown() {
        expiryTasks.values().forEach(task -> task.cancel(false));
        expiryTasks.clear();
        expiryExecutor.shutdownNow();
        for (final WebSession session : List.of(soloSession, room.host().session, room.opponent().session)) {
            session.abandonPendingRequests();
            session.shutdown();
        }
    }
}
