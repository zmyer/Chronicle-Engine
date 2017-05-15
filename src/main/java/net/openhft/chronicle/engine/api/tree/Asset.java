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

package net.openhft.chronicle.engine.api.tree;

import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.util.ThrowingConsumer;
import net.openhft.chronicle.engine.api.map.MapView;
import net.openhft.chronicle.engine.api.pubsub.InvalidSubscriberException;
import net.openhft.chronicle.engine.api.pubsub.Subscriber;
import net.openhft.chronicle.engine.api.pubsub.SubscriptionCollection;
import net.openhft.chronicle.engine.api.pubsub.TopicSubscriber;
import net.openhft.chronicle.engine.map.KVSSubscription;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.function.BiPredicate;

import static net.openhft.chronicle.engine.api.tree.RequestContext.requestContext;

/**
 * An Asset is a point on an AssetTree.  An Asset can not only have any number of Children it has
 * multiple views depending on how you want to access the data. <p></p> A single asset can have a
 * MapView, EntrySetView, KeySetView, ValuesCollection, Subscription, TopicPublisher. A Map may be
 * viewed in terms of the objects it holds e.g. String or Marshallable or the raw data i.e. as a
 * BytesStore.
 */
public interface Asset extends Closeable {

    /**
     * @return the name of this asset (not including the group)
     */
    String name();

    /**
     * @return the parent of this asset or null if it is the root.
     */
    @Nullable
    Asset parent();

    /**
     * @return the full name of this asset including it's parent and theirs etc.
     */
    @NotNull
    default String fullName() {
        @Nullable Asset parent = parent();
        return parent == null
                ? "/"
                : parent.parent() == null
                ? "/" + name()
                : parent.fullName() + "/" + name();
    }

    /**
     * Obtain the default subscription view. If there is more than one this will be the object
     * subscription view.
     *
     * @param createIfAbsent create one if it doesn't exist.
     * @return the Subscription
     * @throws AssetNotFoundException if the Subscription doesn't exist and the tree is not able to
     *                                create the Subscription.
     */
    @Nullable
    SubscriptionCollection subscription(boolean createIfAbsent) throws AssetNotFoundException;

    /**
     * Navigate down the tree to find an asset.
     *
     * @param fullName with names separated by /
     * @return the Asset found or null
     */
    @Nullable
    default Asset getAsset(@NotNull String fullName) {
        if (fullName.isEmpty()) return this;
        int pos = fullName.indexOf("/");
        if (pos == 0) {
            fullName = fullName.substring(1);
            pos = fullName.indexOf("/");
        }
        if (pos >= 0) {
            @NotNull String name1 = fullName.substring(0, pos);
            @NotNull String name2 = fullName.substring(pos + 1);
            Asset asset = getChild(name1);
            if (asset == null) {
                return null;

            } else {
                return asset.getAsset(name2);
            }
        }
        return getChild(fullName);
    }

    /**
     * Get or create an asset under this one.
     *
     * @param childName name of the child asset.
     * @return Asset acquired
     * @throws AssetNotFoundException
     */
    @NotNull
    Asset acquireAsset(String childName) throws AssetNotFoundException;

    /**
     * Search a tree to find the first Asset with the name given.
     *
     * @param name partial name of asset to find.
     * @return the Asset found or null.
     */
    @Nullable
    default Asset findAsset(@NotNull String name) {
        @Nullable Asset asset = getAsset(name);
        @Nullable Asset parent = parent();
        if (asset == null && parent != null)
            asset = parent.findAsset(name);
        return asset;
    }

    /**
     * Search for a view up the tree.
     *
     * @param viewType the class pf the view
     * @return the View found or null.
     */
    @Nullable
    default <V> V findView(@NotNull Class<V> viewType) {
        @Nullable V v = getView(viewType);
        @Nullable Asset parent = parent();
        if (v == null && parent != null)
            v = parent.findView(viewType);
        return v;
    }

    /**
     * Search up the tree for a view of viewType.  If one doesn't exist find a factory and add it to
     * the asset the factory is associated with.  This view can then be shared for the Assets under
     * the Asset where the factory is defined.
     *
     * @param viewType to obtain.
     * @return the view it could be created
     */
    @Nullable
    default <V> V findOrCreateView(@NotNull Class<V> viewType) throws AssetNotFoundException {
        @Nullable V v = getView(viewType);
        if (v == null) {
            if (hasFactoryFor(viewType))
                return acquireView(viewType);
            @Nullable Asset parent = parent();
            if (parent != null)
                v = parent.findOrCreateView(viewType);
        }
        return v;
    }

