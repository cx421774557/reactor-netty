/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.http.client;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpConstants;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Publisher;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionEvents;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.http.Cookies;
import reactor.ipc.netty.http.HttpOperations;
import reactor.ipc.netty.http.websocket.WebsocketInbound;
import reactor.ipc.netty.http.websocket.WebsocketOutbound;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * @author Stephane Maldini
 * @author Simon Baslé
 */
class HttpClientOperations extends HttpOperations<NettyInbound, NettyOutbound>
		implements HttpClientResponse, HttpClientRequest {

	static HttpOperations bindHttp(Connection connection, ConnectionEvents listener) {
		ChannelPipeline pipeline = connection.channel()
		                                     .pipeline();

		pipeline.addLast(NettyPipeline.HttpDecoder, new HttpResponseDecoder())
		        .addLast(NettyPipeline.HttpEncoder, new HttpRequestEncoder());

		HttpClientOperations ops = new HttpClientOperations(connection, listener);

		if (connection.channel()
		              .attr(ACCEPT_GZIP)
		           .get()) {
			pipeline.addAfter(NettyPipeline.HttpDecoder,
					NettyPipeline.HttpDecompressor,
					new HttpContentDecompressor());
			ops.requestHeaders()
			   .set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
		}

		return ops;
	}

	static final AttributeKey<Boolean> ACCEPT_GZIP =
			AttributeKey.newInstance("acceptGzip");

	final String[]    redirectedFrom;
	final boolean     isSecure;
	final HttpRequest nettyRequest;
	final HttpHeaders requestHeaders;

	volatile ResponseState responseState;
	int inboundPrefetch;

	boolean started;

	boolean clientError = true;
	boolean serverError = true;
	boolean redirectable;

	HttpClientOperations(HttpClientOperations replaced) {
		super(replaced);
		this.started = replaced.started;
		this.redirectedFrom = replaced.redirectedFrom;
		this.isSecure = replaced.isSecure;
		this.nettyRequest = replaced.nettyRequest;
		this.responseState = replaced.responseState;
		this.redirectable = replaced.redirectable;
		this.inboundPrefetch = replaced.inboundPrefetch;
		this.requestHeaders = replaced.requestHeaders;
		this.clientError = replaced.clientError;
		this.serverError = replaced.serverError;
	}

	HttpClientOperations(Connection connection, ConnectionEvents listener) {
		super(connection, listener);
		this.isSecure = connection.channel()
		                          .pipeline()
		                       .get(NettyPipeline.SslHandler) != null;
		String[] redirects = connection.channel()
		                               .attr(REDIRECT_ATTR_KEY)
		                               .get();
		this.redirectedFrom = redirects == null ? EMPTY_REDIRECTIONS : redirects;
		this.nettyRequest =
				new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
		this.requestHeaders = nettyRequest.headers();
		this.requestHeaders.set(HttpHeaderNames.USER_AGENT, HttpClient.USER_AGENT);
		this.inboundPrefetch = 16;
		chunkedTransfer(true);
	}

	@Override
	public HttpClientRequest addCookie(Cookie cookie) {
		if (!isSent()) {
			this.requestHeaders.add(HttpHeaderNames.COOKIE,
					ClientCookieEncoder.STRICT.encode(cookie));
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpClientOperations withConnection(Consumer<? super Connection> connectionHandler) {
		Objects.requireNonNull(connectionHandler, "connectionHandler");
		connectionHandler.accept(this);
		return this;
	}

	@Override
	public HttpClientOperations addHandlerLast(ChannelHandler handler) {
		super.addHandlerLast(handler);
		return this;
	}

	@Override
	public HttpClientOperations addHandlerLast(String name, ChannelHandler handler) {
		super.addHandlerLast(name, handler);
		return this;
	}

	@Override
	public HttpClientOperations addHandlerFirst(ChannelHandler handler) {
		super.addHandlerFirst(handler);
		return this;
	}

	@Override
	public HttpClientOperations addHandlerFirst(String name, ChannelHandler handler) {
		super.addHandlerFirst(name, handler);
		return this;
	}

	@Override
	public HttpClientOperations addHandler(ChannelHandler handler) {
		super.addHandler(handler);
		return this;
	}

	@Override
	public HttpClientOperations addHandler(String name, ChannelHandler handler) {
		super.addHandler(name, handler);
		return this;
	}

	@Override
	public HttpClientOperations replaceHandler(String name, ChannelHandler handler) {
		super.replaceHandler(name, handler);
		return this;
	}

	@Override
	public HttpClientOperations removeHandler(String name) {
		super.removeHandler(name);
		return this;
	}

	@Override
	public HttpClientRequest addHeader(CharSequence name, CharSequence value) {
		if (!isSent()) {
			this.requestHeaders.add(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public InetSocketAddress address() {
		return ((SocketChannel) channel()).remoteAddress();
	}

	@Override
	public HttpClientRequest chunkedTransfer(boolean chunked) {
		if (!isSent() && HttpUtil.isTransferEncodingChunked(nettyRequest) != chunked) {
			requestHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
			HttpUtil.setTransferEncodingChunked(nettyRequest, chunked);
		}
		return this;
	}

	@Override
	public Map<CharSequence, Set<Cookie>> cookies() {
		ResponseState responseState = this.responseState;
		if (responseState != null) {
			return responseState.cookieHolder.getCachedCookies();
		}
		return null;
	}

	@Override
	public HttpClientRequest followRedirect() {
		redirectable = true;
		return this;
	}

	@Override
	public HttpClientRequest failOnClientError(boolean shouldFail) {
		clientError = shouldFail;
		return this;
	}

	@Override
	public HttpClientRequest failOnServerError(boolean shouldFail) {
		serverError = shouldFail;
		return this;
	}

	@Override
	protected void onInboundCancel() {
		channel().close();
	}

	@Override
	public HttpClientRequest header(CharSequence name, CharSequence value) {
		if (!isSent()) {
			this.requestHeaders.set(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpClientRequest headers(HttpHeaders headers) {
		if (!isSent()) {
			String host = requestHeaders.get(HttpHeaderNames.HOST);
			this.requestHeaders.set(headers);
			this.requestHeaders.set(HttpHeaderNames.HOST, host);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public boolean isFollowRedirect() {
		return redirectable && redirectedFrom.length <= MAX_REDIRECTS;
	}

	@Override
	public boolean isKeepAlive() {
		return HttpUtil.isKeepAlive(nettyRequest);
	}

	@Override
	public boolean isWebsocket() {
		return get(channel()).getClass()
		                     .equals(WebsocketClientOperations.class);
	}

	@Override
	public HttpClientRequest keepAlive(boolean keepAlive) {
		HttpUtil.setKeepAlive(nettyRequest, keepAlive);
		return this;
	}

	@Override
	public HttpMethod method() {
		return nettyRequest.method();
	}

	@Override
	public final HttpClientOperations onDispose(Disposable onDispose) {
		super.onDispose(onDispose);
		return this;
	}

	@Override
	public String[] redirectedFrom() {
		String[] redirectedFrom = this.redirectedFrom;
		String[] dest = new String[redirectedFrom.length];
		System.arraycopy(redirectedFrom, 0, dest, 0, redirectedFrom.length);
		return dest;
	}

	@Override
	public HttpHeaders requestHeaders() {
		return nettyRequest.headers();
	}

	public HttpHeaders responseHeaders() {
		ResponseState responseState = this.responseState;
		if (responseState != null) {
			return responseState.headers;
		}
		else {
			return null;
		}
	}

	@Override
	public NettyOutbound send(Publisher<? extends ByteBuf> source) {
		if (method() == HttpMethod.GET || method() == HttpMethod.HEAD) {
			ByteBufAllocator alloc = channel().alloc();
			return then(Flux.from(source)
			    .doOnNext(ByteBuf::retain)
			    .collect(alloc::buffer, ByteBuf::writeBytes)
			    .flatMapMany(agg -> {
				    if (!isSent() && !HttpUtil.isTransferEncodingChunked(
						    outboundHttpMessage()) && !HttpUtil.isContentLengthSet(
						    outboundHttpMessage())) {
					    outboundHttpMessage().headers()
					                         .setInt(HttpHeaderNames.CONTENT_LENGTH,
							                         agg.readableBytes());
				    }
				    return send(Mono.just(agg)).then();
			    }));
		}
		return super.send(source);
	}

	final Flux<Long> sendForm(Consumer<HttpClientForm> formCallback) {
		return new FluxSendForm(this, formCallback);
	}

	final WebsocketOutbound sendWebsocket() {
		return sendWebsocket(null);
	}

	final WebsocketOutbound sendWebsocket(String subprotocols) {
		Mono<Void> m = withWebsocketSupport(websocketUri(), subprotocols, noopHandler());

		return new WebsocketOutbound() {

			@Override
			public ByteBufAllocator alloc() {
				return HttpClientOperations.this.alloc();
			}

			@Override
			public NettyOutbound sendFile(Path file, long position, long count) {
				return then(HttpClientOperations.this.sendFile(file, position, count));
			}

			@Override
			public NettyOutbound sendFileChunked(Path file) {
				return then(HttpClientOperations.this.sendFileChunked(file));
			}

			@Override
			public NettyOutbound sendObject(Publisher<?> dataStream) {
				return then(HttpClientOperations.this.sendObject(dataStream));
			}

			@Override
			public String selectedSubprotocol() {
				return subprotocols;
			}

			@Override
			public WebsocketOutbound withConnection(Consumer<? super Connection> onConnection) {
				onConnection.accept(connection());
				return this;
			}

			@Override
			public Mono<Void> then() {
				return m;
			}
		};
	}

	final Mono<Void> receiveWebsocket(String protocols,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler) {
		Objects.requireNonNull(websocketHandler, "websocketHandler");
		return withWebsocketSupport(websocketUri(), protocols, websocketHandler);
	}

	final URI websocketUri() {
		URI uri;
		try {
			String url = uri();
			if (url.startsWith(HttpClient.HTTP_SCHEME) || url.startsWith(HttpClient.WS_SCHEME)) {
				uri = new URI(url);
			}
			else {
				String host = requestHeaders().get(HttpHeaderNames.HOST);
				uri = new URI((isSecure ? HttpClient.WSS_SCHEME :
						HttpClient.WS_SCHEME) + "://" + host + (url.startsWith("/") ?
						url : "/" + url));
			}

		}
		catch (URISyntaxException e) {
			throw Exceptions.bubble(e);
		}
		return uri;
	}

	@Override
	public HttpResponseStatus status() {
		ResponseState responseState = this.responseState;
		if (responseState != null) {
			return HttpResponseStatus.valueOf(responseState.response.status()
			                                                        .code());
		}
		return null;
	}

	@Override
	public final String uri() {
		return this.nettyRequest.uri();
	}

	@Override
	public final HttpVersion version() {
		HttpVersion version = this.nettyRequest.protocolVersion();
		if (version.equals(HttpVersion.HTTP_1_0)) {
			return HttpVersion.HTTP_1_0;
		}
		else if (version.equals(HttpVersion.HTTP_1_1)) {
			return HttpVersion.HTTP_1_1;
		}
		throw new IllegalStateException(version.protocolName() + " not supported");
	}

	@Override
	protected void onOutboundComplete() {
		if (isWebsocket() || isInboundCancelled()) {
			return;
		}
		if (markSentHeaderAndBody()) {
			if (log.isDebugEnabled()) {
				log.debug("No sendHeaders() called before complete, sending " + "zero-length header");
			}
			channel().writeAndFlush(newFullEmptyBodyMessage());
		}
		else if (markSentBody()) {
			channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
		}
		channel().read();
	}

	@Override
	protected void onOutboundError(Throwable err) {
		if(Connection.isPersistent(channel()) && responseState == null){
			listener().onReceiveError(channel(), err);
			onHandlerTerminate();
			return;
		}
		super.onOutboundError(err);
	}

	@Override
	protected void onInboundNext(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof HttpResponse) {
			HttpResponse response = (HttpResponse) msg;
			if (response.decoderResult()
			            .isFailure()) {
				onInboundError(response.decoderResult()
				                       .cause());
				return;
			}
			if (started) {
				if (log.isDebugEnabled()) {
					log.debug("{} An HttpClientOperations cannot proceed more than one "
									+ "Response", channel(),
							response.headers()
							        .toString());
				}
				return;
			}
			started = true;

			if (!isKeepAlive()) {
				markPersistent(false);
			}
			if(isInboundCancelled()){
				ReferenceCountUtil.release(msg);
				return;
			}

			setNettyResponse(response);

			if (log.isDebugEnabled()) {
				log.debug("{} Received response (auto-read:{}) : {}",
						channel(),
						channel().config()
						         .isAutoRead(),
						responseHeaders().entries()
						                 .toString());
			}

			if (checkResponseCode(response)) {
				prefetchMore(ctx);
				listener().onStart(this);
			}
			if (msg instanceof FullHttpResponse) {
				super.onInboundNext(ctx, msg);
				onHandlerTerminate();
			}
			return;
		}
		if (msg instanceof LastHttpContent) {
			if (!started) {
				if (log.isDebugEnabled()) {
					log.debug("{} HttpClientOperations received an incorrect end " +
							"delimiter" + "(previously used connection?)", channel());
				}
				return;
			}
			if (log.isDebugEnabled()) {
				log.debug("{} Received last HTTP packet", channel());
			}
			if (msg != LastHttpContent.EMPTY_LAST_CONTENT) {
				super.onInboundNext(ctx, msg);
			}
			//force auto read to enable more accurate close selection now inbound is done
			channel().config().setAutoRead(true);
			onHandlerTerminate();
			return;
		}

		if (!started) {
			if (log.isDebugEnabled()) {
				if (msg instanceof ByteBufHolder) {
					msg = ((ByteBufHolder) msg).content();
				}
				log.debug("{} HttpClientOperations received an incorrect chunk " + "" +
								"(previously used connection?)",
						channel(), msg);
			}
			return;
		}
		super.onInboundNext(ctx, msg);
		prefetchMore(ctx);
	}

	@Override
	protected HttpMessage outboundHttpMessage() {
		return nettyRequest;
	}

	final boolean checkResponseCode(HttpResponse response) {
		int code = response.status()
		                   .code();
		if (code >= 500) {
			if (serverError){
				if (log.isDebugEnabled()) {
					log.debug("{} Received Server Error, stop reading: {}", channel(),
							response
									.toString());
				}
				Exception ex = new HttpClientException(uri(), response);
				listener().onReceiveError(channel(), ex);
				onHandlerTerminate();
				return false;
			}
			return true;
		}

		if (code >= 400) {
			if (clientError) {
				if (log.isDebugEnabled()) {
					log.debug("{} Received Request Error, stop reading: {}",
							channel(),
							response.toString());
				}
				Exception ex = new HttpClientException(uri(), response);
				listener().onReceiveError(channel(), ex);
				onHandlerTerminate();
				return false;
			}
			return true;
		}
		if (code == 301 || code == 302 && isFollowRedirect()) {
			if (log.isDebugEnabled()) {
				log.debug("{} Received Redirect location: {}",
						channel(),
						response.headers()
						        .entries()
						        .toString());
			}
			Exception ex = new RedirectClientException(uri(), response);
			listener().onReceiveError(channel(), ex);
			onHandlerTerminate();
			return false;
		}
		return true;
	}

	@Override
	protected HttpMessage newFullEmptyBodyMessage() {
		HttpRequest request = new DefaultFullHttpRequest(version(), method(), uri());

		request.headers()
		       .set(requestHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING)
		                          .setInt(HttpHeaderNames.CONTENT_LENGTH, 0));
		return request;
	}

	final HttpRequest getNettyRequest() {
		return nettyRequest;
	}

	final void prefetchMore(ChannelHandlerContext ctx) {
		int inboundPrefetch = this.inboundPrefetch - 1;
		if (inboundPrefetch >= 0) {
			this.inboundPrefetch = inboundPrefetch;
			ctx.read();
		}
	}

	final void setNettyResponse(HttpResponse nettyResponse) {
		ResponseState state = responseState;
		if (state == null) {
			this.responseState =
					new ResponseState(nettyResponse, nettyResponse.headers());
		}
	}

	final Mono<Void> withWebsocketSupport(URI url,
			String protocols,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler) {

		//prevent further header to be sent for handshaking
		if (markSentHeaders()) {
			addHandlerFirst(NettyPipeline.HttpAggregator, new HttpObjectAggregator(8192));

			WebsocketClientOperations
					ops = new WebsocketClientOperations(url, protocols, this);

			if (replace(ops)) {
				Mono<Void> handshake = FutureMono.from(ops.handshakerResult)
				                                 .then(Mono.defer(() -> Mono.from(websocketHandler.apply(
						                                 ops,
						                                 ops))));
				if (websocketHandler != noopHandler()) {
					handshake = handshake.doAfterTerminate(ops);
				}
				return handshake;
			}
		}
		else if (isWebsocket()) {
			WebsocketClientOperations ops =
					(WebsocketClientOperations) get(channel());
			if(ops != null) {
				Mono<Void> handshake = FutureMono.from(ops.handshakerResult);

				if (websocketHandler != noopHandler()) {
					handshake =
							handshake.then(Mono.defer(() -> Mono.from(websocketHandler.apply(ops, ops)))
							         .doAfterTerminate(ops));
				}
				return handshake;
			}
		}
		else {
			log.error("Cannot enable websocket if headers have already been sent");
		}
		return Mono.error(new IllegalStateException("Failed to upgrade to websocket"));
	}

	static final class ResponseState {

		final HttpResponse response;
		final HttpHeaders  headers;
		final Cookies      cookieHolder;

		ResponseState(HttpResponse response, HttpHeaders headers) {
			this.response = response;
			this.headers = headers;
			this.cookieHolder = Cookies.newClientResponseHolder(headers);
		}
	}

	static final class FluxSendForm extends Flux<Long> {

		static final HttpDataFactory DEFAULT_FACTORY = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE);

		final HttpClientOperations     parent;
		final Consumer<HttpClientForm> formCallback;

		FluxSendForm(HttpClientOperations parent,
				Consumer<HttpClientForm> formCallback) {
			this.parent = parent;
			this.formCallback = formCallback;
		}

		@Override
		public void subscribe(CoreSubscriber<? super Long> s) {
			if (parent.channel()
			          .eventLoop()
			          .inEventLoop()) {
				_subscribe(s);
			}
			else {
				parent.channel()
				      .eventLoop()
				      .execute(() -> _subscribe(s));
			}
		}

		void _subscribe(CoreSubscriber<? super Long> s) {
			if (!parent.markSentHeaders()) {
				Operators.error(s,
						new IllegalStateException("headers have already " + "been sent"));
				return;
			}

			HttpDataFactory df = DEFAULT_FACTORY;

			try {
				HttpClientFormEncoder encoder = new HttpClientFormEncoder(df,
						parent.nettyRequest,
						false,
						HttpConstants.DEFAULT_CHARSET,
						HttpPostRequestEncoder.EncoderMode.RFC1738);

				formCallback.accept(encoder);

				encoder = encoder.applyChanges(parent.nettyRequest);
				df = encoder.newFactory;

				if (!encoder.isMultipart()) {
					parent.chunkedTransfer(false);
				}

				parent.addHandlerFirst(NettyPipeline.ChunkedWriter, new ChunkedWriteHandler());

				boolean chunked = HttpUtil.isTransferEncodingChunked(parent.nettyRequest);

				HttpRequest r = encoder.finalizeRequest();

				if (!chunked) {
					HttpUtil.setTransferEncodingChunked(r, false);
					HttpUtil.setContentLength(r, encoder.length());
				}

				ChannelFuture f = parent.channel()
				                        .writeAndFlush(r);

				Flux<Long> tail = encoder.progressFlux.onBackpressureLatest();

				if (encoder.cleanOnTerminate) {
					tail = tail.doOnCancel(encoder)
					           .doAfterTerminate(encoder);
				}

				if (encoder.isChunked()) {
					tail.subscribe(s);
					parent.channel()
					      .writeAndFlush(encoder);
				}
				else {
					FutureMono.from(f)
					          .cast(Long.class)
					          .switchIfEmpty(Mono.just(encoder.length()))
					          .flux()
					          .subscribe(s);
				}


			}
			catch (Throwable e) {
				Exceptions.throwIfFatal(e);
				df.cleanRequestHttpData(parent.nettyRequest);
				Operators.error(s, Exceptions.unwrap(e));
			}
		}
	}

	static final int                    MAX_REDIRECTS      = 50;
	static final String[]               EMPTY_REDIRECTIONS = new String[0];
	static final Logger                 log                =
			Loggers.getLogger(HttpClientOperations.class);
	static final AttributeKey<String[]> REDIRECT_ATTR_KEY  =
			AttributeKey.newInstance("httpRedirects");

	/**
	 * Return a Noop {@link BiFunction} handler
	 *
	 * @param <INBOUND> reified inbound type
	 * @param <OUTBOUND> reified outbound type
	 *
	 * @return a Noop {@link BiFunction} handler
	 */
	@SuppressWarnings("unchecked")
	static <INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound> BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>> noopHandler() {
		return PING;
	}

	static final BiFunction PING = (i, o) -> Flux.empty();
}
