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

package net.openhft.chronicle.engine.mit;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.threads.ThreadDump;
import net.openhft.chronicle.engine.api.map.KeyValueStore;
import net.openhft.chronicle.engine.api.map.MapEvent;
import net.openhft.chronicle.engine.api.map.MapEventListener;
import net.openhft.chronicle.engine.api.map.MapView;
import net.openhft.chronicle.engine.api.pubsub.InvalidSubscriberException;
import net.openhft.chronicle.engine.api.pubsub.Subscriber;
import net.openhft.chronicle.engine.api.pubsub.SubscriptionCollection;
import net.openhft.chronicle.engine.api.pubsub.TopicSubscriber;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.map.ChronicleMapKeyValueStore;
import net.openhft.chronicle.engine.map.KVSSubscription;
import net.openhft.chronicle.engine.map.VanillaMapView;
import net.openhft.chronicle.engine.server.ServerEndpoint;
import net.openhft.chronicle.engine.tree.VanillaAssetTree;
import net.openhft.chronicle.network.TCPRegistry;
import net.openhft.chronicle.network.connection.TcpChannelHub;
import net.openhft.chronicle.wire.WireType;
import net.openhft.chronicle.wire.YamlLogging;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.IntStream;

@Ignore("Long running test")
public class RemoteSubscriptionModelPerformanceTest {

    //TODO DS test having the server side on another machine
    private static final int _noOfPuts = 50;
    private static final int _noOfRunsToAverage = Boolean.parseBoolean(System.getProperty("quick", "true")) ? 2 : 100;
    private static final long _secondInNanos = 1_000_000_000L;
    private static final AtomicInteger counter = new AtomicInteger();
    private static String _twoMbTestString;
    private static int _twoMbTestStringLength;
    private static Map<String, String> _testMap;
    private static VanillaAssetTree serverAssetTree, clientAssetTree;
    private static ServerEndpoint serverEndpoint;
    @NotNull
    private static AtomicReference<Throwable> t = new AtomicReference();
    private final String _mapName = "PerfTestMap" + counter.incrementAndGet();
    private ThreadDump threadDump;

    @BeforeClass
    public static void setUpBeforeClass() throws IOException {
        YamlLogging.setAll(false);

        @NotNull char[] chars = new char[2 << 20];
        Arrays.fill(chars, '~');
        _twoMbTestString = new String(chars);
        _twoMbTestStringLength = _twoMbTestString.length();

        serverAssetTree = new VanillaAssetTree(1).forTesting();
        //The following line doesn't add anything and breaks subscriptions
        serverAssetTree.root().addWrappingRule(MapView.class, "map directly to KeyValueStore", VanillaMapView::new, KeyValueStore.class);
        serverAssetTree.root().addLeafRule(KeyValueStore.class, "use Chronicle Map", (context, asset) ->
                new ChronicleMapKeyValueStore(context.basePath(OS.TARGET).entries(_noOfPuts).averageValueSize(_twoMbTestStringLength), asset));
        TCPRegistry.createServerSocketChannelFor("RemoteSubscriptionModelPerformanceTest.port");
        serverEndpoint = new ServerEndpoint("RemoteSubscriptionModelPerformanceTest.port", serverAssetTree);

        clientAssetTree = new VanillaAssetTree(13).forRemoteAccess("RemoteSubscriptionModelPerformanceTest.port", WireType.BINARY);

    }

    @AfterClass
    public static void tearDownAfterClass() {
        clientAssetTree.close();
        serverEndpoint.close();
        serverAssetTree.close();
        TcpChannelHub.closeAllHubs();
        TCPRegistry.reset();
    }

    @After
    public void afterMethod() {
        final Throwable th = t.getAndSet(null);
        if (th != null) throw Jvm.rethrow(th);
    }

    @Before
    public void threadDump() {
        threadDump = new ThreadDump();
    }

    @After
    public void checkThreadDump() {
        threadDump.assertNoNewThreads();
    }

    @Before
    public void setUp() throws IOException {
        Files.deleteIfExists(Paths.get(OS.TARGET, _mapName));

        _testMap = clientAssetTree.acquireMap(_mapName + "?putReturnsNull=true", String.class, String.class);

        _testMap.clear();
    }

