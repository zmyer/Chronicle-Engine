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

package net.openhft.chronicle.engine.redis;

import net.openhft.chronicle.core.util.SerializableFunction;
import net.openhft.chronicle.engine.api.Updatable;
import net.openhft.chronicle.engine.api.map.MapView;
import net.openhft.chronicle.engine.api.pubsub.Reference;
import net.openhft.chronicle.engine.api.pubsub.TopicPublisher;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The purpose is to provide Redis like command which wrap a MapView or a Reference to an underlying store.
 */
public class RedisEmulator {

    /**
     * If key already exists and is a string, this command appends the value at the end of the string.
     * If key does not exist it is created and set as an empty string, so APPEND will be similar to
     * SET in this special case.
     *
     * @return Integer reply: the length of the string after the append operation.
     */
    public static void append(@NotNull Reference<String> ref, String toAppend) {
        ref.applyTo(v -> v + toAppend);
    }

    /**
     * If key already exists and is a string, this command appends the value at the end of the string.
     * If key does not exist it is created and set as an empty string, so APPEND will be similar to
     * SET in this special case.
     *
     * @return Integer reply: the length of the string after the append operation.
     */
    public static long append(@NotNull MapView<String, String> map, String key, @NotNull String toAppend) {
        return map.applyTo(m -> {
                    @Nullable String v = m.get(key);
                    if (v != null) {
                        m.put(key, v + toAppend);
                        return (long) (v + toAppend).length();
                    } else {
                        m.put(key, toAppend);
                        return (long) toAppend.length();
                    }
                }
        );
    }

    public static int bitcount(@NotNull Reference<BitSet> bits) {
        return bits.applyTo(b -> b.cardinality());
    }

    public static int bitcount(@NotNull MapView<String, BitSet> map, String key) {
        return map.applyToKey(key, b -> b.cardinality());
    }

    public static int bitpos(@NotNull Reference<BitSet> bits) {
        return bits.applyTo(b -> b.nextSetBit(0));
    }

    public static int bitpos(@NotNull Reference<BitSet> bits, int from) {
        return bits.applyTo(b -> b.nextSetBit(from));
    }

