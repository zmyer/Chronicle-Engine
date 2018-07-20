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

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.annotation.Nullable;
import net.openhft.chronicle.core.onoes.ExceptionKey;
import net.openhft.chronicle.core.threads.ThreadDump;
import net.openhft.chronicle.engine.api.map.MapView;
import net.openhft.chronicle.engine.api.tree.AssetTree;
import net.openhft.chronicle.engine.server.ServerEndpoint;
import net.openhft.chronicle.engine.tree.VanillaAssetTree;
import net.openhft.chronicle.network.TCPRegistry;
import net.openhft.chronicle.network.connection.ClientConnectionMonitor;
import net.openhft.chronicle.network.connection.TcpChannelHub;
import net.openhft.chronicle.wire.WireType;
import org.jetbrains.annotations.NotNull;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentMap;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * test using the listener both remotely or locally via the engine
 *
 * @author Rob Austin.
 */
@RunWith(Parameterized.class)
public class TcpFailoverWithMonitoringTest {

    public static final WireType WIRE_TYPE = WireType.TEXT;
    private static final String NAME = "test";
    private static final String CONNECTION_1 = "Test1.host.port";
    private final static String CONNECTION_2 = "Test2.host.port";
    private static ConcurrentMap<String, String> map;
    private final BlockingQueue<String> activity = new ArrayBlockingQueue<>(2);
    @Rule
    public ShutdownHooks hooks = new ShutdownHooks();
    private ServerSocketChannel connection1;
    private ServerSocketChannel connection2;
    private AssetTree failOverClient;
    private VanillaAssetTree serverAssetTree1;
    private VanillaAssetTree serverAssetTree2;
    private ServerEndpoint serverEndpoint1;
    private ServerEndpoint serverEndpoint2;
    private ThreadDump threadDump;
    private Map<ExceptionKey, Integer> exceptions;

    public TcpFailoverWithMonitoringTest() {
    }

    @Parameterized.Parameters
    public static List<Object[]> data() {
        return Arrays.asList(new Object[10][0]);
    }

    @Before
    public void threadDump() {
        threadDump = new ThreadDump();
    }

    @After
    public void afterMethod() {
        ThreadMonitoringTest.filterExceptions((exceptions));
        if (Jvm.hasException(exceptions)) {
            Jvm.dumpException(exceptions);
            Jvm.resetExceptionHandlers();
            Assert.fail();
        }
    }

    @Before
    public void before() throws IOException {
        exceptions = Jvm.recordExceptions();
        //YamlLogging.setAll(true);
        serverAssetTree1 = hooks.addCloseable(new VanillaAssetTree().forTesting());
        serverAssetTree2 = hooks.addCloseable(new VanillaAssetTree().forTesting());

        TCPRegistry.createServerSocketChannelFor(CONNECTION_1);
        TCPRegistry.createServerSocketChannelFor(CONNECTION_2);

        connection1 = hooks.addCloseable(TCPRegistry.acquireServerSocketChannel(CONNECTION_1));
        connection2 = hooks.addCloseable(TCPRegistry.acquireServerSocketChannel(CONNECTION_2));

        @NotNull final String[] connection = {CONNECTION_1, CONNECTION_2};

        failOverClient = hooks.addCloseable(new VanillaAssetTree("failoverClient").forRemoteAccess(connection,
                WIRE_TYPE, clientConnectionMonitor()));

        map = failOverClient.acquireMap(NAME, String.class, String.class);

        serverEndpoint1 = hooks.addCloseable(new ServerEndpoint(CONNECTION_1, serverAssetTree1, "cluster"));
        serverEndpoint2 = hooks.addCloseable(new ServerEndpoint(CONNECTION_2, serverAssetTree2, "cluster"));
    }

    @NotNull
    private ClientConnectionMonitor clientConnectionMonitor() {
        return new ClientConnectionMonitor() {

            @Override
            public void onConnected(@Nullable String name, @NotNull SocketAddress socketAddress) {
                System.out.println("onConnected - with name=" + name + ", " +
                        "to socketAddress=" +
                        socketAddress.toString());
                activity.add("connected " + socketAddress.toString());
            }

            @Override
            public void onDisconnected(@Nullable String name, @NotNull SocketAddress socketAddress) {
                System.out.println("onDisconnected - with name=" + name + ", " +
                        "to socketAddress=" +
                        socketAddress.toString());
                activity.add("disconnected " + socketAddress.toString());
            }
        };
    }

    @After
    public void after() throws IOException {
        failOverClient.close();

        if (serverEndpoint1 != null)
            serverEndpoint1.close();

        if (serverEndpoint2 != null)
            serverEndpoint2.close();

        serverAssetTree1.close();
        serverAssetTree2.close();

        if (map instanceof Closeable)
            ((Closeable) map).close();

        TcpChannelHub.closeAllHubs();
        TCPRegistry.reset();
        TCPRegistry.assertAllServersStopped();

        threadDump.assertNoNewThreads();
    }

    /**
     * the fail over client connects to  server1 ( server1 is the primary) , server1 is then shut
     * down and the client connects to the secondary
     */
    @Test
    public void test() throws InterruptedException {

        @NotNull final MapView<String, String> failoverClient = failOverClient.acquireMap(NAME,
                String.class,
                String.class);

        @NotNull final MapView<String, String> map1 = serverAssetTree1.acquireMap(NAME,
                String.class, String.class);

        Assert.assertEquals("connected " + toString(connection1), activity.poll(10, SECONDS));

        @NotNull final MapView<String, String> map2 = serverAssetTree2.acquireMap(NAME,
                String.class, String.class);

        map1.put("hello", "server1");
        map2.put("hello", "server2");

        Assert.assertEquals("server1", failoverClient.get("hello"));

        // we are now going to shut down server 1
        serverAssetTree1.close();

        Assert.assertEquals("disconnected " + toString(connection1), activity.poll(4, SECONDS));

        Assert.assertEquals("connected " + toString(connection2), activity.poll(100, SECONDS));

        // shutting server1 down should cause the failover client to connect to server 2
        Assert.assertEquals("server2", failoverClient.get("hello"));

    }

    private SocketAddress toString(@NotNull final ServerSocketChannel connection2) {
        return connection2.socket().getLocalSocketAddress();
    }
}

