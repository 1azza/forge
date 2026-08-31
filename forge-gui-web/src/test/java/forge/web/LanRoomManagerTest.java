package forge.web;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * Tests the pure room/token/routing logic of {@link LanRoomManager} against a fake
 * launcher, so no engine, {@code FModel} or {@code GuiBase} is ever initialized.
 */
public class LanRoomManagerTest {

    private LanRoomManager manager;
    private FakeLauncher launcher;

    @AfterMethod
    public void tearDown() {
        System.clearProperty("forge.web.lan.grace.seconds");
        if (manager != null) {
            manager.shutdown();
            manager = null;
        }
        launcher = null;
    }

    private static final class FakeLauncher extends MatchLauncher {
        int lanStarts;
        int soloStarts;

        @Override
        public synchronized String startLan(final LanRoom room, final List<WebSession> sessions,
                final Runnable onGameOver) {
            lanStarts++;
            return null;
        }

        @Override
        public synchronized String startSolo(final WebSession session, final Map<String, Object> request) {
            soloStarts++;
            return null;
        }

        @Override
        public synchronized boolean endActiveMatch(final List<WebSession> sessions) {
            return true;
        }
    }

    /** A transport that records every message pushed to it. */
    private static final class RecordingTransport implements WebSession.Transport {
        final List<String> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(final String text) {
            sent.add(text);
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }

    private LanRoomManager newManager() {
        launcher = new FakeLauncher();
        manager = new LanRoomManager(launcher);
        return manager;
    }

    /** Claims both seats and binds a socket for each, returning the two tokens. */
    private String[] claimAndConnect(final LanRoomManager m) {
        final LanRoomManager.LanResult host = m.claim("Alice");
        final LanRoomManager.LanResult opp = m.claim("Bob");
        assertTrue(host.ok());
        assertTrue(opp.ok());
        m.authenticateSocket(host.token(), new RecordingTransport());
        m.authenticateSocket(opp.token(), new RecordingTransport());
        return new String[] { host.token(), opp.token() };
    }

    @Test
    public void claimAssignsHostThenOpponentThenRejectsThird() {
        final LanRoomManager m = newManager();

        final LanRoomManager.LanResult host = m.claim("Alice");
        assertTrue(host.ok());
        assertEquals(host.role(), SeatRole.HOST);
        assertNotNull(host.token());
        assertEquals(m.room().host().displayName(), "Alice");

        final LanRoomManager.LanResult opp = m.claim("Bob");
        assertTrue(opp.ok());
        assertEquals(opp.role(), SeatRole.OPPONENT);
        assertEquals(m.room().opponent().displayName(), "Bob");

        final LanRoomManager.LanResult third = m.claim("Carol");
        assertFalse(third.ok());
        assertEquals(third.error(), LanRoomManager.ERR_ROOM_FULL);
    }

    @Test
    public void invalidTokenRejectedEverywhere() {
        final LanRoomManager m = newManager();
        m.claim("Alice");

        assertEquals(m.reconnect("bogus").error(), LanRoomManager.ERR_INVALID_TOKEN);
        assertEquals(m.setName("bogus", "X").error(), LanRoomManager.ERR_INVALID_TOKEN);
        assertEquals(m.setDeck("bogus", "random").error(), LanRoomManager.ERR_INVALID_TOKEN);
        assertEquals(m.setReady("bogus", true).error(), LanRoomManager.ERR_INVALID_TOKEN);
        assertEquals(m.setRule("bogus", 20).error(), LanRoomManager.ERR_INVALID_TOKEN);
        assertEquals(m.startLan("bogus").error(), LanRoomManager.ERR_INVALID_TOKEN);
        assertEquals(m.leave("bogus").error(), LanRoomManager.ERR_INVALID_TOKEN);
        assertEquals(m.leave(null).error(), LanRoomManager.ERR_INVALID_TOKEN);
    }

