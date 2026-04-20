package com.strategysquad.ingestion.live.session;

import com.strategysquad.enrichment.OptionEnrichedTick;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory rolling 15-minute aggregator for the current trading session.
 *
 * <p>Accumulates enriched ticks per (instrumentId, timeBucket15m). When the
 * timeBucket15m decreases for an instrument (time has moved into the next lower
 * bucket), the previous bucket is finalized and written to {@code options_live_15m}.
 *
 * <p>Not thread-safe — must be called from a single dispatch thread (the ticker
 * callback thread) or externally synchronized.
 */
public final class Live15mAggregator {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final Live15mWriter writer;
    private final Map<String, BucketAccumulator> open = new HashMap<>();

    public Live15mAggregator(Live15mWriter writer) {
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
    }

    /**
     * Accepts one enriched tick. If the tick belongs to a new (lower) bucket for
     * its instrument, the previous open bucket is finalized and flushed to DB.
     */
    public void accept(Connection connection, OptionEnrichedTick tick) throws SQLException {
        Objects.requireNonNull(tick, "tick must not be null");
        String key = tick.instrumentId();
        BucketAccumulator acc = open.get(key);

        if (acc != null && acc.timeBucket15m != tick.timeBucket15m()) {
            // Bucket has rolled — flush the completed one
            List<Live15mBucket> toFlush = List.of(acc.toBucket());
            writer.write(connection, toFlush);
            open.remove(key);
            acc = null;
        }

        if (acc == null) {
            acc = new BucketAccumulator(tick.instrumentId(), tick.timeBucket15m(), tick.exchangeTs());
            open.put(key, acc);
        }
        acc.add(tick.lastPrice().doubleValue(), tick.volume(), tick.exchangeTs());
    }

    /**
     * Returns a snapshot of all currently open (in-progress) buckets without flushing.
     * Used by the REST API to return the live partial bucket for the current 15m window.
     */
    public List<Live15mBucket> openBuckets(LocalDate sessionDate) {
        List<Live15mBucket> result = new ArrayList<>(open.size());
        for (BucketAccumulator acc : open.values()) {
            result.add(acc.toBucketForDate(sessionDate));
        }
        return result;
    }

    /**
     * Flushes all open buckets to DB (called at end of session or shutdown).
     */
    public void flushAll(Connection connection, LocalDate sessionDate) throws SQLException {
        if (open.isEmpty()) return;
        List<Live15mBucket> all = new ArrayList<>(open.size());
        for (BucketAccumulator acc : open.values()) {
            all.add(acc.toBucketForDate(sessionDate));
        }
        writer.write(connection, all);
        open.clear();
    }

    private static final class BucketAccumulator {
        final String instrumentId;
        final int timeBucket15m;
        final Instant bucketOpenTs;
        double sum;
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        long volumeSum;
        long sampleCount;
        Instant lastTs;

        BucketAccumulator(String instrumentId, int timeBucket15m, Instant bucketOpenTs) {
            this.instrumentId = instrumentId;
            this.timeBucket15m = timeBucket15m;
            this.bucketOpenTs = bucketOpenTs;
            this.lastTs = bucketOpenTs;
        }

        void add(double price, long volume, Instant ts) {
            sum += price;
            if (price < min) min = price;
            if (price > max) max = price;
            volumeSum += volume;
            sampleCount++;
            lastTs = ts;
        }

        Live15mBucket toBucket() {
            return toBucketForDate(LocalDate.ofInstant(bucketOpenTs, IST));
        }

        Live15mBucket toBucketForDate(LocalDate sessionDate) {
            double avg = sampleCount > 0 ? sum / sampleCount : 0.0;
            return new Live15mBucket(
                    bucketOpenTs,
                    sessionDate,
                    instrumentId,
                    timeBucket15m,
                    avg,
                    min == Double.MAX_VALUE ? 0.0 : min,
                    max == Double.MIN_VALUE ? 0.0 : max,
                    volumeSum,
                    sampleCount,
                    lastTs
            );
        }
    }
}