    @After
    public void tearDown() throws IOException {
//        System.out.println("Native memory used "+OS.memory().nativeMemoryUsed());
//        System.gc();

    }

    /**
     * Test that listening to events for a given key can handle 50 updates per second of 2 MB string values.
     */
    @Test
    public void testGetPerformance() {
        _testMap.clear();

        IntStream.range(0, _noOfPuts).forEach(i ->
                _testMap.put(TestUtils.getKey(_mapName, i), _twoMbTestString));

        //Perform test a number of times to allow the JVM to warm up, but verify runtime against average
        TestUtils.runMultipleTimesAndVerifyAvgRuntime(i -> _testMap.size(), () -> {
            IntStream.range(0, _noOfPuts).forEach(i ->
                    _testMap.get(TestUtils.getKey(_mapName, i)));
        }, _noOfRunsToAverage, _secondInNanos * 3 / 2);
    }

    /**
     * Test that 50 updates per second of 2 MB string values completes in 1 second.
     */
    @Test
    public void testPutPerformance() {
        _testMap.clear();

        //Perform test a number of times to allow the JVM to warm up, but verify runtime against average
        TestUtils.runMultipleTimesAndVerifyAvgRuntime(i -> _testMap.size(), () -> {
            IntStream.range(0, _noOfPuts).forEach(i ->
                    _testMap.put(TestUtils.getKey(_mapName, i), _twoMbTestString));
        }, _noOfRunsToAverage, _secondInNanos);
    }

    /**
     * Test that listening to events for a given key can handle 50 updates per second of 2 MB string
     * values.
     */
    @Test
    public void testSubscriptionMapEventOnKeyPerformance() {
        _testMap.clear();

        String key = TestUtils.getKey(_mapName, 0);

        //Create subscriber and register
        //Add 4 for the number of puts that is added to the string
        @NotNull TestChronicleKeyEventSubscriber keyEventSubscriber = new TestChronicleKeyEventSubscriber(_twoMbTestStringLength);

        clientAssetTree.registerSubscriber(_mapName + "/" + key + "?bootstrap=false", String.class, keyEventSubscriber);
        Jvm.pause(100);
        Asset child = serverAssetTree.getAsset(_mapName).getChild(key);
        Assert.assertNotNull(child);
        @Nullable SubscriptionCollection subscription = child.subscription(false);
        Assert.assertEquals(1, subscription.subscriberCount());

        long start = System.nanoTime();
        //Perform test a number of times to allow the JVM to warm up, but verify runtime against average
        TestUtils.runMultipleTimesAndVerifyAvgRuntime(() -> {
            IntStream.range(0, _noOfPuts).forEach(i ->
            {
                _testMap.put(key, _twoMbTestString);
            });
        }, _noOfRunsToAverage, 3 * _secondInNanos);

        waitFor(() -> keyEventSubscriber.getNoOfEvents().get() >= _noOfPuts * _noOfRunsToAverage);
        long time = System.nanoTime() - start;
        System.out.printf("Took %.3f seconds to receive all events%n", time / 1e9);

        //Test that the correct number of events was triggered on event listener
        Assert.assertEquals(_noOfPuts * _noOfRunsToAverage, keyEventSubscriber.getNoOfEvents().get());

        clientAssetTree.unregisterSubscriber(_mapName + "/" + key, keyEventSubscriber);

        Jvm.pause(100);
        Assert.assertEquals(0, subscription.subscriberCount());
    }

