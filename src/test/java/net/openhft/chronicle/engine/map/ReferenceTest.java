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
import net.openhft.chronicle.core.threads.ThreadDump;
import net.openhft.chronicle.engine.ShutdownHooks;
import net.openhft.chronicle.engine.api.pubsub.Reference;
import net.openhft.chronicle.engine.api.pubsub.Subscriber;
import net.openhft.chronicle.engine.api.pubsub.SubscriptionCollection;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.api.tree.AssetTree;
import net.openhft.chronicle.engine.server.ServerEndpoint;
import net.openhft.chronicle.engine.tree.VanillaAssetTree;
import net.openhft.chronicle.network.TCPRegistry;
import net.openhft.chronicle.network.connection.TcpChannelHub;
import net.openhft.chronicle.wire.WireType;
import net.openhft.chronicle.wire.YamlLogging;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static net.openhft.chronicle.engine.Utils.methodName;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(value = Parameterized.class)
public class ReferenceTest {
    @NotNull
    private static AtomicReference<Throwable> t = new AtomicReference<>();
    @NotNull
    @Rule
    public TestName name = new TestName();
    @Rule
    public ShutdownHooks hooks = new ShutdownHooks();

    @Nullable
    WireType wireType;
    VanillaAssetTree serverAssetTree;
    AssetTree assetTree;
    private boolean isRemote;
    private ServerEndpoint serverEndpoint;
    private String hostPortToken;
    private ThreadDump threadDump;

