package forge.web;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.testng.annotations.Test;

/**
 * Tests the per-seat wire plumbing: request-id isolation between sessions, and the
 * disconnect-pause / reconnect-resend behavior a LAN seat relies on.
 */
public class WebSessionTest {

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

    @Test
    public void answerToAnotherSessionDoesNotResolveAPendingPrompt() throws Exception {
        final WebSession a = new WebSession();
        final WebSession b = new WebSession();
        try {
            final RecordingTransport ta = new RecordingTransport();
            final RecordingTransport tb = new RecordingTransport();
            a.attach(ta);
            b.attach(tb);

            final ExecutorService pool = Executors.newSingleThreadExecutor();
            final Future<Map<String, Object>> ask = pool.submit(
                    () -> a.ask("confirm", "\"message\":\"hello\""));
            awaitMessages(ta, 1);
            final int rid = ridOf(ta.sent.get(0));

            // Answering on session B with A's request id must not resolve A.
            b.onMessage("{\"t\":\"answer\",\"rid\":" + rid + ",\"picked\":0}");
            Thread.sleep(120);
            assertFalse(ask.isDone(), "another seat must not be able to answer this seat's prompt");

            a.onMessage("{\"t\":\"answer\",\"rid\":" + rid + ",\"picked\":0}");
            assertNotNull(ask.get(5, TimeUnit.SECONDS));
            pool.shutdownNow();
        } finally {
            a.shutdown();
            b.shutdown();
        }
    }

    @Test
    public void lanSeatPausesOnDisconnectAndResendsThePromptOnReconnect() throws Exception {
        final WebSession s = new WebSession();
        s.setAbandonOnDisconnect(false);
        try {
            final RecordingTransport t1 = new RecordingTransport();
            s.attach(t1);
            assertTrue(s.needsFullState());

            final ExecutorService pool = Executors.newSingleThreadExecutor();
            final Future<Map<String, Object>> ask = pool.submit(
                    () -> s.ask("confirm", "\"message\":\"hello\""));
            awaitMessages(t1, 1);
            final int rid = ridOf(t1.sent.get(0));

            // Dropping the socket must not abandon the parked prompt (the game pauses).
            s.detach(t1);
            Thread.sleep(120);
            assertFalse(ask.isDone(), "disconnect must park the prompt, not auto-answer it");

            // Reconnecting gets a full resync and the parked prompt re-raised.
            final RecordingTransport t2 = new RecordingTransport();
            s.attach(t2);
            assertTrue(s.needsFullState());
            awaitRid(t2, rid);

            s.onMessage("{\"t\":\"answer\",\"rid\":" + rid + ",\"picked\":0}");
            assertNotNull(ask.get(5, TimeUnit.SECONDS));
            pool.shutdownNow();
        } finally {
            s.shutdown();
        }
    }

    private static int ridOf(final String json) {
        return Json.integer(Json.parseObject(json), "rid", -1);
    }

    private static void awaitMessages(final RecordingTransport t, final int count) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (t.sent.size() >= count) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(t.sent.size() >= count, "expected at least " + count + " messages");
    }

    private static void awaitRid(final RecordingTransport t, final int rid) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            for (final String msg : t.sent) {
                if (ridOf(msg) == rid) {
                    return;
                }
            }
            Thread.sleep(10);
        }
        assertTrue(false, "the parked prompt was never re-sent on reconnect");
    }
}
