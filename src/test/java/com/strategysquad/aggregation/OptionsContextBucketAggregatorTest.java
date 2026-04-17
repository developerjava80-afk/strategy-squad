package com.strategysquad.aggregation;

import com.strategysquad.enrichment.OptionEnrichedTick;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionsContextBucketAggregatorTest {

    private static final MathContext MC = MathContext.DECIMAL64;

    @Test
    void aggregatesSingleContextGroup() {
        OptionEnrichedTick tick1 = enrichedTick("NIFTY", "CE", 6, 50, new BigDecimal("100"), new BigDecimal("21950"), 200);
        OptionEnrichedTick tick2 = enrichedTick("NIFTY", "CE", 6, 50, new BigDecimal("110"), new BigDecimal("21950"), 300);

        List<OptionsContextBucket> buckets = new OptionsContextBucketAggregator().aggregate(List.of(tick1, tick2));

        assertEquals(1, buckets.size());
        OptionsContextBucket bucket = buckets.get(0);
        assertEquals("NIFTY", bucket.underlying());
        assertEquals("CE", bucket.optionType());
        assertEquals(6, bucket.timeBucket15m());
        assertEquals(50, bucket.moneynessBucket());
        assertEquals(0, new BigDecimal("105").compareTo(bucket.avgOptionPrice()));
        // avg ratio = avg(100/21950, 110/21950) = avg(0.004556..., 0.005011...) = 0.004784...
        BigDecimal expectedRatio = new BigDecimal("100").divide(new BigDecimal("21950"), MC)
                .add(new BigDecimal("110").divide(new BigDecimal("21950"), MC))
                .divide(BigDecimal.valueOf(2), MC);
        assertEquals(0, expectedRatio.compareTo(bucket.avgPriceToSpotRatio()));
        assertEquals(0, new BigDecimal("250").compareTo(bucket.avgVolume()));
        assertEquals(2, bucket.sampleCount());
    }

    @Test
    void separatesByContextKey() {
        OptionEnrichedTick tick1 = enrichedTick("NIFTY", "CE", 6, 50, new BigDecimal("100"), new BigDecimal("21950"), 200);
        OptionEnrichedTick tick2 = enrichedTick("NIFTY", "PE", 6, 50, new BigDecimal("100"), new BigDecimal("21950"), 200);
        OptionEnrichedTick tick3 = enrichedTick("NIFTY", "CE", 7, 50, new BigDecimal("100"), new BigDecimal("21950"), 200);
        OptionEnrichedTick tick4 = enrichedTick("NIFTY", "CE", 6, 100, new BigDecimal("100"), new BigDecimal("21950"), 200);

        List<OptionsContextBucket> buckets = new OptionsContextBucketAggregator().aggregate(
                List.of(tick1, tick2, tick3, tick4));

        assertEquals(4, buckets.size());
    }

    @Test
    void returnsEmptyForEmptyInput() {
        assertTrue(new OptionsContextBucketAggregator().aggregate(List.of()).isEmpty());
    }

    private static OptionEnrichedTick enrichedTick(
            String underlying, String optionType, int timeBucket15m, int moneynessBucket,
            BigDecimal lastPrice, BigDecimal underlyingPrice, long volume
    ) {
        return new OptionEnrichedTick(
                Instant.parse("2026-04-30T08:30:00Z"),
                "INS_1",
                underlying,
                optionType,
                new BigDecimal("22000"),
                Instant.parse("2026-04-30T10:00:00Z"),
                lastPrice,
                underlyingPrice,
                timeBucket15m * 15,
                timeBucket15m,
                new BigDecimal("0.23"),
                new BigDecimal("50"),
                moneynessBucket,
                volume
        );
    }
}
