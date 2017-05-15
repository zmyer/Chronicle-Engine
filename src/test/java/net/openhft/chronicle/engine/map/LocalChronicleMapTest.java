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

import jdk.nashorn.internal.ir.annotations.Ignore;
import net.openhft.chronicle.engine.map.MapClientTest.LocalMapSupplier;
import net.openhft.chronicle.engine.tree.VanillaAssetTree;
import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;

import static net.openhft.chronicle.engine.Utils.methodName;
import static net.openhft.chronicle.engine.Utils.yamlLoggger;
import static net.openhft.chronicle.wire.YamlLogging.writeMessage;
import static org.junit.Assert.*;

/*
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 * Other contributors include Andrew Wright, Jeffrey Hayes,
 * Pat Fisher, Mike Judd.
 */
@Ignore
public class LocalChronicleMapTest extends JSR166TestCase {
    private static int s_port = 11050;
    @NotNull
    @Rule
    public TestName name = new TestName();

    @NotNull
    private ClosableMapSupplier<Integer, String> newIntString() throws IOException {
        @NotNull final LocalMapSupplier supplier = new LocalMapSupplier<>(Integer
                .class, String.class, new VanillaAssetTree().forTesting());

        return new ClosableMapSupplier<Integer, String>() {

            @NotNull
            @Override
            public Map<Integer, String> get() {
                return supplier.get();
            }

            @Override
            public void close() throws IOException {
                supplier.close();
            }
        };

    }

    @NotNull
    private ClosableMapSupplier<CharSequence, CharSequence> newStrStrMap() throws
            IOException {

        @NotNull final LocalMapSupplier supplier = new LocalMapSupplier<>(CharSequence.class, CharSequence.class, new VanillaAssetTree().forTesting());

        return new ClosableMapSupplier<CharSequence, CharSequence>() {

            @NotNull
            @Override
            public Map<CharSequence, CharSequence> get() {
                return supplier.get();
            }

            @Override
            public void close() throws IOException {
                supplier.close();
            }
        };
    }

    @Before
    public void before() {
        methodName(name.getMethodName());
    }

    /**
     * Returns a new map from Integers 1-5 to Strings "A"-"E".
     */
    @NotNull
    private ClosableMapSupplier<Integer, String> map5() throws IOException {
        @NotNull ClosableMapSupplier<Integer, String> supplier = newIntString();
        final Map<Integer, String> map = supplier.get();
        System.out.println(map.size());
        assertTrue(map.isEmpty());
        map.put(one, "A");
        map.put(two, "B");
        map.put(three, "C");
        map.put(four, "D");
        map.put(five, "E");
        assertFalse(map.isEmpty());
        assertEquals(5, map.size());
        return supplier;
    }

    /**
     * clear removes all pairs
     */
    @Test(timeout = 50000)
    public void testClear() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
            final Map map = supplier.get();

