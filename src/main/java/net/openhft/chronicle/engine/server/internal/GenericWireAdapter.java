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

import net.openhft.chronicle.wire.ValueIn;
import net.openhft.chronicle.wire.ValueOut;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.Function;

class GenericWireAdapter<K, V> implements WireAdapter<K, V> {

    private final BiConsumer<ValueOut, K> keyToWire = ValueOut::object;
    @Nullable
    private final Function<ValueIn, K> wireToKey;
    private final BiConsumer<ValueOut, V> valueToWire = ValueOut::object;
    @NotNull
    private final Function<ValueIn, V> wireToValue;
    @NotNull
    private final Function<ValueIn, Entry<K, V>> wireToEntry;
    private final BiConsumer<ValueOut, Entry<K, V>> entryToWire
            = (v, e) -> v.marshallable(w -> w.write(() -> "key").object(e.getKey())
            .write(() -> "value").object(e.getValue()));

    // if its a string builder re-uses it
    private final ThreadLocal<CharSequence> usingKey = ThreadLocal.withInitial(StringBuilder::new);
    private final ThreadLocal<CharSequence> usingValue = ThreadLocal.withInitial(StringBuilder::new);
    @NotNull
    private final Class<K> kClass;
    @NotNull
    private final Class<V> vClass;

    GenericWireAdapter(@NotNull final Class<K> kClass, @NotNull final Class<V> vClass) {
        this.kClass = kClass;
        this.vClass = vClass;

        wireToKey = (valueIn) -> valueIn.object(kClass);
        wireToValue = in -> in.object(vClass);
        wireToEntry = valueIn -> valueIn.applyToMarshallable(x -> {

            @NotNull final K key = (K) ((kClass == CharSequence.class) ?
                    x.read(() -> "key").object(usingKey.get(), CharSequence.class) :
                    x.read(() -> "key").object(kClass));

            @NotNull final V value = (V) ((vClass == CharSequence.class) ?
                    x.read(() -> "value").object(usingValue.get(), CharSequence.class) :
                    x.read(() -> "value").object(vClass));

            return new Entry<K, V>() {
                @Nullable
                @Override
                public K getKey() {
                    return key;
                }

                @Nullable
                @Override
                public V getValue() {
                    return value;
                }

                @NotNull
                @Override
                public V setValue(V value) {
                    throw new UnsupportedOperationException();
                }
            };
        });
    }

    @NotNull
    public BiConsumer<ValueOut, K> keyToWire() {
        return keyToWire;
    }

    @NotNull
    public Function<ValueIn, K> wireToKey() {
        return wireToKey;
    }

    @NotNull
    public BiConsumer<ValueOut, V> valueToWire() {
        return valueToWire;
    }

    @NotNull
    public Function<ValueIn, V> wireToValue() {
        return wireToValue;
    }

    @NotNull
    public BiConsumer<ValueOut, Entry<K, V>> entryToWire() {
        return entryToWire;
    }

    @NotNull
    public Function<ValueIn, Entry<K, V>> wireToEntry() {
        return wireToEntry;
    }

    @NotNull
    @Override
    public String toString() {
        return "GenericWireAdapter{" +
                "kClass=" + kClass +
                ", vClass=" + vClass +
                '}';
    }
}