    /**
     * Test that listening to events for a given map can handle 50 updates per second of 2 MB string
     * values and are triggering events which contain both the key and value (topic).
     */
    @Test
    public void testSubscriptionMapEventOnTopicPerformance() {
        _testMap.clear();

        String key = TestUtils.getKey(_mapName, 0);

        //Create subscriber and register
        @NotNull TestChronicleTopicSubscriber topicSubscriber = new TestChronicleTopicSubscriber(key, _twoMbTestStringLength);

        clientAssetTree.registerTopicSubscriber(_mapName, String.class, String.class, topicSubscriber);

        Jvm.pause(100);
        @NotNull KVSSubscription subscription = (KVSSubscription) serverAssetTree.getAsset(_mapName).subscription(false);
        Assert.assertEquals(1, subscription.topicSubscriberCount());

        //Perform test a number of times to allow the JVM to warm up, but verify runtime against average
        TestUtils.runMultipleTimesAndVerifyAvgRuntime(i -> {
                    System.out.println("test");
                    int events = _noOfPuts * i;
                    waitFor(() -> events == topicSubscriber.getNoOfEvents().get());
                    Assert.assertEquals(events, topicSubscriber.getNoOfEvents().get());
                }, () -> {
                    IntStream.range(0, _noOfPuts).forEach(i ->
                    {
                        _testMap.put(key, _twoMbTestString);
                    });
                }, _noOfRunsToAverage, 3 * _secondInNanos
        );

        //Test that the correct number of events was triggered on event listener
        int events = _noOfPuts * _noOfRunsToAverage;
        waitFor(() -> events == topicSubscriber.getNoOfEvents().get());
        Assert.assertEquals(events, topicSubscriber.getNoOfEvents().get());

        clientAssetTree.unregisterTopicSubscriber(_mapName, topicSubscriber);
        waitFor(() -> 0 == subscription.topicSubscriberCount());
        Assert.assertEquals(0, subscription.topicSubscriberCount());
    }

    /**
     * Tests the performance of an event listener on the map for Insert events of 2 MB strings.
     * Expect it to handle at least 50 2 MB updates per second.
     */
    @Test
    public void testSubscriptionMapEventListenerInsertPerformance() {
        _testMap.clear();

        YamlLogging.setAll(false);
        //Create subscriber and register
        @NotNull TestChronicleMapEventListener mapEventListener = new TestChronicleMapEventListener(_mapName, _twoMbTestStringLength);

        @NotNull Subscriber<MapEvent> mapEventSubscriber = e -> e.apply(mapEventListener);
        clientAssetTree.registerSubscriber(_mapName, MapEvent.class, mapEventSubscriber);

        Jvm.pause(100);
        @Nullable KVSSubscription subscription = (KVSSubscription) serverAssetTree.getAsset(_mapName).subscription(false);
        Assert.assertEquals(1, subscription.entrySubscriberCount());

        //Perform test a number of times to allow the JVM to warm up, but verify runtime against average
        TestUtils.runMultipleTimesAndVerifyAvgRuntime(i -> {
                    if (i > 0) {
                        waitFor(() -> mapEventListener.getNoOfInsertEvents().get() >= _noOfPuts);
                        Assert.assertEquals(_noOfPuts, mapEventListener.getNoOfInsertEvents().get());
                    }
                    //Test that the correct number of events were triggered on event listener
                    Assert.assertEquals(0, mapEventListener.getNoOfRemoveEvents().get());
                    Assert.assertEquals(0, mapEventListener.getNoOfUpdateEvents().get());

                    _testMap.clear();

                    mapEventListener.resetCounters();
                }, () -> {
                    IntStream.range(0, _noOfPuts).forEach(i ->
                    {
                        _testMap.put(TestUtils.getKey(_mapName, i), _twoMbTestString);
                    });
                }, _noOfRunsToAverage, 2 * _secondInNanos
        );

        clientAssetTree.unregisterSubscriber(_mapName, mapEventSubscriber);

        Jvm.pause(100);
        Assert.assertEquals(0, subscription.entrySubscriberCount());
    }

