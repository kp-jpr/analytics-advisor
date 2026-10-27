package gov.rajasthan.smart.srse.analysis;

import java.util.List;

/** Match definition plus the output columns to group for a chart. */
public record ChartBucketsRequest(RecordMatchRequest match, List<String> columns) {

    public ChartBucketsRequest {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
