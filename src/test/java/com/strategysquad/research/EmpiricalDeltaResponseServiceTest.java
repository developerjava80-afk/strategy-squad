package com.strategysquad.research;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EmpiricalDeltaResponseServiceTest {

    private static final ZoneId IST = MarketSessionStateResolver.EXCHANGE_ZONE;

    @Test
    void computesDeterministicAtOrBeforeRegressionAcrossWindows() throws Exception {
        StubTickDataSource dataSource = new StubTickDataSource()
                .withUnderlying("NIFTY",
                        tick("2026-04-22T09:17:59+05:30", 100.0),
                        tick("2026-04-22T09:18:59+05:30", 101.0),
                        tick("2026-04-22T09:19:59+05:30", 102.0))
                .withOption("OPT-1",
                        tick("2026-04-22T09:18:00+05:30", 200.0),
                        tick("2026-04-22T09:19:00+05:30", 202.0),
                        tick("2026-04-22T09:20:00+05:30", 204.0));

        EmpiricalDeltaResponseService service = new EmpiricalDeltaResponseService(
                dataSource,
                new EmpiricalDeltaResponseService.DeltaResponseConfig(2, 2, 2, 5L, 0.50d, 0.01d),
                fixedClock("2026-04-22T09:20:01+05:30")
        );

        Map<String, EmpiricalDeltaResponseService.ContractDeltaResponse> responses = service.loadResponses(
                "NIFTY",
                List.of(optionKey("OPT-1")),
                instant("2026-04-22T09:20:01+05:30")
        );

        EmpiricalDeltaResponseService.ContractDeltaResponse response = responses.get("OPT-1");
        assertEquals(new BigDecimal("2.000000"), response.deltaResponse2m().slope());
        assertEquals(3, response.deltaResponse2m().observationCount());
        assertEquals(new BigDecimal("2.000000"), response.deltaResponse5m().slope());
        assertEquals(new BigDecimal("2.000000"), response.deltaResponseSod().slope());
        assertEquals(new BigDecimal("2.000000"), response.deltaResponseSod().underlyingMove());
    }

    @Test
    void doesNotUseFutureUnderlyingTicksForPairing() throws Exception {
        StubTickDataSource dataSource = new StubTickDataSource()
                .withUnderlying("NIFTY",
                        tick("2026-04-22T09:15:02+05:30", 100.0),
                        tick("2026-04-22T09:15:04+05:30", 101.0))
                .withOption("OPT-2",
                        tick("2026-04-22T09:15:01+05:30", 10.0),
                        tick("2026-04-22T09:15:03+05:30", 12.0),
                        tick("2026-04-22T09:15:05+05:30", 14.0));

        EmpiricalDeltaResponseService service = new EmpiricalDeltaResponseService(
                dataSource,
                new EmpiricalDeltaResponseService.DeltaResponseConfig(2, 2, 2, 5L, 0.50d, 0.01d),
                fixedClock("2026-04-22T09:15:06+05:30")
        );

        EmpiricalDeltaResponseService.ContractDeltaResponse response = service.loadResponses(
                "NIFTY",
                List.of(optionKey("OPT-2")),
                instant("2026-04-22T09:15:06+05:30")
        ).get("OPT-2");

        assertEquals(new BigDecimal("2.000000"), response.deltaResponse2m().slope());
        assertEquals(2, response.deltaResponse2m().observationCount());
    }

    @Test
    void returnsNullWhenUnderlyingVarianceIsTooSmallOrPairingIsStale() throws Exception {
        StubTickDataSource lowVarianceSource = new StubTickDataSource()
                .withUnderlying("NIFTY",
                        tick("2026-04-22T09:18:00+05:30", 100.0),
                        tick("2026-04-22T09:19:00+05:30", 100.0),
                        tick("2026-04-22T09:20:00+05:30", 100.0))
                .withOption("OPT-3",
                        tick("2026-04-22T09:18:00+05:30", 50.0),
                        tick("2026-04-22T09:19:00+05:30", 52.0),
                        tick("2026-04-22T09:20:00+05:30", 54.0));

        EmpiricalDeltaResponseService lowVarianceService = new EmpiricalDeltaResponseService(
                lowVarianceSource,
                new EmpiricalDeltaResponseService.DeltaResponseConfig(2, 2, 2, 5L, 0.10d, 0.01d),
                fixedClock("2026-04-22T09:20:01+05:30")
        );

        EmpiricalDeltaResponseService.ContractDeltaResponse lowVarianceResponse = lowVarianceService.loadResponses(
                "NIFTY",
                List.of(optionKey("OPT-3")),
                instant("2026-04-22T09:20:01+05:30")
        ).get("OPT-3");
        assertNull(lowVarianceResponse.deltaResponse2m());
        assertNull(lowVarianceResponse.deltaResponse5m());
        assertNull(lowVarianceResponse.deltaResponseSod());

        StubTickDataSource stalePairSource = new StubTickDataSource()
                .withUnderlying("NIFTY", tick("2026-04-22T09:18:00+05:30", 100.0))
                .withOption("OPT-4",
                        tick("2026-04-22T09:18:10+05:30", 50.0),
                        tick("2026-04-22T09:18:20+05:30", 52.0));

        EmpiricalDeltaResponseService staleService = new EmpiricalDeltaResponseService(
                stalePairSource,
                new EmpiricalDeltaResponseService.DeltaResponseConfig(2, 2, 2, 5L, 0.10d, 0.01d),
                fixedClock("2026-04-22T09:18:21+05:30")
        );

        EmpiricalDeltaResponseService.ContractDeltaResponse staleResponse = staleService.loadResponses(
                "NIFTY",
                List.of(optionKey("OPT-4")),
                instant("2026-04-22T09:18:21+05:30")
        ).get("OPT-4");
        assertNull(staleResponse.deltaResponse2m());
    }

    private static CanonicalPriceResolverService.InstrumentKey optionKey(String instrumentId) {
        return CanonicalPriceResolverService.InstrumentKey.option(
                instrumentId,
                "NIFTY",
                instrumentId,
                java.time.LocalDate.of(2026, 4, 30),
                new BigDecimal("22500"),
                "CE"
        );
    }

    private static EmpiricalDeltaResponseService.PriceTick tick(String exchangeLocalTs, double price) {
        return new EmpiricalDeltaResponseService.PriceTick(instant(exchangeLocalTs), price);
    }

    private static Clock fixedClock(String exchangeLocalTs) {
        return Clock.fixed(instant(exchangeLocalTs), IST);
    }

    private static Instant instant(String exchangeLocalTs) {
        return LocalDateTime.parse(exchangeLocalTs.substring(0, 19))
                .atOffset(java.time.OffsetDateTime.parse(exchangeLocalTs).getOffset())
                .toInstant();
    }

    private static final class StubTickDataSource implements EmpiricalDeltaResponseService.TickDataSource {
        private final Map<String, List<EmpiricalDeltaResponseService.PriceTick>> underlyingTicks = new HashMap<>();
        private final Map<String, List<EmpiricalDeltaResponseService.PriceTick>> optionTicks = new HashMap<>();

        private StubTickDataSource withUnderlying(String underlying, EmpiricalDeltaResponseService.PriceTick... ticks) {
            underlyingTicks.put(underlying, List.of(ticks));
            return this;
        }

        private StubTickDataSource withOption(String instrumentId, EmpiricalDeltaResponseService.PriceTick... ticks) {
            optionTicks.put(instrumentId, List.of(ticks));
            return this;
        }

        @Override
        public List<EmpiricalDeltaResponseService.PriceTick> loadUnderlyingTicks(
                String underlying,
                Instant fromInclusive,
                Instant toInclusive
        ) {
            return underlyingTicks.getOrDefault(underlying, List.of()).stream()
                    .filter(tick -> !tick.ts().isBefore(fromInclusive) && !tick.ts().isAfter(toInclusive))
                    .toList();
        }

        @Override
        public Map<String, List<EmpiricalDeltaResponseService.PriceTick>> loadOptionTicks(
                List<String> instrumentIds,
                Instant fromInclusive,
                Instant toInclusive
        ) throws SQLException {
            Map<String, List<EmpiricalDeltaResponseService.PriceTick>> filtered = new HashMap<>();
            for (String instrumentId : instrumentIds) {
                filtered.put(
                        instrumentId,
                        optionTicks.getOrDefault(instrumentId, List.of()).stream()
                                .filter(tick -> !tick.ts().isBefore(fromInclusive) && !tick.ts().isAfter(toInclusive))
                                .toList()
                );
            }
            return filtered;
        }
    }
}