    public ReferenceTest(boolean isRemote, @Nullable WireType wireType) {
        this.wireType = wireType;
        this.isRemote = isRemote;
        YamlLogging.setAll(false);
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
    public void threadDump() {
        threadDump = new ThreadDump();
    }

    @After
    public void afterMethod() {
        final Throwable th = t.getAndSet(null);
        if (th != null) throw Jvm.rethrow(th);
    }

    @Before
    public void before() throws IOException {
        hostPortToken = "ReferenceTest.host.port";
        serverAssetTree = hooks.addCloseable(new VanillaAssetTree().forTesting());

        if (isRemote) {

            methodName(name.getMethodName());
            TCPRegistry.createServerSocketChannelFor(hostPortToken);
            serverEndpoint = hooks.addCloseable(new ServerEndpoint(hostPortToken, serverAssetTree, "cluster"));

            assetTree = hooks.addCloseable(new VanillaAssetTree()
                    .forRemoteAccess(hostPortToken, wireType));
        } else {
            assetTree = serverAssetTree;
        }
    }

    @After
    public void after() {
        assetTree.close();
        if (serverEndpoint != null)
            serverEndpoint.close();
        serverAssetTree.close();
        TcpChannelHub.closeAllHubs();
        TCPRegistry.reset();

        final Throwable th = t.getAndSet(null);
        if (th != null) throw Jvm.rethrow(th);
        threadDump.assertNoNewThreads();
    }

    @Test
    public void testRemoteReference() throws IOException {
        @NotNull Map map = assetTree.acquireMap("group", String.class, String.class);

        map.put("subject", "cs");
        assertEquals("cs", map.get("subject"));

        @NotNull Reference<String> ref = assetTree.acquireReference("group/subject", String.class);
        ref.set("sport");
        assertEquals("sport", map.get("subject"));
        assertEquals("sport", ref.get());

        ref.getAndSet("biology");
        assertEquals("biology", ref.get());

        @Nullable String s = ref.getAndRemove();
        assertEquals("biology", s);

        ref.set("physics");
        assertEquals("physics", ref.get());

        ref.remove();
        assertEquals(null, ref.get());

        ref.set("chemistry");
        assertEquals("chemistry", ref.get());

        s = ref.applyTo(o -> "applied_" + o.toString());
        assertEquals("applied_chemistry", s);

        ref.asyncUpdate(o -> "**" + o.toString());
        assertEquals("**chemistry", ref.get());

        ref.set("maths");
        assertEquals("maths", ref.get());

        s = ref.syncUpdate(o -> "**" + o.toString(), o -> "**" + o.toString());
        assertEquals("****maths", s);
        assertEquals("**maths", ref.get());
    }

    @Test
    public void testReferenceSubscriptions() throws InterruptedException {
        @NotNull Map map = assetTree.acquireMap("group", String.class, String.class);

        map.put("subject", "cs");
        assertEquals("cs", map.get("subject"));

        @NotNull Reference<String> ref = assetTree.acquireReference("group/subject", String.class);
        ref.set("sport");
        assertEquals("sport", map.get("subject"));
        assertEquals("sport", ref.get());
        @NotNull CountDownLatch lacth1 = new CountDownLatch(1);

        @NotNull CountDownLatch lacth2 = new CountDownLatch(2);
        @NotNull CountDownLatch lacth3 = new CountDownLatch(3);
        @NotNull List<String> events = new ArrayList<>();
        @NotNull Subscriber<String> subscriber = s -> {
            events.add(s);
            lacth1.countDown();
            lacth2.countDown();
            lacth3.countDown();
        };

        assetTree.registerSubscriber("group/subject?bootstrap=true", String.class, subscriber);
        lacth1.await(20, TimeUnit.SECONDS);
        assertEquals("sport", events.get(0));//bootstrap

        ref.set("maths");
        lacth2.await(20, TimeUnit.SECONDS);
        assertEquals("maths", events.get(1));

        ref.set("cs");
        lacth3.await(20, TimeUnit.SECONDS);
        assertEquals("cs", events.get(2));
    }

    @Test
    public void testAssetReferenceSubscriptions() {
        @NotNull Map map = assetTree.acquireMap("group", String.class, String.class);
        //TODO The child has to be in the map before you register to it
        map.put("subject", "init");

        @NotNull List<String> events = new ArrayList<>();

        @NotNull Subscriber<String> keyEventSubscriber = new Subscriber<String>() {
            @Override
            public void onMessage(String s) {
                events.add(s);
            }

            @Override
            public void onEndOfSubscription() {
                events.add("END");
            }
        };

        assetTree.registerSubscriber("group" + "/" + "subject" + "?bootstrap=false&putReturnsNull=true", String.class, keyEventSubscriber);

        // Jvm.pause(100);
        Asset child = assetTree.getAsset("group").getChild("subject");
        assertNotNull(child);
        @Nullable SubscriptionCollection subscription = child.subscription(false);

        while (subscription.subscriberCount() == 0) {

        }

        assertEquals(1, subscription.subscriberCount());

        assetTree.unregisterSubscriber("group" + "/" + "subject", keyEventSubscriber);

        while (subscription.subscriberCount() != 0) {

        }
        assertEquals(0, subscription.subscriberCount());

    }

    @Test
    public void testAssetReferenceSubscriptionsBootstrapTrue() {
        @NotNull Map map = assetTree.acquireMap("group", String.class, String.class);
        //TODO The child has to be in the map before you register to it
        map.put("subject", "init");

        @NotNull List<String> events = new ArrayList<>();

        @NotNull Subscriber<String> keyEventSubscriber = new Subscriber<String>() {
            @Override
            public void onMessage(String s) {
                events.add(s);
            }

            @Override
            public void onEndOfSubscription() {
                events.add("END");
            }
        };

        assetTree.registerSubscriber("group" + "/" + "subject" + "?bootstrap=true&putReturnsNull=true", String.class, keyEventSubscriber);

        Jvm.pause(100);
        Asset child = assetTree.getAsset("group").getChild("subject");
        assertNotNull(child);
        @Nullable SubscriptionCollection subscription = child.subscription(false);

        assertEquals(1, subscription.subscriberCount());

        map.put("subject", "cs");
        map.put("subject", "maths");

        assetTree.unregisterSubscriber("group" + "/" + "subject", keyEventSubscriber);

        Jvm.pause(100);
        assertEquals(0, subscription.subscriberCount());

        assertEquals("init", events.get(0));
        assertEquals("cs", events.get(1));
        assertEquals("maths", events.get(2));
        assertEquals("END", events.get(3));
    }

    @Test
    public void testSubscriptionMUFG() {
        @NotNull String key = "subject";
        @NotNull String _mapName = "group";
        @NotNull Map map = assetTree.acquireMap(_mapName, String.class, String.class);
        //TODO does not work without an initial put
        map.put(key, "init");

        @NotNull List<String> events = new ArrayList<>();
        @NotNull Subscriber<String> keyEventSubscriber = s -> {
            System.out.println("** rec:" + s);
            events.add(s);
        };

        assetTree.registerSubscriber(_mapName + "/" + key + "?bootstrap=false&putReturnsNull=true", String.class, keyEventSubscriber);
        // TODO CHENT-49
        Jvm.pause(100);
        Asset child = assetTree.getAsset(_mapName).getChild(key);
        assertNotNull(child);
        @Nullable SubscriptionCollection subscription = child.subscription(false);
        assertEquals(1, subscription.subscriberCount());

//        YamlLogging.showServerWrites(true);
        //Perform test a number of times to allow the JVM to warm up, but verify runtime against average

        @NotNull AtomicInteger count = new AtomicInteger();
        // IntStream.range(0, 3).forEach(i ->
        // {
        //_testMap.put(key, _twoMbTestString + i);
        map.put(key, "" + count.incrementAndGet());
        map.put(key, "" + count.incrementAndGet());
        map.put(key, "" + count.incrementAndGet());
        // });

        for (int i = 0; i < 100; i++) {
            if (events.size() == 3)
                break;
            Jvm.pause(150);
        }

        assertEquals(3, events.size());
        assetTree.unregisterSubscriber(_mapName + "/" + key, keyEventSubscriber);

        Jvm.pause(100);
        assertEquals(0, subscription.subscriberCount());
    }
}
