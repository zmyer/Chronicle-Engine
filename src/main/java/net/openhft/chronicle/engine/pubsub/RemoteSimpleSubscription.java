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

package net.openhft.chronicle.engine.pubsub;

import net.openhft.chronicle.engine.api.pubsub.Reference;
import net.openhft.chronicle.engine.api.pubsub.Subscriber;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.engine.query.Filter;
import org.jetbrains.annotations.NotNull;

/*
 * Created by Peter Lawrey on 29/05/15.
 */
public class RemoteSimpleSubscription<E> implements SimpleSubscription<E> {
    // TODO CE-101 pass to the server
    private final Reference<E> reference;

    public RemoteSimpleSubscription(Reference<E> reference) {
        this.reference = reference;
    }

    public RemoteSimpleSubscription(RequestContext requestContext, Asset asset, Reference<E> reference) {
        this.reference = reference;
    }

    @Override
    public void registerSubscriber(@NotNull RequestContext rc,
                                   @NotNull Subscriber<E> subscriber,
                                   @NotNull Filter<E> filter) {
        reference.registerSubscriber(rc.bootstrap() != Boolean.FALSE,
                rc.throttlePeriodMs(),
                subscriber);
    }

    @Override
    public void unregisterSubscriber(@NotNull Subscriber subscriber) {
        reference.unregisterSubscriber(subscriber);
    }

    @Override
    public int keySubscriberCount() {

        // TODO CE-101 pass to the server
        return subscriberCount();
    }

    @Override
    public int entrySubscriberCount() {
        return 0;
    }

    @Override
    public int topicSubscriberCount() {
        return 0;
    }

    @Override
    public int subscriberCount() {
        return reference.subscriberCount();
    }

    @Override
    public void notifyMessage(Object e) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
    }
}
