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

package net.openhft.chronicle.engine.query;

import net.openhft.chronicle.core.util.SerializableFunction;
import net.openhft.chronicle.core.util.SerializablePredicate;
import net.openhft.chronicle.engine.api.query.Query;
import net.openhft.chronicle.engine.api.query.Subscription;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;
import java.util.stream.Collector;
import java.util.stream.Stream;

/*
 * Created by peter.lawrey on 12/07/2015.
 */
public class VanillaQuery<E> implements Query<E> {
    private final Stream<E> stream;

    public VanillaQuery(Stream<E> stream) {
        this.stream = stream;
    }

    @NotNull
    @Override
    public Query<E> filter(SerializablePredicate<? super E> predicate) {
        return new VanillaQuery<>(stream.filter(predicate));
    }

    @NotNull
    @Override
    public <R> Query<R> map(SerializableFunction<? super E, ? extends R> mapper) {
        return new VanillaQuery<>(stream.map(mapper));
    }

    @NotNull
    @Override
    public <R> Query<R> project(Class<R> rClass) {
        throw new UnsupportedOperationException("todo");
    }

    @NotNull
    @Override
    public <R> Query<R> flatMap(@NotNull SerializableFunction<? super E, ? extends Query<? extends R>> mapper) {
        return new VanillaQuery<>(stream.flatMap(e -> mapper.apply(e).stream()));
    }

    @Override
    public Stream<E> stream() {
        return stream;
    }

    @NotNull
    @Override
    public Subscription subscribe(Consumer<? super E> action) {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public <R, A> R collect(Collector<? super E, A, R> collector) {
        return stream.collect(collector);
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        stream.forEach(action);
    }
}
