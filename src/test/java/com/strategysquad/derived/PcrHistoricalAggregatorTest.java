package com.strategysquad.derived;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PcrHistoricalAggregatorTest {

    @Test
    void aggregatesDeterministicDailyPcr() {
        List<PcrHistoricalPoint> points = new PcrHistoricalAggregator().aggregate(List.of(
                new HistoricalOptionSignalRow(LocalDate.of(2026, 4, 17), "NIFTY", "PE", 300, 600),
                new HistoricalOptionSignalRow(LocalDate.of(2026, 4, 17), "NIFTY", "CE", 200, 400),
                new HistoricalOptionSignalRow(LocalDate.of(2026, 4, 17), "NIFTY", "PE", 100, 100)
        ));

        assertEquals(1, points.size());
        PcrHistoricalPoint point = points.get(0);
        assertEquals(new BigDecimal("2"), point.pcrByVolume().stripTrailingZeros());
        assertEquals(new BigDecimal("1.75"), point.pcrByOpenInterest().stripTrailingZeros());
        assertEquals(400, point.putVolume());
        assertEquals(200, point.callVolume());
        assertEquals(700, point.putOpenInterest());
        assertEquals(400, point.callOpenInterest());
        assertEquals(3, point.sampleCount());
    }

    @Test
    void leavesRatioNullWhenNoCallLegExists() {
        List<PcrHistoricalPoint> points = new PcrHistoricalAggregator().aggregate(List.of(
                new HistoricalOptionSignalRow(LocalDate.of(2026, 4, 17), "BANKNIFTY", "PE", 300, 600)
        ));

        assertNull(points.get(0).pcrByVolume());
        assertNull(points.get(0).pcrByOpenInterest());
    }
}
