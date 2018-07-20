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

package net.openhft.chronicle.engine.api.management;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.ThreadDump;
import net.openhft.chronicle.engine.api.tree.AssetTree;
import net.openhft.chronicle.engine.tree.VanillaAssetTree;
import net.openhft.chronicle.wire.Marshallable;
import net.openhft.chronicle.wire.TextWire;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import static net.openhft.chronicle.bytes.NativeBytes.nativeBytes;
import static org.junit.Assert.assertTrue;

/*
 * Created by pct25 on 6/18/2015.
 */
@Ignore("Long running test")
public class AssetTreeJMXTest {

    @NotNull
    private static AtomicReference<Throwable> t = new AtomicReference();
    private ThreadDump threadDump;

    @Before
    public void threadDump() {
        threadDump = new ThreadDump();
    }

    @After
    public void checkThreadDump() {
        threadDump.assertNoNewThreads();
    }

    @Test
    public void testAssetUpdateEvery10Sec() throws InterruptedException {
        testAssetUpdate(10000);
    }

    @Test
    public void testAssetUpdateEvery1Sec() throws InterruptedException {
        testAssetUpdate(1000);
    }

    @Test
    public void testAssetUpdateEvery100MilliSec() throws InterruptedException {
        testAssetUpdate(100);
    }

    @Ignore(value = "javax.management.InstanceNotFoundException")
    @Test
    public void testAssetUpdateEvery10MilliSec() throws InterruptedException {
        testAssetUpdate(10);
    }

    @Test
    public void add1ThousandMapIntoTree() throws InterruptedException {
        addMapIntoTree(1000);
    }

    @Test
    public void add1LakhMapIntoTree() throws InterruptedException {
        addMapIntoTree(100000);
    }

    @Ignore("java.lang.OutOfMemoryError: GC overhead limit exceeded")
    @Test
    public void add1MillionMapIntoTree() throws InterruptedException {
        addMapIntoTree(1000000);
    }

    @After
    public void afterMethod() {
        final Throwable th = t.getAndSet(null);
        if (th != null) throw Jvm.rethrow(th);
    }

    @Test
    public void addStringValuesMapIntoTree() {
        @NotNull AssetTree tree = new VanillaAssetTree().forTesting();
        tree.enableManagement();
        @NotNull ConcurrentMap<String, String> map = tree.acquireMap("group/map", String.class, String.class);
        map.put("key1", "value1");
        map.put("key2", "value2");
        map.put("key3", "value3");
        map.put("key4", "ABCDEGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyz0132456789ABCDEGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyz0132456789");
        Jvm.pause(100);
    }

    @Test
    public void addDoubleValuesMapIntoTree() {
        @NotNull AssetTree tree = new VanillaAssetTree().forTesting();
        tree.enableManagement();
        @NotNull ConcurrentMap<Double, Double> map = tree.acquireMap("group/map", Double.class, Double.class);
        map.put(1.1, 1.1);
        map.put(1.01, 1.01);
        map.put(1.001, 1.001);
        map.put(1.0001, 1.0001);
        Jvm.pause(200);
    }

    @Test
    public void addIntegerValuesMapIntoTree() {
        @NotNull AssetTree tree = new VanillaAssetTree().forTesting();
        tree.enableManagement();
        @NotNull ConcurrentMap<Integer, Integer> map = tree.acquireMap("group/map", Integer.class, Integer.class);
        map.put(1, 1);
        map.put(2, 2);
        map.put(3, 3);
        map.put(4, 4);
        Jvm.pause(200);
    }

    @Ignore("todo add assertions")
    @Test
    public void addMarshallableValuesMapIntoTree() {
        @NotNull AssetTree tree = new VanillaAssetTree().forTesting();
        tree.enableManagement();

        @NotNull Marshallable m = new MyTypes();

        @NotNull Bytes bytes = nativeBytes();
        assertTrue(bytes.isElastic());
        @NotNull TextWire wire = new TextWire(bytes);
        m.writeMarshallable(wire);
        m.readMarshallable(wire);

        @NotNull ConcurrentMap<String, Marshallable> map = tree.acquireMap("group/map", String.class, Marshallable.class);
        map.put("1", m);
        map.put("2", m);
        map.put("3", m);
        Jvm.pause(200);
    }

    /**
     * Provide the test case for add numbers of map into AssetTree
     *
     * @param number the numbers of map to add into AssetTree
     */
    private void addMapIntoTree(int number) {

        @NotNull AssetTree tree = new VanillaAssetTree().forTesting();
        tree.enableManagement();

        for (int i = 1; i <= number; i++) {
            @NotNull ConcurrentMap<String, String> map1 = tree.acquireMap("group/map" + i, String.class, String.class);
            map1.put("key1", "value1");
        }
        Jvm.pause(200);
    }

    /**
     * Provide the test case for add and remove numbers of map into AssetTree with time interval of milliseconds
     * It will add and remove map for 1 hour.
     *
     * @param milliSeconds the interval in milliSeconds to add and remove map into AssetTree
     */
    private void testAssetUpdate(long milliSeconds) {

        @NotNull AssetTree tree = new VanillaAssetTree().forTesting();
        tree.enableManagement();

        long timeToStop = System.currentTimeMillis() + 3600000;  //3600000 = 60*60*1000 milliseconds = 1 Hour
        int count = 0;
        while (System.currentTimeMillis() <= timeToStop) {
            @NotNull ConcurrentMap<String, String> map1 = tree.acquireMap("group/map" + count, String.class, String.class);
            map1.put("key1", "value1");
            Jvm.pause(milliSeconds);
            tree.root().getAsset("group").removeChild("map" + count);
            count++;
        }
    }
}
