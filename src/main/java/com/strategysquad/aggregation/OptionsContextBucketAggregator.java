package com.strategysquad.aggregation;

import com.strategysquad.enrichment.OptionEnrichedTick;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregates {@link OptionEnrichedTick} records into {@link OptionsContextBucket} rows.
 * Groups by {@code underlying} + {@code option_type} + {@code time_bucket_15m} + {@code moneyness_bucket}.
 */
public class OptionsContextBucketAggregator {
    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    public List<OptionsContextBucket> aggregate(List<OptionEnrichedTick> enrichedTicks) {
        Objects.requireNonNull(enrichedTicks, "enrichedTicks must not be null");
        if (enrichedTicks.isEmpty()) {
            return List.of();
        }

        Map<ContextKey, Accumulator> accumulators = new LinkedHashMap<>();
        for (OptionEnrichedTick tick : enrichedTicks) {
            ContextKey key = new ContextKey(
                    tick.underlying(),
                    tick.optionType(),
                    tick.timeBucket15m(),
                    tick.moneynessBucket()
            );
            accumulators.computeIfAbsent(key, k -> new Accumulator()).add(tick);
        }

        List<OptionsContextBucket> buckets = new ArrayList<>(accumulators.size());
        for (Map.Entry<ContextKey, Accumulator> entry : accumulators.entrySet()) {
            ContextKey key = entry.getKey();
            Accumulator acc = entry.getValue();
            buckets.add(new OptionsContextBucket(
                    acc.latestExchangeTs,
                    key.underlying,
                    key.optionType,
                    key.timeBucket15m,
                    key.moneynessBucket,
                    acc.avgOptionPrice(),
                    acc.avgPriceToSpotRatio(),
                    acc.avgVolume(),
                    acc.count
            ));
        }
        return buckets;
    }

    private record ContextKey(String underlying, String optionType, int timeBucket15m, int moneynessBucket) {
    }

    private static final class Accumulator {
        private BigDecimal priceSum = BigDecimal.ZERO;
        private BigDecimal ratioSum = BigDecimal.ZERO;
        private long volumeSum;
        private Instant latestExchangeTs;
        private long count;

        void add(OptionEnrichedTick tick) {
            priceSum = priceSum.add(tick.lastPrice(), MATH_CONTEXT);
            BigDecimal ratio = tick.lastPrice().divide(tick.underlyingPrice(), MATH_CONTEXT);
            ratioSum = ratioSum.add(ratio, MATH_CONTEXT);
            volumeSum += tick.volume();
            if (latestExchangeTs == null || tick.exchangeTs().isAfter(latestExchangeTs)) {
                latestExchangeTs = tick.exchangeTs();
            }
            count++;
        }

        BigDecimal avgOptionPrice() {
            return priceSum.divide(BigDecimal.valueOf(count), MATH_CONTEXT);
        }

        BigDecimal avgPriceToSpotRatio() {
            return ratioSum.divide(BigDecimal.valueOf(count), MATH_CONTEXT);
        }

        BigDecimal avgVolume() {
            return BigDecimal.valueOf(volumeSum).divide(BigDecimal.valueOf(count), MATH_CONTEXT);
        }
    }
}
