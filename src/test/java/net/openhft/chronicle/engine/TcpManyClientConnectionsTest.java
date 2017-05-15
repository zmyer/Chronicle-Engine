/*
 * Copyright 2014 Higher Frequency Trading
 *
 * http://www.higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.engine;

import net.openhft.chronicle.engine.api.tree.AssetTree;
import net.openhft.chronicle.engine.server.ServerEndpoint;
import net.openhft.chronicle.engine.tree.VanillaAssetTree;
import net.openhft.chronicle.network.TCPRegistry;
import net.openhft.chronicle.network.connection.TcpChannelHub;
import net.openhft.chronicle.threads.NamedThreadFactory;
import net.openhft.chronicle.wire.WireType;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * test using the listener both remotely or locally via the engine
 *
 * @author Rob Austin.
 */

public class TcpManyClientConnectionsTest extends ThreadMonitoringTest {

    public static final WireType WIRE_TYPE = WireType.TEXT;
    public static final int MAX = 50;
    private static final String NAME = "test";
    private static final String CONNECTION = "host.port.TcpManyConnectionsTest";
    @NotNull
    private static ConcurrentMap[] maps = new ConcurrentMap[MAX];
    @NotNull
    private AssetTree[] trees = new AssetTree[MAX];
    private VanillaAssetTree serverAssetTree;
    private ServerEndpoint serverEndpoint;

    @Before
    public void before() throws IOException {
        serverAssetTree = new VanillaAssetTree().forTesting();

        TCPRegistry.createServerSocketChannelFor(CONNECTION);

        serverEndpoint = new ServerEndpoint(CONNECTION, serverAssetTree);

        for (int i = 0; i < MAX; i++) {
            trees[i] = new VanillaAssetTree().forRemoteAccess(CONNECTION, WIRE_TYPE);
            maps[i] = trees[i].acquireMap(NAME, String.class, String.class);
        }
    }

    @After
    public void preAfter() {

        shutdownTrees();

        serverAssetTree.close();
        serverEndpoint.close();

        TcpChannelHub.closeAllHubs();
        TCPRegistry.reset();
    }

    private void shutdownTrees() {
        @NotNull ExecutorService c = Executors.newCachedThreadPool(
                new NamedThreadFactory("Tree Closer", true));
        for (int i = 0; i < MAX; i++) {
            final int j = i;
            c.execute(trees[j]::close);
        }

        c.shutdown();
        try {
            c.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * test many clients connecting to a single server
     */
    @Test
    public void test() throws IOException, InterruptedException {

        @NotNull final ExecutorService executorService = Executors.newCachedThreadPool();

        for (int i = 0; i < MAX; i++) {
            final int j = i;
            executorService.execute(() -> {
                maps[j].put("hello" + j, "world" + j);
                Assert.assertEquals("world" + j, maps[j].get("hello" + j));
            });
        }
        executorService.shutdown();
        executorService.awaitTermination(2, TimeUnit.SECONDS);
    }
}

