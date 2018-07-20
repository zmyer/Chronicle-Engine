package net.openhft.chronicle.engine.api.column;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * @author Rob Austin.
 */
public class ChronicleQueueRow extends Row {
    private long index;
    private long seqNumber;

    /**
     * @param columns all the column names that make up this row
     */
    public ChronicleQueueRow(@NotNull List<Column> columns) {
        super(columns);
    }

    public void index(long index) {
        this.index = index;
    }

    public long index() {
        return index;
    }

    public long seqNumber() {
        return seqNumber;
    }

    public void seqNumber(long seqNumber) {
        this.seqNumber = seqNumber;
    }

}
