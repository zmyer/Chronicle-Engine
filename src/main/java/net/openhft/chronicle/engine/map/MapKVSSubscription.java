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
import net.openhft.chronicle.engine.api.map.KeyValueStore;
import net.openhft.chronicle.engine.api.map.KeyValueStore.Entry;
import net.openhft.chronicle.engine.api.map.MapEvent;
import net.openhft.chronicle.engine.api.map.MapView;
import net.openhft.chronicle.engine.api.pubsub.*;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.engine.cfg.SubscriptionStat;
import net.openhft.chronicle.engine.pubsub.MapSimpleSubscription;
import net.openhft.chronicle.engine.pubsub.SimpleSubscription;
import net.openhft.chronicle.engine.query.Filter;
import net.openhft.chronicle.network.api.session.SessionDetails;
import net.openhft.chronicle.network.api.session.SessionProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static java.lang.Boolean.TRUE;
import static net.openhft.chronicle.engine.api.pubsub.SubscriptionConsumer.notifyEachSubscriber;

/*
 * Created by Peter Lawrey on 22/05/15.
 */
public class MapKVSSubscription<K, V> implements ObjectSubscription<K, V>,
        RawKVSSubscription<K, V> {

    private static final Logger LOG = LoggerFactory.getLogger(MapKVSSubscription.class);
    private final Set<TopicSubscriber<K, V>> topicSubscribers = new CopyOnWriteArraySet<>();
    private final Set<Subscriber<MapEvent<K, V>>> subscribers = new CopyOnWriteArraySet<>();
    private final Set<Subscriber<K>> keySubscribers = new CopyOnWriteArraySet<>();
    private final Set<EventConsumer<K, V>> downstream = new CopyOnWriteArraySet<>();
    @Nullable
    private final SessionProvider sessionProvider;

    @Nullable
    private final Asset asset;
    private final Map<Subscriber, Subscriber> subscriptionDelegate = new IdentityHashMap<>();
    private KeyValueStore<K, V> kvStore;
    @Nullable
    private Map<String, SubscriptionStat> subscriptionMonitoringMap = null;

    public MapKVSSubscription(@NotNull RequestContext requestContext, @NotNull Asset asset) {
        this(requestContext.viewType(), asset);
    }

    public MapKVSSubscription(@Nullable Class viewType, @Nullable Asset asset) {
        this.asset = asset;
        if (viewType != null && asset != null)
            asset.addView(viewType, this);

        sessionProvider = asset == null ? null : asset.findView(SessionProvider.class);
    }

    @Override
    public void close() {
        notifyEndOfSubscription(topicSubscribers);
        notifyEndOfSubscription(subscribers);
        notifyEndOfSubscription(keySubscribers);
        notifyEndOfSubscription(downstream);
    }

    @Override
    public void onEndOfSubscription() {
        throw new UnsupportedOperationException("todo");
    }

    private void notifyEndOfSubscription(@NotNull Set<? extends ISubscriber> subscribers) {
        subscribers.forEach(this::notifyEndOfSubscription);
        subscribers.clear();
    }

    private void notifyEndOfSubscription(@NotNull ISubscriber subscriber) {
        try {
            subscriber.onEndOfSubscription();
        } catch (Exception e) {
            Jvm.warn().on(getClass(), "Failed to send endOfSubscription", e);
        }
    }

    @Override
    public boolean keyedView() {
        return kvStore != null;
    }

    @Override
    public void setKvStore(KeyValueStore<K, V> kvStore) {
        this.kvStore = kvStore;
    }

    @Override
    public void notifyEvent(@NotNull MapEvent<K, V> changeEvent) {
        if (hasSubscribers())
            notifyEvent0(changeEvent);
    }

    @Override
    public int keySubscriberCount() {
        return keySubscribers.size();
    }

    @Override
    public int entrySubscriberCount() {
        return subscribers.size();
    }

    @Override
    public int topicSubscriberCount() {
        return topicSubscribers.size();
    }

    @Override
    public boolean hasSubscribers() {
        return !keySubscribers.isEmpty() || hasValueSubscribers();
    }

    @Override
    public boolean hasValueSubscribers() {
        return !topicSubscribers.isEmpty() || !subscribers.isEmpty()
                || !downstream.isEmpty() || asset.hasChildren();
    }

    private void notifyEvent0(@NotNull MapEvent<K, V> changeEvent) {
        notifyEvent1(changeEvent);
        notifyEventToChild(changeEvent);
    }

    private void notifyEvent1(@NotNull MapEvent<K, V> changeEvent) {
        K key = changeEvent.getKey();

        if (!topicSubscribers.isEmpty()) {
            V value = changeEvent.getValue();
            notifyEachSubscriber(topicSubscribers, ts -> ts.onMessage(key, value));
        }
        if (!subscribers.isEmpty()) {
            notifyEachSubscriber(subscribers, s -> s.onMessage(changeEvent));
        }
        if (!keySubscribers.isEmpty()) {
            notifyEachSubscriber(keySubscribers, s -> s.onMessage(key));
        }
        if (!downstream.isEmpty()) {
            notifyEachSubscriber(downstream, d -> d.notifyEvent(changeEvent));
        }
    }

    private void notifyEventToChild(@NotNull MapEvent<K, V> changeEvent) {
        K key = changeEvent.getKey();
        if (asset == null || !asset.hasChildren()) {
            return;
        }
        String keyStr = key.toString();
        Asset child = asset.getChild(keyStr);
        if (child == null) {
            return;
        }
        @Nullable SubscriptionCollection subscription = child.subscription(false);
        if (subscription instanceof MapSimpleSubscription) {
//                    System.out.println(changeEvent.toString().substring(0, 100));
            ((SimpleSubscription) subscription).notifyMessage(changeEvent.getValue());
        }
    }

    @Override
    public boolean needsPrevious() {
        // todo optimise this to reduce false positives.
        return !subscribers.isEmpty() || !downstream.isEmpty();
    }

    @Override
    public void registerSubscriber(@NotNull RequestContext rc,
                                   @NotNull Subscriber subscriber,
                                   @NotNull Filter filter) {
        @NotNull Class eClass = rc.elementType();
        if (eClass == Entry.class || eClass == MapEvent.class)
            registerSubscriber0(rc, subscriber, filter);
        else
            registerKeySubscriber(rc, subscriber, filter);

    }

    @NotNull
    private <T> Subscriber<T> subscriber(@NotNull Subscriber<T> subscriber,
                                         @NotNull Filter<T> filter) {
        @NotNull final Subscriber<T> sub;
        if (filter.isEmpty())
            sub = subscriber;
        else {
            sub = new Filter.FilteredSubscriber<>(filter, subscriber);
            subscriptionDelegate.put(subscriber, sub);
        }
        return sub;
    }

    private void registerSubscriber0(@NotNull RequestContext rc,
                                     @NotNull Subscriber<MapEvent<K, V>> subscriber,
                                     @NotNull Filter<MapEvent<K, V>> filter) {
        addToStats("subscription");
        @NotNull final Subscriber<MapEvent<K, V>> sub = subscriber(subscriber, filter);
        this.subscribers.add(sub);
        @Nullable Boolean bootstrap = rc.bootstrap();
        if (bootstrap != Boolean.FALSE && kvStore != null) {
            try {
                for (int i = 0; i < kvStore.segments(); i++)
                    kvStore.entriesFor(i, sub::onMessage);

                if (TRUE.equals(rc.endSubscriptionAfterBootstrap())) {

                    sub.onEndOfSubscription();
                    LOG.info("onEndOfSubscription");
                    this.subscribers.remove(sub);
                }

            } catch (InvalidSubscriberException e) {
                this.subscribers.remove(sub);
            }
        }
    }

    @Override
    public void registerKeySubscriber(@NotNull RequestContext rc,
                                      @NotNull Subscriber<K> subscriber,
                                      @NotNull Filter<K> filter) {
        addToStats("keySubscription");
        @Nullable final Boolean bootstrap = rc.bootstrap();
        @NotNull final Subscriber<K> sub = subscriber(subscriber, filter);
        keySubscribers.add(sub);
        if (bootstrap != Boolean.FALSE && kvStore != null) {
            try {
                for (int i = 0; i < kvStore.segments(); i++)
                    kvStore.keysFor(i, sub::onMessage);
                if (TRUE.equals(rc.endSubscriptionAfterBootstrap())) {
                    sub.onEndOfSubscription();
                    keySubscribers.remove(sub);
                }
            } catch (InvalidSubscriberException e) {
                keySubscribers.remove(sub);
            }
        }
    }

    @Override
    public void registerTopicSubscriber(@NotNull RequestContext rc, @NotNull TopicSubscriber subscriber) {
        addToStats("topicSubscription");
        @Nullable Boolean bootstrap = rc.bootstrap();
        topicSubscribers.add((TopicSubscriber<K, V>) subscriber);
        if (bootstrap != Boolean.FALSE && kvStore != null) {
            try {
                for (int i = 0; i < kvStore.segments(); i++)
                    kvStore.entriesFor(i, e -> subscriber.onMessage(e.getKey(), e.getValue()));

            } catch (InvalidSubscriberException dontAdd) {
                topicSubscribers.remove(subscriber);
            }
        }
    }

    @Override
    public void registerDownstream(@NotNull EventConsumer<K, V> subscription) {
        downstream.add(subscription);

    }

    public void unregisterDownstream(EventConsumer<K, V> subscription) {
        downstream.remove(subscription);
    }

    @Override
    public void unregisterSubscriber(@NotNull Subscriber subscriber) {
        final Subscriber delegate = subscriptionDelegate.remove(subscriber);
        @NotNull final Subscriber s = delegate != null ? delegate : subscriber;
        boolean subscription = subscribers.remove(s);
        boolean keySubscription = keySubscribers.remove(s);
        if (subscription) removeFromStats("subscription");
        if (keySubscription) removeFromStats("keySubscription");
        //noinspection ConstantConditions
        if (s != null)
            s.onEndOfSubscription();
    }

    @Override
    public void unregisterTopicSubscriber(@NotNull TopicSubscriber subscriber) {
        topicSubscribers.remove(subscriber);
        removeFromStats("topicSubscription");
        subscriber.onEndOfSubscription();
    }

    //Needs some refactoring - need a definitive way of knowing when this map should become available
    //3 combinations, not lookedUP, exists or does not exist
    @Nullable
    private Map getSubscriptionMap() {
        if (subscriptionMonitoringMap != null) return subscriptionMonitoringMap;
        @Nullable Asset subscriptionAsset = asset.root().getAsset("proc/subscriptions");
        if (subscriptionAsset != null && subscriptionAsset.getView(MapView.class) != null) {
            subscriptionMonitoringMap = subscriptionAsset.getView(MapView.class);
        }
        return subscriptionMonitoringMap;
    }

    private void addToStats(String subType) {
        if (sessionProvider == null) return;

        @Nullable SessionDetails sessionDetails = sessionProvider.get();
        if (sessionDetails != null) {
            @Nullable String userId = sessionDetails.userId();

            @Nullable Map<String, SubscriptionStat> subStats = getSubscriptionMap();
            if (subStats != null) {
                SubscriptionStat stat = subStats.get(userId + "~" + subType);
                if (stat == null) {
                    stat = new SubscriptionStat();
                    stat.setFirstSubscribed(LocalTime.now());
                }
                stat.setTotalSubscriptions(stat.getTotalSubscriptions() + 1);
                stat.setActiveSubscriptions(stat.getActiveSubscriptions() + 1);
                stat.setRecentlySubscribed(LocalTime.now());
                subStats.put(userId + "~" + subType, stat);
            }
        }
    }

    private void removeFromStats(String subType) {
        if (sessionProvider == null) return;

        @Nullable SessionDetails sessionDetails = sessionProvider.get();
        if (sessionDetails != null) {
            @Nullable String userId = sessionDetails.userId();

            @Nullable Map<String, SubscriptionStat> subStats = getSubscriptionMap();
            if (subStats != null) {
                SubscriptionStat stat = subStats.get(userId + "~" + subType);
                if (stat == null) {
                    throw new AssertionError("There should be an active subscription");
                }
                stat.setActiveSubscriptions(stat.getActiveSubscriptions() - 1);
                stat.setRecentlySubscribed(LocalTime.now());
                subStats.put(userId + "~" + subType, stat);
            }
        }
    }
}
