package com.strategysquad.aggregation;

import com.strategysquad.enrichment.OptionEnrichedTick;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregates {@link OptionEnrichedTick} records into {@link Options15mBucket} rows.
 * Groups by {@code instrument_id} + {@code time_bucket_15m} within each trade date.
 */
public class Options15mBucketAggregator {
    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;
    private static final ZoneOffset IST_OFFSET = ZoneOffset.ofHoursMinutes(5, 30);

    public List<Options15mBucket> aggregate(List<OptionEnrichedTick> enrichedTicks) {
        Objects.requireNonNull(enrichedTicks, "enrichedTicks must not be null");
        if (enrichedTicks.isEmpty()) {
            return List.of();
        }

        Map<BucketKey, Accumulator> accumulators = new LinkedHashMap<>();
        for (OptionEnrichedTick tick : enrichedTicks) {
            LocalDate tradeDate = tick.exchangeTs().atOffset(IST_OFFSET).toLocalDate();
            BucketKey key = new BucketKey(tick.instrumentId(), tick.timeBucket15m(), tradeDate);
            accumulators.computeIfAbsent(key, k -> new Accumulator()).add(tick);
        }

        List<Options15mBucket> buckets = new ArrayList<>(accumulators.size());
        for (Map.Entry<BucketKey, Accumulator> entry : accumulators.entrySet()) {
            BucketKey key = entry.getKey();
            Accumulator acc = entry.getValue();
            buckets.add(new Options15mBucket(
                    acc.latestExchangeTs,
                    key.tradeDate,
                    key.instrumentId,
                    key.timeBucket15m,
                    acc.avgPrice(),
                    acc.minPrice,
                    acc.maxPrice,
                    acc.volumeSum,
                    acc.count
            ));
        }
        return buckets;
    }

    private record BucketKey(String instrumentId, int timeBucket15m, LocalDate tradeDate) {
    }

    private static final class Accumulator {
        private BigDecimal priceSum = BigDecimal.ZERO;
        private BigDecimal minPrice;
        private BigDecimal maxPrice;
        private Instant latestExchangeTs;
        private long volumeSum;
        private long count;

        void add(OptionEnrichedTick tick) {
            priceSum = priceSum.add(tick.lastPrice(), MATH_CONTEXT);
            if (minPrice == null || tick.lastPrice().compareTo(minPrice) < 0) {
                minPrice = tick.lastPrice();
            }
            if (maxPrice == null || tick.lastPrice().compareTo(maxPrice) > 0) {
                maxPrice = tick.lastPrice();
            }
            if (latestExchangeTs == null || tick.exchangeTs().isAfter(latestExchangeTs)) {
                latestExchangeTs = tick.exchangeTs();
            }
            volumeSum += tick.volume();
            count++;
        }

        BigDecimal avgPrice() {
            return priceSum.divide(BigDecimal.valueOf(count), MATH_CONTEXT);
        }
    }
}
