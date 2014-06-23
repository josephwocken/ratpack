/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ratpack.http.internal;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.*;
import org.reactivestreams.spi.Publisher;
import org.reactivestreams.spi.Subscriber;
import org.reactivestreams.spi.Subscription;
import ratpack.exec.ExecControl;
import ratpack.http.StreamElement;
import ratpack.http.StreamTransmitter;

import static io.netty.handler.codec.http.HttpHeaders.isKeepAlive;

abstract class StreamTransmitterSupport<T extends StreamElement> implements StreamTransmitter<T> {

  private final FullHttpRequest request;
  private final HttpHeaders httpHeaders;
  protected final Channel channel;

  public StreamTransmitterSupport(FullHttpRequest request, HttpHeaders httpHeaders, Channel channel) {
    this.request = request;
    this.httpHeaders = httpHeaders;
    this.channel = channel;
  }

  @Override
  public void transmit(ExecControl execContext, Publisher<T> stream) {
    final HttpResponse response = new CustomHttpResponse(HttpResponseStatus.OK, httpHeaders);

    setResponseHeaders(response);
    request.content().release();

    HttpResponse minimalResponse = new DefaultHttpResponse(response.getProtocolVersion(), response.getStatus());
    minimalResponse.headers().set(response.headers());

    ChannelFuture writeFuture = channel.writeAndFlush(minimalResponse);
    writeFuture.addListener(new ChannelFutureListener() {
      public void operationComplete(ChannelFuture future) throws Exception {
        if (!future.isSuccess()) {
          channel.close();
        }
      }
    });

    execContext.stream(stream, new Subscriber<T>() {
      Subscription subscription;

      @Override
      public void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        this.subscription.requestMore(Integer.MAX_VALUE);
      }

      @Override
      public void onNext(T element) {
        ChannelFuture writeFuture = channel.writeAndFlush(element.getValue());
        writeFuture.addListener(new ChannelFutureListener() {
          public void operationComplete(ChannelFuture future) throws Exception {
            if (!future.isSuccess()) {
              channel.close();
            }
          }
        });
        this.subscription.requestMore(1); //TODO: request Integer.MAX again when a threshold is reached
      }

      @Override
      public void onComplete() {
        doOnComplete();
        ChannelFuture lastContentFuture = channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        lastContentFuture.addListener(ChannelFutureListener.CLOSE);
      }

      @Override
      public void onError(Throwable cause) {
        ChannelFuture lastContentFuture = channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        lastContentFuture.addListener(ChannelFutureListener.CLOSE);
      }
    });
  }

  protected void doOnComplete() { }

  protected void setResponseHeaders(HttpResponse response) {
    if (isKeepAlive(request)) {
      response.headers().set(HttpHeaderConstants.CONNECTION, HttpHeaderConstants.KEEP_ALIVE);
    }
  }

}