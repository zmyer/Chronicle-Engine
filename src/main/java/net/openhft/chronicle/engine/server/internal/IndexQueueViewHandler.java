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

package net.openhft.chronicle.engine.server.internal;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.engine.api.pubsub.ConsumingSubscriber;
import net.openhft.chronicle.engine.api.pubsub.InvalidSubscriberException;
import net.openhft.chronicle.engine.api.query.IndexQueueView;
import net.openhft.chronicle.engine.api.query.IndexedValue;
import net.openhft.chronicle.engine.api.query.VanillaIndexQuery;
import net.openhft.chronicle.engine.api.query.VanillaIndexQueueView;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.network.connection.WireOutConsumer;
import net.openhft.chronicle.network.connection.WireOutPublisher;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static net.openhft.chronicle.engine.server.internal.IndexQueueViewHandler.EventId.registerSubscriber;
import static net.openhft.chronicle.engine.server.internal.IndexQueueViewHandler.EventId.unregisterSubscriber;
import static net.openhft.chronicle.network.connection.CoreFields.reply;
import static net.openhft.chronicle.network.connection.CoreFields.tid;

/*
 * Created by Rob Austin
 */
public class IndexQueueViewHandler<V extends Marshallable> extends AbstractHandler {

    private static final Logger LOG = LoggerFactory.getLogger(IndexQueueViewHandler.class);
    private final StringBuilder eventName = new StringBuilder();
    private final Map<Long, ConsumingSubscriber<IndexedValue<V>>> tidToListener = new ConcurrentHashMap<>();
    private Asset contextAsset;
    private WireOutPublisher publisher;
    @NotNull
    private final BiConsumer<WireIn, Long> dataConsumer = (inWire, inputTid) -> {

        eventName.setLength(0);
        @NotNull final ValueIn valueIn = inWire.readEventName(eventName);
        try {
            assert startEnforceInValueReadCheck(inWire);

            if (registerSubscriber.contentEquals(eventName)) {
                if (tidToListener.containsKey(inputTid)) {
                    skipValue(valueIn);
                    LOG.info("Duplicate topic registration for tid " + inputTid);
                    return;
                }

                @NotNull final ConsumingSubscriber<IndexedValue<V>> listener = new ConsumingSubscriber<IndexedValue<V>>() {

                    volatile WireOutConsumer wireOutConsumer;
                    volatile boolean subscriptionEnded;

                    @Override
                    public void onMessage(@NotNull IndexedValue indexedEntry) throws InvalidSubscriberException {

                        if (publisher.isClosed())
                            throw new InvalidSubscriberException();

                        publisher.put(indexedEntry.k(), publish -> {
                            publish.writeDocument(true, wire -> wire.writeEventName(tid).int64(inputTid));
                            publish.writeNotCompleteDocument(false, wire ->
                                    wire.writeEventName(reply).typedMarshallable(indexedEntry));
                        });
                    }

                    @Override
                    public void onEndOfSubscription() {
                        subscriptionEnded = true;
                        if (publisher.isClosed())
                            return;
                        publisher.put(null, publish -> {
                            publish.writeDocument(true, wire ->
                                    wire.writeEventName(tid).int64(inputTid));
                            publish.writeDocument(false, wire ->
                                    wire.writeEventName(ObjectKVSubscriptionHandler.EventId.onEndOfSubscription).text(""));
                        });
                    }

                    /**
                     * used to publish bytes on the nio socket thread
                     *
                     * @param supplier reads a chronicle queue and
                     *                 publishes writes the data
                     *                 directly to the socket
                     */
                    @Override
                    public void addSupplier(@NotNull Supplier<Marshallable> supplier) {
                        wireOutConsumer = wireOut -> {

                            Marshallable marshallable = supplier.get();
                            if (marshallable == null)
                                return;

                            if (publisher.isClosed())
                                return;

                            wireOut.writeDocument(true, wire -> wire.writeEventName(tid).int64(inputTid));
                            wireOut.writeNotCompleteDocument(false,
                                    wire -> wire.writeEventName(reply).typedMarshallable(marshallable));

                        };
                        publisher.addWireConsumer(wireOutConsumer);
                    }

                    @Override
                    public void close() {
                        publisher.removeBytesConsumer(wireOutConsumer);
                    }
                };

                tidToListener.put(inputTid, listener);

                @Nullable final VanillaIndexQuery<V> query = valueIn.typedMarshallable();

                if (query.select().isEmpty() || query.valueClass() == null) {
                    Jvm.debug().on(getClass(), "received empty query");
                    return;
                }

                try {
                    query.filter();
                } catch (Exception e) {
                    Jvm.warn().on(getClass(), "unable to load the filter predicate for this query=" + query, e);
                    return;
                }

                @NotNull final IndexQueueView<ConsumingSubscriber<IndexedValue<V>>, V> indexQueueView =
                        contextAsset.acquireView(IndexQueueView.class);
                indexQueueView.registerSubscriber(listener, query);
                return;
            }

            if (unregisterSubscriber.contentEquals(eventName)) {
                skipValue(valueIn);
                @NotNull IndexQueueView<ConsumingSubscriber<IndexedValue<V>>, V> indexQueueView = contextAsset.acquireView(IndexQueueView.class);
                ConsumingSubscriber<IndexedValue<V>> listener = tidToListener.remove(inputTid);

                if (listener == null) {
                    if (Jvm.isDebugEnabled(getClass()))
                        Jvm.debug().on(getClass(), "No subscriber to present to unsubscribe (" + inputTid + ")");
                    return;
                }

                if (listener instanceof Closeable)
                    listener.close();

                indexQueueView.unregisterSubscriber(listener);
                return;
            }
        } finally {
            assert endEnforceInValueReadCheck(inWire);
        }
        outWire.writeDocument(true, wire -> outWire.writeEventName(tid).int64(inputTid));

    };

    @Override
    protected void unregisterAll() {
        @NotNull final VanillaIndexQueueView<V> indexQueueView = contextAsset.acquireView(VanillaIndexQueueView.class);
        tidToListener.forEach((k, listener) -> indexQueueView.unregisterSubscriber(listener));
        tidToListener.clear();
    }

    void process(@NotNull final WireIn inWire,
                 @NotNull final RequestContext requestContext,
                 @NotNull Asset contextAsset,
                 @NotNull final WireOutPublisher publisher,
                 final long tid,
                 @NotNull final Wire outWire) {
        setOutWire(outWire);
        this.outWire = outWire;
        this.publisher = publisher;
        this.contextAsset = contextAsset;
        this.requestContext = requestContext;
        dataConsumer.accept(inWire, tid);
    }

    public enum Params implements WireKey {
        subscribe
    }

    public enum EventId implements ParameterizeWireKey {
        registerSubscriber(Params.subscribe),
        unregisterSubscriber(),
        onEndOfSubscription;

        private final WireKey[] params;

        <P extends WireKey> EventId(P... params) {
            this.params = params;
        }

        @Override
        @NotNull
        public <P extends WireKey> P[] params() {
            return (P[]) this.params;
        }
    }
}
