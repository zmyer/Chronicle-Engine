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

import net.openhft.chronicle.engine.ThreadMonitoringTest;
import net.openhft.chronicle.engine.api.map.MapView;
import net.openhft.chronicle.engine.api.tree.AssetTree;
import net.openhft.chronicle.engine.api.tree.Assetted;
import net.openhft.chronicle.engine.map.remote.RemoteKeyValueStore;
import net.openhft.chronicle.engine.map.remote.RemoteMapView;
import net.openhft.chronicle.engine.server.ServerEndpoint;
import net.openhft.chronicle.engine.tree.VanillaAssetTree;
import net.openhft.chronicle.network.TCPRegistry;
import net.openhft.chronicle.network.connection.TcpChannelHub;
import net.openhft.chronicle.wire.WireType;
import net.openhft.chronicle.wire.YamlLogging;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static net.openhft.chronicle.core.io.Closeable.closeQuietly;
import static net.openhft.chronicle.engine.Utils.yamlLoggger;
import static org.junit.Assert.*;

/**
 * test using the listener both remotely or locally via the engine
 *
 * @author Rob Austin.
 */
@RunWith(value = Parameterized.class)
public class MapClientTest extends ThreadMonitoringTest {
    public static final WireType WIRE_TYPE = WireType.TEXT;
    private static int i;

    // server has it's own asset tree, to the client.
    @NotNull
    private final VanillaAssetTree assetTree = new VanillaAssetTree();
    @Nullable
    private Class<? extends CloseableSupplier> supplier = null;

    public MapClientTest(Class<? extends CloseableSupplier> supplier) {
        this.supplier = supplier;
    }

