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

package net.openhft.chronicle.engine.map;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.util.ThrowingConsumer;
import net.openhft.chronicle.engine.api.EngineReplication;
import net.openhft.chronicle.engine.api.EngineReplication.ReplicationEntry;
import net.openhft.chronicle.engine.api.map.KeyValueStore;
import net.openhft.chronicle.engine.api.map.MapEvent;
import net.openhft.chronicle.engine.api.pubsub.InvalidSubscriberException;
import net.openhft.chronicle.engine.api.pubsub.SubscriptionConsumer;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.api.tree.AssetNotFoundException;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.engine.fs.Clusters;
import net.openhft.chronicle.engine.fs.EngineCluster;
import net.openhft.chronicle.engine.fs.EngineHostDetails;
import net.openhft.chronicle.engine.server.internal.MapReplicationHandler;
import net.openhft.chronicle.engine.tree.HostIdentifier;
import net.openhft.chronicle.hash.replication.EngineReplicationLangBytesConsumer;
import net.openhft.chronicle.map.*;
import net.openhft.chronicle.network.api.session.SessionDetails;
import net.openhft.chronicle.network.api.session.SessionProvider;
import net.openhft.chronicle.network.cluster.ConnectionManager;
import net.openhft.chronicle.network.connection.TcpChannelHub;
import net.openhft.chronicle.network.connection.WireOutPublisher;
import net.openhft.chronicle.threads.NamedThreadFactory;
import net.openhft.lang.io.Bytes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static net.openhft.chronicle.core.io.Closeable.closeQuietly;
import static net.openhft.chronicle.core.pool.ClassAliasPool.CLASS_ALIASES;
import static net.openhft.chronicle.engine.api.pubsub.SubscriptionConsumer.notifyEachEvent;
import static net.openhft.chronicle.engine.server.internal.MapReplicationHandler.newMapReplicationHandler;
import static net.openhft.chronicle.hash.replication.SingleChronicleHashReplication.builder;

