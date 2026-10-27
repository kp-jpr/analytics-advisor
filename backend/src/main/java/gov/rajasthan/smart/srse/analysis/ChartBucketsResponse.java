package gov.rajasthan.smart.srse.analysis;

import java.util.List;

/** Full-result value counts for a chart. The screen preview is not the source of these numbers. */
public record ChartBucketsResponse(
        long totalRows,
        boolean truncated,
        List<ChartBucket> buckets) {

    public record ChartBucket(List<String> values, long count, boolean other) {
    }
}
