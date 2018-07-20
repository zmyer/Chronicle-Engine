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

import net.openhft.chronicle.engine.api.tree.AssetTree;
import net.openhft.chronicle.engine.fs.Clusters;
import net.openhft.chronicle.wire.AbstractMarshallable;
import net.openhft.chronicle.wire.WireIn;
import net.openhft.chronicle.wire.WireOut;
import org.jetbrains.annotations.NotNull;

/*
 * Created by Peter Lawrey on 26/08/15.
 */
public class ClustersCfg extends AbstractMarshallable implements Installable {
    public final Clusters clusters = new Clusters();

    @NotNull
    @Override
    public ClustersCfg install(@NotNull String path, @NotNull AssetTree assetTree) throws Exception {
        assetTree.root().addView(Clusters.class, clusters);
        clusters.install(assetTree);
        return this;
    }

    @Override
    public void readMarshallable(@NotNull WireIn wire) throws IllegalStateException {
        clusters.readMarshallable(wire);
    }

    @Override
    public void writeMarshallable(@NotNull WireOut wire) {
        clusters.writeMarshallable(wire);
    }
}
