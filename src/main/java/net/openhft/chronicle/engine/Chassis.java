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

import net.openhft.chronicle.engine.api.map.MapView;
import net.openhft.chronicle.engine.api.pubsub.*;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.api.tree.AssetNotFoundException;
import net.openhft.chronicle.engine.api.tree.AssetTree;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.engine.tree.QueueView;
import net.openhft.chronicle.engine.tree.VanillaAssetTree;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

import static net.openhft.chronicle.engine.api.tree.RequestContext.requestContext;

/**
 * This class is the starting point for a simple client or server configuration.  It defines how to
 * obtain some common resource types. <p></p> If you need a more complex environment or tests case
 * you can create one or more AssetTrees directly.
 */
public enum Chassis {
    /* no instances */;
    private static volatile AssetTree assetTree;

    static {
        resetChassis();
    }

    /**
     * Replace the underlying
     */
    public static void resetChassis() {
        assetTree = new VanillaAssetTree().forTesting();
    }

    /**
     * @return Obtain the current AssetTree used by default.
     */
    public static AssetTree assetTree() {
        return assetTree;
    }

    /**
     * View an asset as a set.
     *
     * @param uri    of the set
     * @param eClass of the elements of the set
     * @return the set view.
     * @throws AssetNotFoundException if not found or could not be created.
     */
    @NotNull
    public static <E> Set<E> acquireSet(@NotNull String uri, Class<E> eClass) throws AssetNotFoundException {
        return assetTree.acquireSet(uri, eClass);
    }

    /**
     * Get or create a Map, ConcurrentMap or MapView of an asset.
     *
     * @param uri    of the Map.
     * @param kClass key class
     * @param vClass value class
     * @return the Map
     * @throws AssetNotFoundException if not found or could not be created.
     */
    @NotNull
    public static <K, V> MapView<K, V> acquireMap(@NotNull String uri, Class<K> kClass, Class<V> vClass) throws AssetNotFoundException {
        return assetTree.acquireMap(uri, kClass, vClass);
    }

    /**
     * Obtain a reference to an element or value of Map.  Once this has been obtained you can
     * perform a number of operation on this specific value would looking it up again.
     *
     * @param uri    of the resource
     * @param eClass the type of the element
     * @return a view to a resource.
     * @throws AssetNotFoundException if not found or could not be created.
     */
    @NotNull
    public static <E> Reference<E> acquireReference(@NotNull String uri, Class<E> eClass) throws AssetNotFoundException {
        return assetTree.acquireReference(uri, eClass);
    }

    /**
     * Get or create a Publisher view for a given element or update type.
     *
     * @param uri    to publish to.
     * @param eClass of the data to publish
     * @return a Publisher for this uri
     * @throws AssetNotFoundException if not found or could not be created.
     */
    @NotNull
    public static <E> Publisher<E> acquirePublisher(@NotNull String uri, Class<E> eClass) throws AssetNotFoundException {
        return assetTree.acquirePublisher(uri, eClass);
    }

    @NotNull
    public static <T, M> QueueView<T, M> acquireQueue(@NotNull String uri, Class<T> typeClass, Class<M> messageClass) {

        @NotNull final RequestContext requestContext = requestContext(uri);

        if (requestContext.bootstrap() != null)
            throw new UnsupportedOperationException("Its not possible to set the bootstrap when " +
                    "acquiring a queue");

        return assetTree.acquireView(requestContext.view("queue").type(typeClass).type2(messageClass)
                .cluster(""));
    }

    /**
     * Get or create a TopicPublisher. A Topic Publisher can specify the topic to publish to.
     *
     * @param uri    of the group to publish to.  When you specify a topic, in will be immediately
     *               under that group.
     * @param tClass class of the topic.  Typically String.class
     * @param eClass class of the messages to publish on that topic.
     * @return a TopicPublisher for the group uri.
     * @throws AssetNotFoundException if not found or could not be created.
     */
    @NotNull
    public static <T, E> TopicPublisher<T, E> acquireTopicPublisher(@NotNull String uri, Class<T> tClass, Class<E> eClass) throws AssetNotFoundException {
        return assetTree.acquireTopicPublisher(uri, tClass, eClass);
    }

    /**
     * Register a Subscriber to events on an Asset.  The rule of the AssetTree might allow different
     * behaviour depending on the eClass subscribed to. e.g. if you subscribe to MapEvent you may
     * get MapEvent updates, however, if you subscribe to the key type you may get just the keys
     * which changed.
     *
     * @param uri        of the asset to subscribe to event for.
     * @param eClass     of the subscription
     * @param subscriber to listen to events.
     * @throws AssetNotFoundException if not found or could not be created.
     */
    public static <E> void registerSubscriber(@NotNull String uri, Class<E> eClass, @NotNull Subscriber<E> subscriber) throws AssetNotFoundException {
        assetTree.registerSubscriber(uri, eClass, subscriber);
    }

    /**
     * Unregister a subscriber.  Note: the subscriber must be equals() to the subscriber registered
     * otherwise this will silently fail. e.g. two lambdas which capture the same object are never
     * equals()
     *
     * @param uri        of the asset to unsubscribe from.
     * @param subscriber to unregister
     */
    public static <E> void unregisterSubscriber(@NotNull String uri, @NotNull Subscriber<E> subscriber) {
        assetTree.unregisterSubscriber(uri, subscriber);
    }

    /**
     * Register a Topic Subscription to a group.  This subscriber will be give the topic and the
     * message for each event.
     *
     * @param uri        of the group of Assets to listen to.
     * @param tClass     topic class
     * @param eClass     element class for messages.
     * @param subscriber to listen to events on
     * @throws AssetNotFoundException if not found or could not be created.
     */
    public static <T, E> void registerTopicSubscriber(@NotNull String uri, Class<T> tClass, Class<E> eClass, @NotNull TopicSubscriber<T, E> subscriber) throws AssetNotFoundException {
        assetTree.registerTopicSubscriber(uri, tClass, eClass, subscriber);
    }

    /**
     * Unregister a TopicSubscriber.   Note: the subscriber must be equals() to the subscriber
     * registered otherwise this will silently fail. e.g. two lambdas which capture the same object
     * are never equals()
     *
     * @param uri        of the group of Assets to unregister from
     * @param subscriber to remove.
     */
    public static <T, E> void unregisterTopicSubscriber(@NotNull String uri, @NotNull TopicSubscriber<T, E> subscriber) {
        assetTree.unregisterTopicSubscriber(uri, subscriber);
    }

    /**
     * Get or create an empty asset
     *
     * @param name of the asset
     * @return the Asset
     * @throws AssetNotFoundException if not found or could not be created.
     */
    public static Asset acquireAsset(@NotNull String name) throws AssetNotFoundException {
        return assetTree.acquireAsset(name);
    }

    /**
     * Get an existing Asset or return null
     *
     * @param uri of the asset
     * @return the Asset or null if not found
     */
    @Nullable
    public static Asset getAsset(String uri) {
        return assetTree.getAsset(uri);
    }

    /**
     * Shutdown everything in the asset tree.
     */
    public static void close() {
        assetTree.close();
    }
}