    /**
     * Determine when an asset has a factory/rule for a viewType type.
     *
     * @param viewType to look for.
     * @return true, if the factory can be found, or false if not.
     */
    <V> boolean hasFactoryFor(Class<V> viewType);

    /**
     * Get the child of an asset.
     *
     * @param name of the child.
     * @return the Asset or null if it doesn't exist.
     */
    Asset getChild(String name);

    /**
     * Remove a child asset from the tree.
     *
     * @param name of the child to remove.
     */
    void removeChild(String name);

    /**
     * Get or create a view based on a RequestContext.  First it looks for a matching viewType(). If
     * found this is returned.
     *
     * @param requestContext to use in the construction of the view
     * @return the View obtained.
     * @throws AssetNotFoundException if the Asset could not be created. This can happen if a
     *                                required rule is not provided.
     */
    @NotNull
    default <V> V acquireView(@NotNull RequestContext requestContext) throws AssetNotFoundException {
        return (V) acquireView(requestContext.viewType(), requestContext);
    }

    /**
     * Get or create a view based on a RequestContext.  First it looks for a matching viewType(). If
     * found this is returned. The viewType given overrides the type provided in the
     * RequestContext.
     *
     * @param viewType       to obtain.
     * @param requestContext to use in the construction of the view
     * @return the View obtained.
     * @throws AssetNotFoundException if the Asset could not be created. This can happen if a
     *                                required rule is not provided.
     */
    @NotNull
    <V> V acquireView(Class<V> viewType, RequestContext requestContext) throws AssetNotFoundException;

    /**
     * Get or create a view with out a RequestContext.  First it looks for a matching viewType(). If
     * found this is returned. The viewType given overrides the type provided in the
     * RequestContext.
     *
     * @param viewType to obtain.
     * @return the View obtained.
     * @throws AssetNotFoundException if the Asset could not be created. This can happen if a
     *                                required rule is not provided.
     */
    @NotNull
    default <V> V acquireView(Class<V> viewType) {
        return acquireView(viewType, RequestContext.requestContext(fullName()));
    }

    /**
     * Get a view if it already exists on the current Asset.
     *
     * @param viewType the associated interface or class for this view.
     * @return a view which implements viewType, or null if it doesn't exist.
     */
    @Nullable
    <V> V getView(Class<V> viewType);

    /**
     * Provide a specific implementation of a view
     *
     * @param viewType interface or class to associate this implementation with.
     * @param view     implementation of viewType to use.
     */
    <V> void registerView(Class<V> viewType, V view);

    /**
     * Add a rule or factory for creating view on demand.  A Leaf rule doesn't need any view to
     * exist before you create it.  This can be used for building the fundamental data structure
     * which represents this Asset. <p></p> If two rules with the same description are provided, the
     * new factory will replace the old.  At present, any new factory replaces the old one, however
     * in the future we may support multiple factories.
     *
     * @param viewType    interface to associate this factory with.
     * @param description of the factory
     * @param factory     to create a viewType
     */
    <V> void addLeafRule(Class<V> viewType, String description, LeafViewFactory<V> factory);

    /**
     * Add a rule or factory for creating views on demand.  A Wrapping Rule need an underlying view
     * to wrap before it can be created. This can be used for laying functionality on existing
     * views. <p></p> If two rules with the same description are provided, the new factory replaces
     * the old one. <b>Note:</b> if rules with different descriptions are provided, they are called
     * in ASCIIbetical order of the description. <p></p> If a factory returns null, a later factory
     * will be called.
     *
     * @param viewType       class of the view.
     * @param description    to use to comment on the view, determine order of factories and detect
     *                       duplicates.
     * @param factory        to use to create the view. If the factory returns null, the next
     *                       factory is called.
     * @param underlyingType the underlying view type required.
     */
    <V, U> void addWrappingRule(Class<V> viewType, String description, WrappingViewFactory<V, U> factory, Class<U> underlyingType);

    /**
     * Add a rule or factory for creating views on demand.  A Wrapping Rule need an underlying view
     * to wrap before it can be created. This can be used for laying functionality on existing
     * views. <p></p> If two rules with the same description are provided, the new factory replaces
     * the old one. <b>Note:</b> if rules with different descriptions are provided, they are called
     * in ASCIIbetical order of the description. <p></p> If the predicate returns false or a factory
     * returns null, a later factory will be called.
     *
     * @param viewType       class of the view.
     * @param description    to use to comment on the view, determine order of factories and detect
     *                       duplicates.
     * @param predicate      to test whether this factory applies.
     * @param factory        to use to create the view. If the factory returns null, the next
     *                       factory is called.
     * @param underlyingType the underlying view type required.
     */
    <V, U> void addWrappingRule(Class<V> viewType, String description, BiPredicate<RequestContext, Asset> predicate, WrappingViewFactory<V, U> factory, Class<U> underlyingType);

