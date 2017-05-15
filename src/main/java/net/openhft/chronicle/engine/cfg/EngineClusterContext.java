/*
 * Copyright 2016 higherfrequencytrading.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package net.openhft.chronicle.engine.cfg;

import net.openhft.chronicle.core.annotation.UsedViaReflection;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.util.ThrowingFunction;
import net.openhft.chronicle.engine.HeartbeatHandler;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.fs.EngineConnectionManager;
import net.openhft.chronicle.engine.server.internal.EngineNetworkStatsListener;
import net.openhft.chronicle.engine.server.internal.EngineWireHandler;
import net.openhft.chronicle.engine.server.internal.EngineWireNetworkContext;
import net.openhft.chronicle.engine.server.internal.UberHandler;
import net.openhft.chronicle.engine.tree.HostIdentifier;
import net.openhft.chronicle.network.*;
import net.openhft.chronicle.network.api.TcpHandler;
import net.openhft.chronicle.network.api.session.SessionDetailsProvider;
import net.openhft.chronicle.network.cluster.ClusterContext;
import net.openhft.chronicle.network.cluster.HostIdConnectionStrategy;
import net.openhft.chronicle.network.connection.VanillaWireOutPublisher;
import net.openhft.chronicle.wire.WireIn;
import net.openhft.chronicle.wire.WireType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.function.Function;

import static net.openhft.chronicle.network.NetworkStatsListener.notifyHostPort;

/**
 * @author Rob Austin.
 */
public class EngineClusterContext extends ClusterContext {
    private static final Logger LOG = LoggerFactory.getLogger(EngineClusterContext.class);
    Asset assetRoot;
    private byte localIdentifier;
    @NotNull
    private NetworkStatsListener defaultNetworkStatsListener = new NetworkStatsListener() {

        String host;
        long port;

        @Override
        public void close() {
            LOG.info("writeKBps=0" +
                    ", readKBps=0" +
                    ", socketPollCountPerSecond=0" +
                    ", host=" + host +
                    ", port=" + port +
                    ", isConnected=false");
        }

        @Override
        public void networkContext(NetworkContext networkContext) {

        }

        @Override
        public void onNetworkStats(long writeBps,
                                   long readBps,
                                   long socketPollCountPerSecond) {

            LOG.info("writeKBps=" + writeBps / 1000 +
                    ", readKBps=" + readBps / 1000 +
                    ", socketPollCountPerSecond=" + socketPollCountPerSecond +
                    ", host=" + host +
                    ", port=" + port +
                    ", isConnected=true");
        }

        @Override
        public void onHostPort(String hostName, int port) {
            host = hostName;
            this.port = port;
        }

        @Override
        public void onRoundTripLatency(long nanosecondLatency) {

        }
    };

    @UsedViaReflection
    private EngineClusterContext(@NotNull WireIn w) {
        super(w);
    }

    public EngineClusterContext() {
        super();
    }

    @Nullable
    public ThrowingFunction<NetworkContext, TcpEventHandler, IOException> tcpEventHandlerFactory() {
        return (networkContext) -> {

            @NotNull final EngineWireNetworkContext nc = (EngineWireNetworkContext) networkContext;


            if (nc.isAcceptor())
                nc.wireOutPublisher(new VanillaWireOutPublisher(WireType.TEXT));
            // TODO make configurable.
            networkContext.serverThreadingStrategy(ServerThreadingStrategy.CONCURRENT);
            @NotNull final TcpEventHandler handler = new TcpEventHandler(networkContext);

            @NotNull final Function<Object, TcpHandler> consumer = o -> {
                if (o instanceof SessionDetailsProvider) {
                    @NotNull final SessionDetailsProvider sessionDetails = (SessionDetailsProvider) o;
                    nc.heartbeatTimeoutMs(heartbeatTimeoutMs());
                    nc.sessionDetails(sessionDetails);
                    nc.wireType(sessionDetails.wireType());

                    @Nullable final WireType wireType = nc.sessionDetails().wireType();
                    if (wireType != null)
                        nc.wireOutPublisher().wireType(wireType);
                    return new EngineWireHandler();
                } else if (o instanceof TcpHandler)
                    return (TcpHandler) o;

                throw new UnsupportedOperationException("not supported class=" + o.getClass());
            };

            if (nc.networkStatsListener() == null)
                nc.networkStatsListener(defaultNetworkStatsListener);


            final NetworkStatsListener nl = nc.networkStatsListener();
            if (nl != null)
                notifyHostPort(nc.socketChannel(), nl);

            @Nullable final Function<EngineWireNetworkContext, TcpHandler> f
                    = x -> new HeaderTcpHandler<>(handler, consumer, x);

            @NotNull final WireTypeSniffingTcpHandler sniffer = new
                    WireTypeSniffingTcpHandler<>(handler, f);

            handler.tcpHandler(sniffer);
            return handler;

        };
    }


    public Asset assetRoot() {
        return assetRoot;
    }

    @NotNull
    public EngineClusterContext assetRoot(@NotNull Asset assetRoot) {
        this.assetRoot = assetRoot;
        localIdentifier = HostIdentifier.localIdentifier(assetRoot);
        localIdentifier(localIdentifier);
        eventLoop(assetRoot.findOrCreateView(EventLoop.class));
        return this;
    }

    @Override
    public void defaults() {
        wireType(WireType.TEXT);
        handlerFactory(new UberHandler.Factory());
        wireOutPublisherFactory(new VanillaWireOutPublisherFactory());
        networkContextFactory(new EngineWireNetworkContext.Factory());
        networkStatsListenerFactory(new EngineNetworkStatsListener.Factory());
        connectionEventHandler(new EngineConnectionManager.Factory());
        heartbeatFactory(new HeartbeatHandler.Factory());
        heartbeatTimeoutMs(5_000L);
        heartbeatIntervalMs(1_000L);
        connectionStrategy(new HostIdConnectionStrategy());
        serverThreadingStrategy(ServerThreadingStrategy.SINGLE_THREADED);
    }
}
