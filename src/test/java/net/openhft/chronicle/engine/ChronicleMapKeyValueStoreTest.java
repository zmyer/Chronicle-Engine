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

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.BytesStore;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.onoes.ExceptionKey;
import net.openhft.chronicle.core.pool.ClassAliasPool;
import net.openhft.chronicle.core.values.IntValue;
import net.openhft.chronicle.engine.api.EngineReplication;
import net.openhft.chronicle.engine.api.map.MapView;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.api.tree.AssetTree;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.engine.fs.ChronicleMapGroupFS;
import net.openhft.chronicle.engine.fs.FilePerKeyGroupFS;
import net.openhft.chronicle.engine.map.*;
import net.openhft.chronicle.engine.map.VanillaEngineReplication.RemoteNodeReplicationState;
import net.openhft.chronicle.engine.map.VanillaEngineReplication.ReplicationData;
import net.openhft.chronicle.engine.tree.HostIdentifier;
import net.openhft.chronicle.engine.tree.VanillaAssetTree;
import net.openhft.chronicle.wire.Wire;
import net.openhft.chronicle.wire.WireType;
import net.openhft.lang.model.DataValueClasses;
import org.jetbrains.annotations.NotNull;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.function.Function;

import static net.openhft.chronicle.engine.api.tree.RequestContext.requestContext;

/*
 * Created by daniel on 28/05/15.
 */
public class ChronicleMapKeyValueStoreTest {
    public static final String NAME = "/ChMaps/test";

    private static AssetTree tree1;
    private static AssetTree tree2;
    private static AssetTree tree3;
    private static Map<ExceptionKey, Integer> exceptions;

    @BeforeClass
    public static void before() throws IOException {
        exceptions = Jvm.recordExceptions();
        ClassAliasPool.CLASS_ALIASES.addAlias(ChronicleMapGroupFS.class);
        ClassAliasPool.CLASS_ALIASES.addAlias(FilePerKeyGroupFS.class);
        //Delete any files from the last run
        Files.deleteIfExists(Paths.get(OS.TARGET, NAME));
        tree1 = create(1, WireType.TEXT);
        tree2 = create(2, WireType.TEXT);
        tree3 = create(3, WireType.TEXT);
    }

    @AfterClass
    public static void after() {
        tree1.close();
        tree2.close();
        tree3.close();
        ThreadMonitoringTest.filterExceptions(exceptions);
        if (Jvm.hasException(exceptions)) {
            Jvm.dumpException(exceptions);
            Assert.fail();
        }
    }

    @NotNull
    private static String fullPath(@NotNull RequestContext cxt) {
        String basePath = cxt.basePath();
        return basePath == null ? cxt.name() : basePath + "/" + cxt.name();
    }

    @NotNull
    private static AssetTree create(final int hostId, Function<Bytes, Wire> writeType) {
        @NotNull AssetTree tree = new VanillaAssetTree((byte) hostId)
                .forTesting()
                .withConfig(resourcesDir() + "/cmkvst", OS.TARGET + "/" + hostId);

        tree.root().addWrappingRule(MapView.class, "map directly to KeyValueStore",
                VanillaMapView::new,
                AuthenticatedKeyValueStore.class);

        tree.root().addLeafRule(FilePerKeyValueStore.class, "FilePerKey Map",
                FilePerKeyValueStore::new);

        tree.root().addLeafRule(RawKVSSubscription.class, " vanilla",
                MapKVSSubscription::new);

        tree.root().addLeafRule(EngineReplication.class, "Engine replication holder",
                (RequestContext requestContext, Asset asset) -> new VanillaEngineReplication<>(
                        segment -> new FilePerKeyBasedKeyMarshallableValueStore<>(
                                new FilePerKeyValueStore(
                                        requestContext(
                                                Paths.get(fullPath(requestContext),
                                                        "perKeyReplicationData_segment" +
                                                                segment).toString()), asset),
                                BytesStore::toString,
                                s -> {
                                    Bytes bs = BytesStore.wrap(new byte[s.length()])
                                            .bytesForWrite();
                                    bs.writeUtf8(s);
                                    return bs;
                                },
                                () -> DataValueClasses.newInstance(ReplicationData.class)
                        ),
                        new FilePerKeyBasedKeyMarshallableValueStore<>(
                                new FilePerKeyValueStore(
                                        requestContext(
                                                Paths.get(fullPath(requestContext),
                                                        "perRemoteNodeReplicationState")
                                                        .toString()), asset),
                                id -> id.getValue() + "",
                                id -> {
                                    IntValue v = DataValueClasses.newInstance(IntValue.class);
                                    v.setValue(Integer.valueOf(id));
                                    return v;
                                },
                                () -> DataValueClasses.newInstance(RemoteNodeReplicationState.class)
                        ),
                        asset.findOrCreateView(HostIdentifier.class).hostId(),
                        asset.findOrCreateView(FilePerKeyValueStore.class),
                        (kvStore, replicationEntry) -> {
                            if (replicationEntry.isDeleted()) {
                                kvStore.remove(replicationEntry.key().toString());
                            } else {
                                kvStore.put(replicationEntry.key().toString(),
                                        replicationEntry.value());
                            }
                        },
                        (kvStore, key) -> kvStore.get(key.toString()),
                        (kvStore, key) -> kvStore.segmentFor(key.toString()),
                        s -> {
                            Bytes bs = BytesStore.wrap(new byte[s.length()])
                                    .bytesForWrite();
                            bs.writeUtf8(s);
                            return bs;
                        }
                ));

        //  VanillaAssetTreeEgMain.registerTextViewofTree("host " + hostId, tree);

        return tree;
    }

    @NotNull
    public static String resourcesDir() {
        String path = ChronicleMapKeyValueStoreTest.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath();
        if (path == null)
            return ".";
        @NotNull String resources = new File(path).getParentFile().getParentFile() + "/src/test/resources";
        return resources;
    }
}
