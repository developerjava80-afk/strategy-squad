package com.strategysquad.aggregation;

import com.strategysquad.enrichment.OptionEnrichedTick;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Options15mBucketAggregatorTest {

    @Test
    void aggregatesSingleContractSingleBucket() {
        OptionEnrichedTick tick1 = enrichedTick("INS_1", 6, new BigDecimal("100"), 200);
        OptionEnrichedTick tick2 = enrichedTick("INS_1", 6, new BigDecimal("110"), 300);

        List<Options15mBucket> buckets = new Options15mBucketAggregator().aggregate(List.of(tick1, tick2));

        assertEquals(1, buckets.size());
        Options15mBucket bucket = buckets.get(0);
        assertEquals("INS_1", bucket.instrumentId());
        assertEquals(6, bucket.timeBucket15m());
        assertEquals(new BigDecimal("100"), bucket.minPrice());
        assertEquals(new BigDecimal("110"), bucket.maxPrice());
        assertEquals(0, new BigDecimal("105").compareTo(bucket.avgPrice()));
        assertEquals(500, bucket.volumeSum());
        assertEquals(2, bucket.sampleCount());
        assertEquals(LocalDate.of(2026, 4, 30), bucket.tradeDate());
    }

    @Test
    void separatesBucketsByInstrumentAndTimeBucket() {
        OptionEnrichedTick tick1 = enrichedTick("INS_1", 6, new BigDecimal("100"), 200);
        OptionEnrichedTick tick2 = enrichedTick("INS_1", 7, new BigDecimal("110"), 300);
        OptionEnrichedTick tick3 = enrichedTick("INS_2", 6, new BigDecimal("200"), 400);

        List<Options15mBucket> buckets = new Options15mBucketAggregator().aggregate(List.of(tick1, tick2, tick3));

        assertEquals(3, buckets.size());
    }

    @Test
    void returnsEmptyForEmptyInput() {
        assertTrue(new Options15mBucketAggregator().aggregate(List.of()).isEmpty());
    }

    private static OptionEnrichedTick enrichedTick(String instrumentId, int timeBucket15m, BigDecimal lastPrice, long volume) {
        return new OptionEnrichedTick(
                Instant.parse("2026-04-30T08:30:00Z"),
                instrumentId,
                "NIFTY",
                "CE",
                new BigDecimal("22000"),
                Instant.parse("2026-04-30T10:00:00Z"),
                lastPrice,
                new BigDecimal("21950"),
                timeBucket15m * 15,
                timeBucket15m,
                new BigDecimal("0.23"),
                new BigDecimal("50"),
                50,
                volume
        );
    }
}
