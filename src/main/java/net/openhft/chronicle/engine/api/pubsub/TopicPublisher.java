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

package net.openhft.chronicle.engine.api.pubsub;

import net.openhft.chronicle.engine.api.tree.AssetNotFoundException;
import net.openhft.chronicle.engine.api.tree.KeyedView;
import org.jetbrains.annotations.NotNull;

/**
 * Publish to any topic in an Asset group.
 */
public interface TopicPublisher<T, M> extends KeyedView {
    /**
     * Publish to a provided topic.
     *
     * @param topic   to publish to
     * @param message to publish.
     */
    void publish(@NotNull T topic, @NotNull M message);

    /**
     * Add a subscription to this group.
     *
     * @param topicSubscriber to listen to events.
     * @throws AssetNotFoundException if the Asset is no longer valid.
     */
    void registerTopicSubscriber(@NotNull TopicSubscriber<T, M> topicSubscriber) throws AssetNotFoundException;

    /**
     * Unregister a TopicSubscriber
     *
     * @param topicSubscriber to unregister
     */
    void unregisterTopicSubscriber(@NotNull TopicSubscriber<T, M> topicSubscriber);

    @NotNull
    Publisher<M> publisher(@NotNull T topic);

    void registerSubscriber(@NotNull T topic, @NotNull Subscriber<M> subscriber);
}
