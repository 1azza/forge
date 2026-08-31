package forge.web;

/** The two seats in a LAN room. The host owns the lobby rules and the start button. */
public enum SeatRole {
    HOST, OPPONENT;

    public SeatRole other() {
        return this == HOST ? OPPONENT : HOST;
    }
}
