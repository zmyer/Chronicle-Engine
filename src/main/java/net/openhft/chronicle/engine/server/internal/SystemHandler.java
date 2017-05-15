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

package net.openhft.chronicle.engine.server.internal;

import net.openhft.chronicle.engine.cfg.UserStat;
import net.openhft.chronicle.engine.tree.HostIdentifier;
import net.openhft.chronicle.network.ClientClosedProvider;
import net.openhft.chronicle.network.SessionMode;
import net.openhft.chronicle.network.api.session.SessionDetailsProvider;
import net.openhft.chronicle.network.connection.CoreFields;
import net.openhft.chronicle.network.connection.WireOutPublisher;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static net.openhft.chronicle.engine.server.internal.SystemHandler.EventId.heartbeat;
import static net.openhft.chronicle.engine.server.internal.SystemHandler.EventId.onClientClosing;

/**
 * @author Rob Austin.
 */
public class SystemHandler extends AbstractHandler implements ClientClosedProvider {
    private final StringBuilder eventName = new StringBuilder();
    private SessionDetailsProvider sessionDetails;
    private final WireParser<Void> wireParser = wireParser();
    @Nullable
    private Map<String, UserStat> monitoringMap;
    private volatile boolean hasClientClosed;
    private boolean wasHeartBeat;
    @NotNull
    private final BiConsumer<WireIn, Long> dataConsumer = (inWire, tid) -> {
        eventName.setLength(0);
        @NotNull final ValueIn valueIn = inWire.readEventName(eventName);
        try {
            assert startEnforceInValueReadCheck(inWire);

            if (EventId.userId.contentEquals(eventName)) {
                this.sessionDetails.userId(valueIn.text());
                if (this.monitoringMap != null) {
                    @NotNull UserStat userStat = new UserStat();
                    userStat.setLoggedIn(LocalTime.now());
                    monitoringMap.put(sessionDetails.userId(), userStat);
                }

                while (inWire.bytes().readRemaining() > 0)
                    wireParser.parseOne(inWire, null);

                return;
            }

            if (!heartbeat.contentEquals(eventName) && !onClientClosing.contentEquals(eventName))
                return;

            wasHeartBeat = true;

            //noinspection ConstantConditions
            outWire.writeDocument(true, wire -> outWire.writeEventName(CoreFields.tid).int64(tid));

            writeData(inWire, out -> {

                if (heartbeat.contentEquals(eventName)) {
                    outWire.write(EventId.heartbeatReply).int64(valueIn.int64());
                    return;
                }
                while (inWire.hasMore())
                    skipValue(valueIn);

                if (onClientClosing.contentEquals(eventName)) {
                    hasClientClosed = true;
                    outWire.write(EventId.onClosingReply).text("");
                }
            });
        } finally {
            assert endEnforceInValueReadCheck(inWire);
        }
    };

    public boolean wasHeartBeat() {
        return wasHeartBeat;
    }

    void process(@NotNull final WireIn inWire,
                 @NotNull final WireOut outWire, final long tid,
                 @NotNull final SessionDetailsProvider sessionDetails,
                 @Nullable Map<String, UserStat> monitoringMap,
                 boolean isServerSocket,
                 @Nullable Supplier<WireOutPublisher> publisher,
                 @Nullable final HostIdentifier hostId,
                 @NotNull Consumer<WireType> onWireType, @Nullable WireType wireType0) {

        this.wasHeartBeat = false;
        this.sessionDetails = sessionDetails;
        this.monitoringMap = monitoringMap;
        setOutWire(outWire);
        dataConsumer.accept(inWire, tid);

        if (wireType0 == null && sessionDetails.wireType() != null)
            onWireType.accept(sessionDetails.wireType());
/*
        if (isServerSocket && sessionDetails.hostId() != 0) {
            final VanillaSessionDetails sd = new VanillaSessionDetails();

            sd.hostId(hostId.hostId());
            sd.wireType(sessionDetails.wireType());
            publisher.get().put(null, w -> w.writeDocument(false, sd));
        }*/
    }

    @NotNull
    private WireParser<Void> wireParser() {
        @NotNull final WireParser<Void> parser = new VanillaWireParser<>((s, v, $) -> {
        });
        parser.register(EventId.domain::toString, (s, v, $) -> v.text(this, (o, x) -> o.sessionDetails.domain(x)));
        parser.register(EventId.sessionMode::toString, (s, v, $) -> v.text(this, (o, x) -> o
                .sessionDetails.sessionMode(SessionMode.valueOf(x))));
        parser.register(EventId.securityToken::toString, (s, v, $) -> v.text(this, (o, x) -> o
                .sessionDetails.securityToken(x)));
        parser.register(EventId.clientId::toString, (s, v, $) -> v.text(this, (o, x) -> o
                .sessionDetails.clientId(UUID.fromString(x))));
        parser.register(EventId.wireType::toString, (s, v, $) -> v.text(this, (o, x) -> o
                .sessionDetails.wireType(WireType.valueOf(x))));
        parser.register(EventId.hostId::toString, (s, v, $) -> v.int8(this, (o, x) -> o
                .sessionDetails.hostId(x)));
        return parser;
    }

    /**
     * @return {@code true} if the client has intentionally closed
     */
    @Override
    public boolean hasClientClosed() {
        return hasClientClosed;
    }

    public enum EventId implements WireKey {
        heartbeat,
        heartbeatReply,
        onClientClosing,
        onClosingReply,
        userId,
        sessionMode,
        domain,
        securityToken,
        wireType,
        clientId,
        hostId
    }
}