    @Test
    public void hostOnlyRuleAndStartMutations() {
        final LanRoomManager m = newManager();
        final String[] tokens = claimAndConnect(m);

        // Opponent cannot change rules or start.
        final LanRoomManager.LanResult rule = m.setRule(tokens[1], 30);
        assertFalse(rule.ok());
        assertEquals(rule.error(), LanRoomManager.ERR_NOT_HOST);
        assertEquals(m.room().startingLife(), LanRoom.DEFAULT_STARTING_LIFE);

        final LanRoomManager.LanResult start = m.startLan(tokens[1]);
        assertFalse(start.ok());
        assertEquals(start.error(), LanRoomManager.ERR_NOT_HOST);

        // Host can change rules.
        assertTrue(m.setRule(tokens[0], 30).ok());
        assertEquals(m.room().startingLife(), 30);
    }

    @Test
    public void startRequiresTwoReadyConnectedSeats() {
        final LanRoomManager m = newManager();
        final String[] tokens = claimAndConnect(m);

        // Not ready yet.
        final LanRoomManager.LanResult notReady = m.startLan(tokens[0]);
        assertFalse(notReady.ok());
        assertEquals(notReady.error(), LanRoomManager.ERR_NOT_READY);

        assertTrue(m.setReady(tokens[0], true).ok());
        final LanRoomManager.LanResult oneReady = m.startLan(tokens[0]);
        assertFalse(oneReady.ok());
        assertEquals(oneReady.error(), LanRoomManager.ERR_NOT_READY);

        assertTrue(m.setReady(tokens[1], true).ok());
        assertTrue(m.startLan(tokens[0]).ok());
        assertEquals(m.room().state(), LanRoom.State.PLAYING);

        // A second start while playing is refused.
        assertEquals(m.startLan(tokens[0]).error(), LanRoomManager.ERR_ALREADY_STARTED);
    }

    @Test
    public void perSeatSessionsAreIsolated() {
        final LanRoomManager m = newManager();
        final LanRoomManager.LanResult host = m.claim("Alice");
        final LanRoomManager.LanResult opp = m.claim("Bob");

        assertSame(m.room().host().session, m.room().seat(SeatRole.HOST).session);
        assertTrue(m.room().host().session != m.room().opponent().session);
        assertTrue(m.room().host().session != m.soloSession());
        assertTrue(m.room().host().session.getGuiGame() != m.room().opponent().session.getGuiGame());

        final RecordingTransport hostTransport = new RecordingTransport();
        final RecordingTransport oppTransport = new RecordingTransport();
        final LanRoomManager.SocketAuth hostAuth = m.authenticateSocket(host.token(), hostTransport);
        final LanRoomManager.SocketAuth oppAuth = m.authenticateSocket(opp.token(), oppTransport);

        assertSame(hostAuth.session(), m.room().host().session);
        assertSame(oppAuth.session(), m.room().opponent().session);
        assertTrue(hostAuth.session() != oppAuth.session());

        // Closing the opponent's socket leaves the host connected.
        m.onSocketClosed(oppAuth.session(), oppTransport);
        assertTrue(m.room().host().connected());
        assertFalse(m.room().opponent().connected());
    }

    @Test
    public void reconnectRebindsTheSameSeatAndDetachesTheOldSocket() {
        final LanRoomManager m = newManager();
        final LanRoomManager.LanResult host = m.claim("Alice");

        final RecordingTransport first = new RecordingTransport();
        final LanRoomManager.SocketAuth a1 = m.authenticateSocket(host.token(), first);
        assertTrue(m.room().host().connected());
        final int firstMessages = first.sent.size();

        final RecordingTransport second = new RecordingTransport();
        final LanRoomManager.SocketAuth a2 = m.authenticateSocket(host.token(), second);
        assertSame(a1.session(), a2.session(), "reconnect must bind the same seat");
        assertEquals(first.sent.size(), firstMessages, "the old socket must be detached");
        assertTrue(second.sent.size() >= 1, "the new socket gets the room broadcast");
    }

