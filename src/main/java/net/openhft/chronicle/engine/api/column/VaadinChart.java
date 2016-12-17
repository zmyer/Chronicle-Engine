package net.openhft.chronicle.engine.api.column;

/**
 * @author Rob Austin.
 */
public interface VaadinChart {

    /**
     * the chartProperties of the chart
     */
    ChartProperties chartProperties();

    /**
     * @return the name of the field in the column view that will be used to get the value of each
     * chartColumn
     */
    VaadinChartSeries[] series();

    /**
     * @return the name of the field in the column name that will be used to get the value of each
     * chartColumn
     */
    String columnNameField();

    ColumnViewInternal columnView();
}
