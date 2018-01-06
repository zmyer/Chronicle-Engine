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

package net.openhft.chronicle.engine.session;

import net.openhft.chronicle.network.api.session.SessionDetails;
import net.openhft.chronicle.network.api.session.SessionProvider;
import org.jetbrains.annotations.NotNull;

/*
 * Created by Peter Lawrey on 01/06/15.
 */
public class VanillaSessionProvider implements SessionProvider {
    @NotNull
    private final ThreadLocal<SessionDetails> sessionDetails = new ThreadLocal<>();

    public VanillaSessionProvider() {

    }

    @Override
    public SessionDetails get() {
        return this.sessionDetails.get();
    }

    @Override
    public void set(@NotNull SessionDetails sessionDetails) {
        this.sessionDetails.set(sessionDetails);
    }

    @Override
    public void remove() {
        sessionDetails.remove();
    }

    @NotNull
    @Override
    public String toString() {
        return "VanillaSessionProvider{" +
                "sessionDetails=" + sessionDetails.get() +
                '}';
    }
}