            yamlLoggger(map::clear);
            assertEquals(0, map.size());
        }
    }

    /**
     * contains returns true for contained value
     */
    @Test(timeout = 50000)
    public void testContains() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
            final Map map = supplier.get();

            writeMessage("when the key exists");
            yamlLoggger(() -> assertTrue(map.containsValue("A")));
            writeMessage("when it doesnt exist");
            yamlLoggger(() -> assertFalse(map.containsValue("Z")));
        }
    }

    /**
     * containsKey returns true for contained key
     */
    @Test(timeout = 50000)
    public void testContainsKey() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
            final Map map = supplier.get();
            writeMessage("example of containsKey(<key>) returning true");
            yamlLoggger(() -> assertTrue(map.containsKey(one)));
            assertFalse(map.containsKey(zero));
        }
    }

    /**
     * containsValue returns true for held values
     */
    @Test(timeout = 50000)
    public void testContainsValue() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
            final Map map = supplier.get();
            writeMessage("example of containsValue(<value>) returning true");
            yamlLoggger(() -> assertTrue(map.containsValue("A")));
            assertFalse(map.containsValue("Z"));
        }
    }

    /**
     * get returns the correct element at the given key, or null if not present
     */
    @Test
    public void testGet() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
            final Map map = supplier.get();
            assertEquals("A", map.get(one));
            try (@NotNull ClosableMapSupplier empty = newStrStrMap()) {
                writeMessage("example of get(<key>) returning null, when the keys is not " +
                        "present in the map");

                yamlLoggger(() -> {
                    Object object = map.get(notPresent);
                    assertNull(object);
                });
            }
        }
    }

    /**
     * isEmpty is true of empty map and false for non-empty
     */
    @Test(timeout = 50000)
    public void testIsEmpty() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> emptySupplier = newIntString()) {
            final Map empty = emptySupplier.get();
            try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {

                final Map map = supplier.get();
                if (!empty.isEmpty()) {
                    System.out.print("not empty " + empty);
                }

                writeMessage("example of isEmpty() returning true, not it uses the size() method");
                yamlLoggger(() -> assertTrue(empty.isEmpty()));
                assertFalse(map.isEmpty());
            }
        }
    }

    /**
     * keySet returns a Set containing all the keys
     */
    @Test(timeout = 50000)
    public void testKeySet() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
            final Map map = supplier.get();
            writeMessage("example of checking the size of a keyset");
            yamlLoggger(() -> {
                        @NotNull Set s = map.keySet();
                        assertEquals(5, s.size());
                    }
            );
            @NotNull Set s = map.keySet();
            assertTrue(s.contains(one));
            assertTrue(s.contains(two));
            assertTrue(s.contains(three));
            assertTrue(s.contains(four));
            assertTrue(s.contains(five));
        }
    }

    /**
     * keySet.toArray returns contains all keys
     */
    @Test(timeout = 50000)
    public void testKeySetToArray() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
            final Map map = supplier.get();
            @NotNull Set s = map.keySet();
            @NotNull Object[] ar = s.toArray();
            assertTrue(s.containsAll(Arrays.asList(ar)));
            assertEquals(5, ar.length);
            ar[0] = m10;
            assertFalse(s.containsAll(Arrays.asList(ar)));
        }
    }

    /**
     * Values.toArray contains all values
     */
    @Test(timeout = 50000)
    public void testValuesToArray() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
            final Map map = supplier.get();
            @NotNull Collection v = map.values();
            @NotNull Object[] ar = v.toArray();
            @NotNull ArrayList s = new ArrayList(Arrays.asList(ar));
            assertEquals(5, ar.length);
            assertTrue(s.contains("A"));
            assertTrue(s.contains("B"));
            assertTrue(s.contains("C"));
            assertTrue(s.contains("D"));
            assertTrue(s.contains("E"));
        }
    }

    /**
     * entrySet.toArray contains all entries
     */
    @Test(timeout = 50000)
    public void testEntrySetToArray() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
            final Map map = supplier.get();
            writeMessage("map.entrySet().toArray() first gets the entry set and then converts " +
                    "it to an array");
            yamlLoggger(() -> {
                @NotNull Set s = map.entrySet();
                s.toArray();
            });

            @NotNull Set s = map.entrySet();
            @NotNull Object[] ar = s.toArray();
            assertEquals(5, ar.length);
            for (int i = 0; i < 5; ++i) {
                assertTrue(map.containsKey(((Entry) (ar[i])).getKey()));
                assertTrue(map.containsValue(((Entry) (ar[i])).getValue()));
            }
        }
    }

    /**
     * values collection contains all values
     */
    @Test(timeout = 50000)
    public void testValues() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
            final Map map = supplier.get();
            writeMessage("example of getting the values and then calling size()");
            yamlLoggger(() -> {
                @NotNull Collection s = map.values();
                s.size();
            });

            @NotNull Collection s = map.values();
            assertEquals(5, s.size());
            assertTrue(s.contains("A"));
            assertTrue(s.contains("B"));
            assertTrue(s.contains("C"));
            assertTrue(s.contains("D"));
            assertTrue(s.contains("E"));
        }
    }

    /**
     * entrySet contains all pairs
     */
    @Test
    public void testEntrySet() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
            final Map map = supplier.get();
            writeMessage("example of getting and entry set itterator");
            yamlLoggger(() -> {
                @NotNull Set entrySet = map.entrySet();
                entrySet.iterator();
            });

            @NotNull Set s = map.entrySet();
            assertEquals(5, s.size());
            for (final Object value : s) {
                @NotNull Entry e = (Entry) value;
                assertTrue(
                        (e.getKey().equals(one) && e.getValue().equals("A")) ||
                                (e.getKey().equals(two) && e.getValue().equals("B")) ||
                                (e.getKey().equals(three) && e.getValue().equals("C")) ||
                                (e.getKey().equals(four) && e.getValue().equals("D")) ||
                                (e.getKey().equals(five) && e.getValue().equals("E"))
                );
            }
        }
    }

    /**
     * putAll adds all key-value pairs from the given map
     */
    @Test(timeout = 50000)
    public void testPutAll() throws IOException {
        int port = s_port++;
        try (@NotNull ClosableMapSupplier<Integer, String> emptySupplier = newIntString()) {
            final Map<Integer, String> empty = emptySupplier.get();
            try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
                final Map map = supplier.get();
                yamlLoggger(() -> empty.putAll(map));
                assertEquals(5, empty.size());
                assertTrue(empty.containsKey(one));
                assertTrue(empty.containsKey(two));
                assertTrue(empty.containsKey(three));
                assertTrue(empty.containsKey(four));
                assertTrue(empty.containsKey(five));
            }
        }
    }

    /**
     * putIfAbsent works when the given key is not present
     */
    @Test(timeout = 50000)
    public void testPutIfAbsent() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
            final Map map = supplier.get();
            yamlLoggger(() -> map.putIfAbsent(six, "Z"));
            assertTrue(map.containsKey(six));
        }
    }

    /**
     * putIfAbsent does not add the pair if the key is already present
     */
    @Test(timeout = 50000)
    public void testPutIfAbsent2() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
            final Map map = supplier.get();
            yamlLoggger(() -> assertEquals("A", map.putIfAbsent(one, "Z")));
        }
    }

    /**
     * replace fails when the given key is not present
     */
    @Test(timeout = 50000)
    public void testReplace() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
            final Map map = supplier.get();
            writeMessage("example of replace where the value is not known");
            yamlLoggger(() -> assertNull(map.replace(six, "Z")));
            assertFalse(map.containsKey(six));
        }
    }

    /**
     * replace succeeds if the key is already present
     */
    @Test(timeout = 50000)
    public void testReplace2() throws
            IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
            final Map map = supplier.get();
            writeMessage("example of replace where the value is known");
            yamlLoggger(() -> assertNotNull(map.replace(one, "Z")));
            assertEquals("Z", map.get(one));
        }
    }

    /**
     * replace value fails when the given key not mapped to expected value
     */
    @Test(timeout = 50000)
    public void testReplaceValue() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
            final Map map = supplier.get();
            assertEquals("A", map.get(one));
            writeMessage("example of when then value was not replaced");
            yamlLoggger(() -> assertFalse(map.replace(one, "Z", "Z")));
            assertEquals("A", map.get(one));
        }
    }

    /**
     * replace value succeeds when the given key mapped to expected value
     */
    @Test(timeout = 50000)
    public void testReplaceValue2() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
            final Map map = supplier.get();
            assertEquals("A", map.get(one));
            writeMessage("example of replace where the value is known");
            yamlLoggger(() -> assertTrue(map.replace(one, "A", "Z")));
            assertEquals("Z", map.get(one));
        }
    }

    /**
     * remove removes the correct key-value pair from the map
     */
    @Test(timeout = 50000)
    public void testRemove() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
            final Map map = supplier.get();
            yamlLoggger(() -> map.remove(five));
            assertEquals(4, map.size());
            assertFalse(map.containsKey(five));
        }
    }

    /**
     * remove(key,value) removes only if pair present
     */
    @Test(timeout = 50000)
    public void testRemove2
    () throws IOException {
   /*     try(   ClosableMapSupplier map = map5(8076)) {
        map.remove(five, "E");
    assertEquals(4, map.size());
        assertFalse(map.containsKey(five));
        map.remove(four, "A");
        assertEquals(4, map.size());
        assertTrue(map.containsKey(four));
   */
    }

    /**
     * size returns the correct values
     */
    @Test(timeout = 50000)
    public void testSize() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
            final Map map = supplier.get();
            try (@NotNull ClosableMapSupplier<Integer, String> supplier0 = newIntString()) {
                final Map empty = supplier0.get();
                writeMessage("size on an empty map");
                yamlLoggger(() -> assertEquals(0, empty.size()));
                writeMessage("size on a map with entries");
                yamlLoggger(() -> assertEquals(5, map.size()));
            }
        }
    }

    /**
     * size returns the correct values
     */
    @Test(timeout = 150000)
    public void testSize2() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
            final Map map = supplier.get();
            try (@NotNull ClosableMapSupplier<Integer, String> supplier0 = newIntString()) {
                final Map empty = supplier0.get();
                assertEquals(0, empty.size());
                assertEquals(5, map.size());
            }
        }
    }

    /**
     * size returns the correct values
     */
    @Test(timeout = 50000)
    public void testSize3() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = map5()) {
            final Map map = supplier.get();
            try (@NotNull ClosableMapSupplier<Integer, String> supplier0 = newIntString()) {
                final Map empty = supplier0.get();
                assertEquals(0, empty.size());
                assertEquals(5, map.size());
            }
        }
    }

    /**
     * get(null) throws NPE
     */
    @Test(timeout = 50000, expected = NullPointerException.class)
    public void testGet_NullPointerException() throws IOException {

        try (@NotNull ClosableMapSupplier<Integer, String> supplier = newIntString()) {
            Map<Integer, String> c = supplier.get();
            writeMessage("get(null) returns a NullPointerException");
            yamlLoggger(() -> c.get(null));
        }
    }

    /**
     * containsKey(null) throws NPE
     */
    @Test(timeout = 50000, expected = NullPointerException.class)
    public void testContainsKey_NullPointerException() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = newIntString()) {
            Map<Integer, String> c = supplier.get();
            writeMessage("c.containsKey(null) will throw a NullPointerException");
            yamlLoggger(() -> c.containsKey(null));
        }
    }

    /**
     * put(null,x) throws NPE
     */
    @Test(timeout = 50000, expected = NullPointerException.class)
    public void testPut1_NullPointerException() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = newIntString()) {
            Map<Integer, String> c = supplier.get();
            writeMessage("put(null) will throw a NullPointerException");
            yamlLoggger(() -> c.put(null, "whatever"));
        }
    }

    /**
     * put(x, null) throws NPE
     */
    @Test(timeout = 50000, expected = NullPointerException.class)
    public void testPut2_NullPointerException() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = newIntString()) {
            Map<Integer, String> c = supplier.get();
            writeMessage("put(notPresent,null) will throw a NullPointerException");
            yamlLoggger(() -> c.put(notPresent, null));
        }
    }

    /**
     * putIfAbsent(null, x) throws NPE
     */
    @Test(timeout = 50000, expected = NullPointerException.class)
    public void testPutIfAbsent1_NullPointerException() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = newIntString()) {
            Map<Integer, String> c = supplier.get();
            writeMessage("put(null, \"whatever\") will throw a NullPointerException");
            yamlLoggger(() -> c.putIfAbsent(null, "whatever"));
        }
    }

    /**
     * replace(null, x) throws NPE
     */
    @Test(timeout = 50000, expected = NullPointerException.class)
    public void testReplace_NullPointerException() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = newIntString()) {
            Map<Integer, String> c = supplier.get();
            c.replace(null, "whatever");
        }
    }

    /**
     * replace(null, x, y) throws NPE
     */
    @Test(timeout = 50000, expected = NullPointerException.class)
    public void testReplaceValue_NullPointerException() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = newIntString()) {
            Map<Integer, String> c = supplier.get();
            c.replace(null, "A", "whatever");
        }
    }

    /**
     * putIfAbsent(x, null) throws NPE
     */
    @Test(timeout = 50000, expected = NullPointerException.class)
    public void testPutIfAbsent2_NullPointerException() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = newIntString()) {
            Map<Integer, String> c = supplier.get();
            c.putIfAbsent(notPresent, null);
        }
    }

    /**
     * replace(x, null) throws NPE
     */
    @Test(timeout = 50000, expected = NullPointerException.class)
    public void testReplace2_NullPointerException() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = newIntString()) {
            Map<Integer, String> c = supplier.get();
            writeMessage("replace(notPresent,null) will throw a NullPointerException");
            yamlLoggger(() -> c.replace(notPresent, null));
        }
    }

    /**
     * replace(x, null, y) throws NPE
     */
    @Test(timeout = 50000, expected = NullPointerException.class)
    public void testReplaceValue2_NullPointerException() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = newIntString()) {
            Map<Integer, String> c = supplier.get();
            c.replace(notPresent, null, "A");
        }
    }

    /**
     * replace(x, y, null) throws NPE
     */
    @Test(timeout = 50000, expected = NullPointerException.class)
    public void testReplaceValue3_NullPointerException() throws IOException {
        try (@NotNull ClosableMapSupplier<Integer, String> supplier = newIntString()) {
            Map<Integer, String> c = supplier.get();
            writeMessage("replace(notPresent, \"A\", null will throw a NullPointerException");
            yamlLoggger(() -> c.replace(notPresent, "A", null));
        }
    }

    /**
     * remove(null) throws NPE
     */
    @Test(timeout = 50000, expected = NullPointerException.class)
    public void testRemove1_NullPointerException() throws IOException {
        try (@NotNull ClosableMapSupplier<CharSequence, CharSequence> supplier = newStrStrMap()) {
            Map<CharSequence, CharSequence> c = supplier.get();
            c.put("sadsdf", "asdads");

            writeMessage("remove(null) will throw a NullPointerException");
            yamlLoggger(() -> c.remove(null));
        }
    }

    /**
     * remove(null, x) throws NPE
     */
    @Test(timeout = 50000, expected = NullPointerException.class)
    public void testRemove2_NullPointerException
    () throws IOException {
        try (@NotNull ClosableMapSupplier<CharSequence, CharSequence> supplier = newStrStrMap()) {
            Map<CharSequence, CharSequence> c = supplier.get();
            c.put("sadsdf", "asdads");
            writeMessage("remove(null,whatever) will throw a NullPointerException");
            yamlLoggger(() -> c.remove(null, "whatever"));
        }
    }

    /**
     * remove(x, null) returns false
     */
    @Test(timeout = 50000, expected = NullPointerException.class)
    public void testRemove3() throws IOException {
        try (@NotNull ClosableMapSupplier<CharSequence, CharSequence> supplier = newStrStrMap()) {
            Map<CharSequence, CharSequence> c = supplier.get();
            c.put("sadsdf", "asdads");
            assertFalse(c.remove("sadsdf", null));
        }
    }

    // classes for testing Comparable fallbacks
    static class BI implements Comparable<BI> {
        private final int value;

        BI(int value) {
            this.value = value;
        }

        public int compareTo(@NotNull BI other) {
            return Integer.compare(value, other.value);
        }

        public boolean equals(Object x) {
            return (x instanceof BI) && ((BI) x).value == value;
        }

        public int hashCode() {
            return 42;
        }
    }

    private static class CI extends BI {
        CI(int value) {
            super(value);
        }
    }

    private static class DI extends BI {
        DI(int value) {
            super(value);
        }
    }

    static class BS implements Comparable<BS> {
        private final String value;

        BS(String value) {
            this.value = value;
        }

        public int compareTo(@NotNull BS other) {
            return value.compareTo(other.value);
        }

        public boolean equals(Object x) {
            return (x instanceof BS) && value.equals(((BS) x).value);
        }

        public int hashCode() {
            return 42;
        }
    }

    static class LexicographicList<E extends Comparable<E>> extends ArrayList<E>
            implements Comparable<LexicographicList<E>> {
        private static final long serialVersionUID = 0;
        static long total;
        static long n;

        LexicographicList(@NotNull Collection<E> c) {
            super(c);
        }

        LexicographicList(E e) {
            super(Collections.singleton(e));
        }

        public int compareTo(@NotNull LexicographicList<E> other) {
            long start = System.currentTimeMillis();
            int common = Math.min(size(), other.size());
            int r = 0;
            for (int i = 0; i < common; i++) {
                if ((r = get(i).compareTo(other.get(i))) != 0)
                    break;
            }
            if (r == 0)
                r = Integer.compare(size(), other.size());
            total += System.currentTimeMillis() - start;
            n++;
            return r;
        }
    }
}