    /**
     * Tests the performance of an event listener on the map for Update events of 2 MB strings.
     * Expect it to handle at least 50 2 MB updates per second.
     */
    @Test
    public void testSubscriptionMapEventListenerUpdatePerformance() {
        _testMap.clear();

        //Put values before testing as we want to ignore the insert events
        @NotNull Function<Integer, Object> putFunction = a -> _testMap.put(TestUtils.getKey(_mapName, a), _twoMbTestString);

        IntStream.range(0, _noOfPuts).forEach(i ->
        {
            putFunction.apply(i);
        });

        Jvm.pause(100);
        //Create subscriber and register
        @NotNull TestChronicleMapEventListener mapEventListener = new TestChronicleMapEventListener(_mapName, _twoMbTestStringLength);

        @NotNull Subscriber<MapEvent> mapEventSubscriber = e -> e.apply(mapEventListener);
        clientAssetTree.registerSubscriber(_mapName + "?bootstrap=false", MapEvent.class, mapEventSubscriber);

        @NotNull KVSSubscription subscription = (KVSSubscription) serverAssetTree.getAsset(_mapName).subscription(false);

        waitFor(() -> subscription.entrySubscriberCount() == 1);
        Assert.assertEquals(1, subscription.entrySubscriberCount());

        //Perform test a number of times to allow the JVM to warm up, but verify runtime against average
        TestUtils.runMultipleTimesAndVerifyAvgRuntime(i -> {
                    if (i > 0) {
                        waitFor(() -> mapEventListener.getNoOfUpdateEvents().get() >= _noOfPuts);

                        //Test that the correct number of events were triggered on event listener
                        Assert.assertEquals(_noOfPuts, mapEventListener.getNoOfUpdateEvents().get());
                    }
                    Assert.assertEquals(0, mapEventListener.getNoOfInsertEvents().get());
                    Assert.assertEquals(0, mapEventListener.getNoOfRemoveEvents().get());

                    mapEventListener.resetCounters();

                }, () -> {
                    IntStream.range(0, _noOfPuts).forEach(i ->
                    {
                        putFunction.apply(i);
                    });
                }, _noOfRunsToAverage, 3 * _secondInNanos
        );
        clientAssetTree.unregisterSubscriber(_mapName, mapEventSubscriber);

        waitFor(() -> subscription.entrySubscriberCount() == 0);
        Assert.assertEquals(0, subscription.entrySubscriberCount());
    }

    private void waitFor(@NotNull BooleanSupplier b) {
        for (int i = 1; i <= 40; i++)
            if (!b.getAsBoolean())
                Jvm.pause(i * i);
    }

    /**
     * Tests the performance of an event listener on the map for Remove events of 2 MB strings.
     * Expect it to handle at least 50 2 MB updates per second.
     */
    @Test
    public void testSubscriptionMapEventListenerRemovePerformance() {
        _testMap.clear();
        //Put values before testing as we want to ignore the insert and update events

        //Create subscriber and register
        @NotNull TestChronicleMapEventListener mapEventListener = new TestChronicleMapEventListener(_mapName, _twoMbTestStringLength);

        @NotNull Subscriber<MapEvent> mapEventSubscriber = e -> e.apply(mapEventListener);
        clientAssetTree.registerSubscriber(_mapName, MapEvent.class, mapEventSubscriber);

        //Perform test a number of times to allow the JVM to warm up, but verify runtime against average
        long runtimeInNanos = 0;

        for (int i = 0; i < _noOfRunsToAverage; i++) {
            //Put values before testing as we want to ignore the insert and update events
            IntStream.range(0, _noOfPuts).forEach(c ->
            {
                _testMap.put(TestUtils.getKey(_mapName, c), _twoMbTestString);
            });
            waitFor(() -> mapEventListener.getNoOfInsertEvents().get() >= _noOfPuts);

            mapEventListener.resetCounters();

            long startTime = System.nanoTime();

            IntStream.range(0, _noOfPuts).forEach(c ->
            {
                _testMap.remove(TestUtils.getKey(_mapName, c));
            });

            runtimeInNanos += System.nanoTime() - startTime;
            waitFor(() -> mapEventListener.getNoOfRemoveEvents().get() >= _noOfPuts);

            //Test that the correct number of events were triggered on event listener
            Assert.assertEquals(0, mapEventListener.getNoOfInsertEvents().get());
            Assert.assertEquals(_noOfPuts, mapEventListener.getNoOfRemoveEvents().get());
            Assert.assertEquals(0, mapEventListener.getNoOfUpdateEvents().get());
        }

        Assert.assertTrue((runtimeInNanos / (_noOfPuts * _noOfRunsToAverage)) <= 2 * _secondInNanos);
        clientAssetTree.unregisterSubscriber(_mapName, mapEventSubscriber);
    }

