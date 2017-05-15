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

package net.openhft.chronicle.engine.api;

import net.openhft.chronicle.bytes.BytesStore;
import net.openhft.chronicle.engine.api.pubsub.Replication;
import net.openhft.chronicle.wire.Marshallable;
import net.openhft.chronicle.wire.WireIn;
import net.openhft.chronicle.wire.WireOut;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * @author Rob Austin.
 */
public interface EngineReplication extends Replication {

    String ENGINE_REPLICATION_COMPRESSION = System.getProperty("EngineReplication" +
            ".Compression");

    /**
     * Provides the unique Identifier associated with this instance. <p> An identifier is used to
     * determine which replicating node made the change. <p> If two nodes update their map at the
     * same time with different values, we have to deterministically resolve which update wins,
     * because of eventual consistency both nodes should end up locally holding the same data.
     * Although it is rare two remote nodes could receive an update to their maps at exactly the
     * same time for the same key, we have to handle this edge case, its therefore important not to
     * rely on timestamps alone to reconcile the updates. Typically the update with the newest
     * timestamp should win,  but in this example both timestamps are the same, and the decision
     * made to one node should be identical to the decision made to the other. We resolve this
     * simple dilemma by using a node identifier, each node will have a unique identifier, the
     * update from the node with the smallest identifier wins.
     *
     * @return the unique Identifier associated with this map instance
     */
    byte identifier();

    /**
     * Gets (if it does not exist, creates) an instance of ModificationIterator associated with a
     * remote node, this weak associated is bound using the {@code identifier}.
     *
     * @param remoteIdentifier the identifier of the remote node
     * @return the ModificationIterator dedicated for replication to the remote node with the given
     * identifier
     * @see #identifier()
     */
    @Nullable
    ModificationIterator acquireModificationIterator(byte remoteIdentifier);

    /**
     * Returns the timestamp of the last change from the specified remote node, already replicated
     * to this host.  <p>Used in conjunction with replication, to back fill data from a remote node.
     * This node may have missed updates while it was not been running or connected via TCP.
     *
     * @param remoteIdentifier the identifier of the remote node to check last replicated update
     *                         time from
     * @return a timestamp of the last modification to an entry, or 0 if there are no entries.
     * @see #identifier()
     */
    long lastModificationTime(byte remoteIdentifier);

    void setLastModificationTime(byte identifier, long timestamp);

    /**
     * notifies when there is a changed to the modification iterator
     */
    @FunctionalInterface
    interface ModificationNotifier {
        ModificationNotifier NOP = () -> {
        };

        /**
         * called when ever there is a change applied to the modification iterator
         */
        void onChange();
    }

    /**
     * Holds a record of which entries have modification. Each remote map supported will require a
     * corresponding ModificationIterator instance
     */
    interface ModificationIterator {

        //  void forEach(@NotNull Consumer<ReplicationEntry> consumer);

        boolean hasNext();

        /**
         * @param consumer gets called with the next entry
         * @return {@code true} if the entry was read
         */
        boolean nextEntry(Consumer<ReplicationEntry> consumer);

        /**
         * Dirties all entries with a modification time equal to {@code fromTimeStamp} or newer. It
         * means all these entries will be considered as "new" by this ModificationIterator and
         * iterated once again no matter if they have already been.  <p>This functionality is used
         * to publish recently modified entries to a new remote node as it connects.
         *
         * @param fromTimeStamp the timestamp from which all entries should be dirty
         */
        void dirtyEntries(long fromTimeStamp);

        /**
         * the {@code modificationNotifier} is called when ever there is a change applied to the
         * modification iterator
         *
         * @param modificationNotifier gets notified when a change occurs
         */
        void setModificationNotifier(@NotNull final ModificationNotifier modificationNotifier);
    }

    /**
     * Implemented typically by a replicator, This interface provides the event, which will get
     * called whenever a put() or remove() has occurred to the map
     */
    @FunctionalInterface
    interface EntryCallback {

        /**
         * Called whenever a put() or remove() has occurred to a replicating map.
         */
        boolean onEntry(@NotNull ReplicationEntry entry);
    }

    interface ReplicationEntry extends Marshallable {
        @Nullable
        BytesStore key();

        @Nullable
        BytesStore value();

        long timestamp();

        byte identifier();

        byte remoteIdentifier();

        boolean isDeleted();

        long bootStrapTimeStamp();

        default void key(BytesStore key) {
            throw new UnsupportedOperationException("immutable entry");
        }

        default void value(BytesStore key) {
            throw new UnsupportedOperationException("immutable entry");
        }

        default void timestamp(long timestamp) {
            throw new UnsupportedOperationException("immutable entry");
        }

        default void identifier(byte identifier) {
            throw new UnsupportedOperationException("immutable entry");
        }

        default void isDeleted(boolean isDeleted) {
            throw new UnsupportedOperationException("immutable entry");
        }

        default void bootStrapTimeStamp(long bootStrapTimeStamp) {
            throw new UnsupportedOperationException("immutable entry");
        }

        @Override
        default void readMarshallable(@NotNull final WireIn wire) throws IllegalStateException {
            key(wire.read(() -> "key").bytesStore());
            value(wire.read(() -> "value").bytesStore());
            timestamp(wire.read(() -> "timestamp").int64());
            identifier(wire.read(() -> "identifier").int8());
            isDeleted(wire.read(() -> "isDeleted").bool());
            bootStrapTimeStamp(wire.read(() -> "bootStrapTimeStamp").int64());
        }

        @Override
        default void writeMarshallable(@NotNull final WireOut wire) {
            wire.write(() -> "key").bytes(key());

            if (ENGINE_REPLICATION_COMPRESSION != null && value() != null)
                wire.write(() -> "value").compress(ENGINE_REPLICATION_COMPRESSION, value().bytesForRead());
            else
                wire.write(() -> "value").bytes(value());

            wire.write(() -> "timestamp").int64(timestamp());
            wire.write(() -> "identifier").int8(identifier());
            wire.write(() -> "isDeleted").bool(isDeleted());
            wire.write(() -> "bootStrapTimeStamp").int64(bootStrapTimeStamp());
            wire.writeComment("remoteIdentifier=" + remoteIdentifier());
        }
    }
}
