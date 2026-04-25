package com.strategysquad.research;

import com.strategysquad.ingestion.live.session.LiveSessionState;
import com.strategysquad.support.QuestDbConnectionFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Replays a day's live tick data from QuestDB into {@link LiveSessionState}.
 *
 * <p>Feeds {@code spot_live} and {@code options_live} rows for a chosen date back through
 * the same {@code updateSpot}/{@code updateQuote} paths used during live trading, so all
 * downstream services (price resolution, delta response, etc.) treat replay data exactly
 * as live data.  No live trading code path is modified; the only difference is that
 * {@link SimulationClock} is fixed to a market-hours instant on the replay date so that
 * {@link MarketSessionStateResolver} returns {@code LIVE_MARKET}.
 *
 * <p>All spot and option ticks for the chosen date are loaded upfront into a unified
 * sorted timeline.  The replay loop iterates over this timeline with no DB I/O, so
 * both spot and option prices advance in lock-step regardless of any timestamp skew
 * between the two tables.
 */
public final class HistoricalReplayService {

    private static final LocalTime REPLAY_START = MarketSessionStateResolver.MARKET_OPEN;
    private static final LocalTime REPLAY_END   = LocalTime.of(15, 35);

    private static final String SPOT_SQL =
            "SELECT exchange_ts, underlying, last_price"
            + " FROM spot_live"
            + " WHERE exchange_ts >= ? AND exchange_ts <= ?"
            + " ORDER BY exchange_ts ASC";

    private static final String OPTIONS_SQL =
            "SELECT exchange_ts, instrument_id, last_price, bid_price, ask_price, volume, open_interest"
            + " FROM options_live"
            + " WHERE exchange_ts >= ? AND exchange_ts <= ?"
            + " ORDER BY exchange_ts ASC";

    private final String jdbcUrl;
    private final LiveSessionState sessionState;
    private final SimulationClock simulationClock;
    private final BatchLoader batchLoader;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<LocalDate> replayDate = new AtomicReference<>(null);
    private final AtomicInteger currentSpeed = new AtomicInteger(1);
    private final AtomicInteger ticksReplayed = new AtomicInteger(0);
    private final AtomicInteger totalTicks = new AtomicInteger(0);
    private final AtomicReference<Instant> currentSimTime = new AtomicReference<>(null);
    private volatile Thread replayThread;

    public HistoricalReplayService(String jdbcUrl, LiveSessionState sessionState, SimulationClock simulationClock) {
        this(jdbcUrl, sessionState, simulationClock, null);
    }

