/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty.request.body;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;

import static org.asynchttpclient.test.TestUtils.LARGE_IMAGE_BYTES_MD5;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.NoSuchElementException;

import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.util.ByteBufUtils;
import org.asynchttpclient.util.Base64;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.netty.HandlerSubscriber;

public class NettyReactiveStreamsBody implements NettyBody {

	private static final Logger LOGGER = LoggerFactory.getLogger(NettyReactiveStreamsBody.class);
	private static final String NAME_IN_CHANNEL_PIPELINE = "request-body-streamer";

	private final Publisher<ByteBuf> publisher;

	private final long contentLength;

	public NettyReactiveStreamsBody(Publisher<ByteBuf> publisher, long contentLength) {
		this.publisher = publisher;
		this.contentLength = contentLength;
	}

	@Override
	public long getContentLength() {
		return contentLength;
	}

	@Override
	public void write(Channel channel, NettyResponseFuture<?> future) throws IOException {
		if (future.isStreamConsumed()) {
			LOGGER.warn("Stream has already been consumed and cannot be reset");
		} else {
			future.setStreamConsumed(true);
			NettySubscriber subscriber = new NettySubscriber(channel, future);
			channel.pipeline().addLast(NAME_IN_CHANNEL_PIPELINE, subscriber);
			publisher.subscribe(new SubscriberAdapter(subscriber));
		}
	}

	private static class SubscriberAdapter implements Subscriber<ByteBuf> {
		private final Subscriber<HttpContent> subscriber;

		public SubscriberAdapter(Subscriber<HttpContent> subscriber) {
			this.subscriber = subscriber;
		}

		@Override
		public void onSubscribe(Subscription s) {
			subscriber.onSubscribe(s);
		}

		@Override
		public void onNext(ByteBuf buffer) {
			HttpContent content = new DefaultHttpContent(buffer);
			subscriber.onNext(content);
		}

		@Override
		public void onError(Throwable t) {
			subscriber.onError(t);
		}

		@Override
		public void onComplete() {
			subscriber.onComplete();
		}
	}

	private static class NettySubscriber extends HandlerSubscriber<HttpContent> {
		private static final Logger LOGGER = LoggerFactory.getLogger(NettySubscriber.class);

		private final Channel channel;
		private final NettyResponseFuture<?> future;

		public NettySubscriber(Channel channel, NettyResponseFuture<?> future) {
			super(channel.eventLoop());
			this.channel = channel;
			this.future = future;
		}

		private static MessageDigest newMd5() {
			try {
				return MessageDigest.getInstance("MD5");
			} catch (NoSuchAlgorithmException e) {
				throw new InternalError(e);
			}
		}
		private final MessageDigest md = newMd5();
		
		@Override
		public void onNext(HttpContent t) {
			LOGGER.debug(">>>>>>>>>>>>onNext with {} bytes", t.content().readableBytes());
			if (t.content().isReadable()) {
				byte[] bytes = ByteBufUtils.byteBuf2Bytes(t.content().duplicate());
				md.update(bytes, 0, bytes.length);
			}
			
			// TODO Auto-generated method stub
			super.onNext(t);
		}

		@Override
		protected void complete() {
			LOGGER.debug(">>>>>>>>>>>>complete with {} md5", Base64.encode(md.digest()));
			md.reset();
			channel.eventLoop().execute(() -> channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
					.addListener(future -> removeFromPipeline())); // FIXME why not remove immediately?
		}

		@Override
		protected void error(Throwable error) {
			if (error == null)
				throw null;
			removeFromPipeline();
			future.abort(error);
		}

		private void removeFromPipeline() {
			try {
				channel.pipeline().remove(this);
				LOGGER.debug(String.format("Removed handler %s from pipeline.", NAME_IN_CHANNEL_PIPELINE));
			} catch (NoSuchElementException e) {
				LOGGER.debug(String.format("Failed to remove handler %s from pipeline.", NAME_IN_CHANNEL_PIPELINE), e);
			}
		}
	}
}