public class ChronicleMapKeyValueStore<K, V> implements ObjectKeyValueStore<K, V>,
        Closeable, Supplier<EngineReplication> {

    private static final ScheduledExecutorService DELAYED_CLOSER = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("ChronicleMapKeyValueStore Closer", true));
    private static final Logger LOG = LoggerFactory.getLogger(ChronicleMapKeyValueStore.class);

    static {
        CLASS_ALIASES.addAlias(MapReplicationHandler.class);
    }

    private final ChronicleMap<K, V> chronicleMap;
    @NotNull
    private final ObjectSubscription<K, V> subscriptions;
    @Nullable
    private final EngineReplication engineReplicator;
    @NotNull
    private final Asset asset;
    @NotNull
    private final String assetFullName;
    @Nullable
    private final EventLoop eventLoop;
    private final AtomicBoolean isClosed = new AtomicBoolean();
    @Nullable
    private final SessionProvider sessionProvider;
    private Class keyType;
    private Class valueType;
    @Nullable
    private SessionDetails replicationSessionDetails;

    public ChronicleMapKeyValueStore(@NotNull RequestContext context, @NotNull Asset asset) {
        String basePath = context.basePath();
        keyType = context.keyType();
        valueType = context.valueType();
        double averageValueSize = context.getAverageValueSize();
        long maxEntries = context.getEntries();
        this.asset = asset;
        this.assetFullName = asset.fullName();
        this.subscriptions = asset.acquireView(ObjectSubscription.class, context);
        this.subscriptions.setKvStore(this);
        this.eventLoop = asset.findOrCreateView(EventLoop.class);
        assert eventLoop != null;
        sessionProvider = asset.findView(SessionProvider.class);
        eventLoop.start();

        replicationSessionDetails = asset.root().findView(SessionDetails.class);

        ChronicleMapBuilder<K, V> builder = ChronicleMapBuilder.of(context.keyType(), context.valueType());
        @Nullable HostIdentifier hostIdentifier = null;
        @Nullable EngineReplication engineReplicator1 = null;
        try {
            engineReplicator1 = asset.acquireView(EngineReplication.class);

            @Nullable final EngineReplicationLangBytesConsumer langBytesConsumer = asset.findView
                    (EngineReplicationLangBytesConsumer.class);

            hostIdentifier = asset.findOrCreateView(HostIdentifier.class);
            assert hostIdentifier != null;
            builder.putReturnsNull(context.putReturnsNull() != Boolean.FALSE)
                    .removeReturnsNull(context.removeReturnsNull() != Boolean.FALSE);
            builder.replication(builder().engineReplication(langBytesConsumer)
                    .createWithId(hostIdentifier.hostId()));

        } catch (AssetNotFoundException anfe) {
            if (LOG.isDebugEnabled())
                Jvm.debug().on(getClass(), "replication not enabled ", anfe);
        }

        this.engineReplicator = engineReplicator1;

        @Nullable Boolean nullOldValueOnUpdateEvent = context.nullOldValueOnUpdateEvent();
        if (nullOldValueOnUpdateEvent != null && nullOldValueOnUpdateEvent) {
            builder.bytesEventListener(new NullOldValuePublishingOperations());
        } else {
            builder.eventListener(new PublishingOperations());
        }

        if (context.putReturnsNull() != Boolean.FALSE)
            builder.putReturnsNull(true);
        if (context.removeReturnsNull() != Boolean.FALSE)
            builder.removeReturnsNull(true);
        if (averageValueSize > 0)
            builder.averageValueSize(averageValueSize);
        if (maxEntries > 0) builder.entries(maxEntries + 1); // we have to add a head room of 1

        if (basePath == null) {
            chronicleMap = builder.create();
        } else {
            @NotNull String pathname = basePath + "/" + context.name();
            //noinspection ResultOfMethodCallIgnored
            new File(basePath).mkdirs();
            try {
                chronicleMap = builder.createPersistedTo(new File(pathname));

            } catch (IOException e) {
                @NotNull IORuntimeException iore = new IORuntimeException("Could not access " + pathname);
                iore.initCause(e);
                throw iore;
            }
        }

        if (hostIdentifier == null)
            return;

        @Nullable Clusters clusters = asset.findView(Clusters.class);

        if (clusters == null) {
            Jvm.warn().on(getClass(), "no clusters found.");
            return;
        }

        final EngineCluster engineCluster = clusters.get(context.cluster());

        if (engineCluster == null) {
            Jvm.warn().on(getClass(), "no cluster found, name=" + context.cluster());
            return;
        }

        byte localIdentifier = hostIdentifier.hostId();

        if (LOG.isDebugEnabled())
            Jvm.debug().on(getClass(), "hostDetails : localIdentifier=" + localIdentifier + ",cluster=" + engineCluster.hostDetails());

        for (@NotNull EngineHostDetails hostDetails : engineCluster.hostDetails()) {
            try {
                // its the identifier with the larger values that will establish the connection
                byte remoteIdentifier = (byte) hostDetails.hostId();

                if (remoteIdentifier == localIdentifier)
                    continue;


                ConnectionManager connectionManager = engineCluster.findConnectionManager(remoteIdentifier);
                if (connectionManager == null) {
                    Jvm.warn().on(getClass(), "connectionManager==null for remoteIdentifier=" + remoteIdentifier);
                    engineCluster.findConnectionManager(remoteIdentifier);
                    continue;
                }

                connectionManager.addListener((nc, isConnected) -> {

                    if (!isConnected)
                        return;

                    if (nc.isAcceptor())
                        return;

                    @NotNull final String csp = context.fullName();

                    final long lastUpdateTime = ((Replica) chronicleMap).lastModificationTime(remoteIdentifier);

                    WireOutPublisher publisher = nc.wireOutPublisher();
                    publisher.publish(newMapReplicationHandler(lastUpdateTime, keyType, valueType, csp, nc.newCid()));
                });


            } catch (Exception e) {
                Jvm.warn().on(getClass(), "hostDetails=" + hostDetails, e);
            }
        }
    }

    @NotNull
    @Override
    public KVSSubscription<K, V> subscription(boolean createIfAbsent) {
        return subscriptions;
    }

    @Override
    public boolean put(K key, V value) {
        try {
            return chronicleMap.update(key, value) != UpdateResult.INSERT;

        } catch (RuntimeException e) {
            if (LOG.isDebugEnabled())
                Jvm.debug().on(getClass(), "Failed to write " + key + ", " + value, e);
            throw e;
        }
    }

    @Nullable
    @Override
    public V getAndPut(K key, V value) {
        if (!isClosed.get())
            return chronicleMap.put(key, value);
        else
            return null;
    }

    @Override
    public boolean remove(K key) {
        return chronicleMap.remove(key) != null;
    }

    @Nullable
    @Override
    public V getAndRemove(K key) {

        if (!isClosed.get())
            return chronicleMap.remove(key);
        else
            return null;
    }

    @Override
    public V getUsing(K key, @Nullable Object value) {
        if (value != null)
            throw new UnsupportedOperationException("Mutable values not supported");
        return chronicleMap.getUsing(key, (V) value);
    }

    @Override
    public long longSize() {
        return chronicleMap.size();
    }

    @Override
    public void keysFor(int segment, @NotNull SubscriptionConsumer<K> kConsumer) throws
            InvalidSubscriberException {
        //Ignore the segments and return keysFor the whole map
        notifyEachEvent(chronicleMap.keySet(), kConsumer);
    }

    @Override
    public void entriesFor(int segment,
                           @NotNull SubscriptionConsumer<MapEvent<K, V>> kvConsumer) throws InvalidSubscriberException {
        //Ignore the segments and return entriesFor the whole map
        chronicleMap.entrySet().stream()
                .map(e -> InsertedEvent.of(assetFullName, e.getKey(), e.getValue(), false))
                .forEach(ThrowingConsumer.asConsumer(kvConsumer::accept));
    }

    @NotNull
    @Override
    public Iterator<Map.Entry<K, V>> entrySetIterator() {
        return chronicleMap.entrySet().iterator();
    }

    @NotNull
    @Override
    public Iterator<K> keySetIterator() {
        return chronicleMap.keySet().iterator();
    }

    @Override
    public void clear() {
        chronicleMap.clear();
    }

    @Override
    public boolean containsValue(final V value) {
        throw new UnsupportedOperationException("todo");
    }

    @NotNull
    @Override
    public Asset asset() {
        return asset;
    }

    @Nullable
    @Override
    public KeyValueStore<K, V> underlying() {
        return null;
    }

    @Override
    public void close() {
        isClosed.set(true);
        assert eventLoop != null;
        eventLoop.stop();
        closeQuietly(asset.findView(TcpChannelHub.class));
        DELAYED_CLOSER.schedule(() -> Closeable.closeQuietly(chronicleMap), 1, TimeUnit.SECONDS);
    }

    @Override
    public void accept(@NotNull final ReplicationEntry replicationEntry) {
        if (!isClosed.get() && engineReplicator != null)
            engineReplicator.applyReplication(replicationEntry);
        else
            Jvm.warn().on(getClass(), "message skipped as closed replicationEntry=" + replicationEntry);
    }

    @Nullable
    @Override
    public EngineReplication get() {
        return engineReplicator;
    }

    @Override
    public Class<K> keyType() {
        return keyType;
    }

    @Override
    public Class<V> valueType() {
        return valueType;
    }

    private class PublishingOperations extends MapEventListener<K, V> {
        @Override
        public boolean isActive() {
            return subscriptions.hasSubscribers();
        }

        @Override
        public boolean usesValue() {
            return subscriptions.hasValueSubscribers();
        }

        @Override
        public void onRemove(@NotNull K key, V value, boolean replicationEvent, byte identifier, byte replacedIdentifier, long timestamp, long replacedTimeStamp) {
            if (replicationEvent &&
                    replicationSessionDetails != null &&
                    sessionProvider.get() == null) {

                // todo - this is a bit of a hack, to prevent the AuthenticationKeyValueSubscription
                // from throwing an exception that there is has no session details from a replication event
                /// the reason that this was failing, is that client connection "don't and should not hold"
                // session details of their servers, however in a replication cluster replication events are being authenticated
                // event thought they originate from a client connect
                sessionProvider.set(replicationSessionDetails);
            }

            onRemove0(key, value, replicationEvent);
        }

        public void onRemove0(@NotNull K key, V value, boolean replicationEven) {
            subscriptions.notifyEvent(RemovedEvent.of(assetFullName, key, value, replicationEven));
        }

        private void onPut0(@NotNull K key, V newValue, @Nullable V replacedValue,
                            boolean replicationEvent, boolean added, boolean hasValueChanged) {
            if (added) {
                subscriptions.notifyEvent(InsertedEvent.of(assetFullName, key, newValue, replicationEvent));
            } else {
                if (hasValueChanged)
                    subscriptions.notifyEvent(UpdatedEvent.of(assetFullName, key, replacedValue,
                            newValue, replicationEvent, hasValueChanged));
            }
        }

        @Override
        public void onPut(@NotNull K key,
                          V newValue,
                          @Nullable V replacedValue,
                          boolean replicationEvent,
                          boolean added,
                          boolean hasValueChanged,
                          byte identifier,
                          byte replacedIdentifier, long timestamp, long replacedTimestamp) {

            if (!added && !hasValueChanged && replacedTimestamp == timestamp
                    && identifier == replacedIdentifier) {
                Jvm.debug().on(getClass(), "ignore update as nothing has changed");
                return;
            }

            if (replicationEvent &&
                    replicationSessionDetails != null &&
                    sessionProvider.get() == null) {

                // todo - this is a bit of a hack, to prevent the AuthenticationKeyValueSubscription
                // from throwing an exception that there is has no session details from a replication event
                /// the reason that this was failing, is that client connection "don't and should not hold"
                // session details of their servers, however in a replication cluster replication events are being authenticated
                // event thought they originate from a client connection
                sessionProvider.set(replicationSessionDetails);
            }

            onPut0(key, newValue, replacedValue, replicationEvent, added, hasValueChanged);
        }
    }

    private class NullOldValuePublishingOperations extends BytesMapEventListener {
        @Override
        public void onPut(Bytes entry, long metaDataPos, long keyPos, long valuePos, boolean added, boolean replicationEvent, boolean hasValueChanged, byte identifier, byte replacedIdentifier, long timeStamp, long replacedTimeStamp, @NotNull SharedSegment segment) {
            if (identifier == replacedIdentifier && timeStamp == replacedTimeStamp &&
                    !hasValueChanged)
                return;

            K key = chronicleMap.readKey(entry, keyPos);
            V value = chronicleMap.readValue(entry, valuePos);

            segment.writeUnlock();
            try {
                if (added) {
                    subscriptions.notifyEvent(InsertedEvent.of(assetFullName, key, value, replicationEvent));
                } else {
                    subscriptions.notifyEvent(UpdatedEvent.of(assetFullName, key, null, value,
                            replicationEvent, hasValueChanged));
                }
            } finally {
                segment.writeLock();
            }
        }

        @Override
        public void onRemove(Bytes entry, long metaDataPos, long keyPos, long valuePos, boolean replicationEvent, byte identifier, byte replacedIdentifier, long timeStamp, long replacedTimeStamp, @NotNull SharedSegment segment) {
            if (identifier == replacedIdentifier && timeStamp == replacedTimeStamp)
                return;

            K key = chronicleMap.readKey(entry, keyPos);
            V value = chronicleMap.readValue(entry, valuePos);

            segment.writeUnlock();
            try {
                subscriptions.notifyEvent(RemovedEvent.of(assetFullName, key, value, replicationEvent));
            } finally {
                segment.writeLock();
            }
        }
    }
}
