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

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.engine.ShutdownHooks;
import net.openhft.chronicle.engine.ThreadMonitoringTest;
import net.openhft.chronicle.engine.api.map.MapView;
import net.openhft.chronicle.engine.api.tree.AssetTree;
import net.openhft.chronicle.engine.server.ServerEndpoint;
import net.openhft.chronicle.engine.tree.VanillaAssetTree;
import net.openhft.chronicle.network.TCPRegistry;
import net.openhft.chronicle.network.connection.TcpChannelHub;
import net.openhft.chronicle.wire.WireType;
import org.jetbrains.annotations.NotNull;
import org.junit.*;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

import static net.openhft.chronicle.engine.Utils.methodName;

/**
 * @author Rob Austin.
 */

/**
 * test using the listener both remotely or locally via the engine
 *
 * @author Rob Austin.
 */
@RunWith(value = Parameterized.class)
public class ValuesViewTest extends ThreadMonitoringTest {
    private static final String NAME = "test";
    private static MapView<String, String> map;
    private final Boolean isRemote;
    private final WireType wireType;
    @NotNull
    public String connection = "ValuesViewTest.host.port";
    @NotNull
    @Rule
    public TestName name = new TestName();
    @Rule
    public ShutdownHooks hooks = new ShutdownHooks();
    private AssetTree assetTree = hooks.addCloseable(new VanillaAssetTree().forTesting());
    private VanillaAssetTree serverAssetTree;
    private ServerEndpoint serverEndpoint;
    public ValuesViewTest(boolean isRemote, WireType wireType) {
        this.isRemote = isRemote;
        this.wireType = wireType;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(
                new Object[]{false, null}
                , new Object[]{true, WireType.TEXT}
                , new Object[]{true, WireType.BINARY}
        );
    }

    @Before
    public void before() throws IOException {
        serverAssetTree = hooks.addCloseable(new VanillaAssetTree().forTesting());

        if (isRemote) {

            methodName(name.getMethodName());
            connection = "ValuesViewTest." + name.getMethodName() + ".host.port";
            TCPRegistry.createServerSocketChannelFor(connection);
            serverEndpoint = hooks.addCloseable(new ServerEndpoint(connection, serverAssetTree, "cluster"));
            assetTree = hooks.addCloseable(new VanillaAssetTree().forRemoteAccess(connection, wireType));
        } else {
            assetTree = serverAssetTree;
        }

        map = assetTree.acquireMap(NAME, String.class, String.class);
    }

    @Override
    @After
    public void preAfter() {
        assetTree.close();
        Jvm.pause(100);
        if (serverEndpoint != null)
            serverEndpoint.close();
        serverAssetTree.close();
        net.openhft.chronicle.core.io.Closeable.closeQuietly(map);
        TcpChannelHub.closeAllHubs();
        TCPRegistry.reset();
    }

    @Test
    public void testValues() {

        @NotNull final MapView<String, String> map = assetTree.acquireMap("name", String.class, String
                .class);
        map.put("1", "1");
        map.put("2", "2");
        map.put("3", "2");
        @NotNull final Collection<String> values = map.values();

        @NotNull final ArrayList<String> result = new ArrayList<>(values);
        result.sort(String::compareTo);
        Assert.assertEquals(Arrays.asList("1", "2", "2"), result);

    }
}

