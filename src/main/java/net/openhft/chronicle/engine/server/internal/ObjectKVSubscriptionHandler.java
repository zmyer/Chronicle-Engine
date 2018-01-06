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
import net.openhft.chronicle.engine.api.pubsub.Subscriber;
import net.openhft.chronicle.engine.api.pubsub.SubscriptionCollection;
import net.openhft.chronicle.engine.api.pubsub.TopicSubscriber;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.engine.map.ObjectSubscription;
import net.openhft.chronicle.network.connection.WireOutPublisher;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;

import static net.openhft.chronicle.engine.server.internal.ObjectKVSubscriptionHandler.EventId.registerTopicSubscriber;
import static net.openhft.chronicle.network.connection.CoreFields.reply;
import static net.openhft.chronicle.network.connection.CoreFields.tid;

/*
 * Created by Rob Austin
 */
public final class ObjectKVSubscriptionHandler extends SubscriptionHandler<SubscriptionCollection> {
    private static final Logger LOG = LoggerFactory.getLogger(ObjectKVSubscriptionHandler.class);
    @NotNull
    private final BiConsumer<WireIn, Long> dataConsumer = (inWire, inputTid) -> {

        eventName.setLength(0);
        @NotNull final ValueIn valueIn = inWire.readEventName(eventName);
        assert startEnforceInValueReadCheck(inWire);
        try {
            if (registerTopicSubscriber.contentEquals(eventName)) {

                if (tidToListener.containsKey(tid)) {
                    skipValue(valueIn);
                    LOG.info("Duplicate topic registration for tid " + tid);
                    return;
                }

                @NotNull final TopicSubscriber listener = new TopicSubscriber() {
                    volatile boolean subscriptionEnded;

                    @Override
                    public void onMessage(final Object topic, final Object message) {
                        synchronized (publisher) {
                            publisher.put(topic, publish -> {
                                publish.writeDocument(true, wire -> wire.writeEventName(tid).int64(inputTid));
                                publish.writeNotCompleteDocument(false, wire -> wire.writeEventName(reply)
                                        .marshallable(m -> {
                                            m.write(() -> "topic").object(topic);
                                            m.write(() -> "message").object(message);
                                        }));
                            });
                        }
                    }

                    @Override
                    public void onEndOfSubscription() {
                        subscriptionEnded = true;
                        synchronized (publisher) {
                            if (!publisher.isClosed()) {
                                publisher.put(null, publish -> {
                                    publish.writeDocument(true, wire ->
                                            wire.writeEventName(tid).int64(inputTid));
                                    publish.writeDocument(false, wire ->
                                            wire.writeEventName(EventId.onEndOfSubscription).text(""));
                                });
                            }
                        }
                    }
                };

                valueIn.marshallable(m -> {
                    final Class kClass = m.read(() -> "keyType").typeLiteral();
                    final Class vClass = m.read(() -> "valueType").typeLiteral();

                    final StringBuilder eventName = Wires.acquireStringBuilder();

                    @NotNull final ValueIn bootstrap = m.readEventName(eventName);
                    assert listener != null;
                    tidToListener.put(inputTid, listener);

                    if ("bootstrap".contentEquals(eventName))
                        asset.registerTopicSubscriber(requestContext.fullName()
                                + "?bootstrap=" + bootstrap.bool(), kClass, vClass, listener);
                    else
                        asset.registerTopicSubscriber(requestContext.fullName(), kClass,
                                vClass, listener);
                });
                return;
            }

            if (EventId.unregisterTopicSubscriber.contentEquals(eventName)) {
                skipValue(valueIn);
                @NotNull TopicSubscriber listener = (TopicSubscriber) tidToListener.remove(inputTid);
                if (listener == null) {
                    Jvm.debug().on(getClass(), "No subscriber to present to unsubscribe (" + inputTid + ")");
                    return;
                }
                asset.unregisterTopicSubscriber(requestContext, listener);

                return;
            }

            if (before(inputTid, valueIn)) return;

        } finally {
            assert endEnforceInValueReadCheck(inWire);
        }

        outWire.writeDocument(true, wire -> outWire.writeEventName(tid).int64(inputTid));

        writeData(inWire, out -> {

            if (after(eventName)) return;

            if (EventId.notifyEvent.contentEquals(eventName)) {
                ((ObjectSubscription) subscription).notifyEvent(valueIn.typedMarshallable());
                outWire.writeEventName(reply).int8(subscription.entrySubscriberCount());
            }

        });

    };

    @Override
    protected void unregisterAll() {

        tidToListener.forEach((k, listener) -> {
            try {
                if (listener instanceof TopicSubscriber)
                    asset.unregisterTopicSubscriber(requestContext, (TopicSubscriber) listener);
                else if (listener instanceof Subscriber)
                    asset.unregisterSubscriber(requestContext, (Subscriber) listener);
                else
                    Jvm.warn().on(getClass(), "Listener was " + listener);

            } catch (Exception e) {
                Jvm.warn().on(getClass(), "listener: " + listener, e);
            }
        });
        tidToListener.clear();
    }

    void process(@NotNull final WireIn inWire,
                 @NotNull final RequestContext requestContext,
                 @NotNull final WireOutPublisher publisher,
                 @NotNull final Asset rootAsset, final long tid,
                 @NotNull final Wire outWire,
                 @NotNull final SubscriptionCollection subscription) {
        setOutWire(outWire);
        this.outWire = outWire;
        this.subscription = subscription;
        this.requestContext = requestContext;
        this.publisher = publisher(publisher);
        this.asset = rootAsset;
        dataConsumer.accept(inWire, tid);

    }

    public enum EventId implements ParameterizeWireKey {
        registerTopicSubscriber,
        unregisterTopicSubscriber,
        onEndOfSubscription,
        notifyEvent;

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
