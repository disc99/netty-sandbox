package io.disc99.netty.http2;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

import static io.netty.handler.logging.LogLevel.INFO;

public class Server {

    private static final int PORT = 6565;

    public static void main(String[] args) throws Exception {
        var bootstrap = new ServerBootstrap();
        var group = new NioEventLoopGroup();
        bootstrap.option(ChannelOption.SO_BACKLOG, 1024)
                .group(group)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ServerInitializer());
        try {
            bootstrap.bind(PORT).sync()
                    .channel().closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    static class ServerInitializer extends ChannelInitializer<SocketChannel> {

        private static final Http2FrameLogger logger =
                new Http2FrameLogger(INFO, ServerInitializer.class);

        protected void initChannel(SocketChannel socketChannel) throws Exception {
            var connection = new DefaultHttp2Connection(true);
            var connectionHandler = new Http2ConnectionHandlerBuilder()
                    .connection(connection)
                    .frameListener(new FrameListener())
                    .frameLogger(logger)
                    .build();
            var sourceHandler = new SourceHandler(connection, connectionHandler.encoder());
            socketChannel.pipeline()
                    .addLast(connectionHandler, sourceHandler);
        }
    }

    static class FrameListener extends Http2EventAdapter {
        @Override
        public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency, short weight, boolean exclusive, int padding, boolean endStream) throws Http2Exception {
            Http2HeadersFrame frame = new DefaultHttp2HeadersFrame(headers, endStream, padding);
            ctx.fireChannelRead(frame);
        }

        @Override
        public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) throws Http2Exception {
            Http2DataFrame frame = new DefaultHttp2DataFrame(data, endOfStream, padding);
            ctx.fireChannelRead(frame);
            return data.readableBytes() + padding;
        }
    }

    static class SourceHandler extends ChannelDuplexHandler {
        private Http2Connection connection;
        private Http2ConnectionEncoder encoder;

        final String html = "<html>\n" +
                "<head><title>ChanakaDKB</title><link rel=\"stylesheet\" type=\"text/css\" href=\"main.css\"></head>\n" +
                "<body><h1>https://medium.com/@chanakadkb</h1></body>\n" +
                "</html>";
        final String css = "h1 {\n" +
                "    color: white;\n" +
                "    text-align: center;\n" +
                "    background-color: lightblue;\n" +
                "}";

        public SourceHandler(Http2Connection connection, Http2ConnectionEncoder encoder) {
            this.connection = connection;
            this.encoder = encoder;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof Http2HeadersFrame) {
                Http2HeadersFrame frame = (Http2HeadersFrame) msg;
                String path = frame.headers().path().toString();

                if (path.equalsIgnoreCase("/all")) {
                    int prmiseId = connection.local().incrementAndGetNextStreamId();
                    Http2Headers prmiseHeader = new DefaultHttp2Headers();
                    prmiseHeader.scheme(frame.headers().scheme()).
                            status(HttpResponseStatus.OK.codeAsText()).
                            path("main.css").
                            add(HttpHeaderNames.CONTENT_TYPE, "text/css");

                    ChannelPromise promise = ctx.newPromise();

                    Http2Headers responseHeader = new DefaultHttp2Headers();
                    responseHeader.scheme(frame.headers().scheme())
                            .status(HttpResponseStatus.OK.codeAsText()).
                            path("index.html").
                            add(HttpHeaderNames.CONTENT_TYPE, "text/html");

                    encoder.writePushPromise(ctx, frame.stream().id(), prmiseId, prmiseHeader, 0, promise);

                    ByteBuf promiseBuf = Unpooled.copiedBuffer(css.getBytes());
                    encoder.writeHeaders(ctx, prmiseId, prmiseHeader, 0, false, promise);
                    encoder.writeData(ctx, prmiseId, promiseBuf, 0, true, promise);

                    ByteBuf htmlBuf = Unpooled.copiedBuffer(html.getBytes());
                    encoder.writeHeaders(ctx, frame.stream().id(), responseHeader, 0, false, promise);
                    encoder.writeData(ctx, frame.stream().id(), htmlBuf, 0, true, promise);

                    ctx.flush();
                } else if (path.equalsIgnoreCase("/main.css")) {
                    ChannelPromise promise = ctx.newPromise();
                    Http2Headers responseHeader = new DefaultHttp2Headers();
                    responseHeader.scheme(frame.headers().scheme())
                            .status(HttpResponseStatus.OK.codeAsText()).
                            path("main.css").
                            add(HttpHeaderNames.CONTENT_TYPE, "text/css");
                    ByteBuf cssBuf = Unpooled.copiedBuffer(css.getBytes());
                    encoder.writeHeaders(ctx, frame.stream().id(), responseHeader, 0, false, promise);
                    encoder.writeData(ctx, frame.stream().id(), cssBuf, 0, true, promise);
                    ctx.flush();

                } else {
                    System.out.println(path);
                    ChannelPromise promise = ctx.newPromise();
                    Http2Headers responseHeader = new DefaultHttp2Headers();
                    responseHeader.scheme(frame.headers().scheme())
                            .status(HttpResponseStatus.OK.codeAsText()).
                            path("index.html").
                            add(HttpHeaderNames.CONTENT_TYPE, "text/html");
                    ByteBuf htmlBuf = Unpooled.copiedBuffer(html.getBytes());
                    encoder.writeHeaders(ctx, frame.stream().id(), responseHeader, 0, false, promise);
                    encoder.writeData(ctx, frame.stream().id(), htmlBuf, 0, true, promise);
                    ctx.flush();
                }
            }
        }
    }
}
