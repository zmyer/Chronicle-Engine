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

package net.openhft.chronicle.engine.api.query;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.annotation.UsedViaReflection;
import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.engine.map.QueueObjectSubscription;
import net.openhft.chronicle.wire.AbstractMarshallable;
import net.openhft.chronicle.wire.Demarshallable;
import net.openhft.chronicle.wire.WireIn;
import net.openhft.chronicle.wire.Wires;
import net.openhft.compiler.CompilerUtils;
import net.openhft.lang.model.DataValueGenerator;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Rob Austin.
 */
public class VanillaIndexQuery<V> extends AbstractMarshallable implements Demarshallable,
        IndexQuery<V> {

    private static final Logger LOG = LoggerFactory.getLogger(QueueObjectSubscription.class);
    private Class<V> valueClass;
    private String select;
    private String eventName;
    private long from;
    private boolean bootstrap = true;

    public VanillaIndexQuery() {
    }

    @UsedViaReflection
    public VanillaIndexQuery(@NotNull WireIn wire) {
        readMarshallable(wire);
    }


    @Override
    public void readMarshallable(@NotNull WireIn wire) throws IORuntimeException {
        Wires.readMarshallable(this, wire, false);
    }


    public Class<V> valueClass() {
        return valueClass;
    }

    @NotNull
    public String select() {
        return select == null ? "" : select;
    }

    public String eventName() {
        return eventName;
    }

    public void eventName(String eventName) {
        this.eventName = eventName;
    }

    /**
     * @param valueClass the type of the value
     * @param select     java source
     * @return this
     */
    @NotNull
    public VanillaIndexQuery select(@NotNull Class valueClass, @NotNull String select) {
        this.select = select;
        this.valueClass = valueClass;

        // used to test-compile the predicate on the client side
        try {
            ClassCache.newInstance0(new ClassCache.TypedSelect(valueClass, select), "TestCompile");

        } catch (Exception e) {
            Jvm.warn().on(getClass(), e.getMessage());
        }
        return this;
    }

    @Override
    public boolean bootstrap() {
        return bootstrap;
    }

    public long fromIndex() {
        return from;
    }

    /**
     * @param from if 0 takes data from the end of the queue, if -1 takes data from the start of the
     *             queue, otherwise the index used
     * @return that
     */
    @NotNull
    public IndexQuery<V> fromIndex(long from) {
        this.from = from;
        return this;
    }

    public Predicate<V> filter() {
        return ClassCache.newInstance(valueClass, select);
    }

    @NotNull
    @Override
    public String toString() {
        return "VanillaMarshableQuery{" +
                "valueClass=" + valueClass +
                ", select='" + select + '\'' +
                ", eventName='" + eventName + '\'' +
                ", from=" + Long.toHexString(from) +
                '}';
    }

    public VanillaIndexQuery bootstrap(boolean bootstrap) {
        this.bootstrap = bootstrap;
        return this;
    }

    /**
     * ensures that the same select/predicate will return an existing class instance
     */
    private static class ClassCache {
        private static final ConcurrentMap<TypedSelect, Predicate> cache = new
                ConcurrentHashMap<>();
        @NotNull
        private static AtomicLong uniqueClassId = new AtomicLong();
        private static Pattern p = Pattern.compile("\"");
        private static ThreadLocal<StringBuffer> sbTl = ThreadLocal.withInitial(StringBuffer::new);

        private static Predicate newInstance(final Class clazz0, final String select) {
            return cache.computeIfAbsent(new TypedSelect(clazz0, select), ClassCache::newInstanceAutoGenerateClassName);
        }

        private static <V> Predicate<V> newInstanceAutoGenerateClassName(@NotNull TypedSelect typedSelect) {
            return newInstance0(typedSelect, "AutoGeneratedPredicate" + uniqueClassId.incrementAndGet());
        }

        private static CharSequence escapeQuotes(@NotNull final String source) {
            @NotNull Matcher m = p.matcher(source);
            final StringBuffer sb = sbTl.get();
            sb.setLength(0);
            while (m.find()) {
                m.appendReplacement(sb, "\\\\\"");
            }
            m.appendTail(sb);
            return sb;
        }

        private static <V> Predicate<V> newInstance0(@NotNull TypedSelect typedSelect,
                                                     final String className) {
            String clazz = typedSelect.clazz.getName();

            final String select = typedSelect.select;

            CharSequence toString = select.contains("\"") ? escapeQuotes(select) : select;

            @NotNull String source = new StringBuilder().
                    append("package net.openhft.chronicle.engine.api.query;\npublic class ")
                    .append(className)
                    .append(" implements ")
                    .append("java.util.function.Predicate<")
                    .append(clazz).append("> {\n\tpublic ")
                    .append("boolean test(").append(clazz).append(" value) ")
                    .append("{\n\t\treturn ").append(typedSelect.select)
                    .append(";\n\t}\n\n\tpublic String toString(){\n\t\treturn \"")
                    .append(toString).append("\";\n\t}\n}").toString();

            LoggerFactory.getLogger(DataValueGenerator.class).info(source);
            ClassLoader classLoader = ClassCache.class.getClassLoader();
            try

            {
                Class<Predicate> clazzP = CompilerUtils.CACHED_COMPILER.loadFromJava(classLoader,
                        "net.openhft.chronicle.engine.api.query." + className, source);
                return clazzP.newInstance();

            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        static class TypedSelect extends AbstractMarshallable {
            private String select;
            private Class clazz;

            private TypedSelect(Class clazz, String select) {
                this.select = select;
                this.clazz = clazz;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof TypedSelect)) return false;

                @NotNull TypedSelect that = (TypedSelect) o;

                if (select != null ? !select.equals(that.select) : that.select != null)
                    return false;
                return clazz != null ? clazz.equals(that.clazz) : that.clazz == null;

            }

            @Override
            public int hashCode() {
                int result = select != null ? select.hashCode() : 0;
                result = 31 * result + (clazz != null ? clazz.hashCode() : 0);
                return result;
            }
        }
    }
}

