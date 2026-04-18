package com.strategysquad.derived;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Derives daily PCR snapshots from canonical historical options rows.
 */
public class PcrHistoricalAggregator {
    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;
    private static final LocalTime MARKET_CLOSE_IST = LocalTime.of(15, 30);
    private static final ZoneOffset IST_OFFSET = ZoneOffset.ofHoursMinutes(5, 30);

    public List<PcrHistoricalPoint> aggregate(List<HistoricalOptionSignalRow> rows) {
        Objects.requireNonNull(rows, "rows must not be null");
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<Key, Accumulator> accumulators = new LinkedHashMap<>();
        for (HistoricalOptionSignalRow row : rows) {
            Key key = new Key(row.tradeDate(), row.underlying());
            accumulators.computeIfAbsent(key, ignored -> new Accumulator()).add(row);
        }

        List<PcrHistoricalPoint> points = new ArrayList<>(accumulators.size());
        for (Map.Entry<Key, Accumulator> entry : accumulators.entrySet()) {
            Key key = entry.getKey();
            Accumulator accumulator = entry.getValue();
            points.add(new PcrHistoricalPoint(
                    key.tradeDate.atTime(MARKET_CLOSE_IST).toInstant(IST_OFFSET),
                    key.tradeDate,
                    key.underlying,
                    accumulator.pcrByVolume(),
                    accumulator.pcrByOpenInterest(),
                    accumulator.putVolume,
                    accumulator.callVolume,
                    accumulator.putOpenInterest,
                    accumulator.callOpenInterest,
                    accumulator.sampleCount
            ));
        }
        return points;
    }

    private record Key(java.time.LocalDate tradeDate, String underlying) {
    }

    private static final class Accumulator {
        private long putVolume;
        private long callVolume;
        private long putOpenInterest;
        private long callOpenInterest;
        private long sampleCount;

        void add(HistoricalOptionSignalRow row) {
            if ("PE".equals(row.optionType())) {
                putVolume += row.volume();
                putOpenInterest += row.openInterest();
            } else if ("CE".equals(row.optionType())) {
                callVolume += row.volume();
                callOpenInterest += row.openInterest();
            }
            sampleCount++;
        }

        BigDecimal pcrByVolume() {
            if (callVolume == 0L) {
                return null;
            }
            return BigDecimal.valueOf(putVolume).divide(BigDecimal.valueOf(callVolume), MATH_CONTEXT);
        }

        BigDecimal pcrByOpenInterest() {
            if (callOpenInterest == 0L) {
                return null;
            }
            return BigDecimal.valueOf(putOpenInterest).divide(BigDecimal.valueOf(callOpenInterest), MATH_CONTEXT);
        }
    }
}