    HistoricalReplayService(
            String jdbcUrl,
            LiveSessionState sessionState,
            SimulationClock simulationClock,
            BatchLoader batchLoader
    ) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
        this.sessionState = Objects.requireNonNull(sessionState, "sessionState must not be null");
        this.simulationClock = Objects.requireNonNull(simulationClock, "simulationClock must not be null");
        this.batchLoader = batchLoader != null ? batchLoader : this::loadUnifiedBatchesFromDb;
    }

    // -------------------------------------------------------------------------
    // Public control API
    // -------------------------------------------------------------------------

    /**
     * Starts replaying {@code date} at {@code speed}× real time (1 = real-time, 10 = 10×, etc.).
     *
     * @throws IllegalStateException if simulation is already running or no data exists for the date
     */
    public synchronized void start(LocalDate date, int speed) {
        Objects.requireNonNull(date, "date must not be null");
        if (running.get()) {
            throw new IllegalStateException("Simulation already running – stop it first");
        }

        // Load spot + option ticks into a unified sorted timeline
        List<UnifiedBatch> batches;
        try {
            batches = batchLoader.load(date);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to query tick data for " + date + ": " + exception.getMessage(), exception);
        }
        if (batches.isEmpty()) {
            throw new IllegalStateException("No spot_live data found for " + date + ". Replay requires at least one recorded trading day.");
        }

        long spotCount = batches.stream().mapToLong(b -> b.spotTicks().size()).sum();
        long optCount  = batches.stream().mapToLong(b -> b.optionTicks().size()).sum();
        System.out.printf("[simulation] loaded %d unified batches (%d spot, %d option ticks) for %s%n",
                batches.size(), spotCount, optCount, date);

        replayDate.set(date);
        currentSpeed.set(Math.max(1, Math.min(speed, 200)));
        ticksReplayed.set(0);
        totalTicks.set(batches.size());
        simulationClock.setSimulatedDate(date);
        sessionState.resetForLogin();
        sessionState.setStatus(LiveSessionState.Status.CONNECTED);
        primeFirstBatch(batches.get(0));
        running.set(true);

        replayThread = new Thread(
                () -> replayLoop(date, batches),
                "simulation-replay-" + date
        );
        replayThread.setDaemon(true);
        replayThread.start();
    }

    /**
     * Stops the active simulation and resets the clock back to system time.
     * Safe to call even when no simulation is running.
     */
    public synchronized void stop() {
        running.set(false);
        simulationClock.reset();
        currentSimTime.set(null);
        Thread thread = replayThread;
        if (thread != null) {
            thread.interrupt();
            replayThread = null;
        }
    }

    /** Returns the current simulation status as a snapshot. */
    public SimulationStatus getStatus() {
        Instant simTs = currentSimTime.get();
        String timeIst = simTs == null ? null
                : simTs.atZone(MarketSessionStateResolver.EXCHANGE_ZONE)
                        .toLocalTime()
                        .toString();
        LocalDate date = replayDate.get();
        return new SimulationStatus(
                running.get(),
                date == null ? null : date.toString(),
                timeIst,
                currentSpeed.get(),
                ticksReplayed.get(),
                totalTicks.get()
        );
    }

    // -------------------------------------------------------------------------
    // Replay loop  (no DB I/O – all data pre-loaded)
    // -------------------------------------------------------------------------

    private void replayLoop(LocalDate date, List<UnifiedBatch> batches) {
        try {
            for (int index = 1; index < batches.size(); index++) {
                if (!running.get()) {
                    break;
                }
                applyBatch(batches.get(index));

                long sleepMs = Math.max(1L, 2_000L / currentSpeed.get());
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            running.set(false);
            simulationClock.reset();
            currentSimTime.set(null);
            System.out.printf("[simulation] replay of %s finished (%d/%d ticks)%n",
                    date, ticksReplayed.get(), totalTicks.get());
        }
    }

    private void primeFirstBatch(UnifiedBatch batch) {
        applyBatch(batch);
    }

    private void applyBatch(UnifiedBatch batch) {
        simulationClock.setSimulatedInstant(batch.ts());

        for (SpotTick spot : batch.spotTicks()) {
            sessionState.updateSpot(
                    spot.underlying(),
                    BigDecimal.valueOf(spot.lastPrice()),
                    batch.ts()
            );
        }

        for (OptionTick opt : batch.optionTicks()) {
            sessionState.updateQuote(
                    opt.instrumentId(),
                    BigDecimal.valueOf(opt.lastPrice()),
                    BigDecimal.valueOf(opt.bidPrice()),
                    BigDecimal.valueOf(opt.askPrice()),
                    opt.volume(),
                    opt.openInterest(),
                    batch.ts()
            );
        }

        sessionState.recordTick(batch.ts());
        currentSimTime.set(batch.ts());
        ticksReplayed.incrementAndGet();
    }

    // -------------------------------------------------------------------------
    // Data loading – unified timeline
    // -------------------------------------------------------------------------

    /**
     * Loads all spot and option ticks for {@code date} and merges them into a single
     * timeline sorted by {@code exchange_ts}. Each {@link UnifiedBatch} holds all
     * spot and option quotes that share the same timestamp.
     */
    private List<UnifiedBatch> loadUnifiedBatchesFromDb(LocalDate date) throws SQLException {
        Instant dayStart = date.atTime(REPLAY_START)
                .atZone(MarketSessionStateResolver.EXCHANGE_ZONE).toInstant();
        Instant dayEnd = date.atTime(REPLAY_END)
                .atZone(MarketSessionStateResolver.EXCHANGE_ZONE).toInstant();

        // 1. Load spot ticks grouped by exchange_ts
        Map<Instant, List<SpotTick>> spotByTs = new LinkedHashMap<>();
        try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl);
             PreparedStatement stmt = conn.prepareStatement(SPOT_SQL)) {
            stmt.setTimestamp(1, Timestamp.from(dayStart));
            stmt.setTimestamp(2, Timestamp.from(dayEnd));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Instant ts = exchangeTimestamp(rs.getTimestamp("exchange_ts"));
                    spotByTs.computeIfAbsent(ts, ignored -> new ArrayList<>())
                            .add(new SpotTick(rs.getString("underlying"), rs.getDouble("last_price")));
                }
            }
        }

        if (spotByTs.isEmpty()) {
            return List.of();
        }

        // 2. Load option ticks grouped by exchange_ts
        Map<Instant, List<OptionTick>> optsByTs = new LinkedHashMap<>();
        try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl);
             PreparedStatement stmt = conn.prepareStatement(OPTIONS_SQL)) {
            stmt.setTimestamp(1, Timestamp.from(dayStart));
            stmt.setTimestamp(2, Timestamp.from(dayEnd));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Instant ts = exchangeTimestamp(rs.getTimestamp("exchange_ts"));
                    optsByTs.computeIfAbsent(ts, ignored -> new ArrayList<>())
                            .add(new OptionTick(
                                    rs.getString("instrument_id"),
                                    rs.getDouble("last_price"),
                                    rs.getDouble("bid_price"),
                                    rs.getDouble("ask_price"),
                                    rs.getLong("volume"),
                                    rs.getLong("open_interest")
                            ));
                }
            }
        }

        // 3. Merge into a single sorted timeline
        TreeSet<Instant> allTs = new TreeSet<>();
        allTs.addAll(spotByTs.keySet());
        allTs.addAll(optsByTs.keySet());

        List<UnifiedBatch> batches = new ArrayList<>(allTs.size());
        for (Instant ts : allTs) {
            batches.add(new UnifiedBatch(
                    ts,
                    spotByTs.getOrDefault(ts, List.of()),
                    optsByTs.getOrDefault(ts, List.of())
            ));
        }
        return batches;
    }

    private static Instant exchangeTimestamp(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        LocalDateTime localDateTime = timestamp.toLocalDateTime();
        return localDateTime.toInstant(ZoneOffset.UTC);
    }

    // -------------------------------------------------------------------------
    // Internal records
    // -------------------------------------------------------------------------

    private record UnifiedBatch(Instant ts, List<SpotTick> spotTicks, List<OptionTick> optionTicks) {}
    private record SpotTick(String underlying, double lastPrice) {}
    private record OptionTick(String instrumentId, double lastPrice, double bidPrice, double askPrice, long volume, long openInterest) {}

    @FunctionalInterface
    interface BatchLoader {
        List<UnifiedBatch> load(LocalDate date) throws SQLException;
    }

    // -------------------------------------------------------------------------
    // Public status record
    // -------------------------------------------------------------------------

    /**
     * Immutable snapshot of simulation state returned by {@link #getStatus()}.
     *
     * @param active        whether a replay is currently running
     * @param replayDate    the ISO date being replayed, or {@code null}
     * @param replayTimeIst current replayed wall-clock time (HH:mm:ss IST), or {@code null}
     * @param speed         replay speed multiplier
     * @param ticksReplayed number of 2-second tick batches replayed so far
     * @param totalTicks    total tick batches in the replay dataset
     */
    public record SimulationStatus(
            boolean active,
            String replayDate,
            String replayTimeIst,
            int speed,
            int ticksReplayed,
            int totalTicks
    ) {}
}
