/*
 *     Copyright (C) 2015  higherfrequencytrading.com
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.engine.chronicle.demo.data;

import net.openhft.chronicle.wire.Marshallable;
import net.openhft.chronicle.wire.WireIn;
import net.openhft.chronicle.wire.WireOut;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

/*
 * Created by Peter Lawrey on 27/08/15.
 */
public class EndOfDay implements Marshallable, Serializable {
    long daysVolume;
    //    Symbol,Name,Open,High,Low,Close,Net Chg,% Chg,Volume,52 Wk High,52 Wk Low,Div,Yield,P/E,YTD % Chg
//    AIR,AAR Corp.,18.92,19.28,18.74,19.26,0.83,4.5,415272,26.08,14.24,,,11.67,-16.19
    private String name;
    private double openingPrice, highPrice, lowPrice, closingPrice, change, changePercent, high52, low52, div, yield, pe, ytdPercentChange;

    @Override
    public void readMarshallable(@NotNull WireIn wire) throws IllegalStateException {
        wire.read(() -> "name").text(this, (o, s) -> o.name = s)
                .read(() -> "openingPrice").float64(this, (o, d) -> o.openingPrice = d)
                .read(() -> "highPrice").float64(this, (o, d) -> o.highPrice = d)
                .read(() -> "lowPrice").float64(this, (o, d) -> o.lowPrice = d)
                .read(() -> "closingPrice").float64(this, (o, d) -> o.closingPrice = d)
                .read(() -> "change").float64(this, (o, d) -> o.change = d)
                .read(() -> "changePercent").float64(this, (o, d) -> o.changePercent = d)
                .read(() -> "daysVolume").int64(this, (o, d) -> o.daysVolume = d)
                .read(() -> "high52").float64(this, (o, d) -> o.high52 = d)
                .read(() -> "low52").float64(this, (o, d) -> o.low52 = d)
                .read(() -> "div").float64(this, (o, d) -> o.div = d)
                .read(() -> "yield").float64(this, (o, d) -> o.yield = d)
                .read(() -> "pe").float64(this, (o, d) -> o.pe = d)
                .read(() -> "closingPrice").float64(this, (o, d) -> o.closingPrice = d)
        ;
    }

    @Override
    public void writeMarshallable(WireOut wire) {
        wire.write(() -> "name").text(name)
                .write(() -> "openingPrice").float64(openingPrice)
                .write(() -> "highPrice").float64(highPrice)
                .write(() -> "lowPrice").float64(lowPrice)
                .write(() -> "closingPrice").float64(closingPrice)
                .write(() -> "change").float64(change)
                .write(() -> "changePercent").float64(changePercent)
                .write(() -> "daysVolume").int64(daysVolume)
                .write(() -> "high52").float64(high52)
                .write(() -> "low52").float64(low52)
                .write(() -> "div").float64(div)
                .write(() -> "yield").float64(yield)
                .write(() -> "pe").float64(pe)
                .write(() -> "ytdPercentChange").float64(ytdPercentChange);
    }

    @Override
    public String toString() {
        return "EndOfDay{" +
                "name='" + name + '\'' +
                ", openingPrice=" + openingPrice +
                ", highPrice=" + highPrice +
                ", lowPrice=" + lowPrice +
                ", closingPrice=" + closingPrice +
                ", change=" + change +
                ", changePercent=" + changePercent +
                ", high52=" + high52 +
                ", low52=" + low52 +
                ", div=" + div +
                ", yield=" + yield +
                ", pe=" + pe +
                ", ytdPercentChange=" + ytdPercentChange +
                ", daysVolume=" + daysVolume +
                '}';
    }
}
