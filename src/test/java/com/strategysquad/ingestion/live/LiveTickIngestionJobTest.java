package com.strategysquad.ingestion.live;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LiveTickIngestionJobTest {

    @Test
    void ingestsSpotAndOptionsTransactionally() throws Exception {
        JdbcRecordingSupport.ConnectionRecorder connectionRecorder =
                new JdbcRecordingSupport.ConnectionRecorder(null, true);
        TrackingOptionsLiveWriter optionsWriter = new TrackingOptionsLiveWriter(2);
        TrackingSpotLiveWriter spotWriter = new TrackingSpotLiveWriter(1, false);

        LiveTickIngestionJob.IngestionResult result = new LiveTickIngestionJob(optionsWriter, spotWriter).ingest(
                connectionRecorder.proxy(),
                List.of(optionTick(), optionTick()),
                List.of(spotTick())
        );

        assertEquals(new LiveTickIngestionJob.IngestionResult(2, 2, 1, 1), result);
        assertEquals(List.of("setAutoCommit:false", "commit", "setAutoCommit:true"), connectionRecorder.events());
        assertEquals(1, optionsWriter.calls);
        assertEquals(1, spotWriter.calls);
    }

    @Test
    void rollsBackWhenWriterFails() {
        JdbcRecordingSupport.ConnectionRecorder connectionRecorder =
                new JdbcRecordingSupport.ConnectionRecorder(null, true);
        TrackingOptionsLiveWriter optionsWriter = new TrackingOptionsLiveWriter(0);
        TrackingSpotLiveWriter spotWriter = new TrackingSpotLiveWriter(0, true);

        assertThrows(SQLException.class, () -> new LiveTickIngestionJob(optionsWriter, spotWriter).ingest(
                connectionRecorder.proxy(),
                List.of(optionTick()),
                List.of(spotTick())
        ));

        assertEquals(List.of("setAutoCommit:false", "rollback", "setAutoCommit:true"), connectionRecorder.events());
        assertEquals(0, optionsWriter.calls);
        assertEquals(1, spotWriter.calls);
    }

    private static OptionLiveTick optionTick() {
        return new OptionLiveTick(
                Instant.parse("2026-04-17T09:15:00Z"),
                Instant.parse("2026-04-17T09:15:01Z"),
                "NIFTY-20260430-22000-CE",
                "NIFTY",
                new BigDecimal("102.50"),
                new BigDecimal("102.45"),
                new BigDecimal("102.55"),
                125,
                900
        );
    }

    private static SpotLiveTick spotTick() {
        return new SpotLiveTick(
                Instant.parse("2026-04-17T09:15:00Z"),
                Instant.parse("2026-04-17T09:15:01Z"),
                "NIFTY",
                new BigDecimal("24210.15")
        );
    }

    private static final class TrackingOptionsLiveWriter extends OptionsLiveWriter {
        private final int returnValue;
        private int calls;

        private TrackingOptionsLiveWriter(int returnValue) {
            this.returnValue = returnValue;
        }

        @Override
        public int write(java.sql.Connection connection, List<OptionLiveTick> ticks) {
            calls++;
            return returnValue;
        }
    }

    private static final class TrackingSpotLiveWriter extends SpotLiveWriter {
        private final int returnValue;
        private final boolean fail;
        private int calls;

        private TrackingSpotLiveWriter(int returnValue, boolean fail) {
            this.returnValue = returnValue;
            this.fail = fail;
        }

        @Override
        public int write(java.sql.Connection connection, List<SpotLiveTick> ticks) throws SQLException {
            calls++;
            if (fail) {
                throw new SQLException("boom");
            }
            return returnValue;
        }
    }
}
