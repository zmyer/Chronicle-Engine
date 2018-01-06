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

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.BytesStore;
import net.openhft.chronicle.bytes.PointerBytesStore;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.pool.ClassAliasPool;
import net.openhft.chronicle.engine.api.EngineReplication;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.engine.map.replication.Bootstrap;
import net.openhft.chronicle.hash.replication.EngineReplicationLangBytesConsumer;
import net.openhft.chronicle.map.EngineReplicationLangBytes;
import net.openhft.chronicle.map.EngineReplicationLangBytes.EngineModificationIterator;
import net.openhft.chronicle.wire.TextWire;
import net.openhft.chronicle.wire.WireIn;
import net.openhft.chronicle.wire.Wires;
import net.openhft.lang.io.NativeBytes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static java.lang.ThreadLocal.withInitial;

/*
 * Created by Rob Austin
 */
public class CMap2EngineReplicator implements EngineReplication,
        EngineReplicationLangBytesConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(CMap2EngineReplicator.class);

    static {
        ClassAliasPool.CLASS_ALIASES.addAlias(VanillaReplicatedEntry.class);
        ClassAliasPool.CLASS_ALIASES.addAlias(Bootstrap.class);
    }

    final ThreadLocal<KvBytes> kvBytesThreadLocal = ThreadLocal.withInitial(KvBytes::new);
    private final RequestContext context;
    private final ThreadLocal<PointerBytesStore> keyLocal = withInitial(PointerBytesStore::new);
    private final ThreadLocal<PointerBytesStore> valueLocal = withInitial(PointerBytesStore::new);
    private final ThreadLocal<KvLangBytes> kvByte = ThreadLocal.withInitial(KvLangBytes::new);
    private EngineReplicationLangBytes engineReplicationLang;

    public CMap2EngineReplicator(RequestContext requestContext, @NotNull Asset asset) {
        this(requestContext);
        asset.addView(EngineReplicationLangBytesConsumer.class, this);
    }

    public CMap2EngineReplicator(final RequestContext context) {
        this.context = context;
    }

    @Override
    public void set(@NotNull final EngineReplicationLangBytes engineReplicationLangBytes) {
        this.engineReplicationLang = engineReplicationLangBytes;
    }

    @NotNull
    private net.openhft.lang.io.Bytes toLangBytes(@NotNull BytesStore b, @NotNull Bytes tmpBytes, @NotNull net.openhft.lang.io.NativeBytes lb) {
        if (b.isDirectMemory()) {
//            check(b);
            lb.setStartPositionAddress(b.addressForRead(b.start()), b.addressForRead(b.readLimit()));
//            check(lb);

        } else {
            tmpBytes.clear();
            tmpBytes.write(b);
            lb.setStartPositionAddress(tmpBytes.addressForRead(tmpBytes.start()),
                    tmpBytes.addressForRead(tmpBytes.readLimit()));
        }
        return lb;
    }

    private void check(@NotNull BytesStore b) {
        for (long i = b.start(); i < b.readLimit(); i++) {
            int ch = b.readByte(i);
            if (ch < ' ')
                throw new AssertionError("Char " + ch);
        }
    }

    private void check(@NotNull net.openhft.lang.io.Bytes b) {
        if (b.position() != 0)
            throw new AssertionError();
        if (b.remaining() != b.limit())
            throw new AssertionError();
        for (long i = 0; i < 16 && i < b.limit(); i++) {
            int ch = b.readByte(i);
            if (ch < ' ')
                throw new AssertionError("Char " + ch);
        }
        if (b.limit() > 32)
            for (long i = b.limit() - 16; i < b.limit(); i++) {
                int ch = b.readByte(i);
                if (ch < ' ')
                    throw new AssertionError("Char " + ch);
            }
    }

    public void put(@NotNull final BytesStore key, @NotNull final BytesStore value,
                    final byte remoteIdentifier,
                    final long timestamp) {

//        assert key.refCount() == 1;
//        assert value.refCount()== 1;
        final KvLangBytes kv = kvByte.get();

        @NotNull net.openhft.lang.io.Bytes keyBytes = toLangBytes(key, kv.tmpKeyBytes, kv.key);
        @NotNull net.openhft.lang.io.Bytes valueBytes = toLangBytes(value, kv.tmpValueBytes, kv.value);

        engineReplicationLang.put(keyBytes, valueBytes, remoteIdentifier, timestamp);
        keyBytes.position(0);
        valueBytes.position(0);

    }

    private void remove(@NotNull final BytesStore key, final byte remoteIdentifier, final long timestamp) {

        KvLangBytes kv = kvByte.get();
        @NotNull net.openhft.lang.io.Bytes keyBytes = toLangBytes(key, kv.tmpKeyBytes, kv.key);
        engineReplicationLang.remove(keyBytes, remoteIdentifier, timestamp);
    }

    @Override
    public byte identifier() {
        return engineReplicationLang.identifier();
    }

    private void put(@NotNull final ReplicationEntry entry) {
        put(entry.key(), entry.value(), entry.identifier(), entry.timestamp());
    }

    private void remove(@NotNull final ReplicationEntry entry) {
        remove(entry.key(), entry.identifier(), entry.timestamp());
    }

    @Override
    public void applyReplication(@NotNull final ReplicationEntry entry) {

        if (LOG.isDebugEnabled())
            Jvm.debug().on(getClass(), "applyReplication entry=" + entry);

        if (entry.isDeleted())
            remove(entry);
        else
            put(entry);

    }

    @Nullable
    @Override
    public ModificationIterator acquireModificationIterator(final byte remoteIdentifier) {
        final EngineModificationIterator instance = engineReplicationLang
                .acquireEngineModificationIterator(remoteIdentifier);

        return new EngineModificationIteratorAdaptor(instance, remoteIdentifier);
    }

    /**
     * @param remoteIdentifier the identifier of the remote node to check last replicated update
     *                         time from
     * @return the last time that host denoted by the {@code remoteIdentifier} was updated in
     * milliseconds.
     */
    @Override
    public long lastModificationTime(final byte remoteIdentifier) {
        return engineReplicationLang.lastModificationTime(remoteIdentifier);
    }

    /**
     * @param identifier the identifier of the remote node to check last replicated update time
     *                   from
     * @param timestamp  set the last time that host denoted by the {@code remoteIdentifier} was
     *                   updated in milliseconds.
     */
    @Override
    public void setLastModificationTime(final byte identifier, final long timestamp) {
        engineReplicationLang.setLastModificationTime(identifier, timestamp);
    }

    @NotNull
    @Override
    public String toString() {
        return "CMap2EngineReplicator{" +
                "context=" + context +
                ", identifier=" + engineReplicationLang.identifier() +
                ", keyLocal=" + keyLocal +
                ", valueLocal=" + valueLocal +
                '}';
    }

    static class KvLangBytes {

        final NativeBytes key = NativeBytes.empty();
        final NativeBytes value = NativeBytes.empty();
        final Bytes tmpKeyBytes = Bytes.allocateElasticDirect();
        final Bytes tmpValueBytes = Bytes.allocateElasticDirect();
    }

    private static class KvBytes {
        private final PointerBytesStore key = BytesStore.nativePointer();
        private final PointerBytesStore value = BytesStore.nativePointer();

        @NotNull
        private PointerBytesStore key(long size) {
            return key;
        }

        @NotNull
        private PointerBytesStore value(long size) {
            return value;
        }
    }

    public static class VanillaReplicatedEntry implements ReplicationEntry {

        private final byte remoteIdentifier;
        private BytesStore key;
        @Nullable
        private BytesStore value;
        private long timestamp;
        private byte identifier;
        private boolean isDeleted;
        private long bootStrapTimeStamp;

        /**
         * @param key                the key of the entry
         * @param value              the value of the entry
         * @param timestamp          the timestamp send from the remote server, this time stamp was
         *                           the time the entry was removed
         * @param identifier         the identifier of the remote server
         * @param bootStrapTimeStamp sent to the client on every update this is the timestamp that
         *                           the remote client should bootstrap from when there has been a
         * @param remoteIdentifier   the identifier of the server we are sending data to ( only used
         *                           as a comment )
         */
        VanillaReplicatedEntry(@NotNull final BytesStore key,
                               @Nullable final BytesStore value,
                               final long timestamp,
                               final byte identifier,
                               final boolean isDeleted,
                               final long bootStrapTimeStamp,
                               byte remoteIdentifier) {
            this.key = key;
            this.remoteIdentifier = remoteIdentifier;
            // must be native
            assert key.underlyingObject() == null;
            this.value = value;
            // must be native
            assert value == null || value.underlyingObject() == null;
            this.timestamp = timestamp;
            this.identifier = identifier;
            this.isDeleted = isDeleted;
            this.bootStrapTimeStamp = bootStrapTimeStamp;
        }

        // for deserialization only.
        public VanillaReplicatedEntry() {
            remoteIdentifier = 0;
            key = BytesStore.nativePointer();
            value = BytesStore.nativePointer();
        }

        public void clear() {
            key.isPresent(false);
            value.isPresent(false);
            timestamp = 0;
            identifier = 0;
            isDeleted = false;
            bootStrapTimeStamp = 0;
        }

        @Override
        public void readMarshallable(@NotNull WireIn wire) {
            wire.read(() -> "key").bytesSet((PointerBytesStore) key);
            wire.read(() -> "value").bytesSet((PointerBytesStore) value);
            timestamp(wire.read(() -> "timestamp").int64());
            identifier(wire.read(() -> "identifier").int8());
            isDeleted(wire.read(() -> "isDeleted").bool());
            bootStrapTimeStamp(wire.read(() -> "bootStrapTimeStamp").int64());
        }

        @Override
        public BytesStore key() {
            return key;
        }

        @Nullable
        @Override
        public BytesStore value() {
            return value != null && value.isPresent() ? value : null;
        }

        @Override
        public long timestamp() {
            return timestamp;
        }

        @Override
        public byte identifier() {
            return identifier;
        }

        @Override
        public byte remoteIdentifier() {
            return remoteIdentifier;
        }

        @Override
        public boolean isDeleted() {
            return isDeleted;
        }

        @Override
        public long bootStrapTimeStamp() {
            return bootStrapTimeStamp;
        }

        @Override
        public void key(BytesStore key) {
            this.key = key;
        }

        @Override
        public void value(BytesStore value) {
            this.value = value;
        }

        @Override
        public void timestamp(long timestamp) {
            this.timestamp = timestamp;
        }

        @Override
        public void identifier(byte identifier) {
            this.identifier = identifier;
        }

        @Override
        public void isDeleted(boolean isDeleted) {
            this.isDeleted = isDeleted;
        }

        @Override
        public void bootStrapTimeStamp(long bootStrapTimeStamp) {
            this.bootStrapTimeStamp = bootStrapTimeStamp;
        }

        @NotNull
        @Override
        public String toString() {
            final Bytes<ByteBuffer> bytes = Bytes.elasticByteBuffer();
            new TextWire(bytes).writeDocument(false, d -> d.write().typedMarshallable(this));
            return "\n" + Wires.fromSizePrefixedBlobs(bytes);

        }
    }

    private class EngineModificationIteratorAdaptor implements ModificationIterator
    {
        private final EngineModificationIterator instance;
        private final byte remoteIdentifier;
        private Consumer<ReplicationEntry> replicationEntryListener;
        private volatile Thread consumerThread;

        private EngineModificationIteratorAdaptor(final EngineModificationIterator instance, final byte remoteIdentifier)
        {
            this.instance = instance;
            this.remoteIdentifier = remoteIdentifier;
        }

        @Override
        public boolean hasNext() {
            return instance.hasNext();
        }

        @Override
        public boolean nextEntry(@NotNull Consumer<ReplicationEntry> consumer) {
            //noinspection AssertWithSideEffects
            assert consumerAccessIsSingleThreaded();
            replicationEntryListener = consumer;

            return instance.nextEntry(this::onEntry);
        }

        private boolean onEntry(@NotNull net.openhft.lang.io.Bytes key,
                                @Nullable net.openhft.lang.io.Bytes value, long timestamp,
                                byte identifier,
                                boolean isDeleted,
                                long bootStrapTimeStamp) {
            final KvBytes threadLocal = kvBytesThreadLocal.get();
            @NotNull VanillaReplicatedEntry entry = new VanillaReplicatedEntry(
                    toKey(key, threadLocal.key(key.remaining())),
                    toValue(value, threadLocal.value(value.remaining())),
                    timestamp,
                    identifier,
                    isDeleted,
                    bootStrapTimeStamp,
                    remoteIdentifier);
            replicationEntryListener.accept(entry);
            return true;
        }

        @Override
        public void dirtyEntries(final long fromTimeStamp) {
            instance.dirtyEntries(fromTimeStamp);
        }

        @Override
        public void setModificationNotifier(
                @NotNull final ModificationNotifier modificationNotifier) {
            instance.setModificationNotifier(modificationNotifier::onChange);
        }

        private boolean consumerAccessIsSingleThreaded() {
            final Thread currentThread = Thread.currentThread();
            if (consumerThread == null) {
                consumerThread = currentThread;
            } else {
                if (currentThread != consumerThread) {
                    return false;
                }
            }

            return true;
        }

        @NotNull
        private PointerBytesStore toKey(final @NotNull net.openhft.lang.io.Bytes key,
                                        @NotNull final PointerBytesStore pbs) {
            pbs.set(key.address(), key.capacity());
            return pbs;
        }

        @Nullable
        private BytesStore toValue(final @Nullable net.openhft.lang.io.Bytes value,
                                   @NotNull final PointerBytesStore pbs) {
            if (value == null)
                return null;

            pbs.set(value.address(), value.capacity());
            return pbs;
        }
    }
}
