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
import net.openhft.chronicle.core.util.SerializableBiFunction;
import net.openhft.chronicle.core.util.SerializableUpdaterWithArg;
import net.openhft.chronicle.core.util.ThrowingConsumer;
import net.openhft.chronicle.core.util.Time;
import net.openhft.chronicle.engine.api.EngineReplication.ReplicationEntry;
import net.openhft.chronicle.engine.api.map.KeyValueStore;
import net.openhft.chronicle.engine.api.map.MapEvent;
import net.openhft.chronicle.engine.api.map.MapView;
import net.openhft.chronicle.engine.api.pubsub.InvalidSubscriberException;
import net.openhft.chronicle.engine.api.pubsub.SubscriptionConsumer;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.engine.collection.ClientWiredStatelessChronicleCollection;
import net.openhft.chronicle.engine.collection.ClientWiredStatelessChronicleSet;
import net.openhft.chronicle.engine.map.InsertedEvent;
import net.openhft.chronicle.engine.map.ObjectKeyValueStore;
import net.openhft.chronicle.engine.map.ObjectSubscription;
import net.openhft.chronicle.network.connection.AbstractStatelessClient;
import net.openhft.chronicle.network.connection.CoreFields;
import net.openhft.chronicle.network.connection.TcpChannelHub;
import net.openhft.chronicle.wire.ValueIn;
import net.openhft.chronicle.wire.Wires;
import net.openhft.chronicle.wire.WriteMarshallable;
import net.openhft.chronicle.wire.WriteValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.function.Function;

import static net.openhft.chronicle.engine.server.internal.MapWireHandler.EventId;
import static net.openhft.chronicle.engine.server.internal.MapWireHandler.EventId.*;
import static net.openhft.chronicle.network.connection.CoreFields.stringEvent;