    @Nullable
    public static <T> T blpop(@NotNull Reference<BlockingDeque<T>> bd, int timeoutMS) {
        return bd.applyTo(d -> {
            try {
                return d.pollFirst(timeoutMS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return null;
            }
        });
    }

    @Nullable
    public static <T> T brpop(@NotNull Reference<BlockingDeque<T>> bd, int timeoutMS) {
        return bd.applyTo(d -> {
            try {
                return d.pollLast(timeoutMS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return null;
            }
        });
    }

    @Nullable
    public static <T> T brpoplpush(@NotNull MapView<String, BlockingDeque<T>> deques, String d1, String d2, int timeoutMS) {
        return deques.applyTo(ds -> {
            try {
                T t = ds.get(d1).pollLast(timeoutMS, TimeUnit.MILLISECONDS);
                ds.get(d2).offer(t);
                return t;
            } catch (InterruptedException e) {
                return null;
            }
        });
    }

    public static long decr(@NotNull Reference<Long> l) {
        return l.syncUpdate(v -> v - 1, v -> v);
    }

    public static long decr(@NotNull MapView<String, Long> map, String key) {
        return map.applyToKey(key, v -> v - 1);
    }

    public static void del(@NotNull MapView<String, ?> map, String... keys) {
        map.keySet().removeAll(Arrays.asList(keys));
    }

    public static void echo(@NotNull Updatable updatable, String message) {
        updatable.asyncUpdate(v -> Logger.getAnonymousLogger().info(message));
    }

    /**
     * Returns if key exists.
     * Since Redis 3.0.3 it is possible to specify multiple keys instead of a
     * single one. In such a case, it returns the total number of keys existing.
     * Note that returning 1 or 0 for a single key is just a special case
     * of the variadic usage, so the command is completely backward compatible.
     * <p>
     * The user should be aware that if the same existing key is mentioned
     * in the arguments multiple times, it will be counted multiple times.
     * So if somekey exists, EXISTS somekey somekey will return 2.
     *
     * @param map
     * @param keys
     * @return Integer reply, specifically:
     * 1 if the key exists.
     * 0 if the key does not exist.
     * Since Redis 3.0.3 the command accepts a variable number of keys and the
     * return value is generalized:
     * The number of keys existing among the ones specified as arguments.
     * Keys mentioned multiple times and existing are counted multiple times.
     */
    public static long exists(@NotNull MapView<String, ?> map, @NotNull String... keys) {
        if (keys.length == 1) return map.containsKey(keys) ? 1 : 0;

        return map.applyTo(m -> {
            long count = 0;
            for (int i = 0; i < keys.length; i++) {
                if (m.containsKey(keys[i])) count++;
            }
            return count;
        });
    }

    /**
     * Get the value of key. If the key does not exist the special value nil is returned.
     * An error is returned if the value stored at key is not a string,
     * because GET only handles string values.
     *
     * @return Bulk string reply: the value of key, or nil when key does not exist.
     */
    @Nullable
    public static <V> V get(@NotNull MapView<String, V> map, String key) {
        return map.get(key);
    }

    public static boolean getBit(@NotNull Reference<BitSet> bits, int index) {
        return bits.applyTo(b -> b.get(index));
    }

    @Nullable
    public static String getRange(@NotNull Reference<String> str, int start, int end) {
        return str.applyTo(s -> s.substring(start, end));
    }

    @Nullable
    public static <V> V getSet(@NotNull Reference<V> v, V newValue) {
        return v.getAndSet(newValue);
    }

    /**
     * Removes the specified fields from the hash stored at key. Specified fields that do not exist within
     * this hash are ignored. If key does not exist, it is treated as an empty hash
     * and this command returns 0.
     *
     * @return Integer reply: the number of fields that were removed from the hash,
     * not including specified but non existing fields.
     */
    public static <T> long hdel(@NotNull MapView<String, T> map, @NotNull String... keys) {
        if (keys.length == 1) {
            return map.getAndRemove(keys[0]) == null ? 0 : 1;
        }

        //todo not able to apply return value
        return map.applyTo((SerializableFunction<MapView<String, T>, Long>) m -> {
            long counter = 0;
            for (String key : keys) {
                if (m.getAndRemove(key) != null) counter++;
            }
            return counter;
        });
    }

    /**
     * Returns if field is an existing field in the hash stored at key.
     *
     * @return Integer reply, specifically:
     * 1 if the hash contains field.
     * 0 if the hash does not contain field, or key does not exist.
     */
    public static int hexists(@NotNull MapView<String, ?> map, String key) {
        return map.containsKey(key) ? 1 : 0;
    }

    /**
     * Returns the value associated with field in the hash stored at key.
     *
     * @return Bulk string reply: the value associated with field,
     * or nil when field is not present in the hash or key does not exist.
     */
    @Nullable
    public static <V> V hget(@NotNull MapView<String, V> map, String key) {
        return map.get(key);
    }

    /**
     * Returns all fields and values of the hash stored at key. In the returned value,
     * every field name is followed by its value,
     * so the length of the reply is twice the size of the hash.
     * <p>
     * Note Redis returns the keys in the same order they were inserted
     * Chronicle returns them in an arbitrary order
     *
     * @reply Array reply: list of fields and their values stored in the hash, or
     * an empty list when key does not exist.
     */
    public static <K, V> void hgetall(@NotNull MapView<K, V> map, @NotNull Consumer<Map.Entry<K, V>> entryConsumer) {
        map.entrySet().forEach(entryConsumer);
    }

    /**
     * Increments the number stored at field in the hash stored at key by increment.
     * If key does not exist, a new key holding a hash is created. If field does
     * not exist the value is set to 0 before the operation is performed.
     *
     * @return Integer reply: the value at field after the increment operation.
     */
    public static void hincrby(@NotNull MapView<String, Long> map, String key, long toAdd) {
        map.asyncUpdateKey(key, v -> v + toAdd);
    }

    public static void hincrbyfloat(@NotNull MapView<String, Double> map, String key, double toAdd) {
        map.asyncUpdateKey(key, v -> v + toAdd);
    }

    public static <K, V> void hkeys(@NotNull MapView<K, V> map, @NotNull Consumer<K> keyConsumer) {
        map.keySet().forEach(keyConsumer);
    }

    public static int hlen(@NotNull MapView map) {
        return map.size();
    }

    /**
     * Returns the values associated with the specified fields in the hash stored at key.
     * For every field that does not exist in the hash, a nil value is returned.
     * Because a non-existing keys are treated as empty hashes, running HMGET against
     * a non-existing key will return a list of nil values.
     *
     * @return Array reply: list of values associated with the given fields,
     * in the same order as they are requested.
     */
    @Nullable
    public static Map<String, Object> hmget(@NotNull MapView<String, Object> map, @NotNull String... keys) {
        return map.applyTo(m -> {
            @NotNull Map<String, Object> ret = new LinkedHashMap<String, Object>();
            for (String key : keys) {
                ret.put(key, m.get(key));
            }
            return ret;
        });
    }

    public static void hmset(@NotNull MapView<String, String> map, @NotNull String... keyAndValues) {
        map.asyncUpdate(m -> {
            for (int i = 0; i < keyAndValues.length; i += 2)
                map.put(keyAndValues[i], keyAndValues[i + 1]);
        });
    }

    /**
     * Delete all the keys of the currently selected DB. This command never fails.
     * The time-complexity for this operation is O(N), N being the number of keys in the database.
     */
    public static <V> String flushdb(@Nullable MapView<String, V> map) {
        if (map != null)
            map.clear();
        return "OK";
    }

    /**
     * Return the number of keys in the currently-selected database.
     */
    public static <V> int dbsize(@NotNull MapView<String, V> map) {
        return map.size();
    }

    /**
     * Sets field in the hash stored at key to value. If key does not exist, a new key holding a hash is created.
     * If field already exists in the hash, it is overwritten.
     *
     * @return Integer reply, specifically:
     * 1 if field is a new field in the hash and value was set.
     * 0 if field already exists in the hash and the value was updated.
     */
    public static <V> int hset(@NotNull MapView<String, V> map, String key, V value) {
        @Nullable V put = map.getAndPut(key, value);
        return put == null ? 1 : 0;
    }

    public static <V> void hsetnx(@NotNull MapView<String, V> map, @NotNull String key, V value) {
        map.putIfAbsent(key, value);
    }

    public static int hstrlen(@NotNull MapView<String, String> map, String key) {
        return map.applyToKey(key, String::length);
    }

    public static <K, V> void hvals(@NotNull MapView<K, V> map, @NotNull Consumer<V> valueConsumer) {
        map.values().forEach(valueConsumer);
    }

    public static long incr(@NotNull Reference<Long> l) {
        return l.syncUpdate(v -> v + 1, v -> v);
    }

    /**
     * Increments the number stored at key by one. If the key does not exist, it is set to 0
     * before performing the operation. An error is returned if the key contains a value
     * of the wrong type or contains a string that can not be represented as integer.
     * This operation is limited to 64 bit signed integers.
     * Note: this is a string operation because Redis does not have a dedicated integer type.
     * The string stored at the key is interpreted as a base-10 64 bit signed integer
     * to execute the operation.
     * Redis stores integers in their integer representation, so for string values
     * that actually hold an integer, there is no overhead for storing
     * the string representation of the integer.
     *
     * @return Integer reply: the value of key after the increment
     */
    public static long incr(@NotNull MapView<String, Long> map, String key) {
        return map.applyToKey(key, v -> v + 1);
    }

    /**
     * Increments the number stored at key by increment. If the key does not exist,
     * it is set to 0 before performing the operation. An error is returned if the
     * key contains a value of the wrong type or contains a string that can not be
     * represented as integer. This operation is limited to 64 bit signed integers.
     * See INCR for extra information on increment/decrement operations.
     *
     * @return Integer reply: the value of key after the increment
     */
    public static long incrby(@NotNull MapView<String, Long> map, String key, long toAdd) {
        return map.applyToKey(key, v -> v + toAdd);
    }

    public static double incrbyfloat(@NotNull MapView<String, Double> map, String key, double toAdd) {
        return map.syncUpdateKey(key, v -> v + toAdd, v -> v);
    }

    @Nullable
    public static Set<String> keys(@NotNull MapView<String, ?> map, @NotNull String pattern) {
        return map.applyTo(m -> {
            Pattern compile = Pattern.compile(pattern);
            return m.keySet().stream()
                    .filter(k -> compile.matcher(k).matches())
                    .collect(Collectors.toSet());
        });
    }

    @Nullable
    public static <V> V lindex(@NotNull MapView<String, List<V>> map, String name, int index) {
        return map.applyToKey(name, l -> l.get(index));
    }

    public static <V> void linsert(@NotNull MapView<String, List<V>> map, String name, boolean after, V pivot, V element) {
        map.asyncUpdateKey(name, l -> {
            int index = l.indexOf(pivot);
            if (index >= 0) {
                if (after) index++;
                l.add(index, element);
            }
            return l;
        });
    }

    public static <V> int llen(@NotNull MapView<String, List<V>> map, String name) {
        return map.applyToKey(name, l -> l.size());
    }

    @Nullable
    public static <V> V lpop(@NotNull MapView<String, List<V>> map, String name) {
        return map.applyToKey(name, l -> l.remove(0));
    }

    public static <V> int lpush(@NotNull MapView<String, List<V>> map, String name, V... values) {
        map.asyncUpdateKey(name, l -> {
            l.addAll(0, Arrays.asList(values));
            return l;
        });
        //todo this should be part of the update
        return 0;
    }

    public static <V> void lpushx(@NotNull MapView<String, List<V>> map, String name, V value) {
        map.computeIfPresent(name, (k, l) -> {
            l.add(value);
            return l;
        });
    }

    @Nullable
    public static <V> List<V> lrange(@NotNull MapView<String, List<V>> map, String name, int start, int stop) {
        return map.applyToKey(name, l -> l.subList(start, stop));
    }

    public static <V> void lset(@NotNull MapView<String, List<V>> map, String name, int index, V value) {
        map.asyncUpdateKey(name, l -> {
            l.set(index, value);
            return l;
        });
    }

    @Nullable
    public static Map<String, Object> mget(@NotNull MapView<String, Object> map, String... keys) {
        return hmget(map, keys);
    }

    public static void mset(@NotNull MapView<String, String> map, String... keyAndValues) {
        hmset(map, keyAndValues);
    }

    public static <T, M> void publish(@NotNull TopicPublisher<T, M> publisher, @NotNull T topic, @NotNull M message) {
        publisher.publish(topic, message);
    }

    public static <V> void rename(@NotNull MapView<String, V> map, String from, String to) {
        map.asyncUpdate(m -> m.put(to, m.remove(from)));
    }

    public static <V> void renamenx(@NotNull MapView<String, V> map, String from, String to) {
        map.asyncUpdate(m -> m.computeIfAbsent(to, k -> m.remove(from)));
    }

    @Nullable
    public static <V> V rpop(@NotNull MapView<String, Deque<V>> map, String key) {
        return map.applyToKey(key, Deque::removeLast);
    }

    @Nullable
    public static <V> V rpoplpush(@NotNull MapView<String, Deque<V>> deques, String from, String to) {
        return deques.applyTo(ds -> {
            V t = ds.get(from).poll();
            ds.get(to).offer(t);
            return t;
        });
    }

    public static <V> void rpush(@NotNull MapView<String, Deque<V>> map, String key, V... values) {
        map.asyncUpdateKey(key, d -> {
            d.addAll(Arrays.asList(values));
            return d;
        });
    }
}
