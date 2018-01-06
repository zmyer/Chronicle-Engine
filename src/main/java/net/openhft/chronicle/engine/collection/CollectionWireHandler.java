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

package net.openhft.chronicle.engine.collection;

/*
 * Created by Rob Austin
 */

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.network.connection.CoreFields;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * @author Rob Austin.
 */
public class CollectionWireHandler<U, C extends Collection<U>> {

    private static final int SIZE_OF_SIZE = 4;
    private static final Logger LOG = LoggerFactory.getLogger(CollectionWireHandler.class);
    private Function<ValueIn, U> fromWire;
    private BiConsumer<ValueOut, U> toWire;

    @Nullable
    private WireIn inWire = null;
    @Nullable
    private WireOut outWire = null;
    private C underlyingCollection;
    private long tid;
    private Supplier<C> factory;

    private final ReadMarshallable dataConsumer = new ReadMarshallable() {
        @Override
        public void readMarshallable(WireIn wire) throws IllegalStateException {

            try {
                final StringBuilder eventName = Wires.acquireStringBuilder();
                @NotNull @SuppressWarnings("ConstantConditions")
                final ValueIn valueIn = inWire.readEventName(eventName);

                outWire.writeDocument(true, w -> w.writeEventName(CoreFields.tid).int64
                        (CollectionWireHandler.this.tid));

                outWire.writeDocument(false, out -> {

                    // note :  remove on the key-set returns a boolean and on the map returns the
                    // old value
                    if (EventId.remove.contentEquals(eventName)) {
                        outWire.write(CoreFields.reply).bool(underlyingCollection.remove(fromWire.apply(valueIn)));
                        return;
                    }

                    // note :  remove on the key-set returns a boolean and on the map returns the
                    // old value
                    if (EventId.iterator.contentEquals(eventName)) {
                        @NotNull final ValueOut valueOut = out.writeEventName(CoreFields.reply);
                        valueOut.sequence(v -> underlyingCollection.forEach(e -> toWire.accept(v, e)));
                        return;
                    }

                    if (EventId.numberOfSegments.contentEquals(eventName)) {
                        outWire.write(CoreFields.reply).int32(1);
                        return;
                    }

                    if (EventId.isEmpty.contentEquals(eventName)) {
                        outWire.write(CoreFields.reply).bool(underlyingCollection.isEmpty());
                        return;
                    }

                    if (EventId.size.contentEquals(eventName)) {
                        outWire.write(CoreFields.reply).int32(underlyingCollection.size());
                        return;
                    }

                    if (EventId.clear.contentEquals(eventName)) {
                        underlyingCollection.clear();
                        return;
                    }

                    if (EventId.contains.contentEquals(eventName)) {
                        outWire.write(CoreFields.reply).bool(
                                underlyingCollection.contains(fromWire.apply(valueIn)));
                        return;
                    }

                    if (EventId.add.contentEquals(eventName)) {
                        outWire.write(CoreFields.reply).bool(
                                underlyingCollection.add(fromWire.apply(valueIn)));
                        return;
                    }

                    if (EventId.remove.contentEquals(eventName)) {
                        outWire.write(CoreFields.reply).bool(
                                underlyingCollection.remove(fromWire.apply(valueIn)));
                        return;
                    }

                    if (EventId.containsAll.contentEquals(eventName)) {
                        outWire.write(CoreFields.reply).bool(
                                underlyingCollection.remove(collectionFromWire()));
                        return;
                    }

                    if (EventId.addAll.contentEquals(eventName)) {
                        outWire.write(CoreFields.reply).bool(
                                underlyingCollection.addAll(collectionFromWire()));
                        return;
                    }

                    if (EventId.removeAll.contentEquals(eventName)) {
                        outWire.write(CoreFields.reply).bool(
                                underlyingCollection.removeAll(collectionFromWire()));
                        return;
                    }

                    if (EventId.retainAll.contentEquals(eventName)) {
                        outWire.write(CoreFields.reply).bool(
                                underlyingCollection.retainAll(collectionFromWire()));
                        return;
                    }

                    throw new IllegalStateException("unsupported event=" + eventName);
                });

            } catch (Exception e) {
                Jvm.warn().on(getClass(), e);

            } finally {

                if (YamlLogging.showServerWrites()) {
                    assert outWire.startUse();
                    try {
                        @NotNull final Bytes<?> outBytes = outWire.bytes();
                        long len = outBytes.writePosition();
                        if (len >= SIZE_OF_SIZE) {
                            String s = Wires.fromSizePrefixedBlobs((Wire) outWire);

                            LOG.info("server writes:\n\n" + s);
                        }
                    }finally {
                        assert outWire.endUse();
                    }
                }
            }
        }
    };

    private C collectionFromWire() {
        C c = factory.get();
        @NotNull @SuppressWarnings("ConstantConditions")
        final ValueIn valueIn = ((Wire) outWire).getValueIn();
        while (valueIn.hasNextSequenceItem()) {
            c.add(fromWire.apply(valueIn));
        }
        return c;
    }

    @SuppressWarnings("unchecked")
    public void process(@NotNull WireIn in,
                        @NotNull WireOut out,
                        @NotNull C collection,
                        @NotNull BiConsumer toWire,
                        @NotNull Function fromWire,
                        @NotNull Supplier factory,
                        long tid) {

        this.fromWire = fromWire;
        this.toWire = toWire;
        this.underlyingCollection = collection;
        this.factory = factory;

        try {
            this.inWire = in;
            this.outWire = out;
            this.tid = tid;
            assert in.startUse();
            dataConsumer.readMarshallable(in);

        } catch (RuntimeException e) {
            Jvm.warn().on(getClass(), e);

        }finally {
            assert in.endUse();
        }
    }

    enum Params implements WireKey {
        key,
        segment,
    }

    enum EventId implements ParameterizeWireKey {
        size,
        isEmpty,
        add,
        addAll,
        retainAll,
        containsAll,
        removeAll,
        clear,
        remove(CollectionWireHandler.Params.key),
        numberOfSegments,
        contains(CollectionWireHandler.Params.key),
        identifier,
        iterator(CollectionWireHandler.Params.segment);

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

