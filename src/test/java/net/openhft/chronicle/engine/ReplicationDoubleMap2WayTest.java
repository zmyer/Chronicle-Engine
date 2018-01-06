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

package net.openhft.chronicle.engine;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.onoes.ExceptionKey;
import net.openhft.chronicle.core.pool.ClassAliasPool;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.ThreadDump;
import net.openhft.chronicle.engine.api.EngineReplication;
import net.openhft.chronicle.engine.api.map.KeyValueStore;
import net.openhft.chronicle.engine.api.map.MapView;
import net.openhft.chronicle.engine.api.tree.AssetTree;
import net.openhft.chronicle.engine.fs.ChronicleMapGroupFS;
import net.openhft.chronicle.engine.fs.FilePerKeyGroupFS;
import net.openhft.chronicle.engine.map.CMap2EngineReplicator;
import net.openhft.chronicle.engine.map.ChronicleMapKeyValueStore;
import net.openhft.chronicle.engine.map.VanillaMapView;
import net.openhft.chronicle.engine.server.ServerEndpoint;
import net.openhft.chronicle.engine.tree.VanillaAssetTree;
import net.openhft.chronicle.network.TCPRegistry;
import net.openhft.chronicle.network.connection.TcpChannelHub;
import net.openhft.chronicle.queue.impl.single.StoreComponentReferenceHandler;
import net.openhft.chronicle.wire.WireType;
import net.openhft.chronicle.wire.YamlLogging;
import org.jetbrains.annotations.NotNull;
import org.junit.*;
import org.junit.rules.TestName;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static org.junit.Assert.assertNotNull;

public class ReplicationDoubleMap2WayTest {
    public static final WireType WIRE_TYPE = WireType.TEXT;

    public static ServerEndpoint serverEndpoint1;
    public static ServerEndpoint serverEndpoint2;

    private static AssetTree tree1;
    private static AssetTree tree2;
    private static Map<ExceptionKey, Integer> exceptions;
    @NotNull
    @Rule
    public TestName testName = new TestName();
    @Rule
    public ShutdownHooks hooks = new ShutdownHooks();
    public String name;
    private ThreadDump threadDump;

    @Before
    public void before() throws IOException {
        exceptions = Jvm.recordExceptions();
        exceptions.clear();
        threadDump = new ThreadDump();
        threadDump.ignore("tree-1/Heartbeat");
        threadDump.ignore("tree-2/Heartbeat");
        threadDump.ignore("process reaper");
        threadDump.ignore("tree-1/closer");
        threadDump.ignore(StoreComponentReferenceHandler.THREAD_NAME);
        YamlLogging.setAll(false);

        ClassAliasPool.CLASS_ALIASES.addAlias(ChronicleMapGroupFS.class);
        ClassAliasPool.CLASS_ALIASES.addAlias(FilePerKeyGroupFS.class);
        //Delete any files from the last run

        TCPRegistry.createServerSocketChannelFor(
                "host.port1",
                "host.port2");

        @NotNull WireType writeType = WireType.TEXT;
        tree1 = create(1, writeType, "clusterTwo");
        tree2 = create(2, writeType, "clusterTwo");

        serverEndpoint1 = hooks.addCloseable(new ServerEndpoint("host.port1", tree1, "cluster"));
        serverEndpoint2 = hooks.addCloseable(new ServerEndpoint("host.port2", tree2, "cluster"));
    }

    @After
    public void after() {
        if (Jvm.hasException(exceptions)) {
            Jvm.dumpException(exceptions);
            Jvm.resetExceptionHandlers();
            Assert.fail();
        }

        if (serverEndpoint1 != null)
            serverEndpoint1.close();
        if (serverEndpoint2 != null)
            serverEndpoint2.close();

        try {
            tree1.close();
            tree2.close();

            tree1.root().findView(EventLoop.class).awaitTermination();
            tree2.root().findView(EventLoop.class).awaitTermination();
        } catch (RuntimeException e) {
            System.err.println("Error while closing trees: " + e.getMessage());
        }

        TcpChannelHub.closeAllHubs();
        TCPRegistry.reset();

    }

    @NotNull
    private AssetTree create(final int hostId, WireType writeType, final String clusterTwo) {
        @NotNull AssetTree tree = hooks.addCloseable(new VanillaAssetTree((byte) hostId)
                .forTesting(false)
                .withConfig(resourcesDir() + "/2way", OS.TARGET + "/" + hostId));

        tree.root().addWrappingRule(MapView.class, "map directly to KeyValueStore",
                VanillaMapView::new,
                KeyValueStore.class);
        tree.root().addLeafRule(EngineReplication.class, "Engine replication holder",
                CMap2EngineReplicator::new);
        tree.root().addLeafRule(KeyValueStore.class, "KVS is Chronicle Map", (context, asset) ->
                new ChronicleMapKeyValueStore(context.wireType(writeType).cluster(clusterTwo),
                        asset));

        //  VanillaAssetTreeEgMain.registerTextViewofTree("host " + hostId, tree);

        return tree;
    }

    @NotNull
    public static String resourcesDir() {
        String path = ChronicleMapKeyValueStoreTest.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        if (path == null)
            return ".";
        return new File(path).getParentFile().getParentFile() + "/src/test/resources";
    }

    @Before
    public void beforeTest() throws IOException {
        name = testName.getMethodName();
        Files.deleteIfExists(Paths.get(OS.TARGET, name.toString()));
    }

    //@Ignore
    @Test
    public void testBootstrap() throws InterruptedException {

        //YamlLogging.setAll(true);

        @NotNull final ConcurrentMap<Double, Double> map1 = tree1.acquireMap(name, Double.class, Double
                .class);
        assertNotNull(map1);

        map1.put(1.0, 1.1);

        @NotNull final ConcurrentMap<Double, Double> map2 = tree2.acquireMap(name, Double.class, Double
                .class);
        assertNotNull(map2);

        map2.put(2.0, 2.1);

        for (int i = 1; i <= 50; i++) {
            if (map1.size() == 2 && map2.size() == 2)
                break;
            Jvm.pause(300);
        }

        for (@NotNull Map m : new Map[]{map1, map2}) {
            Assert.assertEquals(1.1, m.get(1.0));
            Assert.assertEquals(2.1, m.get(2.0));
            Assert.assertEquals(2, m.size());
        }
    }
}

