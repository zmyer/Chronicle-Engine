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

import net.openhft.chronicle.engine.server.ServerEndpoint;
import net.openhft.chronicle.engine.tree.VanillaAssetTree;
import net.openhft.chronicle.wire.WireType;
import net.openhft.chronicle.wire.YamlLogging;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Created by andre on 01/05/2015.
 */
public class BinaryWireMain {

    public static final net.openhft.chronicle.wire.WireType WIRE_TYPE = WireType.BINARY;

    public static void main(@NotNull String[] args) throws IOException {
        int port = 9090;
        // YamlLogging.setAll(true);
        @NotNull VanillaAssetTree assetTree = new VanillaAssetTree().forTesting(false);
        @NotNull final ServerEndpoint serverEndpoint = new ServerEndpoint("*:" + port, assetTree);

        if (args.length == 1 && args[0].compareTo("-debug") == 0) {
            System.out.println("Enabling message logging");
            YamlLogging.showServerReads(true);
            YamlLogging.showServerWrites(true);
        }
        System.out.println("Server port seems to be " + port);
    }
}
