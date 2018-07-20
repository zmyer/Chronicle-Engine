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

package net.openhft.chronicle.engine.cfg;

import net.openhft.chronicle.engine.api.map.MapView;
import net.openhft.chronicle.engine.api.tree.AssetTree;
import net.openhft.chronicle.engine.tree.AssetRuleProvider;
import net.openhft.chronicle.engine.tree.VanillaAssetRuleProvider;
import net.openhft.chronicle.wire.ValueIn;
import net.openhft.chronicle.wire.WireIn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/*
 * Created by Peter Lawrey on 26/08/15.
 */
public class EngineCfg implements Installable {
    static final Logger LOGGER = LoggerFactory.getLogger(EngineCfg.class);
    public final Map<String, Installable> installableMap = new LinkedHashMap<>();
    private AssetRuleProvider ruleProvider;

    @Nullable
    @Override
    public Void install(@NotNull String path, @NotNull AssetTree assetTree) throws Exception {
        LOGGER.info("Building Engine " + assetTree);
        for (@NotNull Map.Entry<String, Installable> entry : installableMap.entrySet()) {
            String path2 = entry.getKey();
            LOGGER.info("Installing " + path2 + ": " + entry.getValue());
            Object install = entry.getValue().install(path2, assetTree);
            if (install != null) {
                int pos = path2.lastIndexOf('/');
                @NotNull String parent = path2.substring(0, pos);
                @NotNull MapView<String, Object> map = assetTree.acquireMap(parent, String.class, Object.class);
                @NotNull String name = path2.substring(pos + 1);
                map.put(name, install);
            }
        }
        return null;
    }

    @Override
    public void readMarshallable(@NotNull WireIn wire) throws IllegalStateException {
        readMarshallable("", wire);
    }

    private void readMarshallable(String path, @NotNull WireIn wire) {
        @NotNull StringBuilder name = new StringBuilder();
        while (!wire.isEmpty()) {
            @NotNull ValueIn in = wire.read(name);
            long pos = wire.bytes().readPosition();
            @NotNull String path2 = path + "/" + name;
            if (path2.equals("/ruleProvider")) {
                ruleProvider = in.typedMarshallable();
            } else if (wire.getValueIn().isTyped()) {
                wire.bytes().readPosition(pos);
                @Nullable Object o = in.typedMarshallable();
                installableMap.put(path2, (Installable) o);
            } else {
                in.marshallable(w -> this.readMarshallable(path2, w));
            }
        }
    }

    public AssetRuleProvider getRuleProvider() {
        return Optional.ofNullable(ruleProvider).orElseGet(VanillaAssetRuleProvider::new);
    }
}
