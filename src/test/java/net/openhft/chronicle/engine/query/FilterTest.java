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

package net.openhft.chronicle.engine.query;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.core.pool.ClassAliasPool;
import net.openhft.chronicle.engine.ThreadMonitoringTest;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.wire.BinaryWire;
import net.openhft.chronicle.wire.TextWire;
import net.openhft.chronicle.wire.Wire;
import net.openhft.chronicle.wire.WireType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @author Rob Austin.
 */

@RunWith(value = Parameterized.class)
public class FilterTest extends ThreadMonitoringTest {

    static {
        // dummy call to ensure all the aliases are initialised.
        RequestContext.requestContext();
        ClassAliasPool.CLASS_ALIASES.addAlias(FilterTest.class);
    }

    private final WireType wireType;

    @NotNull
    @Rule
    public TestName name = new TestName();

    public FilterTest(WireType wireType) {
        this.wireType = wireType;
    }

    @NotNull
    @Parameterized.Parameters
    public static Collection<Object[]> data() {

        @NotNull final List<Object[]> list = new ArrayList<>();
//            list.add(new Object[]{WireType.RAW});
        list.add(new Object[]{WireType.TEXT});
        list.add(new Object[]{WireType.BINARY});
        return list;
    }

    @Test
    public void test() {

        final Bytes b = Bytes.elasticByteBuffer();
        final Wire wire = wireType.apply(b);

        @NotNull Filter<String> expected = new Filter<>();
        expected.addFilter(o -> true);

        wire.write(() -> "filter").object(expected);

        if (wireType == WireType.TEXT) {
            System.out.println(wireType + ": " + b);
        } else if (wireType == WireType.BINARY) {
            final Bytes b2 = Bytes.elasticByteBuffer();
            Bytes bytes = b.bytesForRead();
            new BinaryWire(bytes).copyTo(new TextWire(b2));
            System.out.println(wireType + ": " + b2);
        }
        @Nullable final Filter actual = wire.read(() -> "filter").object(Filter.class);

        assert actual != null;
        Assert.assertEquals(1, actual.pipelineSize());
        Assert.assertEquals(Operation.OperationType.FILTER, actual.getPipeline(0).op());
    }
}