    @Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Class[][]{
                {LocalMapSupplier.class},
                {RemoteMapSupplier.class}
        });
    }

    @Before
    public void clearState() {

        if (supplier == LocalMapSupplier.class)
            assetTree.forTesting();

        TcpChannelHub.closeAllHubs();
        TCPRegistry.reset();
        YamlLogging.setAll(false);
    }

    @Test(timeout = 50000)
    public void testPutAndGet() throws IOException, InterruptedException {
        yamlLoggger(() -> {
            try {
                supplyMap(Integer.class, String.class, mapProxy -> {

                    mapProxy.put(1, "hello");
                    assertEquals("hello", mapProxy.get(1));
                    assertEquals(1, mapProxy.size());

                    assertEquals("{1=hello}", mapProxy.toString());

                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    /*    @Test(timeout = 50000)
        public void testSubscriptionTest() throws IOException, InterruptedException {
            yamlLoggger(() -> {
                try {
                    supplyMap(Integer.class, String.class, map -> {
                        try {
                            supplyMapEventListener(Integer.class, String.class, mapEventListener -> {
                                Chassis.registerSubscriber("test", MapEvent.class, e -> e.apply(mapEventListener));

                                map.put(i, "one");

                            });

                        } catch (IOException e) {
                            Jvm.rethrow(e);
                        }
                    });

                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }*/
    @Test(timeout = 50000)
    public void testEntrySetIsEmpty() throws IOException, InterruptedException {

        supplyMap(Integer.class, String.class, mapProxy -> {
            long size = mapProxy.longSize();
            boolean empty = mapProxy.isEmpty();
            assertTrue(empty);
            assertEquals(0, size);
        });
    }

    @Test
    public void testPutAll() throws IOException, InterruptedException {

        supplyMap(Integer.class, String.class, mapProxy -> yamlLoggger(() -> {
            @NotNull final Set<Entry<Integer, String>> entries = mapProxy.entrySet();

            assertEquals(0, entries.size());
            assertEquals(true, entries.isEmpty());

            @NotNull Map<Integer, String> data = new HashMap<>();
            data.put(1, "hello");
            data.put(2, "world");
            mapProxy.putAll(data);

            @NotNull final Set<Entry<Integer, String>> e = mapProxy.entrySet();
            @NotNull final Iterator<Entry<Integer, String>> iterator = e.iterator();
            Entry<Integer, String> entry = iterator.next();

            if (entry.getKey() == 1) {
                assertEquals("hello", entry.getValue());
                entry = iterator.next();
                assertEquals("world", entry.getValue());

            } else if (entry.getKey() == 2) {
                assertEquals("world", entry.getValue());
                entry = iterator.next();
                assertEquals("hello", entry.getValue());
            }

            assertEquals(2, mapProxy.size());
        }));
    }

    @Test
    public void testMapsAsValues() throws IOException, InterruptedException {

        supplyMap(Integer.class, Map.class, map -> {

            {
                @NotNull final Map value = new HashMap<String, String>();
                value.put("k1", "v1");
                value.put("k2", "v2");

                map.put(1, value);
            }

            {
                @NotNull final Map value = new HashMap<String, String>();
                value.put("k3", "v3");
                value.put("k4", "v4");

                map.put(2, value);
            }

            assertEquals("v1", map.get(1).get("k1"));
            assertEquals("v2", map.get(1).get("k2"));

            assertEquals(null, map.get(1).get("k3"));
            assertEquals(null, map.get(1).get("k4"));

            assertEquals("v3", map.get(2).get("k3"));
            assertEquals("v4", map.get(2).get("k4"));

            assertEquals(2, map.size());
        });
    }

    @Test
    public void testValuesCollection() throws IOException, InterruptedException {
        @NotNull HashMap<String, String> data = new HashMap<>();
        data.put("test1", "value1");
        data.put("test1", "value1");
        supplyMap(String.class, String.class, mapProxy -> {
            mapProxy.putAll(data);
            assertEquals(data.size(), mapProxy.size());
            assertEquals(data.size(), mapProxy.values().size());

            @NotNull Iterator<String> it = mapProxy.values().iterator();
            @NotNull ArrayList<String> values = new ArrayList<>();
            while (it.hasNext()) {
                values.add(it.next());
            }
            Collections.sort(values);
            @NotNull Object[] dataValues = data.values().toArray();
            Arrays.sort(dataValues);
            assertArrayEquals(dataValues, values.toArray());
        });
    }

    @Test
    public void testDoubleValues() throws IOException, InterruptedException {

        supplyMap(Double.class, Double.class, mapProxy -> {

            mapProxy.put(1.0, 1.0);
            mapProxy.put(2.0, 2.0);
            mapProxy.put(3.0, 0.0);
            assertEquals(1.0, mapProxy.get(1.0), 0);
            assertEquals(2.0, mapProxy.get(2.0), 0);
            assertEquals(0.0, mapProxy.get(3.0), 0);

            assertEquals(3, mapProxy.size());
        });
    }

    @Test
    public void testFloatValues() throws IOException, InterruptedException {

        supplyMap(Float.class, Float.class, mapProxy -> {

            mapProxy.put(1.0f, 1.0f);
            mapProxy.put(2.0f, 2.0f);
            mapProxy.put(3.0f, 0.0f);
            assertEquals(1.0f, mapProxy.get(1.0f), 0);
            assertEquals(2.0f, mapProxy.get(2.0f), 0);
            assertEquals(0.0f, mapProxy.get(3.0f), 0);

            assertEquals(3, mapProxy.size());
        });
    }

    @org.junit.Ignore("Will be very slow, of course")
    @Test
    public void testLargeUpdates() throws IOException, InterruptedException {
        String val = new String(new char[1024 * 1024]).replace("\0", "X");
        supplyMap(String.class, String.class, mapProxy -> {
            for (int j = 0; j < 30 * 1000; j++) {
                mapProxy.put("key", val);
            }
        });
    }

    @Test
    public void testStringString() throws IOException, InterruptedException {

        supplyMap(String.class, String.class, mapProxy -> {
            mapProxy.put("hello", "world");
            assertEquals("world", mapProxy.get("hello"));
            assertEquals(1, mapProxy.size());
        });
    }

    @Test
    public void testApplyTo() throws IOException, InterruptedException {

        supplyMap(String.class, String.class, mapProxy -> {
            mapProxy.asyncUpdate(m -> m.put("hello", "world"));
            assertEquals("world", mapProxy.applyTo(m -> m.get("hello")));
            assertEquals("world2", mapProxy.syncUpdate(m -> m.put("hello", "world2"), m -> m.get("hello")));
            assertEquals(1, mapProxy.size());
        });
    }

    @Test
    public void testToString() throws IOException, InterruptedException {

        supplyMap(Integer.class, String.class, mapProxy -> {

            mapProxy.put(1, "Hello");

            assertEquals("Hello", mapProxy.get(1));
            assertEquals("{1=Hello}", mapProxy.toString());
            mapProxy.remove(1);

            mapProxy.put(2, "World");
            assertEquals("{2=World}", mapProxy.toString());
        });
    }

    /**
     * supplies a listener and closes it once the tests are finished
     */
    private <K, V>
    void supplyMap(@NotNull Class<K> kClass, @NotNull Class<V> vClass, @NotNull Consumer<MapView<K, V>> c)
            throws IOException {

        CloseableSupplier<MapView<K, V>> result;
        if (LocalMapSupplier.class.equals(supplier)) {
            result = new LocalMapSupplier<>(kClass, vClass, assetTree);

        } else if (RemoteMapSupplier.class.equals(supplier)) {
            result = new RemoteMapSupplier<>("test", kClass, vClass, WireType.TEXT, assetTree, "test");
            assertTrue(result.get() instanceof RemoteMapView);

        } else {
            throw new IllegalStateException("unsuported type");
        }

        try {
            c.accept(result.get());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            result.close();
        }
    }

    private interface CloseableSupplier<X> extends Closeable, Supplier<X> {
    }

    public static class RemoteMapSupplier<K, V> implements CloseableSupplier<MapView<K, V>> {

        @NotNull
        private final ServerEndpoint serverEndpoint;
        @NotNull
        private final MapView<K, V> map;
        @NotNull
        private final AssetTree serverAssetTree;
        @NotNull
        private final AssetTree clientAssetTree;

        public RemoteMapSupplier(
                @NotNull String hostPortDescription,
                @NotNull final Class<K> kClass,
                @NotNull final Class<V> vClass,
                @NotNull final WireType wireType,
                @NotNull final AssetTree clientAssetTree,
                @NotNull final String name) throws IOException {
            this.clientAssetTree = clientAssetTree;
            this.serverAssetTree = new VanillaAssetTree().forTesting(false);
            TCPRegistry.createServerSocketChannelFor(hostPortDescription);
            serverEndpoint = new ServerEndpoint(hostPortDescription, serverAssetTree);
            ((VanillaAssetTree) clientAssetTree).forRemoteAccess(hostPortDescription, wireType);

            map = clientAssetTree.acquireMap(name, kClass, vClass);

            if (!(((Assetted) map).underlying() instanceof RemoteKeyValueStore)) {
                throw new IllegalStateException();
            }
        }

        @Override
        public void close() throws IOException {
            closeQuietly(map);

            clientAssetTree.close();
            serverEndpoint.close();
            serverAssetTree.close();
            TcpChannelHub.closeAllHubs();
            TCPRegistry.reset();
        }

        @NotNull
        @Override
        public MapView<K, V> get() {
            return map;
        }
    }

    public static class LocalMapSupplier<K, V> implements CloseableSupplier<MapView<K, V>> {

        @NotNull
        private final MapView<K, V> map;

        public LocalMapSupplier(Class<K> kClass, Class<V> vClass, @NotNull AssetTree assetTree) {
            map = assetTree.acquireMap("test" + i++ + "?putReturnsNull=false&removeReturnsNull=false", kClass, vClass);
        }

        @Override
        public void close() throws IOException {
            if (map instanceof Closeable)
                ((Closeable) map).close();
        }

        @NotNull
        @Override
        public MapView<K, V> get() {
            return map;
        }
    }
}

