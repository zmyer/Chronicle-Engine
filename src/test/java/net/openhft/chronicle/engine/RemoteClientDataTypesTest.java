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
import net.openhft.chronicle.core.threads.ThreadDump;
import net.openhft.chronicle.engine.api.map.MapEvent;
import net.openhft.chronicle.engine.api.tree.AssetTree;
import net.openhft.chronicle.engine.server.ServerEndpoint;
import net.openhft.chronicle.engine.tree.VanillaAssetTree;
import net.openhft.chronicle.network.TCPRegistry;
import net.openhft.chronicle.wire.WireType;
import net.openhft.chronicle.wire.YamlLogging;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Daniel Schiermer
 */
@RunWith(Parameterized.class)
public class RemoteClientDataTypesTest {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteClientDataTypesTest.class);

    private static AssetTree _serverAssetTree;
    private static AssetTree _clientAssetTree;
    private static ServerEndpoint _serverEndpoint;
    @NotNull
    private static String _serverAddress = "host.port1";
    @NotNull
    private static AtomicReference<Throwable> t = new AtomicReference();
    private final WireType _wireType;
    private Class _keyClass;
    private Class _valueClass;
    private Object _key;
    private Object _value;
    private String _mapUri;
    private ThreadDump threadDump;

    public RemoteClientDataTypesTest(WireType wireType, Class keyClass, Class valueClass, Object key, Object value, String mapUri) {
        _wireType = wireType;
        _keyClass = keyClass;
        _valueClass = valueClass;
        _key = key;
        _value = value;
        _mapUri = mapUri;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {WireType.TEXT, String.class, String.class, "key1", "value1", "/tests/ddp/data/hub/remote/string/string/test-map"},
                {WireType.BINARY, String.class, String.class, "key1", "value1", "/tests/ddp/data/hub/remote/string/string/test-map"},
                {WireType.TEXT, String.class, Double.class, "k1", 1.321, "/tests/ddp/data/hub/remote/string/double/test-map"},
                {WireType.BINARY, String.class, Double.class, "k1", 1.143, "/tests/ddp/data/hub/remote/string/double/test-map"},
                {WireType.TEXT, Double.class, Double.class, 2.1, 1.321, "/tests/ddp/data/hub/remote/double/double/test-map"},
                {WireType.BINARY, Double.class, Double.class, 2.1, 1.143, "/tests/ddp/data/hub/remote/double/double/test-map"},
                {WireType.TEXT, Double.class, String.class, 2.1, "Value1", "/tests/ddp/data/hub/remote/double/string/test-map"},
                {WireType.BINARY, Double.class, String.class, 2.1, "Value2", "/tests/ddp/data/hub/remote/double/string/test-map"}
        });
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
        _serverAssetTree = new VanillaAssetTree().forServer();

        TCPRegistry.createServerSocketChannelFor(_serverAddress);
        _serverEndpoint = new ServerEndpoint(_serverAddress, _serverAssetTree);
        _clientAssetTree = new VanillaAssetTree().forRemoteAccess(_serverAddress, _wireType);
    }

    @After
    public void tearDown() {

        final Throwable throwable = t.get();

        if (throwable != null)
            LOG.error("", throwable);

        if (_clientAssetTree != null)
            _clientAssetTree.close();

        if (_serverAssetTree != null)
            _serverAssetTree.close();

        if (_serverEndpoint != null)
            _serverEndpoint.close();

        TCPRegistry.reset();
        final Throwable th = t.getAndSet(null);
        if (th != null) throw Jvm.rethrow(th);
    }

    @Test
    public void testDataTypesMapAndEvents() throws InterruptedException {

        YamlLogging.setAll(false);

        @NotNull BlockingQueue valueSubscriptionQueue = new ArrayBlockingQueue<>(1);
        @NotNull BlockingQueue eventSubscriptionQueue = new ArrayBlockingQueue<>(1);
        @NotNull BlockingQueue topicSubscriptionQueue = new ArrayBlockingQueue<>(1);
        @NotNull BlockingQueue topicOnlySubscriptionQueue = new ArrayBlockingQueue<>(1);

        @NotNull Map testMap = _clientAssetTree.acquireMap(_mapUri, _keyClass, _valueClass);

        //Check that the store is empty
        int size = testMap.size();
        Assert.assertEquals(0, size);

        @NotNull String subscriberMapUri = _mapUri + "?bootstrap=false";
        @NotNull String valueOnlySubscriberUri = _mapUri + "/" + _key.toString() + "?bootstrap=false";

        //Register all types of subscribers
        _clientAssetTree.registerTopicSubscriber(subscriberMapUri, _keyClass, _valueClass, (t, v) -> topicSubscriptionQueue.add(v));
        _clientAssetTree.registerSubscriber(subscriberMapUri, _keyClass, topicOnlySubscriptionQueue::add);
        _clientAssetTree.registerSubscriber(subscriberMapUri, MapEvent.class, mapEvent -> mapEvent.apply((assetName, key, oldValue, newValue) -> eventSubscriptionQueue.add(newValue)));
        _clientAssetTree.registerSubscriber(valueOnlySubscriberUri, _valueClass, valueSubscriptionQueue::add);

        //put the kvp
        testMap.put(_key, _value);

        //Get the value back and check that it is the same
        Object valueGet = testMap.get(_key);
        Assert.assertEquals(_value, valueGet);

        int timeout = 200;
        Assert.assertEquals(_value, valueSubscriptionQueue.poll(timeout, TimeUnit.MILLISECONDS));
        Assert.assertEquals(_value, eventSubscriptionQueue.poll(timeout, TimeUnit.MILLISECONDS));
        Assert.assertEquals(_value, topicSubscriptionQueue.poll(timeout, TimeUnit.MILLISECONDS));
        Assert.assertEquals(_key, topicOnlySubscriptionQueue.poll(timeout, TimeUnit.MILLISECONDS));
    }
}