    /**
     * Dump the rules in YAML format.
     *
     * @return the leaf and wrapping rules in text.
     */
    String dumpRules();

    /**
     * Add an implementation of a view to the asset.. This can be used instead of, or in addition to
     * adding rules.
     *
     * @param viewType to associate this implementation with.
     * @param view
     * @return the view provided.
     */
    <V> V addView(Class<V> viewType, V view);

    default <V> V addView(@NotNull V view) {
        return addView((Class<V>) view.getClass(), view);
    }

    /**
     * @return true if this is a simplified Asset attached to a keyed Asset.  E.g. if you subscribe
     * to a key in a Map this key uses a SubAsset.
     */
    boolean isSubAsset();

    /**
     * Find the root Asset for this tree.
     *
     * @return the root.
     */
    @NotNull
    default Asset root() {
        @Nullable final Asset parent = parent();
        return parent == null ? this : parent.root();
    }

    /**
     * Is this a leaf node.
     *
     * @return if this Asset has Asset as children.
     */
    boolean hasChildren();

    /**
     * Iterate of all the children of this Asset.
     *
     * @param consumer to accept each child.
     * @throws InvalidSubscriberException to throw if the accept is no longer interested in getting
     *                                    more children.
     */
    <T extends Throwable> void forEachChild(ThrowingConsumer<Asset, T> consumer) throws T;

    void getUsageStats(AssetTreeStats ats);

    default <E> void unregisterSubscriber(
            @NotNull RequestContext requestContext,
            @NotNull Subscriber<Object> subscriber) {

        @NotNull final Class<SubscriptionCollection> subscriptionType = requestContext.getSubscriptionType();
        @Nullable final SubscriptionCollection subscription = getView(subscriptionType);

        if (subscription == null)
            subscriber.onEndOfSubscription();
        else
            subscription.unregisterSubscriber(subscriber);

    }

    default <T, E> void unregisterTopicSubscriber(@NotNull RequestContext requestContext,
                                                  @NotNull TopicSubscriber<T, E> subscriber) throws AssetNotFoundException {
        @Nullable SubscriptionCollection subscription = getView(requestContext.getSubscriptionType());
        if (subscription instanceof KVSSubscription)
            ((KVSSubscription) subscription).unregisterTopicSubscriber(subscriber);
        else
            subscriber.onEndOfSubscription();
    }

    default <T, E> void unregisterTopicSubscriber(@NotNull String uri,
                                                  @NotNull Class<T> topicClass,
                                                  @NotNull Class<E> messageClass,
                                                  @NotNull TopicSubscriber<T, E> subscriber) throws AssetNotFoundException {
        @NotNull RequestContext rc = requestContext(uri).keyType(topicClass).valueType(messageClass);
        @Nullable SubscriptionCollection subscription = getView(rc.getSubscriptionType());
        if (subscription instanceof KVSSubscription)
            ((KVSSubscription) subscription).unregisterTopicSubscriber(subscriber);
        else
            subscriber.onEndOfSubscription();
    }

    default <T, E> void registerTopicSubscriber(@NotNull String uri,
                                                @NotNull Class<T> topicClass,
                                                @NotNull Class<E> messageClass,
                                                @NotNull TopicSubscriber<T, E> subscriber) throws AssetNotFoundException {
        @NotNull RequestContext rc = requestContext(uri).keyType(topicClass).valueType(messageClass);
        @NotNull final SubscriptionCollection subscriptionCollection = acquireSubscription(rc);
        @NotNull final KVSSubscription kvsSubscription = (KVSSubscription) subscriptionCollection;
        kvsSubscription.registerTopicSubscriber(rc, subscriber);
    }

    @NotNull
    default SubscriptionCollection acquireSubscription(@NotNull RequestContext requestContext) {
        @NotNull Class<SubscriptionCollection> subscriptionType = requestContext.getSubscriptionType();
        requestContext.viewType(subscriptionType);
        return acquireView(subscriptionType, requestContext);
    }


    /**
     * @return the current view types that are available at this asset.
     */
    default Set<Class> viewTypes() {
        return Collections.emptySet();
    }


    @NotNull
    default <K, V> MapView<K, V> acquireMap(@NotNull String uri, Class<K> kClass, Class<V> vClass) throws AssetNotFoundException {
        @NotNull final RequestContext requestContext = requestContext(uri);

        if (requestContext.bootstrap() != null)
            throw new UnsupportedOperationException("Its not possible to set the bootstrap when " +
                    "acquiring a map");

        return acquireView(requestContext.view("map").type(kClass).type2(vClass));
    }

}