    /**
     * Checks that all updates triggered are for the key specified in the constructor and increments
     * the number of updates.
     */
    class TestChronicleKeyEventSubscriber implements Subscriber<String> {
        private int _stringLength;
        @NotNull
        private AtomicInteger _noOfEvents = new AtomicInteger(0);

        public TestChronicleKeyEventSubscriber(int stringLength) {
            _stringLength = stringLength;
        }

        @NotNull
        public AtomicInteger getNoOfEvents() {
            return _noOfEvents;
        }

        @Override
        public void onMessage(@Nullable String newValue) {
            if (newValue == null) {
                System.out.println("No value");
            } else {
                Assert.assertEquals(_stringLength, newValue.length());
                _noOfEvents.incrementAndGet();
            }
        }
    }

    /**
     * Topic subscriber checking for each message that it is for the right key (in constructor) and
     * the expected size value. Increments event counter which can be checked at the end of the
     * test.
     */
    class TestChronicleTopicSubscriber implements TopicSubscriber<String, String> {
        private String _keyName;
        private int _stringLength;
        @NotNull
        private AtomicInteger _noOfEvents = new AtomicInteger(0);

        public TestChronicleTopicSubscriber(String keyName, int stringLength) {
            _keyName = keyName;
            _stringLength = stringLength;
        }

        /**
         * Test that the topic/key is the one specified in constructor and the message is the
         * expected size.
         *
         * @throws InvalidSubscriberException
         */
        @Override
        public void onMessage(String topic, @NotNull String message) throws InvalidSubscriberException {
            Assert.assertEquals(_keyName, topic);
            Assert.assertEquals(_stringLength, message.length());

            _noOfEvents.incrementAndGet();
        }

        @NotNull
        public AtomicInteger getNoOfEvents() {
            return _noOfEvents;
        }
    }

    /**
     * Map event listener for performance testing. Checks that the key is the one expected and the
     * size of the value is as expected. Increments event specific counters that can be used to
     * check against the expected number of events.
     */
    class TestChronicleMapEventListener implements MapEventListener<String, String> {
        @NotNull
        private AtomicInteger _noOfInsertEvents = new AtomicInteger(0);
        @NotNull
        private AtomicInteger _noOfUpdateEvents = new AtomicInteger(0);
        @NotNull
        private AtomicInteger _noOfRemoveEvents = new AtomicInteger(0);

        private String _mapName;
        private int _stringLength;

        public TestChronicleMapEventListener(String mapName, int stringLength) {
            _mapName = mapName;
            _stringLength = stringLength;
        }

        @Override
        public void update(String assetName, String key, String oldValue, @NotNull String newValue) {
            testKeyAndValue(key, newValue, _noOfUpdateEvents);
        }

        @Override
        public void insert(String assetName, String key, @NotNull String value) {
            testKeyAndValue(key, value, _noOfInsertEvents);
        }

        @Override
        public void remove(String assetName, String key, @NotNull String value) {
            testKeyAndValue(key, value, _noOfRemoveEvents);
        }

        @NotNull
        public AtomicInteger getNoOfInsertEvents() {
            return _noOfInsertEvents;
        }

        @NotNull
        public AtomicInteger getNoOfUpdateEvents() {
            return _noOfUpdateEvents;
        }

        @NotNull
        public AtomicInteger getNoOfRemoveEvents() {
            return _noOfRemoveEvents;
        }

        public void resetCounters() {
            _noOfInsertEvents = new AtomicInteger(0);
            _noOfUpdateEvents = new AtomicInteger(0);
            _noOfRemoveEvents = new AtomicInteger(0);
        }

        private void testKeyAndValue(String key, @NotNull String value, @NotNull AtomicInteger counterToIncrement) {
            int counter = counterToIncrement.getAndIncrement();
            Assert.assertEquals(TestUtils.getKey(_mapName, counter), key);
            Assert.assertEquals(_stringLength, value.length());
        }
    }
}