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
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.pool.ClassAliasPool;
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

/**
 * Created by Rob Austin
 */

public class Replication3WayTest extends ThreadMonitoringTest {
    public static final WireType WIRE_TYPE = WireType.TEXT;

    static {
        //System.setProperty("ReplicationHandler3", "true");
    }

    public ServerEndpoint serverEndpoint1;
    public ServerEndpoint serverEndpoint2;
    public ServerEndpoint serverEndpoint3;
    @NotNull
    @Rule
    public TestName testName = new TestName();
    public String name;
    private AssetTree tree1;
    private AssetTree tree2;
    private AssetTree tree3;

    @NotNull
    public static String resourcesDir() {
        String path = ChronicleMapKeyValueStoreTest.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        if (path == null)
            return ".";
        return new File(path).getParentFile().getParentFile() + "/src/test/resources";
    }

    public void before() throws IOException {
        YamlLogging.setAll(false);

        //YamlLogging.showServerWrites = true;

        ClassAliasPool.CLASS_ALIASES.addAlias(ChronicleMapGroupFS.class);
        ClassAliasPool.CLASS_ALIASES.addAlias(FilePerKeyGroupFS.class);
        //Delete any files from the last run

        TCPRegistry.createServerSocketChannelFor(
                "host.port1",
                "host.port2",
                "host.port3");

        @NotNull WireType writeType = WireType.TEXT;
        tree1 = create(1, writeType, "clusterThree");
        tree2 = create(2, writeType, "clusterThree");
        tree3 = create(3, writeType, "clusterThree");

        serverEndpoint1 = new ServerEndpoint("host.port1", tree1);
        serverEndpoint2 = new ServerEndpoint("host.port2", tree2);
        serverEndpoint3 = new ServerEndpoint("host.port3", tree3);
    }

    @Before
    public void threadDump() {
        threadDump = new ThreadDump();
    }

    @After
    public void preAfter() {
  
        Closeable.closeQuietly(tree1);
        Closeable.closeQuietly(tree2);
        Closeable.closeQuietly(tree3);
        Closeable.closeQuietly(serverEndpoint1);
        Closeable.closeQuietly(serverEndpoint2);
        Closeable.closeQuietly(serverEndpoint3);





        TcpChannelHub.closeAllHubs();
        TCPRegistry.reset();

        threadDump.ignore("tree-1/Heartbeat");
        threadDump.ignore("tree-2/Heartbeat");
        threadDump.ignore("tree-3/Heartbeat");
        threadDump.ignore("main/ChronicleMapKeyValueStore Closer");
    }

    @NotNull
    private AssetTree create(final int hostId, WireType writeType, final String clusterTwo) {
        @NotNull AssetTree tree = new VanillaAssetTree((byte) hostId)
                .forTesting()
                .withConfig(resourcesDir() + "/3way", OS.TARGET + "/" + hostId);

        tree.root().addWrappingRule(MapView.class, "map directly to KeyValueStore",
                VanillaMapView::new,
                KeyValueStore.class);
        tree.root().addLeafRule(EngineReplication.class, "Engine replication holder",
                CMap2EngineReplicator::new);
        tree.root().addLeafRule(KeyValueStore.class, "KVS is Chronicle Map", (context, asset) ->
                new ChronicleMapKeyValueStore(context.wireType(writeType).cluster(clusterTwo),
                        asset));

        return tree;
    }

    @Before
    public void beforeTest() throws IOException {
        before();
        name = testName.getMethodName();

        Files.deleteIfExists(Paths.get(OS.TARGET, name.toString()));
    }

    @Test
    public void testThreeWay() throws InterruptedException {

        @NotNull final ConcurrentMap<String, String> map1 = tree1.acquireMap(name, String.class, String
                .class);
        assertNotNull(map1);

        map1.put("hello1", "world1");

        @NotNull final ConcurrentMap<String, String> map2 = tree2.acquireMap(name, String.class, String
                .class);
        assertNotNull(map2);

        map2.put("hello2", "world2");

        @NotNull final ConcurrentMap<String, String> map3 = tree3.acquireMap(name, String.class, String
                .class);
        assertNotNull(map3);

        map3.put("hello3", "world3");

        for (int i = 1; i <= 50; i++) {
            if (map1.size() == 3 && map2.size() == 3 && map3.size() == 3)
                break;
            Jvm.pause(300);
        }

        for (@NotNull Map m : new Map[]{map1, map2}) {
            Assert.assertEquals("world1", m.get("hello1"));
            Assert.assertEquals("world2", m.get("hello2"));
            Assert.assertEquals("world3", m.get("hello3"));
            Assert.assertEquals(3, m.size());
        }
    }
}

