package forge.web;

/**
 * One seat in the LAN room, together with the per-seat web session that owns its own GUI,
 * serializer, prompt, pending-request map and transport set.
 *
 * <p>Only a {@link CapabilityTokens#hash(String)} of the seat's token is kept here; the raw
 * token never touches this object. All fields are volatile so the Netty threads, the room
 * coordinator and the push loop can read a consistent view without a lock.
 */
public final class LanSeat {

    public final SeatRole role;
    public final WebSession session;

    private volatile String tokenHash;
    private volatile String displayName;
    private volatile String deckName;
    private volatile boolean ready;
    private volatile boolean connected;
    private volatile long reconnectDeadlineMs;

    LanSeat(final SeatRole role, final WebSession session) {
        this.role = role;
        this.session = session;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public void setTokenHash(final String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String displayName() {
        return displayName;
    }

    public void setDisplayName(final String displayName) {
        this.displayName = displayName;
    }

    public String deckName() {
        return deckName;
    }

    public void setDeckName(final String deckName) {
        this.deckName = deckName;
    }

    public boolean ready() {
        return ready;
    }

    public void setReady(final boolean ready) {
        this.ready = ready;
    }

    public boolean connected() {
        return connected;
    }

    public void setConnected(final boolean connected) {
        this.connected = connected;
    }

    public long reconnectDeadlineMs() {
        return reconnectDeadlineMs;
    }

    public void setReconnectDeadlineMs(final long reconnectDeadlineMs) {
        this.reconnectDeadlineMs = reconnectDeadlineMs;
    }

    public boolean occupied() {
        return tokenHash != null;
    }

    /** Frees the seat so another client can claim it. */
    public void clear() {
        tokenHash = null;
        displayName = null;
        deckName = null;
        ready = false;
        connected = false;
        reconnectDeadlineMs = 0;
    }
}
