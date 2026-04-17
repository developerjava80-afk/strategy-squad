package com.strategysquad.enrichment;

import com.strategysquad.ingestion.live.SpotLiveTick;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotLiveLookupTest {

    @Test
    void resolvesLatestSpotAtOrBeforeOptionExchangeTime() throws Exception {
        EnrichmentJdbcTestSupport.PreparedStatementRecorder statementRecorder =
                new EnrichmentJdbcTestSupport.PreparedStatementRecorder(
                        new int[0],
                        EnrichmentJdbcTestSupport.resultSet(List.of(Map.of(
                                "exchange_ts", Timestamp.from(Instant.parse("2026-04-17T09:15:00Z")),
                                "ingest_ts", Timestamp.from(Instant.parse("2026-04-17T09:15:01Z")),
                                "underlying", "NIFTY",
                                "last_price", new BigDecimal("24210.15")
                        )))
                );

        SpotLiveLookup lookup = new SpotLiveLookup();
        SpotLiveTick spotTick = lookup.findLatestAtOrBefore(
                        new EnrichmentJdbcTestSupport.ConnectionRecorder(statementRecorder.proxy(), true).proxy(),
                        "NIFTY",
                        Instant.parse("2026-04-17T09:15:02Z")
                )
                .orElseThrow();

        assertEquals("NIFTY", spotTick.underlying());
        assertEquals(new BigDecimal("24210.15"), spotTick.lastPrice());
        assertEquals("NIFTY", statementRecorder.currentParameters().get(1));
        assertEquals(Timestamp.from(Instant.parse("2026-04-17T09:15:02Z")), statementRecorder.currentParameters().get(2));
    }

    @Test
    void returnsEmptyWhenNoSpotExists() throws Exception {
        EnrichmentJdbcTestSupport.PreparedStatementRecorder statementRecorder =
                new EnrichmentJdbcTestSupport.PreparedStatementRecorder(new int[0], EnrichmentJdbcTestSupport.resultSet(List.of()));

        assertTrue(new SpotLiveLookup().findLatestAtOrBefore(
                new EnrichmentJdbcTestSupport.ConnectionRecorder(statementRecorder.proxy(), true).proxy(),
                "NIFTY",
                Instant.parse("2026-04-17T09:15:02Z")
        ).isEmpty());
    }
}