public class RemoteKeyValueStore<K, V> extends AbstractStatelessClient<EventId>
        implements Cloneable, ObjectKeyValueStore<K, V> {

    private static final WriteValue VOID_PARAMETERS = WriteMarshallable.EMPTY;

    private final Class<K> kClass;
    private final Class<V> vClass;
    private final Map<Long, String> cidToCsp = new HashMap<>();
    @NotNull
    private final RequestContext context;
    @NotNull
    private final Asset asset;
    // todo
    @NotNull
    private final ObjectSubscription<K, V> subscriptions;

    public RemoteKeyValueStore(@NotNull final RequestContext context,
                               @NotNull Asset asset,
                               @NotNull final TcpChannelHub hub) {
        super(hub, (long) 0, toUri(context));
        this.asset = asset;
        this.kClass = context.keyType();
        this.vClass = context.valueType();
        this.context = context;

        subscriptions = asset.acquireView(ObjectSubscription.class, context);
        subscriptions.setKvStore(this);
    }

    public RemoteKeyValueStore(@NotNull RequestContext requestContext, @NotNull Asset asset) {
        this(requestContext, asset, asset.findView(TcpChannelHub.class));
    }

    @NotNull
    private static String toUri(@NotNull final RequestContext context) {
        return context.viewType(MapView.class).toUri();
    }

    @Override
    public boolean isKeyType(Object key) {
        return kClass.isInstance(key);
    }

    @NotNull
    public File file() {
        throw new UnsupportedOperationException();
    }

    @Override
    @Nullable
    @SuppressWarnings("NullableProblems")
    public V putIfAbsent(K key, V value) {
        checkKey(key);
        checkValue(value);
        return proxyReturnTypedObject(putIfAbsent, null, vClass, key, value);
    }

    @Override
    public boolean containsValue(final V value) {
        throw new UnsupportedOperationException("todo");
    }

    private void checkValue(@Nullable Object value) {
        if (value == null)
            throw new NullPointerException("value must not be null");
    }

    @SuppressWarnings("NullableProblems")
    public boolean remove(@Nullable Object key, Object value) {
        if (key == null)
            return false;
        checkValue(value);

        return proxyReturnBooleanWithArgs(removeWithValue, key, value);
    }

    @SuppressWarnings("NullableProblems")
    public boolean replace(K key, V oldValue, V newValue) {
        checkKey(key);
        checkValue(oldValue);
        checkValue(newValue);
        return proxyReturnBooleanWithArgs(replaceForOld, key, oldValue, newValue);
    }

    @Override
    @Nullable
    @SuppressWarnings("NullableProblems")
    public V replace(K key, V value) {
        checkKey(key);
        checkValue(value);
        return proxyReturnTypedObject(replace, null, vClass, key, value);
    }

    @Nullable
    public <A, R> R applyTo(@NotNull SerializableBiFunction<MapView<K, V>, A, R> function, A arg) {
        return (R) proxyReturnTypedObject(applyTo2, null, Object.class, function, arg);
    }

    @Nullable
    public <R, UA, RA> R syncUpdate(SerializableUpdaterWithArg updateFunction, UA ua, SerializableBiFunction returnFunction, RA ra) {
        return (R) proxyReturnTypedObject(update4, null, Object.class, updateFunction, ua, returnFunction, ra);
    }

    public <A> void asyncUpdate(SerializableUpdaterWithArg updateFunction, A arg) {
        sendEventAsync(update2, toParameters(update2, updateFunction, arg), true);
    }

    @Override
    public void keysFor(final int segment, @NotNull final SubscriptionConsumer<K> kConsumer) throws InvalidSubscriberException {
        keySet().forEach(ThrowingConsumer.asConsumer(kConsumer::accept));
    }

    @Override
    public void entriesFor(final int segment, @NotNull final SubscriptionConsumer<MapEvent<K, V>> kvConsumer) throws InvalidSubscriberException {
        @NotNull String assetName = asset.fullName();
        entrySet().forEach(ThrowingConsumer.asConsumer(entry ->
                kvConsumer.accept(InsertedEvent.of(assetName, entry.getKey(), entry.getValue(), false))));
    }

    /**
     * calling this method should be avoided at all cost, as the entire {@code object} is
     * serialized. This equals can be used to compare map that extends ChronicleMap.  So two
     * Chronicle Maps that contain the same data are considered equal, even if the instances of the
     * chronicle maps were of different types
     *
     * @param object the object that you are comparing against
     * @return true if the contain the same data
     */
    @Override
    public boolean equals(@Nullable Object object) {
        if (this == object) return true;
        if (object == null || object.getClass().isAssignableFrom(Map.class))
            return false;

        @NotNull final Map<? extends K, ? extends V> that = (Map<? extends K, ? extends V>) object;

        if (that.size() != longSize())
            return false;

        @NotNull final Set<Map.Entry<K, V>> entries = entrySet();
        return that.entrySet().equals(entries);
    }

    @Override
    public int hashCode() {
        return proxyReturnInt(hashCode);
    }

    @NotNull

    public String toString() {
        if (Jvm.isDebug()) return "toString() not available while debugging";

        @NotNull final Iterator<Map.Entry<K, V>> entries = entrySet().iterator();
        if (!entries.hasNext())
            return "{}";

        @NotNull StringBuilder sb = new StringBuilder();
        sb.append('{');

        while (entries.hasNext()) {
            final Map.Entry<K, V> e = entries.next();
            final K key = e.getKey();
            final V value = e.getValue();
            sb.append(key == this ? "(this Map)" : key);
            sb.append('=');
            sb.append(value == this ? "(this Map)" : value);
            if (!entries.hasNext())
                return sb.append('}').toString();
            sb.append(',').append(' ');
        }

        return sb.toString();
    }

    public boolean isEmpty() {
        return longSize() == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        checkKey(key);
        return proxyReturnBoolean(containsKey, out -> out.object(key));
    }

    @Override
    @Nullable
    public V get(Object key) {
        checkKey(key);
        return this.proxyReturnTypedObject(get, null, vClass, key);
    }

    @Override
    @Nullable
    public V getUsing(K key, Object usingValue) {
        checkKey(key);
        return this.proxyReturnTypedObject(get, (V) usingValue, vClass, key);
    }

    @Override
    public long longSize() {
        return proxyReturnLong(size);
    }

    @Override
    public boolean remove(Object key) {
        checkKey(key);
        sendEventAsync(remove, toParameters(remove, key), true);
        return false;
    }

    @Nullable
    @Override
    public V getAndRemove(final Object key) {
        checkKey(key);
        return proxyReturnTypedObject(getAndRemove, null, vClass, key);
    }

    private void checkKey(@Nullable Object key) {
        if (key == null)
            throw new NullPointerException("key can not be null");
    }

    @Override
    public boolean put(K key, V value) {
        checkKey(key);
        checkValue(value);
        sendEventAsync(put, toParameters(put, key, value), true);
        return false;
    }

    @Nullable
    @Override
    public V getAndPut(final Object key, final Object value) {
        checkKey(key);
        checkValue(value);
        return proxyReturnTypedObject(getAndPut, null, vClass, key, value);
    }

    @Override
    public void clear() {
        proxyReturnVoid(clear);
    }

    @Nullable
    public Collection<V> values() {
        final StringBuilder csp = Wires.acquireStringBuilder();
        long cid = proxyReturnWireConsumer(values, read -> {

            read.typePrefix();
            return read.applyToMarshallable(w -> {
                stringEvent(CoreFields.csp, csp, w);
                final long cid0 = CoreFields.cid(w);
                cidToCsp.put(cid0, csp.toString());
                return cid0;
            });
        });

        @Nullable final Function<ValueIn, V> consumer = valueIn -> valueIn.object(vClass);

        return new ClientWiredStatelessChronicleCollection<>(hub, ArrayList::new, consumer, "/" + context.name() + "?view=" + "values", cid
        );
    }

    @NotNull
    public Set<Map.Entry<K, V>> entrySet() {

        final StringBuilder csp = Wires.acquireStringBuilder();

        long cid = proxyReturnWireConsumer(entrySet, read -> {

            read.typePrefix();
            return read.applyToMarshallable(w -> {
                stringEvent(CoreFields.csp, csp, w);
                final long cid0 = CoreFields.cid(w);
                cidToCsp.put(cid0, csp.toString());
                return cid0;
            });
        });

        @NotNull Function<ValueIn, Map.Entry<K, V>> consumer = valueIn -> valueIn.applyToMarshallable(r -> {

                    @Nullable final K k = r.read(() -> "key").object(kClass);
                    @Nullable final V v = r.read(() -> "value").object(vClass);

                    return new Map.Entry<K, V>() {
                        @Nullable
                        @Override
                        public K getKey() {
                            return k;
                        }

                        @Nullable
                        @Override
                        public V getValue() {
                            return v;
                        }

                        @NotNull
                        @Override
                        public V setValue(Object value) {
                            throw new UnsupportedOperationException();
                        }
                    };
                }

        );

        return new ClientWiredStatelessChronicleSet<>(hub, csp.toString(), cid, consumer);
    }

    @NotNull
    @Override
    public Iterator<Map.Entry<K, V>> entrySetIterator() {
        return entrySet().iterator();
    }

    @NotNull
    public Set<K> keySet() {
        final StringBuilder csp = Wires.acquireStringBuilder();

        long cid = proxyReturnWireConsumer(keySet, read -> {
            read.typePrefix();
            return read.applyToMarshallable(w -> {
                stringEvent(CoreFields.csp, csp, w);
                final long cid0 = CoreFields.cid(w);
                cidToCsp.put(cid0, csp.toString());
                return cid0;
            });
        });

        return new ClientWiredStatelessChronicleSet<>(hub,
                csp.toString(), cid, valueIn -> valueIn.object(kClass));
    }

    @SuppressWarnings("SameParameterValue")
    private boolean proxyReturnBoolean(@NotNull final EventId eventId,
                                       @Nullable final WriteValue consumer) {
        final long startTime = Time.currentTimeMillis();
        return attempt(() -> readBoolean(sendEvent(startTime, eventId, consumer), startTime));
    }

    @SuppressWarnings("SameParameterValue")
    private int proxyReturnInt(@NotNull final EventId eventId) {
        final long startTime = Time.currentTimeMillis();
        return attempt(() -> readInt(sendEvent(startTime, eventId, VOID_PARAMETERS), startTime));
    }

    @NotNull
    @Override
    public Asset asset() {
        return asset;
    }

    @NotNull
    @Override
    public KeyValueStore<K, V> underlying() {
        throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public ObjectSubscription<K, V> subscription(boolean createIfAbsent) {
        return subscriptions;
    }

    @Override
    public Class<K> keyType() {
        return kClass;
    }

    @Override
    public Class<V> valueType() {
        return vClass;
    }

    @Override
    public void accept(final ReplicationEntry replicationEntry) {
        throw new UnsupportedOperationException("");
    }

    class Entry implements Map.Entry<K, V> {

        final K key;
        final V value;

        /**
         * Creates new entry.
         */
        Entry(K k1, V v) {
            value = v;
            key = k1;
        }

        @Override
        public final K getKey() {
            return key;
        }

        @Override
        public final V getValue() {
            return value;
        }

        @Override
        public final V setValue(V newValue) {
            V oldValue = value;
            RemoteKeyValueStore.this.put(getKey(), newValue);
            return oldValue;
        }

        public final boolean equals(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            @NotNull final Map.Entry e = (Map.Entry) o;
            final Object k1 = getKey();
            final Object k2 = e.getKey();
            if (k1 == k2 || (k1 != null && k1.equals(k2))) {
                Object v1 = getValue();
                Object v2 = e.getValue();
                if (v1 == v2 || (v1 != null && v1.equals(v2)))
                    return true;
            }
            return false;
        }

        public final int hashCode() {
            return (key == null ? 0 : key.hashCode()) ^
                    (value == null ? 0 : value.hashCode());
        }

        @NotNull
        public final String toString() {
            return getKey() + "=" + getValue();
        }
    }
}

