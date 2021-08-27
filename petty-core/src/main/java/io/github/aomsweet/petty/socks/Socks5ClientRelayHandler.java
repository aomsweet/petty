package io.github.aomsweet.petty.socks;

import io.github.aomsweet.petty.*;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;

public final class Socks5ClientRelayHandler extends ClientRelayHandler<Socks5CommandRequest> {

    private final static InternalLogger logger = InternalLoggerFactory.getInstance(Socks5ClientRelayHandler.class);

    public static final DefaultSocks5InitialResponse NO_AUTH_RESPONSE = new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH);
    public static final DefaultSocks5InitialResponse PASSWORD_RESPONSE = new DefaultSocks5InitialResponse(Socks5AuthMethod.PASSWORD);

    public static final DefaultSocks5PasswordAuthResponse AUTH_SUCCESS = new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS);
    public static final DefaultSocks5PasswordAuthResponse AUTH_FAILURE = new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE);

    public Socks5ClientRelayHandler(PettyServer petty) {
        super(petty, logger);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        ChannelPipeline pipeline = ctx.pipeline();
        if (msg instanceof Socks5InitialRequest) {
            initialRequestHandler(ctx, (Socks5InitialRequest) msg, pipeline);
        } else if (msg instanceof Socks5PasswordAuthRequest) {
            authRequestHandler(ctx, (Socks5PasswordAuthRequest) msg, pipeline);
        } else if (msg instanceof Socks5CommandRequest) {
            cmdRequestHandler(ctx, (Socks5CommandRequest) msg, pipeline);
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    protected void initialRequestHandler(ChannelHandlerContext ctx, Socks5InitialRequest initialRequest, ChannelPipeline pipeline) {
        Object response;
        if (petty.getProxyAuthenticator() != null
            || initialRequest.authMethods().contains(Socks5AuthMethod.PASSWORD)) {
            pipeline.replace(HandlerNames.DECODER, HandlerNames.DECODER, new Socks5PasswordAuthRequestDecoder());
            response = PASSWORD_RESPONSE;
        } else {
            pipeline.replace(HandlerNames.DECODER, HandlerNames.DECODER, new Socks5CommandRequestDecoder());
            response = NO_AUTH_RESPONSE;
        }
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    protected void authRequestHandler(ChannelHandlerContext ctx, Socks5PasswordAuthRequest authRequest, ChannelPipeline pipeline) {
        credentials = new Credentials(authRequest.username(), authRequest.password());
        ProxyAuthenticator proxyAuthenticator = petty.getProxyAuthenticator();
        if (proxyAuthenticator == null || proxyAuthenticator.authenticate(authRequest.username(), authRequest.password())) {
            pipeline.replace(HandlerNames.DECODER, HandlerNames.DECODER, new Socks5CommandRequestDecoder());
            ctx.writeAndFlush(AUTH_SUCCESS).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        } else {
            ctx.writeAndFlush(AUTH_FAILURE).addListener(ChannelFutureListener.CLOSE);
        }
    }

    protected void cmdRequestHandler(ChannelHandlerContext ctx, Socks5CommandRequest request, ChannelPipeline pipeline) throws Exception {
        if (request.type() == Socks5CommandType.CONNECT) {
            serverAddress = InetSocketAddress.createUnresolved(request.dstAddr(), request.dstPort());
            doConnectServer(ctx, ctx.channel(), request);
        } else {
            logger.error("Unsupported Socks5 {} command.", request.type());
            release(ctx);
        }
    }

    @Override
    protected void onConnected(ChannelHandlerContext ctx, Channel clientChannel, Socks5CommandRequest request) {
        Object response = new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS,
            request.dstAddrType(), request.dstAddr(), request.dstPort());
        ctx.writeAndFlush(response).addListener(future -> {
            if (future.isSuccess()) {
                ChannelPipeline pipeline = clientChannel.pipeline();
                pipeline.remove(HandlerNames.DECODER);
                pipeline.remove(Socks5ServerEncoder.DEFAULT);
                doServerRelay(ctx);
            }
        });
    }

    @Override
    protected void onConnectFailed(ChannelHandlerContext ctx, Channel clientChannel, Socks5CommandRequest request) {
        Object response = new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, request.dstAddrType());
        ctx.writeAndFlush(response);
        release(ctx);
    }
}
