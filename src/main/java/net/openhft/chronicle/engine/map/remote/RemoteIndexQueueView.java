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

package net.openhft.chronicle.engine.map.remote;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.engine.api.pubsub.InvalidSubscriberException;
import net.openhft.chronicle.engine.api.pubsub.Subscriber;
import net.openhft.chronicle.engine.api.query.IndexQuery;
import net.openhft.chronicle.engine.api.query.IndexQueueView;
import net.openhft.chronicle.engine.api.query.IndexedValue;
import net.openhft.chronicle.engine.api.query.VanillaIndexQuery;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.engine.server.internal.MapWireHandler;
import net.openhft.chronicle.network.connection.AbstractAsyncSubscription;
import net.openhft.chronicle.network.connection.AbstractStatelessClient;
import net.openhft.chronicle.network.connection.TcpChannelHub;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static net.openhft.chronicle.engine.server.internal.IndexQueueViewHandler.EventId.*;
import static net.openhft.chronicle.network.connection.CoreFields.reply;

/**
 * @author Rob Austin.
 */
public class RemoteIndexQueueView<K extends Marshallable, V extends Marshallable> extends
        AbstractStatelessClient<MapWireHandler.EventId>
        implements IndexQueueView<Subscriber<IndexedValue<V>>, V> {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteIndexQueueView.class);
    private final Map<Object, Long> subscribersToTid = new ConcurrentHashMap<>();
    int i;

    public RemoteIndexQueueView(@NotNull final RequestContext context,
                                @NotNull Asset asset) {
        super(asset.findView(TcpChannelHub.class), (long) 0, toUri(context));
    }

    @NotNull
    private static String toUri(@NotNull final RequestContext context) {
        return context.viewType(IndexQueueView.class).toUri();
    }

    @Override
    public void registerSubscriber(@NotNull Subscriber<IndexedValue<V>> subscriber,
                                   @NotNull IndexQuery<V> vanillaIndexQuery) {

        @NotNull final AtomicBoolean hasAlreadySubscribed = new AtomicBoolean();

        if (hub.outBytesLock().isHeldByCurrentThread())
            throw new IllegalStateException("Cannot view map while debugging");

        @Nullable final AbstractAsyncSubscription asyncSubscription = new AbstractAsyncSubscription(
                hub,
                csp,
                "RemoteIndexQueueView registerTopicSubscriber") {

            // this allows us to resubscribe from the last index we received
            volatile long fromIndex = 0;

            @Override
            public void onSubscribe(@NotNull final WireOut wireOut) {
                IndexQuery<V> q;
                // this allows us to resubscribe from the last index we received
                if (hasAlreadySubscribed.getAndSet(true)) {
                    VanillaIndexQuery query = vanillaIndexQuery.deepCopy();
                    query.fromIndex(fromIndex + 1);
                    query.bootstrap(false);
                    q = query;
                } else
                    q = vanillaIndexQuery;

                subscribersToTid.put(subscriber, tid());
                wireOut.writeEventName(registerSubscriber)
                        .typedMarshallable(q);
            }

            private final IndexedValue<V> instance = new IndexedValue<>();
            private final Function<Class, ReadMarshallable> reuseFunction = c -> instance;

            @Override
            public void onConsumer(@NotNull final WireIn inWire) {

                try (DocumentContext dc = inWire.readingDocument()) {
                    if (!dc.isPresent())
                        return;

                    final StringBuilder sb = Wires.acquireStringBuilder();
                    @NotNull final ValueIn valueIn = dc.wire().readEventName(sb);

                    if (reply.contentEquals(sb))
                        try {
                            @Nullable final IndexedValue<V> e = valueIn.typedMarshallable(reuseFunction);
                            fromIndex = Math.max(fromIndex, e.index());
                            subscriber.onMessage(e);
                        } catch (InvalidSubscriberException e) {
                            RemoteIndexQueueView.this.unregisterSubscriber(subscriber);
                        }
                    else if (onEndOfSubscription.contentEquals(sb)) {
                        subscriber.onEndOfSubscription();
                        hub.unsubscribe(tid());
                    }
                } catch (Exception e) {
                    Jvm.warn().on(getClass(), e);
                }
            }
        };

        hub.subscribe(asyncSubscription);

    }

    @Override
    public void unregisterSubscriber(@NotNull Subscriber<IndexedValue<V>> listener) {
        Long tid = subscribersToTid.get(listener);

        if (tid == null) {
            Jvm.warn().on(getClass(), "There is no subscription to unsubscribe, was " + subscribersToTid.size() + " other subscriptions.");
            return;
        }

        hub.preventSubscribeUponReconnect(tid);

        if (!hub.isOpen()) {
            hub.unsubscribe(tid);
            return;
        }

        hub.lock(() -> {
            writeMetaDataForKnownTID(tid);
            hub.outWire().writeDocument(false, wireOut ->
                    wireOut.writeEventName(unregisterSubscriber).text(""));
        });

    }
}

