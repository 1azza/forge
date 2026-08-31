package forge.web;

/**
 * The single LAN room: two seats and the host-controlled rules for a constructed 1v1.
 *
 * <p>This class is a plain data holder — the {@link LanRoomManager} owns every transition,
 * so state changes are serialized/atomic at the coordinator level. Rules are immutable once
 * a match starts because {@link #setStartingLife} is only reachable while the room is
 * {@link State#WAITING}.
 */
public final class LanRoom {

    public enum State { WAITING, STARTING, PLAYING, FINISHED }

    public static final int DEFAULT_STARTING_LIFE = 20;
    public static final int MIN_STARTING_LIFE = 1;
    public static final int MAX_STARTING_LIFE = 100;

    private final LanSeat host;
    private final LanSeat opponent;

    private volatile State state = State.WAITING;
    private volatile int startingLife = DEFAULT_STARTING_LIFE;

    LanRoom(final LanSeat host, final LanSeat opponent) {
        this.host = host;
        this.opponent = opponent;
    }

    public LanSeat seat(final SeatRole role) {
        return role == SeatRole.HOST ? host : opponent;
    }

    public LanSeat host() {
        return host;
    }

    public LanSeat opponent() {
        return opponent;
    }

    public State state() {
        return state;
    }

    public void setState(final State state) {
        this.state = state;
    }

    public int startingLife() {
        return startingLife;
    }

    public void setStartingLife(final int startingLife) {
        this.startingLife = startingLife;
    }

    public boolean isFull() {
        return host.occupied() && opponent.occupied();
    }

    public boolean isEmpty() {
        return !host.occupied() && !opponent.occupied();
    }

    /**
     * Serializes the room body (no {@code "t"} wrapper) as seen by {@code you}. The result
     * is shared by HTTP responses (wrapped in a {@code "room"} field) and WebSocket
     * broadcasts (wrapped in a {@code "t":"room"} envelope). Never contains a token.
     */
    public String roomBody(final SeatRole you) {
        final StringBuilder sb = new StringBuilder(512);
        final Json.Obj root = Json.obj(sb);
        writeBody(root, you);
        root.end();
        return sb.toString();
    }

    /** A WebSocket broadcast envelope: {@code {"t":"room", ...}}. */
    public String roomMessage(final SeatRole you) {
        final StringBuilder sb = new StringBuilder(512);
        final Json.Obj root = Json.obj(sb);
        root.put("t", "room");
        writeBody(root, you);
        root.end();
        return sb.toString();
    }

    private void writeBody(final Json.Obj root, final SeatRole you) {
        root.put("state", state.name());
        if (you != null) {
            root.put("you", you.name());
        }
        root.put("startingLife", startingLife);
        final Json.Arr seats = root.arr("seats");
        writeSeat(seats, host);
        writeSeat(seats, opponent);
        seats.end();
    }

    private static void writeSeat(final Json.Arr arr, final LanSeat seat) {
        final Json.Obj o = arr.obj();
        o.put("role", seat.role.name());
        o.put("name", seat.displayName());
        o.put("deck", seat.deckName());
        o.put("ready", seat.ready());
        o.put("connected", seat.connected());
        o.end();
    }
}