    @Test
    public void disconnectGraceExpiryFreesTheSeatAndRejectsTheStaleToken() throws InterruptedException {
        System.setProperty("forge.web.lan.grace.seconds", "1");
        final LanRoomManager m = newManager();
        final LanRoomManager.LanResult host = m.claim("Alice");
        final RecordingTransport transport = new RecordingTransport();
        m.authenticateSocket(host.token(), transport);

        m.onSocketClosed(m.room().host().session, transport);
        assertFalse(m.room().host().connected());
        assertTrue(m.room().host().occupied(), "seat stays reserved during the grace period");

        Thread.sleep(1300);

        assertFalse(m.room().host().occupied(), "seat must be freed after the grace period");
        assertEquals(m.reconnect(host.token()).error(), LanRoomManager.ERR_INVALID_TOKEN);
    }

    @Test
    public void leaveALiveMatchReturnsBothSeatsToTheLobby() throws InterruptedException {
        final LanRoomManager m = newManager();
        final String[] tokens = claimAndConnect(m);
        assertTrue(m.setReady(tokens[0], true).ok());
        assertTrue(m.setReady(tokens[1], true).ok());
        assertTrue(m.startLan(tokens[0]).ok());
        assertEquals(m.room().state(), LanRoom.State.PLAYING);

        assertTrue(m.leave(tokens[0]).ok());

        awaitState(m, LanRoom.State.WAITING);
        assertTrue(m.room().host().occupied(), "seats kept for a rematch");
        assertTrue(m.room().opponent().occupied());
        assertFalse(m.room().host().ready());
        assertFalse(m.room().opponent().ready());
    }

    @Test
    public void inGameExpiryFreesTheExpiredSeatAndKeepsTheSurvivor() throws InterruptedException {
        System.setProperty("forge.web.lan.grace.seconds", "1");
        final LanRoomManager m = newManager();
        final LanRoomManager.LanResult host = m.claim("Alice");
        final LanRoomManager.LanResult opp = m.claim("Bob");
        final RecordingTransport hostT = new RecordingTransport();
        final RecordingTransport oppT = new RecordingTransport();
        m.authenticateSocket(host.token(), hostT);
        m.authenticateSocket(opp.token(), oppT);
        assertTrue(m.setReady(host.token(), true).ok());
        assertTrue(m.setReady(opp.token(), true).ok());
        assertTrue(m.startLan(host.token()).ok());
        assertEquals(m.room().state(), LanRoom.State.PLAYING);

        // The host drops mid-game; once the grace period elapses the match ends and only
        // the host's seat is freed. The surviving opponent keeps its token and transport.
        m.onSocketClosed(m.room().host().session, hostT);
        Thread.sleep(1300);

        awaitState(m, LanRoom.State.WAITING);
        assertFalse(m.room().host().occupied(), "expired seat must be freed");
        assertTrue(m.room().opponent().occupied(), "the surviving seat must be preserved");
        assertTrue(m.room().opponent().connected(), "the survivor keeps its open transport");
        assertFalse(m.room().opponent().ready(), "the survivor must re-ready for a rematch");
        assertEquals(m.room().opponent().displayName(), "Bob");
        assertEquals(m.reconnect(opp.token()).role(), SeatRole.OPPONENT, "survivor token stays valid");
        assertEquals(m.reconnect(host.token()).error(), LanRoomManager.ERR_INVALID_TOKEN, "expired token rejected");
    }

    @Test
    public void soloStartDelegatesToTheLauncher() {
        final LanRoomManager m = newManager();
        final String error = m.startSolo(Map.of("deck", "random", "opponentDeck", "random"));
        assertNull(error);
        assertEquals(launcher.soloStarts, 1);
    }

    @Test
    public void roomJsonNeverExposesAToken() {
        final LanRoomManager m = newManager();
        final LanRoomManager.LanResult host = m.claim("Alice");
        final String json = m.room().roomBody(SeatRole.HOST);
        assertFalse(json.contains(host.token()), "room broadcasts must never include the raw token");
        assertFalse(json.contains(CapabilityTokens.hash(host.token())));
    }

    private void awaitState(final LanRoomManager m, final LanRoom.State expected) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (m.room().state() == expected) {
                return;
            }
            Thread.sleep(20);
        }
        assertEquals(m.room().state(), expected, "room did not reach " + expected + " in time");
    }
}
