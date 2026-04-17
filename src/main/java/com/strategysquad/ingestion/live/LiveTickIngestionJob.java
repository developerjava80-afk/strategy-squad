package com.strategysquad.ingestion.live;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.List;
import java.util.Objects;

/**
 * Persists canonical live ticks into the raw Golden Source live tables.
 */
public class LiveTickIngestionJob {
    private final OptionsLiveWriter optionsLiveWriter;
    private final SpotLiveWriter spotLiveWriter;

    public LiveTickIngestionJob() {
        this(new OptionsLiveWriter(), new SpotLiveWriter());
    }

    public LiveTickIngestionJob(OptionsLiveWriter optionsLiveWriter, SpotLiveWriter spotLiveWriter) {
        this.optionsLiveWriter = Objects.requireNonNull(optionsLiveWriter, "optionsLiveWriter must not be null");
        this.spotLiveWriter = Objects.requireNonNull(spotLiveWriter, "spotLiveWriter must not be null");
    }

    public IngestionResult ingest(
            Connection connection,
            List<OptionLiveTick> optionTicks,
            List<SpotLiveTick> spotTicks
    ) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(optionTicks, "optionTicks must not be null");
        Objects.requireNonNull(spotTicks, "spotTicks must not be null");

        boolean autoCommit = connection.getAutoCommit();
        Savepoint savepoint = null;
        if (autoCommit) {
            connection.setAutoCommit(false);
        } else {
            savepoint = connection.setSavepoint("live_tick_ingestion_write");
        }

        try {
            int spotInserted = spotLiveWriter.write(connection, spotTicks);
            int optionsInserted = optionsLiveWriter.write(connection, optionTicks);
            if (autoCommit) {
                connection.commit();
            }
            return new IngestionResult(optionTicks.size(), optionsInserted, spotTicks.size(), spotInserted);
        } catch (SQLException ex) {
            if (autoCommit) {
                connection.rollback();
            } else if (savepoint != null) {
                connection.rollback(savepoint);
            }
            throw ex;
        } finally {
            if (!autoCommit && savepoint != null) {
                connection.releaseSavepoint(savepoint);
            }
            if (autoCommit) {
                connection.setAutoCommit(true);
            }
        }
    }

    public record IngestionResult(
            int optionTicksReceived,
            int optionTicksInserted,
            int spotTicksReceived,
            int spotTicksInserted
    ) {
    }
}
