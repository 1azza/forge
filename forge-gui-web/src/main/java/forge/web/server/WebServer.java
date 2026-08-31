package forge.web.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;

import forge.web.WebSession;

/** Netty HTTP server that also carries the game socket on {@code /ws}. */
public class WebServer {

    public static final String WEBSOCKET_PATH = "/ws";
    /** Card art can be a few hundred KB; the aggregator has to allow for it. */
    private static final int MAX_CONTENT_LENGTH = 1024 * 1024;

    private final int port;
    private final WebSession session;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel channel;

    public WebServer(final int port, final WebSession session) {
        this.port = port;
        this.session = session;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        final ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        final ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(MAX_CONTENT_LENGTH));
                        pipeline.addLast(new ChunkedWriteHandler());
                        // Passes non-websocket requests through to the HTTP handler below.
                        pipeline.addLast(new WebSocketServerProtocolHandler(WEBSOCKET_PATH, null, true));
                        pipeline.addLast(new RequestHandler(session));
                    }
                });

        channel = bootstrap.bind(port).sync().channel();
    }

    public void awaitShutdown() throws InterruptedException {
        channel.closeFuture().sync();
    }

    public void stop() {
        if (channel != null) {
            channel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }
}
