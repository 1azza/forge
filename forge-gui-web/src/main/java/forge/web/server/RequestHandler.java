package forge.web.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

import forge.web.CardImages;
import forge.web.Json;
import forge.web.MatchLauncher;
import forge.web.WebSession;

/** Serves the client, the card art endpoint and the game socket. */
public class RequestHandler extends SimpleChannelInboundHandler<Object> {

    /** Image lookups can hit the network, so they never run on an event loop thread. */
    private final ExecutorService imageWorkers = Executors.newFixedThreadPool(4, r -> {
        final Thread t = new Thread(r, "Forge Web image");
        t.setDaemon(true);
        return t;
    });

    private final WebSession session;

    public RequestHandler(final WebSession session) {
        this.session = session;
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof FullHttpRequest request) {
            handleHttp(ctx, request);
        } else if (msg instanceof TextWebSocketFrame frame) {
            session.onMessage(frame.text());
        }
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            session.attach(new ChannelTransport(ctx.channel()));
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        session.detach(new ChannelTransport(ctx.channel()));
        // Don't strand the game thread on a dialog nobody is going to answer.
        session.abandonPendingRequests();
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        if (!(cause instanceof IOException)) {
            cause.printStackTrace();
        }
        ctx.close();
    }

    /** Netty channel wrapped as something the game side can push text into. */
    private record ChannelTransport(Channel channel) implements WebSession.Transport {
        @Override
        public void send(final String text) {
            if (channel.isActive()) {
                channel.writeAndFlush(new TextWebSocketFrame(text));
            }
        }

        @Override
        public boolean isOpen() {
            return channel.isActive();
        }
    }

    // ------------------------------------------------------------------ HTTP

    private void handleHttp(final ChannelHandlerContext ctx, final FullHttpRequest request) {
        final QueryStringDecoder query = new QueryStringDecoder(request.uri());
        final String path = query.path();

        if (path.startsWith("/api/")) {
            handleApi(ctx, request, query, path);
            return;
        }

        final String resource = "/".equals(path) ? "/index.html" : path;
        final byte[] body = StaticFiles.read(resource);
        if (body == null) {
            sendStatus(ctx, request, HttpResponseStatus.NOT_FOUND);
            return;
        }
        send(ctx, request, HttpResponseStatus.OK, StaticFiles.contentType(resource), body, "no-cache");
    }

    private void handleApi(final ChannelHandlerContext ctx, final FullHttpRequest request,
            final QueryStringDecoder query, final String path) {
        switch (path) {
            case "/api/card-image" -> {
                final String key = first(query.parameters(), "key");
                final String name = first(query.parameters(), "name");
                final String set = first(query.parameters(), "set");
                // Thumbnails are the common case; the inspector explicitly asks for normal.
                final boolean small = "small".equals(first(query.parameters(), "size"));
                // Retain the request; the worker thread replies after this call returns.
                request.retain();
                imageWorkers.execute(() -> {
                    try {
                        final byte[] image = CardImages.get(key, name, set, small);
                        ctx.executor().execute(() -> {
                            if (image == null) {
                                sendStatus(ctx, request, HttpResponseStatus.NOT_FOUND);
                            } else {
                                send(ctx, request, HttpResponseStatus.OK, "image/jpeg", image,
                                        "public, max-age=31536000, immutable");
                            }
                            request.release();
                        });
                    } catch (final RuntimeException e) {
                        ctx.executor().execute(() -> {
                            sendStatus(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR);
                            request.release();
                        });
                    }
                });
            }
            case "/api/decks" -> {
                final StringBuilder sb = new StringBuilder(256);
                final Json.Arr arr = Json.arr(sb);
                for (final String name : MatchLauncher.deckNames()) {
                    arr.add(name);
                }
                arr.end();
                sendJson(ctx, request, sb.toString());
            }
            case "/api/start" -> {
                if (!HttpMethod.POST.equals(request.method())) {
                    sendStatus(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED);
                    return;
                }
                final Map<String, Object> body = Json.parseObject(request.content().toString(StandardCharsets.UTF_8));
                final String error = MatchLauncher.start(session, body);
                final StringBuilder sb = new StringBuilder(64);
                Json.obj(sb).put("ok", error == null).put("error", error).end();
                sendJson(ctx, request, sb.toString());
            }
            default -> sendStatus(ctx, request, HttpResponseStatus.NOT_FOUND);
        }
    }

    private static String first(final Map<String, List<String>> params, final String name) {
        final List<String> values = params.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static void sendJson(final ChannelHandlerContext ctx, final FullHttpRequest request, final String json) {
        send(ctx, request, HttpResponseStatus.OK, "application/json; charset=utf-8",
                json.getBytes(StandardCharsets.UTF_8), "no-store");
    }

    private static void sendStatus(final ChannelHandlerContext ctx, final FullHttpRequest request,
            final HttpResponseStatus status) {
        send(ctx, request, status, "text/plain; charset=utf-8",
                status.reasonPhrase().getBytes(StandardCharsets.UTF_8), "no-store");
    }

    private static void send(final ChannelHandlerContext ctx, final FullHttpRequest request,
            final HttpResponseStatus status, final String contentType, final byte[] body, final String cacheControl) {
        final FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, cacheControl);

        final boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * Client assets. A packaged build reads them out of the jar; setting
     * {@code -Dforge.web.static} points at the source folder instead so the UI can be
     * edited without rebuilding.
     */
    static final class StaticFiles {
        private static final String CLASSPATH_ROOT = "/web";

        private StaticFiles() { }

        static byte[] read(final String resource) {
            if (resource.contains("..")) {
                return null;
            }
            final String override = System.getProperty("forge.web.static");
            if (override != null && !override.isBlank()) {
                final Path file = Path.of(override).resolve(resource.substring(1)).normalize();
                if (Files.isReadable(file) && file.startsWith(Path.of(override).normalize())) {
                    try {
                        return Files.readAllBytes(file);
                    } catch (final IOException e) {
                        return null;
                    }
                }
                return null;
            }
            try (InputStream in = StaticFiles.class.getResourceAsStream(CLASSPATH_ROOT + resource)) {
                return in == null ? null : in.readAllBytes();
            } catch (final IOException e) {
                return null;
            }
        }

        static String contentType(final String resource) {
            if (resource.endsWith(".html")) {
                return "text/html; charset=utf-8";
            }
            if (resource.endsWith(".css")) {
                return "text/css; charset=utf-8";
            }
            if (resource.endsWith(".js")) {
                return "text/javascript; charset=utf-8";
            }
            if (resource.endsWith(".svg")) {
                return "image/svg+xml";
            }
            if (resource.endsWith(".png")) {
                return "image/png";
            }
            return "application/octet-stream";
        }
    }
}
