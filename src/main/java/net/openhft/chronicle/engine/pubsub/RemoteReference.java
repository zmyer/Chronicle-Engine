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

package net.openhft.chronicle.engine.pubsub;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.pool.ClassAliasPool;
import net.openhft.chronicle.core.util.SerializableBiFunction;
import net.openhft.chronicle.core.util.SerializableFunction;
import net.openhft.chronicle.engine.api.pubsub.InvalidSubscriberException;
import net.openhft.chronicle.engine.api.pubsub.Reference;
import net.openhft.chronicle.engine.api.pubsub.Subscriber;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.api.tree.AssetNotFoundException;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.engine.server.internal.ReferenceHandler;
import net.openhft.chronicle.engine.server.internal.ReferenceHandler.EventId;
import net.openhft.chronicle.network.connection.AbstractAsyncSubscription;
import net.openhft.chronicle.network.connection.AbstractStatelessClient;
import net.openhft.chronicle.network.connection.CoreFields;
import net.openhft.chronicle.network.connection.TcpChannelHub;
import net.openhft.chronicle.wire.ValueIn;
import net.openhft.chronicle.wire.WireIn;
import net.openhft.chronicle.wire.WireOut;
import net.openhft.chronicle.wire.Wires;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static net.openhft.chronicle.engine.server.internal.ReferenceHandler.EventId.*;

/*
 * Created by Rob Austin
 */
public class RemoteReference<E> extends AbstractStatelessClient<ReferenceHandler.EventId> implements Reference<E> {
    private static final Logger LOG = LoggerFactory.getLogger(ReferenceHandler.class);
    private final Class<E> messageClass;
    private final Map<Object, Long> subscribersToTid = new ConcurrentHashMap<>();

    public RemoteReference(@NotNull RequestContext requestContext, @NotNull Asset asset) {
        this(asset.findView(TcpChannelHub.class), requestContext.messageType(), asset.fullName());
    }

    public RemoteReference(@NotNull TcpChannelHub hub, Class<E> messageClass, String fullName)
            throws AssetNotFoundException {
        super(hub, (long) 0, toUri(fullName, messageClass));

        this.messageClass = messageClass;
    }

    private static String toUri(String fullName, Class messageClass) {
        @NotNull StringBuilder uri = new StringBuilder();
        uri.append(fullName).append("?view=reference");

        if (messageClass != String.class)
            uri.append("&messageType=").append(ClassAliasPool.CLASS_ALIASES.nameFor(messageClass));

        return uri.toString();
    }

    @Override
    public long set(final E event) {
        checkEvent(event);
        sendEventAsync(set, valueOut -> valueOut.object(event), true);
        return 0;
    }

    @Nullable
    @Override
    public E get() {
        return proxyReturnTypedObject(get, null, messageClass);
    }

    @Nullable
    @Override
    public E getAndSet(E e) {
        return proxyReturnTypedObject(getAndSet, null, messageClass, e);
    }

    @Override
    public void remove() {
        sendEventAsync(remove, null, true);
    }

    @Nullable
    @Override
    public E getAndRemove() {
        return proxyReturnTypedObject(getAndRemove, null, messageClass);
    }

    @Override
    public void unregisterSubscriber(Subscriber subscriber) {

        final Long tid = subscribersToTid.get(subscriber);
        if (tid == null) {
            Jvm.debug().on(getClass(), "No subscriber to unsubscribe");
            return;
        }

        hub.preventSubscribeUponReconnect(tid);

        if (!hub.isOpen()) {
            hub.unsubscribe(tid);
            return;
        }

        sendEventAsync(unregisterSubscriber, valueOut -> valueOut.int64(tid), false);

    }

    @Override
    public int subscriberCount() {
        return proxyReturnInt(countSubscribers);
    }

    @Override
    public void registerSubscriber(boolean bootstrap,
                                   int throttlePeriodMs,
                                   @NotNull final Subscriber subscriber)
            throws AssetNotFoundException {

        if (hub.outBytesLock().isHeldByCurrentThread())
            throw new IllegalStateException("Cannot view map while debugging");

        @NotNull final AbstractAsyncSubscription asyncSubscription = new AbstractAsyncSubscription(hub,
                csp + "&bootstrap=" + bootstrap + "&throttlePeriodMs=" + throttlePeriodMs,
                "Remote Ref registerSubscriber") {

            @Override
            public void onSubscribe(@NotNull final WireOut wireOut) {
                subscribersToTid.put(subscriber, tid());
                wireOut.writeEventName(registerSubscriber).text("");
            }

            @Override
            public void onConsumer(@NotNull final WireIn w) {
                w.readDocument(null, d -> {
                    final StringBuilder eventname = Wires.acquireStringBuilder();
                    @NotNull final ValueIn valueIn = d.readEventName(eventname);

                    if (EventId.onEndOfSubscription.contentEquals(eventname)) {
                        subscriber.onEndOfSubscription();
                        subscribersToTid.remove(this);
                        hub.unsubscribe(tid());
                    } else if (CoreFields.reply.contentEquals(eventname)) {
                        valueIn.marshallable(m -> {
                            @Nullable final E message = m.read(() -> "message").object(messageClass);
                            RemoteReference.this.onEvent(message, subscriber);
                        });
                    }

                });
            }

        };

        hub.subscribe(asyncSubscription);
    }

    @Override
    public Class getType() {
        return messageClass;
    }

    void onEvent(@Nullable E message, @NotNull Subscriber<E> subscriber) {
        if (message == null)
            return;
        try {
            subscriber.onMessage(message);

        } catch (InvalidSubscriberException noLongerValid) {
            unregisterSubscriber(subscriber);
        }
    }

    private void checkEvent(@Nullable Object key) {
        if (key == null)
            throw new NullPointerException("event can not be null");
    }

    @Nullable
    @Override
    public <R> R applyTo(@NotNull SerializableFunction<E, R> function) {
        return applyTo((x, $) -> function.apply(x), null);
    }

    @Override
    public void asyncUpdate(@NotNull SerializableFunction<E, E> updateFunction) {
        asyncUpdate((x, $) -> updateFunction.apply(x), null);
    }

    @Nullable
    @Override
    public <R> R syncUpdate(@NotNull SerializableFunction<E, E> updateFunction, @NotNull SerializableFunction<E, R> returnFunction) {
        return syncUpdate((x, $) -> updateFunction.apply(x), null, (x, $) -> returnFunction.apply(x), null);
    }

    @Nullable
    @Override
    public <T, R> R applyTo(@NotNull SerializableBiFunction<E, T, R> function, T argument) {
        return (R) super.proxyReturnTypedObject(applyTo2, null, Object.class, function, argument);
    }

    @Override
    public <T> void asyncUpdate(@NotNull SerializableBiFunction<E, T, E> updateFunction, T argument) {
        sendEventAsync(update2, toParameters(update2, updateFunction, argument), true);
    }

    @Nullable
    @Override
    public <UT, RT, R> R syncUpdate(@NotNull SerializableBiFunction<E, UT, E> updateFunction, @Nullable UT updateArgument,
                                    @NotNull SerializableBiFunction<E, RT, R> returnFunction, @Nullable RT returnArgument) {
        return (R) proxyReturnTypedObject(update4, null, Object.class, updateFunction,
                updateArgument, returnFunction, returnArgument);
    }
}
