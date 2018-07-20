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

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.BytesStore;
import net.openhft.chronicle.core.pool.ClassAliasPool;
import net.openhft.chronicle.core.threads.ThreadDump;
import net.openhft.chronicle.engine.Factor;
import net.openhft.chronicle.wire.BinaryWire;
import net.openhft.chronicle.wire.TextWire;
import net.openhft.chronicle.wire.YamlLogging;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.Assert.assertEquals;

/*
 * Created by Peter Lawrey on 12/06/15.
 */
public class InsertedEventTest {
    static {
        ClassAliasPool.CLASS_ALIASES.addAlias(InsertedEvent.class, Factor.class);
    }

    private ThreadDump threadDump;

    @Before
    public void threadDump() {
        threadDump = new ThreadDump();
    }

    @After
    public void checkThreadDump() {
        threadDump.assertNoNewThreads();
    }

    @Test
    public void testMarshalling() {
        YamlLogging.setAll(false);
        Bytes bytes = Bytes.elasticByteBuffer();
        @NotNull InsertedEvent<String, String> insertedEvent = InsertedEvent.of("asset", "key", "name", false);
        @NotNull TextWire textWire = new TextWire(bytes);
        textWire.write(() -> "reply")
                .typedMarshallable(insertedEvent);
        System.out.println("text: " + bytes);
        @Nullable InsertedEvent ie = textWire.read(() -> "reply").typedMarshallable();
        assertEquals(insertedEvent, ie);
    }

    @Test
    public void testMarshalling2() {
        YamlLogging.setAll(false);
        Bytes bytes = Bytes.elasticByteBuffer();
        @NotNull InsertedEvent<String, Factor> insertedEvent = InsertedEvent.of("asset", "key", new Factor(), false);
        @NotNull TextWire textWire = new TextWire(bytes);
        textWire.write(() -> "reply")
                .typedMarshallable(insertedEvent);
        System.out.println("text: " + bytes);
        @Nullable InsertedEvent ie = textWire.read(() -> "reply").typedMarshallable();
        assertEquals(insertedEvent, ie);
    }

    @Test
    @Ignore("TODO Fix")
    public void testMarshalling3a() {
        YamlLogging.setAll(false);
        Bytes bytes = Bytes.elasticByteBuffer();
        @NotNull InsertedEvent<String, BytesStore> insertedEvent = InsertedEvent.of("asset", "key", BytesStore.wrap("£Hello World".getBytes(ISO_8859_1)), false);
        @NotNull TextWire textWire = new TextWire(bytes);
        textWire.write(() -> "reply")
                .typedMarshallable(insertedEvent);
        System.out.println("text: " + bytes);
        @Nullable InsertedEvent ie = textWire.read(() -> "reply").typedMarshallable();
        assertEquals(insertedEvent, ie);
    }

    @Test
    public void testMarshalling3() {
        YamlLogging.setAll(false);
        Bytes bytes = Bytes.elasticByteBuffer();
        @NotNull InsertedEvent<String, BytesStore> insertedEvent = InsertedEvent.of("asset", "key", BytesStore.wrap("Hello World".getBytes(ISO_8859_1)), false);
        @NotNull TextWire textWire = new TextWire(bytes);
        textWire.write(() -> "reply")
                .typedMarshallable(insertedEvent);
        System.out.println("text: " + bytes);
        @Nullable InsertedEvent ie = textWire.read(() -> "reply").typedMarshallable();
        insertedEvent.equals(ie);
        assertEquals(insertedEvent, ie);
    }

    @Test
    public void testMarshallingB() {
        Bytes bytes = Bytes.elasticByteBuffer();
        @NotNull InsertedEvent<String, String> insertedEvent = InsertedEvent.of("asset", "key", "name", false);
        @NotNull BinaryWire binaryWire = new BinaryWire(bytes);
        binaryWire.write(() -> "reply").typedMarshallable(insertedEvent);
        System.out.println("text: " + bytes);
        @Nullable InsertedEvent ie = binaryWire.read(() -> "reply").typedMarshallable();
        assertEquals(insertedEvent, ie);
    }

    @Test
    public void testMarshalling2B() {
        Bytes bytes = Bytes.elasticByteBuffer();
        @NotNull InsertedEvent<String, Factor> insertedEvent = InsertedEvent.of("asset", "key", new Factor(), false);
        @NotNull BinaryWire binaryWire = new BinaryWire(bytes);
        binaryWire.write(() -> "reply")
                .typedMarshallable(insertedEvent);
        System.out.println("text: " + bytes);
        @Nullable InsertedEvent ie = binaryWire.read(() -> "reply").typedMarshallable();
        assertEquals(insertedEvent, ie);
    }

    @Test
    public void testMarshalling3B() {
        Bytes bytes = Bytes.elasticByteBuffer();
        @NotNull InsertedEvent<String, BytesStore> insertedEvent = InsertedEvent.of("asset", "key", BytesStore.wrap("Hello World".getBytes(ISO_8859_1)), false);
        @NotNull BinaryWire binaryWire = new BinaryWire(bytes);
        binaryWire.write(() -> "reply")
                .typedMarshallable(insertedEvent);
        System.out.println("text: " + bytes);
        @Nullable InsertedEvent ie = binaryWire.read(() -> "reply")
                .typedMarshallable();
        assertEquals(insertedEvent, ie);
    }
}