/*
 * Copyright 2016 Netflix, Inc.
 * <p>
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 *  the License. You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 *  specific language governing permissions and limitations under the License.
 */

package io.reactivesocket.transport.tcp.server;

import io.netty.buffer.ByteBuf;
import io.reactivesocket.ConnectionSetupHandler;
import io.reactivesocket.DefaultReactiveSocket;
import io.reactivesocket.Frame;
import io.reactivesocket.LeaseGovernor;
import io.reactivesocket.ReactiveSocket;
import io.reactivesocket.internal.EmptySubject;
import io.reactivesocket.internal.Publishers;
import io.reactivesocket.rx.Completable;
import io.reactivesocket.transport.tcp.ReactiveSocketFrameCodec;
import io.reactivesocket.transport.tcp.ReactiveSocketLengthCodec;
import io.reactivesocket.transport.tcp.TcpDuplexConnection;
import io.reactivex.netty.channel.Connection;
import io.reactivex.netty.protocol.tcp.server.ConnectionHandler;
import io.reactivex.netty.protocol.tcp.server.TcpServer;
import rx.Observable;
import rx.RxReactiveStreams;

import java.net.SocketAddress;
import java.util.function.Function;

public class TcpReactiveSocketServer {

    private final TcpServer<Frame, Frame> server;

    private TcpReactiveSocketServer(TcpServer<Frame, Frame> server) {
        this.server = server;
    }

    public StartedServer start(ConnectionSetupHandler setupHandler) {
        return start(setupHandler, LeaseGovernor.UNLIMITED_LEASE_GOVERNOR);
    }

    public StartedServer start(ConnectionSetupHandler setupHandler, LeaseGovernor leaseGovernor) {
        server.start(new ConnectionHandler<Frame, Frame>() {
            @Override
            public Observable<Void> handle(Connection<Frame, Frame> newConnection) {
                TcpDuplexConnection c = new TcpDuplexConnection(newConnection);
                ReactiveSocket rs = DefaultReactiveSocket.fromServerConnection(c, setupHandler, leaseGovernor,
                                                                               Throwable::printStackTrace);
                EmptySubject startNotifier = new EmptySubject();
                rs.start(new Completable() {
                    @Override
                    public void success() {
                        startNotifier.onComplete();
                    }

                    @Override
                    public void error(Throwable e) {
                        startNotifier.onError(e);
                    }
                });
                return RxReactiveStreams.toObservable(Publishers.concatEmpty(startNotifier, rs.onClose()));
            }
        });

        return new StartedServer();
    }

    /**
     * Configures the underlying server using the passed {@code configurator}.
     *
     * @param configurator Function to transform the underlying server.
     *
     * @return New instance of {@code TcpReactiveSocketServer}.
     */
    public TcpReactiveSocketServer configureServer(
            Function<TcpServer<Frame, Frame>, TcpServer<Frame, Frame>> configurator) {
        return new TcpReactiveSocketServer(configurator.apply(server));
    }

    public static TcpReactiveSocketServer create() {
        return create(TcpServer.newServer());
    }

    public static TcpReactiveSocketServer create(int port) {
        return create(TcpServer.newServer(port));
    }

    public static TcpReactiveSocketServer create(SocketAddress address) {
        return create(TcpServer.newServer(address));
    }

    public static TcpReactiveSocketServer create(TcpServer<ByteBuf, ByteBuf> rxNettyServer) {
        return new TcpReactiveSocketServer(configure(rxNettyServer));
    }

    private static TcpServer<Frame, Frame> configure(TcpServer<ByteBuf, ByteBuf> rxNettyServer) {
        return rxNettyServer.addChannelHandlerLast("line-codec", ReactiveSocketLengthCodec::new)
                            .addChannelHandlerLast("frame-codec", ReactiveSocketFrameCodec::new);
    }

    public final class StartedServer {

        public SocketAddress getServerAddress() {
            return server.getServerAddress();
        }

        public int getServerPort() {
            return server.getServerPort();
        }

        public void awaitShutdown() {
            server.awaitShutdown();
        }

        public void shutdown() {
            server.shutdown();
        }
    }
}
