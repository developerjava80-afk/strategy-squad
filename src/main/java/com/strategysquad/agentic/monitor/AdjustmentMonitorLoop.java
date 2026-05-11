package com.strategysquad.agentic.monitor;

import com.strategysquad.ingestion.live.session.LiveSessionState;
import com.strategysquad.order.MonitorAdjustmentExecutionResult;
import com.strategysquad.order.MonitorAdjustmentExecutionService;
import com.strategysquad.order.MonitorAdjustmentIntent;
import com.strategysquad.order.OptionOrderService;
import com.strategysquad.order.PositionSessionActionService;
import com.strategysquad.order.PositionSessionSnapshot;
import com.strategysquad.order.ResearchPositionSessionService;
import com.strategysquad.platform.config.AppConfig;
import com.strategysquad.platform.db.QuestDbConnectionFactory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background loop that monitors an open position session every 15 seconds and
 * automatically reduces 1 lot on the stressed leg when a signal fires.
 *
 * <h2>Signals (priority order)</h2>
 * <ol>
 *   <li>Delta stress — net |delta2m| > 0.25 on CE or PE side → REDUCE on stressed side</li>
 *   <li>Near ITM — any short leg moneyness_points &lt; 100 → REDUCE on nearest leg</li>
 *   <li>Theta adverse — deltaAdjustedTheta2m &lt; -2.0 on any leg → REDUCE on worst leg</li>
 * </ol>
 *
 * <h2>Guards</h2>
 * <ul>
 *   <li>60-second cooldown after any action — no further reduces during cooldown</li>
 *   <li>Minimum 1 lot floor — never reduces a leg below 1 open lot</li>
 * </ul>
 *
 * <h2>Data sources</h2>
 * <ul>
 *   <li>{@code options_live_enriched} — live last_price, underlying_price, moneyness_points per instrument</li>
 *   <li>{@code spot_live} — live spot price for net-delta computation and context</li>
 *   <li>{@code ResearchPositionSessionService} — active session snapshot (file-backed)</li>
 * </ul>
 *
 * <p>Uses {@code options_live_enriched} over raw {@code options_live} because it already
 * carries pre-computed {@code moneyness_points} and {@code underlying_price} — avoiding
 * an extra join. The enrichment job runs continuously during live sessions.
 */
public final class AdjustmentMonitorLoop {

    private static final Logger LOG = Logger.getLogger(AdjustmentMonitorLoop.class.getName());

    // -------------------------------------------------------------------------
    // Signal thresholds
    // -------------------------------------------------------------------------

    /** Net |delta2m| threshold above which delta-stress signal fires. */
    public static final double DELTA_STRESS_THRESHOLD = 0.25;

    /** Moneyness (pts from spot) below which near-ITM signal fires. */
    public static final double NEAR_ITM_THRESHOLD_POINTS = 100.0;

    /** Delta-adjusted theta 2m below which theta-adverse signal fires (index pts). */
    public static final double THETA_ADVERSE_THRESHOLD = -2.0;

    /** Cooldown between any two reduce actions (milliseconds). */
    public static final long COOLDOWN_MS = 60_000L;

    /** Minimum open lots — never reduce below this. */
    public static final int MIN_LOTS_FLOOR = 1;

    /** Tick interval (milliseconds). */
    public static final long TICK_INTERVAL_MS = 15_000L;

    /** Maximum decision log rows retained in memory. */
    private static final int LOG_RING_SIZE = 100;

    // -------------------------------------------------------------------------
    // SQL — reads from options_live_enriched (has moneyness_points pre-computed)
    // -------------------------------------------------------------------------

    /**
     * Latest option price for a leg — looks back up to 48 hours.
     * Using 48h ensures the monitor displays Friday's last prices over the
     * weekend (last close ~27h before Saturday evening) and survives any
     * overnight gaps without going blank.
     */
    // Also selects empirical_delta, theta_decay_per_minute, avg_volume (V011 columns).
    // These are non-null only for dummy-feed ticks; live Kite rows always return NULL/NaN.
    // Used as a fallback when the two-timestamp delta computation yields null (dummy mode
    // has no price-history accumulation in the DB on the first few ticks).
    private static final String SQL_LIVE_LEG =
            "SELECT ole.last_price, ole.underlying_price, ole.moneyness_points," +
            "       ole.exchange_ts," +
            "       ole.empirical_delta, ole.theta_decay_per_minute, ole.avg_volume" +
            "  FROM options_live_enriched ole" +
            " WHERE ole.instrument_id = ?" +
            "   AND ole.exchange_ts >= dateadd('h', -48, now())" +
            " ORDER BY ole.exchange_ts DESC" +
            " LIMIT 1";

    /**
     * Latest spot price — looks back up to 48 hours.
     * Allows spot to be resolved over weekends and after market close.
     */
    private static final String SQL_SPOT_NOW =
            "SELECT last_price" +
            "  FROM spot_live" +
            " WHERE underlying = ?" +
            "   AND exchange_ts >= dateadd('h', -48, now())" +
            " ORDER BY exchange_ts DESC" +
            " LIMIT 1";

    /**
     * Oldest option price in a 2-minute window for empirical delta computation.
     * Kept at 5 minutes so the delta window stays short and meaningful.
     * Delta returns null when the spot move is too small (< 0.5 pts) — this is
     * expected outside market hours or during low-volatility periods.
     */
    private static final String SQL_OPTION_PRICE_2M =
            "SELECT last_price, exchange_ts" +
            "  FROM options_live_enriched" +
            " WHERE instrument_id = ?" +
            "   AND exchange_ts >= dateadd('m', -5, now())" +
            " ORDER BY exchange_ts ASC" +
            " LIMIT 1";  // oldest in 5-min window → used as "2m ago" reference

    private static final String SQL_SPOT_2M_AGO =
            "SELECT last_price" +
            "  FROM spot_live" +
            " WHERE underlying = ?" +
            "   AND exchange_ts >= dateadd('m', -5, now())" +
            " ORDER BY exchange_ts ASC" +
            " LIMIT 1";

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final ResearchPositionSessionService sessionService;
    private final PositionSessionActionService actionService;
    private final String jdbcUrl;
    private volatile MonitorAdjustmentExecutionService adjustmentExecutionService;

    /**
     * Phase 1.5: Direct reference to the order service for per-tick invariant checks.
     * Uses {@link OptionOrderService#loadExecutionLots()} (lightweight, no enrichment).
     */
    private volatile OptionOrderService orderService;

    /**
     * Optional in-memory live feed state. When set, used as a fallback (and primary
     * when DB has no recent rows) for spot and option prices. This allows the monitor
     * to work in simulation mode where {@code data.persistence.enabled=false} prevents
     * ticks from reaching the database tables.
     */
    private volatile LiveSessionState liveSessionState;

    // -------------------------------------------------------------------------
    // In-memory rolling price history for delta computation
    // Used when the DB 5-minute window has no data (sim/replay mode).
    // All access is from the single scheduler thread — no synchronization needed.
    // -------------------------------------------------------------------------

    /** How far back to keep history entries (5 minutes = 20 ticks at 15s interval). */
    private static final long PRICE_HISTORY_WINDOW_MS = 5 * 60 * 1_000L;
    private static final int  PRICE_HISTORY_MAX        = 25;

    /** Per-instrument option price snapshots. Each entry: {epochMs, Double.doubleToRawLongBits(price)}. */
    private final Map<String, Deque<long[]>> optionPriceHistory = new java.util.HashMap<>();

    /** Per-underlying spot price snapshots. Same encoding as optionPriceHistory. */
    private final Map<String, Deque<long[]>> spotPriceHistory = new java.util.HashMap<>();

    // -------------------------------------------------------------------------
    // Mutable runtime state (all accesses on the single scheduler thread
    // except statusRef and logRef which are AtomicReference/synchronized)
    // -------------------------------------------------------------------------

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "adjustment-monitor");
                t.setDaemon(true);
                return t;
            });

    private ScheduledFuture<?> tickFuture;
    private volatile boolean running = false;

    /** Last tick's full result — exposed to REST status endpoint. */
    private final AtomicReference<MonitorTickResult> latestStatus = new AtomicReference<>();

    /** Ring buffer of recent decisions — exposed to REST log endpoint. */
    private final Deque<MonitorTickResult> decisionLog = new ArrayDeque<>(LOG_RING_SIZE);

    /** Timestamp of the last reduce action — used for cooldown. */
    private Instant lastActionTs = null;

    /** Session ID seen in the last tick — used to detect session change and reset cooldown. */
    private String lastKnownSessionId = null;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public AdjustmentMonitorLoop(
            ResearchPositionSessionService sessionService,
            PositionSessionActionService actionService,
            String jdbcUrl) {
        this.sessionService = sessionService;
        this.actionService = actionService;
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * Wires in-memory live feed state so the monitor can read spot and option prices
     * directly when the database has no recent rows (e.g. sim/replay mode where
     * {@code data.persistence.enabled=false}).
     *
     * @param state the shared live session state; may be null (no-op)
     * @return this, for fluent chaining
     */
    public AdjustmentMonitorLoop withLiveSessionState(LiveSessionState state) {
        this.liveSessionState = state;
        return this;
    }

    public AdjustmentMonitorLoop withOrderService(OptionOrderService orderService) {
        if (orderService != null) {
            this.orderService = orderService; // Phase 1.5: kept for per-tick invariant checks
            this.adjustmentExecutionService = new MonitorAdjustmentExecutionService(
                    orderService,
                    sessionService,
                    actionService,
                    AppConfig.load().getExecutionMode(),
                    null);
        }
        return this;
    }

    public AdjustmentMonitorLoop withAdjustmentExecutionService(MonitorAdjustmentExecutionService executionService) {
        this.adjustmentExecutionService = executionService;
        return this;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public synchronized void start() {
        if (running) {
            LOG.info("[Monitor] Already running — ignoring start()");
            return;
        }
        running = true;
        lastActionTs = null;
        lastKnownSessionId = null;
        tickFuture = scheduler.scheduleWithFixedDelay(
                this::tick, 0, TICK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        LOG.info("[Monitor] Started — tick interval " + TICK_INTERVAL_MS + "ms");
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (tickFuture != null) {
            tickFuture.cancel(false);
            tickFuture = null;
        }
        LOG.info("[Monitor] Stopped");
    }

    public boolean isRunning() {
        return running;
    }

    // -------------------------------------------------------------------------
    // Status / log accessors (called from REST thread)
    // -------------------------------------------------------------------------

    public MonitorTickResult latestStatus() {
        return latestStatus.get();
    }

    /** Returns the last N rows from the decision log, most-recent first. */
    public List<MonitorTickResult> recentLog(int limit) {
        List<MonitorTickResult> copy;
        synchronized (decisionLog) {
            copy = new ArrayList<>(decisionLog);
        }
        // decisionLog is head=oldest, tail=newest — reverse for most-recent-first
        java.util.Collections.reverse(copy);
        return copy.size() <= limit ? copy : copy.subList(0, limit);
    }

    // -------------------------------------------------------------------------
    // Core tick
    // -------------------------------------------------------------------------

    private void tick() {
        try {
            MonitorTickResult result = evaluate();
            latestStatus.set(result);
            synchronized (decisionLog) {
                decisionLog.addLast(result);
                while (decisionLog.size() > LOG_RING_SIZE) {
                    decisionLog.removeFirst();
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[Monitor] Tick failed — will retry next interval", e);
        }
    }

    private MonitorTickResult evaluate() {
        Instant now = Instant.now();

        // ------------------------------------------------------------------
        // 1. Load active session
        // ------------------------------------------------------------------
        PositionSessionSnapshot session = findActiveSession();
        if (session == null) {
            return noSession(now);
        }

        // Reset cooldown if session changed (new session opened)
        if (!session.sessionId().equals(lastKnownSessionId)) {
            lastKnownSessionId = session.sessionId();
            lastActionTs = null;
            LOG.info("[Monitor] New session detected: " + session.sessionId());
        }

        // Phase 1.5: Per-tick execution-session invariant check.
        // Compares execution ledger lots vs session openQuantity for each execution-backed leg.
        // Logs SEVERE on mismatch so the operator can investigate before the next reduce fires.
        verifySessionExecutionConsistency(session);

        String underlying = session.underlying();

        // ------------------------------------------------------------------
        // 2. Load live spot price
        // ------------------------------------------------------------------
        BigDecimal spotPrice = loadSpot(underlying);

        // Fallback: use spot recorded at session-open time (always available)
        // This allows the monitor to display moneyness on weekends / after market close
        // when DB and in-memory state have no recent ticks.
        if (spotPrice == null && session.spot() != null && session.spot().signum() > 0) {
            spotPrice = session.spot();
            LOG.info("[Monitor] Live spot unavailable — using session-open spot " + spotPrice + " as display fallback");
        }

        // ------------------------------------------------------------------
        // 3. Build leg signals for all open short legs
        // ------------------------------------------------------------------
        List<PositionSessionSnapshot.PositionLegSnapshot> shortLegs = session.legs().stream()
                .filter(l -> l.isShortLeg() && l.openQuantity() > 0)
                .toList();

        if (shortLegs.isEmpty()) {
            return noData(now, session.sessionId(), underlying, spotPrice,
                    "No open short legs in session");
        }

        List<MonitorTickResult.LegSignal> legSignals = new ArrayList<>();
        for (PositionSessionSnapshot.PositionLegSnapshot leg : shortLegs) {
            legSignals.add(buildLegSignal(leg, underlying, spotPrice));
        }

        // ------------------------------------------------------------------
        // 4. Compute net delta across all legs
        // ------------------------------------------------------------------
        BigDecimal netDelta = computeNetDelta(legSignals, shortLegs);

        // ------------------------------------------------------------------
        // 5. Evaluate signals and pick decision
        // ------------------------------------------------------------------
        return applyRules(now, session, underlying, spotPrice, netDelta, legSignals, shortLegs);
    }

    // -------------------------------------------------------------------------
    // Rule engine
    // -------------------------------------------------------------------------

    private MonitorTickResult applyRules(
            Instant now,
            PositionSessionSnapshot session,
            String underlying,
            BigDecimal spotPrice,
            BigDecimal netDelta,
            List<MonitorTickResult.LegSignal> legSignals,
            List<PositionSessionSnapshot.PositionLegSnapshot> shortLegs) {

        List<String> globalFlags = new ArrayList<>();
        double netDeltaDouble = netDelta == null ? 0.0 : netDelta.doubleValue();

        // ------------------------------------------------------------------
        // Rule 1 — Delta stress
        // ------------------------------------------------------------------
        String deltaDecisionSide = null;
        if (netDeltaDouble > DELTA_STRESS_THRESHOLD) {
            globalFlags.add("DELTA_HIGH_CE");
            deltaDecisionSide = "CE";
        } else if (netDeltaDouble < -DELTA_STRESS_THRESHOLD) {
            globalFlags.add("DELTA_HIGH_PE");
            deltaDecisionSide = "PE";
        }

        if (deltaDecisionSide != null) {
            String side = deltaDecisionSide;
            // Find the CE/PE short leg with highest |delta2m|
            MonitorTickResult.LegSignal target = legSignals.stream()
                    .filter(ls -> side.equals(ls.optionType()))
                    .filter(ls -> ls.delta2m() != null)
                    .max(java.util.Comparator.comparingDouble(
                            ls -> Math.abs(ls.delta2m().doubleValue())))
                    .orElse(null);

            if (target != null) {
                String reasonCode = "DELTA_REDUCE_" + side;
                String explanation = String.format(
                        "Net delta2m %.3f %s threshold ±%.2f — reducing %s leg %s by 1 lot",
                        netDeltaDouble, side.equals("CE") ? ">" : "<",
                        DELTA_STRESS_THRESHOLD, side, target.label());
                return reduce(now, session, underlying, spotPrice, netDelta,
                        legSignals, globalFlags, target, reasonCode, explanation);
            }
        }

        // ------------------------------------------------------------------
        // Rule 2 — Near ITM
        // ------------------------------------------------------------------
        MonitorTickResult.LegSignal nearItmLeg = legSignals.stream()
                .filter(ls -> ls.moneynessPoints() != null
                        && ls.moneynessPoints().doubleValue() < NEAR_ITM_THRESHOLD_POINTS
                        && ls.moneynessPoints().doubleValue() >= 0)
                .min(java.util.Comparator.comparingDouble(
                        ls -> ls.moneynessPoints().doubleValue()))
                .orElse(null);

        if (nearItmLeg != null) {
            globalFlags.add("NEAR_ITM");
            String explanation = String.format(
                    "Leg %s is %.0f pts from spot (threshold %.0f) — reducing by 1 lot",
                    nearItmLeg.label(),
                    nearItmLeg.moneynessPoints().doubleValue(),
                    NEAR_ITM_THRESHOLD_POINTS);
            return reduce(now, session, underlying, spotPrice, netDelta,
                    legSignals, globalFlags, nearItmLeg, "NEAR_ITM_REDUCE", explanation);
        }

        // ------------------------------------------------------------------
        // Rule 3 — Theta adverse
        // ------------------------------------------------------------------
        MonitorTickResult.LegSignal thetaAdverseLeg = legSignals.stream()
                .filter(ls -> ls.deltaAdjustedTheta2m() != null
                        && ls.deltaAdjustedTheta2m().doubleValue() < THETA_ADVERSE_THRESHOLD)
                .min(java.util.Comparator.comparingDouble(
                        ls -> ls.deltaAdjustedTheta2m().doubleValue())) // most negative = worst
                .orElse(null);

        if (thetaAdverseLeg != null) {
            globalFlags.add("THETA_ADVERSE");
            String explanation = String.format(
                    "Leg %s has delta-adj theta %.2f pts (threshold %.1f) — reducing by 1 lot",
                    thetaAdverseLeg.label(),
                    thetaAdverseLeg.deltaAdjustedTheta2m().doubleValue(),
                    THETA_ADVERSE_THRESHOLD);
            return reduce(now, session, underlying, spotPrice, netDelta,
                    legSignals, globalFlags, thetaAdverseLeg, "THETA_ADVERSE_REDUCE", explanation);
        }

        // ------------------------------------------------------------------
        // Rule 4 — Hedge entry: if any short leg is severely stressed AND no hedge
        // leg exists yet for that side, add a far-OTM protective buy
        // ------------------------------------------------------------------
        for (MonitorTickResult.LegSignal ls : legSignals) {
            if (ls.delta2m() == null) continue;
            double absDelta = Math.abs(ls.delta2m().doubleValue());
            boolean severelyStressed = absDelta > DELTA_STRESS_THRESHOLD * 1.5;
            if (!severelyStressed) continue;

            // Check whether a hedge leg already exists for this option type
            String optType = ls.optionType();
            boolean hedgeExists = session.legs().stream()
                    .anyMatch(l -> l.isHedgeLeg() && optType.equals(l.optionType()) && l.openQuantity() > 0);
            if (hedgeExists) continue;

            // Find a far-OTM strike to hedge (strike 200+ pts away from spot, same expiry)
            String hedgeInstrument = findFarOtmHedgeInstrument(underlying, optType, spotPrice, ls);
            if (hedgeInstrument == null) continue;

            String explanation = String.format(
                    "Leg %s has severe delta2m=%.3f — adding %s hedge before further reductions",
                    ls.label(), ls.delta2m().doubleValue(), optType);
            globalFlags.add("HEDGE_ENTRY_" + optType);
            return enter(now, session, underlying, spotPrice, netDelta, legSignals, globalFlags,
                    ls, hedgeInstrument, optType, reasonCode(ls, optType), explanation);
        }

        // ------------------------------------------------------------------
        // Default — HOLD
        // ------------------------------------------------------------------
        return new MonitorTickResult(now, session.sessionId(), underlying, spotPrice,
                netDelta, legSignals, List.of(),
                "HOLD", "MONITORING_HOLD",
                String.format("No signals. net delta=%.3f", netDeltaDouble),
                false, null, null, null);
    }

    // -------------------------------------------------------------------------
    // Reduce action (with cooldown + floor guards)
    // -------------------------------------------------------------------------

    MonitorTickResult reduce(
            Instant now,
            PositionSessionSnapshot session,
            String underlying,
            BigDecimal spotPrice,
            BigDecimal netDelta,
            List<MonitorTickResult.LegSignal> legSignals,
            List<String> globalFlags,
            MonitorTickResult.LegSignal targetLeg,
            String reasonCode,
            String explanation) {

        // Cooldown guard
        if (lastActionTs != null) {
            long msSinceLast = now.toEpochMilli() - lastActionTs.toEpochMilli();
            if (msSinceLast < COOLDOWN_MS) {
                long remainingMs = COOLDOWN_MS - msSinceLast;
                return new MonitorTickResult(now, session.sessionId(), underlying, spotPrice,
                        netDelta, legSignals, globalFlags,
                        "COOLDOWN_ACTIVE", "COOLDOWN_ACTIVE",
                        String.format("Signal %s fired but cooldown active (%ds remaining) — %s",
                                reasonCode, remainingMs / 1000, explanation),
                        false, null, null, null);
            }
        }

        // Find the actual leg snapshot to get current openQty
        PositionSessionSnapshot.PositionLegSnapshot leg = session.legs().stream()
                .filter(l -> l.legId().equals(findLegIdByLabel(session, targetLeg.label())))
                .findFirst()
                .orElse(null);

        // Fallback: match by optionType + strike
        if (leg == null) {
            leg = session.legs().stream()
                    .filter(l -> l.isShortLeg()
                            && l.openQuantity() > 0
                            && l.optionType().equals(targetLeg.optionType())
                            && l.strike() != null
                            && targetLeg.strike() != null
                            && l.strike().compareTo(targetLeg.strike()) == 0)
                    .findFirst()
                    .orElse(null);
        }

        if (leg == null) {
            return new MonitorTickResult(now, session.sessionId(), underlying, spotPrice,
                    netDelta, legSignals, globalFlags,
                    "NO_DATA", "LEG_NOT_FOUND",
                    "Signal fired but target leg not found in session: " + targetLeg.label(),
                    false, null, null, null);
        }

        // Min lots floor
        if (leg.openQuantity() <= MIN_LOTS_FLOOR) {
            return new MonitorTickResult(now, session.sessionId(), underlying, spotPrice,
                    netDelta, legSignals, globalFlags,
                    "MIN_LOTS_FLOOR", "MIN_LOTS_FLOOR",
                    String.format("Signal %s fired but leg %s is at minimum (%d lot) — not reducing",
                            reasonCode, leg.label(), MIN_LOTS_FLOOR),
                    false, null, null, null);
        }

        // ------------------------------------------------------------------
        // Apply the reduce — exit 1 lot at last traded price
        // ------------------------------------------------------------------
        int lotsBefore = leg.openQuantity();
        BigDecimal exitPrice = targetLeg.lastPrice() != null
                ? targetLeg.lastPrice()
            : leg.entryPrice(); // fallback: use entry price if LTP unavailable
        if (exitPrice == null || exitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return new MonitorTickResult(now, session.sessionId(), underlying, spotPrice,
                netDelta, legSignals, globalFlags,
                "REDUCE_FAILED", "MISSING_FILL_PRICE",
                "Reduce action failed: MISSING_FILL_PRICE for leg " + leg.legId(),
                false, leg.label(), lotsBefore, lotsBefore);
        }

        try {
            MonitorAdjustmentExecutionService executionService = adjustmentExecutionService;
            if (executionService == null) {
            return new MonitorTickResult(now, session.sessionId(), underlying, spotPrice,
                netDelta, legSignals, globalFlags,
                "REDUCE_FAILED", "REDUCE_FAILED",
                "Reduce action failed: adjustment execution service is not configured",
                false, null, null, null);
            }
            MonitorAdjustmentIntent intent = MonitorAdjustmentIntent.forReduce(
                session.sessionId(),
                session.sessionId(),
                leg.legId(),
                1,
                reasonCode,
                exitPrice,
                now);
            MonitorAdjustmentExecutionResult executionResult = executionService.execute(intent);
            if (executionResult.filled()) {
            lastActionTs = now;
            LOG.info(String.format(
                "[Monitor] REDUCED | source=MONITOR | legId=%s | execId=%s | label=%s | %d→%d lots"
                + " | fillPrice=%s | reason=%s | cooldownActiveUntil=+60s",
                leg.legId(),
                leg.executionId() != null ? leg.executionId() : "<none>",
                leg.label(),
                executionResult.lotsBefore(), executionResult.lotsAfter(),
                exitPrice, reasonCode));
            return new MonitorTickResult(now, session.sessionId(), underlying, spotPrice,
                netDelta, legSignals, globalFlags,
                "REDUCE", reasonCode, explanation,
                true, leg.label(), executionResult.lotsBefore(), executionResult.lotsAfter());
            }
            if (executionResult.pending()) {
            lastActionTs = now;
            return new MonitorTickResult(now, session.sessionId(), underlying, spotPrice,
                netDelta, legSignals, globalFlags,
                "REDUCE_PENDING", "REDUCE_PENDING",
                executionResult.message(),
                false, leg.label(), executionResult.lotsBefore(), executionResult.lotsAfter());
            }
            return new MonitorTickResult(now, session.sessionId(), underlying, spotPrice,
                netDelta, legSignals, globalFlags,
                "REDUCE_FAILED", executionResult.failureReason() == null ? "REDUCE_FAILED" : executionResult.failureReason(),
                executionResult.message(),
                false, leg.label(), executionResult.lotsBefore(), executionResult.lotsAfter());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[Monitor] Failed to apply reduce action", e);
            return new MonitorTickResult(now, session.sessionId(), underlying, spotPrice,
                    netDelta, legSignals, globalFlags,
                    "REDUCE_FAILED", "REDUCE_FAILED",
                    "Reduce action failed: " + e.getMessage(),
                    false, null, null, null);
        }
    }

    // -------------------------------------------------------------------------
    // Enter / Hedge action — place a new long option order as a hedge
    // -------------------------------------------------------------------------

    /**
     * Triggers a new BUY order for a far-OTM hedge option.
     * Uses the same {@link MonitorAdjustmentExecutionService#execute} path as Strategy Lab.
     *
     * @param hedgeTradingSymbol NSE trading symbol of the option to buy (from scanner)
     * @param optionType "CE" or "PE"
     */
    MonitorTickResult enter(
            Instant now,
            PositionSessionSnapshot session,
            String underlying,
            BigDecimal spotPrice,
            BigDecimal netDelta,
            List<MonitorTickResult.LegSignal> legSignals,
            List<String> globalFlags,
            MonitorTickResult.LegSignal triggerLeg,
            String hedgeTradingSymbol,
            String optionType,
            String reasonCode,
            String explanation) {

        if (adjustmentExecutionService == null) {
            return new MonitorTickResult(now, session.sessionId(), underlying, spotPrice,
                    netDelta, legSignals, globalFlags,
                    "ENTER_FAILED", "ENTER_FAILED",
                    "Enter action failed: adjustment execution service is not configured",
                    false, null, null, null);
        }

        // Extract expiry from an existing session leg (all legs share the same expiry)
        String expiry = session.legs().stream()
                .filter(l -> l.expiryDate() != null)
                .map(PositionSessionSnapshot.PositionLegSnapshot::expiryDate)
                .findFirst()
                .orElse(null);
        if (expiry == null) {
            return new MonitorTickResult(now, session.sessionId(), underlying, spotPrice,
                    netDelta, legSignals, globalFlags,
                    "ENTER_FAILED", "ENTER_FAILED",
                    "Enter action failed: cannot determine expiry from session legs",
                    false, null, null, null);
        }

        // Derive a far-OTM strike: 200 pts away from spot on the hedge side
        BigDecimal hedgeStrike = deriveHedgeStrike(optionType, spotPrice);
        BigDecimal hedgeLtp = triggerLeg.lastPrice(); // use stressed leg's LTP as price hint

        try {
            MonitorAdjustmentIntent intent = MonitorAdjustmentIntent.forEnter(
                    session.sessionId(),
                    session.sessionId(),
                    underlying,
                    expiry,
                    hedgeStrike,
                    optionType,
                    "BUY",
                    1,
                    "paper",
                    hedgeLtp,
                    reasonCode,
                    now,
                    session.sessionId(),
                    session.orientation(),
                    session.strategyLabel() + " HEDGE");

            MonitorAdjustmentExecutionResult executionResult = adjustmentExecutionService.execute(intent);
            if (executionResult.filled()) {
                lastActionTs = now;
                LOG.info(String.format("[Monitor] HEDGE ENTERED %s %s x1 | reason=%s",
                        optionType, hedgeStrike, reasonCode));
                return new MonitorTickResult(now, session.sessionId(), underlying, spotPrice,
                        netDelta, legSignals, globalFlags,
                        "ENTER", reasonCode, explanation,
                        true, optionType + " hedge @ " + hedgeStrike,
                        0, 1);
            }
            return new MonitorTickResult(now, session.sessionId(), underlying, spotPrice,
                    netDelta, legSignals, globalFlags,
                    "ENTER_FAILED",
                    executionResult.failureReason() == null ? "ENTER_FAILED" : executionResult.failureReason(),
                    executionResult.message(),
                    false, null, null, null);
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING, "[Monitor] Failed to apply enter action", e);
            return new MonitorTickResult(now, session.sessionId(), underlying, spotPrice,
                    netDelta, legSignals, globalFlags,
                    "ENTER_FAILED", "ENTER_FAILED",
                    "Enter action failed: " + e.getMessage(),
                    false, null, null, null);
        }
    }

    /**
     * Derives the far-OTM strike for a hedge: 200 pts away from spot on the stressed side.
     * CE stress → hedge with a CE 200 pts above spot.
     * PE stress → hedge with a PE 200 pts below spot.
     */
    private static BigDecimal deriveHedgeStrike(String optionType, BigDecimal spotPrice) {
        if (spotPrice == null) return BigDecimal.valueOf(0);
        BigDecimal buffer = BigDecimal.valueOf(200);
        BigDecimal rawStrike = "CE".equals(optionType)
                ? spotPrice.add(buffer)
                : spotPrice.subtract(buffer);
        // Round to nearest 50 (standard NSE strike spacing)
        long rounded = Math.round(rawStrike.doubleValue() / 50.0) * 50L;
        return BigDecimal.valueOf(rounded);
    }

    /**
     * Attempts to find a far-OTM option contract in live data to use as a hedge.
     * Returns the trading symbol if found, null if no suitable contract is available.
     */
    private String findFarOtmHedgeInstrument(
            String underlying, String optionType, BigDecimal spotPrice,
            MonitorTickResult.LegSignal stressedLeg) {
        BigDecimal hedgeStrike = deriveHedgeStrike(optionType, spotPrice);
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            // No DB access — signal that a synthetic placement should be attempted
            // (the enter() caller will use the session's known expiry)
            return underlying + "_HEDGE_" + optionType;
        }
        try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl)) {
            String sql = "SELECT trading_symbol FROM options_live_enriched " +
                    "WHERE underlying = ? AND option_type = ? AND strike = ? " +
                    "ORDER BY ts DESC LIMIT 1";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, underlying);
                ps.setString(2, optionType);
                ps.setBigDecimal(3, hedgeStrike);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString("trading_symbol");
                }
            }
        } catch (Exception e) {
            LOG.warning("[Monitor] findFarOtmHedgeInstrument failed: " + e.getMessage());
        }
        return null;
    }

    private static String reasonCode(MonitorTickResult.LegSignal leg, String optionType) {
        return "HEDGE_" + optionType + "_" + (leg.label() != null ? leg.label().replace(" ", "_") : "UNKNOWN");
    }

    // -------------------------------------------------------------------------
    // Leg signal builder — queries options_live_enriched
    // -------------------------------------------------------------------------

    private MonitorTickResult.LegSignal buildLegSignal(
            PositionSessionSnapshot.PositionLegSnapshot leg,
            String underlying,
            BigDecimal spotNow) {

        BigDecimal lastPrice = null;
        BigDecimal moneynessPoints = null;
        BigDecimal underlyingPriceFromEnriched = null;

        // Dummy-feed Greek fallbacks (null on every live Kite row — V011 columns are NULL there).
        // Populated only when the enriched row was written by the dummy-kite-feed adapter.
        Double storedDelta           = null;
        Double storedThetaPerMinute  = null;
        Double storedAvgVolume       = null;

        // Priority 1: in-memory live session state.
        // Always preferred for LTP because it reflects the current sim/live tick.
        // Stale DB rows in options_live_enriched (48h lookback) would otherwise override
        // the current replay price with an old historical close, corrupting the display.
        {
            LiveSessionState state = liveSessionState;
            if (state != null) {
                LiveSessionState.OptionQuote oq = state.getLatestQuote(leg.instrumentId());
                if (oq != null) {
                    if (oq.lastPrice() != null && oq.lastPrice().signum() > 0) {
                        lastPrice = oq.lastPrice();
                    }
                    if (oq.empiricalDelta() != null) storedDelta = oq.empiricalDelta();
                    if (oq.thetaDecayPerMinute() != null) storedThetaPerMinute = oq.thetaDecayPerMinute();
                }
            }
        }

        // DB row: options_live_enriched (48-hour lookback).
        // Used only for moneyness, underlying_price, and V011 Greeks — never for LTP.
        // LTP always comes from the feed (Kite or replay engine) via LiveSessionState above.
        try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(SQL_LIVE_LEG)) {
            ps.setString(1, leg.instrumentId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double mp = rs.getDouble("moneyness_points");
                    if (!rs.wasNull()) {
                        // moneyness_points in options_live_enriched is signed:
                        // CE: strike - spot (negative = ITM), PE: spot - strike
                        // We want absolute distance from ATM for "how close to ITM"
                        moneynessPoints = BigDecimal.valueOf(Math.abs(mp))
                                .setScale(2, RoundingMode.HALF_UP);
                    }

                    double up = rs.getDouble("underlying_price");
                    if (!rs.wasNull() && up > 0) {
                        underlyingPriceFromEnriched = BigDecimal.valueOf(up);
                    }

                    // V011 Greek columns — NULL for every live Kite row, non-null for dummy rows.
                    if (storedDelta == null) {
                        double ed = rs.getDouble("empirical_delta");
                        if (!rs.wasNull()) storedDelta = ed;
                    }
                    if (storedThetaPerMinute == null) {
                        double td = rs.getDouble("theta_decay_per_minute");
                        if (!rs.wasNull()) storedThetaPerMinute = td;
                    }
                    double av = rs.getDouble("avg_volume");
                    if (!rs.wasNull()) storedAvgVolume = av;
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "[Monitor] options_live_enriched query failed for " + leg.instrumentId(), e);
        }

        // Priority 3: entry price from session leg (always available — shows last known value).
        if (lastPrice == null && leg.entryPrice() != null && leg.entryPrice().signum() > 0) {
            lastPrice = leg.entryPrice();
            LOG.fine("[Monitor] LTP unavailable for " + leg.instrumentId() + " — using entry price " + lastPrice + " as display fallback");
        }

        // Push resolved LTP to rolling in-memory history for delta computation across ticks.
        if (lastPrice != null) {
            pushHistory(optionPriceHistory, leg.instrumentId(), Instant.now().toEpochMilli(), lastPrice.doubleValue());
        }

        // Fallback moneyness from spot if enriched unavailable
        if (moneynessPoints == null && leg.strike() != null && spotNow != null) {
            moneynessPoints = leg.strike().subtract(spotNow).abs()
                    .setScale(2, RoundingMode.HALF_UP);
        }

        // Fallback moneyness from strike only when spot also unavailable (show rough ATM distance)
        if (moneynessPoints == null && leg.strike() != null) {
            // Cannot compute without spot — leave null; UI will show —
            LOG.fine("[Monitor] Moneyness unavailable for " + leg.instrumentId() + " — no spot price");
        }

        // Empirical delta2m — primary: compute from two timestamps in options_live_enriched.
        // This is the live-mode path and works whenever price history has accumulated in DB.
        BigDecimal delta2m = computeEmpiricalDelta2m(leg, underlying);

        // Dummy-feed fallback: if the two-timestamp computation yielded null (e.g. dummy mode
        // where persistence is on but only one tick exists yet, or spot move < 0.5 pts),
        // use the pre-computed empirical delta stored on the enriched row by the dummy adapter.
        // Live Kite rows always have storedDelta == null so this branch never fires in live mode.
        if (delta2m == null && storedDelta != null) {
            delta2m = BigDecimal.valueOf(storedDelta).setScale(4, RoundingMode.HALF_UP);
            LOG.fine("[Monitor] Using stored empirical_delta fallback for " + leg.instrumentId()
                    + " delta=" + storedDelta);
        }

        // Delta-adjusted theta 2m — primary: computed from price history.
        // Dummy fallback: if delta came from stored value and no window is available,
        // estimate theta from the stored decay rate × 2 minutes.
        BigDecimal deltaAdjustedTheta2m = computeDeltaAdjustedTheta(
                leg, delta2m, underlying, spotNow, underlyingPriceFromEnriched);
        if (deltaAdjustedTheta2m == null && storedThetaPerMinute != null) {
            // 2-minute proxy: storedTheta × 2 min, negated for a short leg (decay = benefit)
            deltaAdjustedTheta2m = BigDecimal.valueOf(storedThetaPerMinute * 2.0)
                    .setScale(4, RoundingMode.HALF_UP);
            LOG.fine("[Monitor] Using stored theta_decay_per_minute fallback for " + leg.instrumentId()
                    + " thetaPerMin=" + storedThetaPerMinute);
        }

        // Theta progress ratio: (entryPrice - lastPrice) / entryPrice
        BigDecimal thetaProgressRatio = null;
        if (leg.entryPrice() != null && leg.entryPrice().signum() > 0 && lastPrice != null) {
            BigDecimal decay = leg.entryPrice().subtract(lastPrice);
            thetaProgressRatio = decay.divide(leg.entryPrice(), 4, RoundingMode.HALF_UP);
        }

        // Leg-level signal flags
        List<String> legFlags = new ArrayList<>();
        if (delta2m != null) {
            double d = delta2m.doubleValue();
            if ("CE".equals(leg.optionType()) && d > DELTA_STRESS_THRESHOLD) legFlags.add("DELTA_HIGH");
            if ("PE".equals(leg.optionType()) && d < -DELTA_STRESS_THRESHOLD) legFlags.add("DELTA_HIGH");
        }
        if (moneynessPoints != null && moneynessPoints.doubleValue() < NEAR_ITM_THRESHOLD_POINTS) {
            legFlags.add("NEAR_ITM");
        }
        if (deltaAdjustedTheta2m != null && deltaAdjustedTheta2m.doubleValue() < THETA_ADVERSE_THRESHOLD) {
            legFlags.add("THETA_ADVERSE");
        }

        // VolumeState for this leg: use storedAvgVolume from enriched row when available.
        // In live mode storedAvgVolume is null so this flag is not set from here.
        if (storedAvgVolume != null && storedAvgVolume > 0 && lastPrice != null) {
            // avg_volume stored in enriched row is the historical daily average from context buckets.
            // We don't add a volume flag here — the scanner handles VolumeState at entry time.
            // Log for observability only.
            LOG.fine("[Monitor] storedAvgVolume=" + storedAvgVolume + " for " + leg.instrumentId());
        }

        String label = leg.label() != null ? leg.label()
                : "Short " + leg.optionType() + " " + (leg.strike() != null ? leg.strike().toPlainString() : "?");

        return new MonitorTickResult.LegSignal(
                leg.legId(), label, leg.optionType(), leg.strike(),
                leg.openQuantity(), lastPrice, delta2m, deltaAdjustedTheta2m,
                thetaProgressRatio, moneynessPoints, legFlags);
    }

    /**
     * Empirical delta2m = (latest option price - oldest price in 5m window)
     *                   / (latest spot - oldest spot in 5m window).
     * Returns null when the underlying move is too small to be meaningful.
     */
    private BigDecimal computeEmpiricalDelta2m(
            PositionSessionSnapshot.PositionLegSnapshot leg, String underlying) {

        BigDecimal optionPriceOldest = null;
        BigDecimal optionPriceLatest = null;
        BigDecimal spotOldest = null;
        BigDecimal spotLatest = null;

        // Oldest option price in the 5-min window
        try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(SQL_OPTION_PRICE_2M)) {
            ps.setString(1, leg.instrumentId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double p = rs.getDouble("last_price");
                    if (!rs.wasNull() && p > 0) optionPriceOldest = BigDecimal.valueOf(p);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "[Monitor] option oldest-price query failed", e);
        }

        // Latest option price
        try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(SQL_LIVE_LEG)) {
            ps.setString(1, leg.instrumentId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double p = rs.getDouble("last_price");
                    if (!rs.wasNull() && p > 0) optionPriceLatest = BigDecimal.valueOf(p);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "[Monitor] option latest-price query failed", e);
        }

        // Fallback latest option price from in-memory state (sim mode / DB lag).
        // Do NOT set optionPriceOldest here — let the history fallback below handle it
        // so the oldest reflects the price from a prior tick, not this same tick.
        if (optionPriceLatest == null) {
            LiveSessionState state = liveSessionState;
            if (state != null) {
                LiveSessionState.OptionQuote oq = state.getLatestQuote(leg.instrumentId());
                if (oq != null && oq.lastPrice() != null && oq.lastPrice().signum() > 0) {
                    optionPriceLatest = oq.lastPrice();
                }
            }
        }

        // Oldest option price from rolling in-memory history (sim mode).
        // Fires when the 5-min DB window has no rows (no recent writes to options_live_enriched).
        // History is populated in buildLegSignal after this call, so it contains prior-tick prices.
        if (optionPriceOldest == null) {
            long nowMs = Instant.now().toEpochMilli();
            long[] oldest = historyOldest(optionPriceHistory, leg.instrumentId(), nowMs);
            if (oldest != null && optionPriceLatest != null) {
                optionPriceOldest = BigDecimal.valueOf(Double.longBitsToDouble(oldest[1]));
            }
        }

        // Oldest spot in 5-min window
        try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(SQL_SPOT_2M_AGO)) {
            ps.setString(1, underlying);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double p = rs.getDouble("last_price");
                    if (!rs.wasNull() && p > 0) spotOldest = BigDecimal.valueOf(p);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "[Monitor] spot oldest-price query failed", e);
        }

        // Latest spot (already has in-memory fallback via loadSpot)
        spotLatest = loadSpot(underlying);

        // Fallback: oldest spot from rolling in-memory history (sim / DB-lag mode).
        // Preferred over spotLatest so delta is computable when history has accumulated.
        if (spotOldest == null) {
            long nowMs = Instant.now().toEpochMilli();
            long[] oldest = historyOldest(spotPriceHistory, underlying, nowMs);
            if (oldest != null) {
                spotOldest = BigDecimal.valueOf(Double.longBitsToDouble(oldest[1]));
            }
        }

        // Final fallback: no move → delta guard returns null (avoids division noise)
        if (spotOldest == null && spotLatest != null) {
            spotOldest = spotLatest;
        }

        if (optionPriceOldest == null || optionPriceLatest == null
                || spotOldest == null || spotLatest == null) {
            return null;
        }

        BigDecimal optionMove = optionPriceLatest.subtract(optionPriceOldest);
        BigDecimal spotMove = spotLatest.subtract(spotOldest);

        // Require minimum underlying move to avoid division noise
        if (spotMove.abs().compareTo(new BigDecimal("0.50")) < 0) {
            return null;
        }

        BigDecimal rawDelta = optionMove.divide(spotMove, 4, RoundingMode.HALF_UP);

        // Clamp to [-1.5, 1.5]
        if (rawDelta.compareTo(new BigDecimal("1.5")) > 0) return new BigDecimal("1.5");
        if (rawDelta.compareTo(new BigDecimal("-1.5")) < 0) return new BigDecimal("-1.5");

        return rawDelta;
    }

    /**
     * Delta-adjusted theta for a short leg (index points).
     * = option price change - (delta × underlying price change)
     * For a short seller, positive = option decaying faster than delta explains (good).
     * Negative = premium expanding adversely.
     */
    private BigDecimal computeDeltaAdjustedTheta(
            PositionSessionSnapshot.PositionLegSnapshot leg,
            BigDecimal delta2m,
            String underlying,
            BigDecimal spotNow,
            BigDecimal spotFromEnriched) {

        if (delta2m == null || leg.entryPrice() == null) return null;

        // Use underlying price from enriched row if spot query returned null
        BigDecimal effectiveSpotNow = spotNow != null ? spotNow : spotFromEnriched;
        if (effectiveSpotNow == null) return null;

        // Entry-time underlying price — use the oldest spot in the 5-min window
        // as a proxy for "recent reference point"
        BigDecimal spotRef = null;
        try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(SQL_SPOT_2M_AGO)) {
            ps.setString(1, underlying);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double p = rs.getDouble("last_price");
                    if (!rs.wasNull() && p > 0) spotRef = BigDecimal.valueOf(p);
                }
            }
        } catch (Exception ignored) {}

        // Fallback: oldest spot from rolling in-memory history (sim mode)
        if (spotRef == null) {
            long nowMs = Instant.now().toEpochMilli();
            long[] oldest = historyOldest(spotPriceHistory, underlying, nowMs);
            if (oldest != null) {
                spotRef = BigDecimal.valueOf(Double.longBitsToDouble(oldest[1]));
            }
        }

        if (spotRef == null) return null;

        // Option price change in window
        BigDecimal latestOptionPrice = null;
        try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(SQL_LIVE_LEG)) {
            ps.setString(1, leg.instrumentId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double p = rs.getDouble("last_price");
                    if (!rs.wasNull() && p > 0) latestOptionPrice = BigDecimal.valueOf(p);
                }
            }
        } catch (Exception ignored) {}

        // Fallback: live state LTP (current tick — DB may be empty in sim mode).
        // Must be checked before history since history contains the *previous* tick's price.
        if (latestOptionPrice == null) {
            LiveSessionState state2 = liveSessionState;
            if (state2 != null) {
                LiveSessionState.OptionQuote oq2 = state2.getLatestQuote(leg.instrumentId());
                if (oq2 != null && oq2.lastPrice() != null && oq2.lastPrice().signum() > 0) {
                    latestOptionPrice = oq2.lastPrice();
                }
            }
        }

        // Secondary fallback: most-recent entry in rolling history (previous tick's price)
        if (latestOptionPrice == null) {
            Double hist = historyLatestPrice(optionPriceHistory, leg.instrumentId());
            if (hist != null && hist > 0) latestOptionPrice = BigDecimal.valueOf(hist);
        }

        if (latestOptionPrice == null) return null;

        BigDecimal optionPriceRef = null;
        try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(SQL_OPTION_PRICE_2M)) {
            ps.setString(1, leg.instrumentId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double p = rs.getDouble("last_price");
                    if (!rs.wasNull() && p > 0) optionPriceRef = BigDecimal.valueOf(p);
                }
            }
        } catch (Exception ignored) {}

        // Fallback: oldest option price from rolling in-memory history (sim mode)
        if (optionPriceRef == null) {
            long nowMs = Instant.now().toEpochMilli();
            long[] oldest = historyOldest(optionPriceHistory, leg.instrumentId(), nowMs);
            if (oldest != null) {
                optionPriceRef = BigDecimal.valueOf(Double.longBitsToDouble(oldest[1]));
            }
        }

        if (optionPriceRef == null) return null;

        BigDecimal optionMove = latestOptionPrice.subtract(optionPriceRef);
        BigDecimal spotMove = effectiveSpotNow.subtract(spotRef);
        BigDecimal expectedDeltaMove = delta2m.multiply(spotMove, MathContext.DECIMAL64);

        // For a short leg: theta benefit = -(actual option move) + expected delta move
        // Short sellers profit when option price falls; we negate option move for PnL sign
        // Then subtract delta component to isolate theta
        return expectedDeltaMove.subtract(optionMove).setScale(2, RoundingMode.HALF_UP);
    }

    // -------------------------------------------------------------------------
    // Net delta computation
    // -------------------------------------------------------------------------

    /**
     * Net delta = sum of (signed delta contribution per leg).
     * CE short: delta2m is positive (option rises with market) → contributes +delta × lots
     * PE short: delta2m is negative → contributes delta × lots (already negative)
     * Net positive = CE stressed; net negative = PE stressed.
     */
    private BigDecimal computeNetDelta(
            List<MonitorTickResult.LegSignal> legSignals,
            List<PositionSessionSnapshot.PositionLegSnapshot> legs) {

        BigDecimal net = BigDecimal.ZERO;
        boolean anyDelta = false;

        for (int i = 0; i < legSignals.size(); i++) {
            MonitorTickResult.LegSignal ls = legSignals.get(i);
            if (ls.delta2m() == null) continue;
            int qty = legs.get(i).openQuantity();
            net = net.add(ls.delta2m().multiply(BigDecimal.valueOf(qty)));
            anyDelta = true;
        }

        return anyDelta ? net.setScale(4, RoundingMode.HALF_UP) : null;
    }

    // -------------------------------------------------------------------------
    // In-memory price history helpers (scheduler thread only)
    // -------------------------------------------------------------------------

    private void pushHistory(Map<String, Deque<long[]>> map, String key, long epochMs, double price) {
        Deque<long[]> dq = map.computeIfAbsent(key, k -> new ArrayDeque<>(PRICE_HISTORY_MAX + 1));
        dq.addLast(new long[]{epochMs, Double.doubleToRawLongBits(price)});
        // Trim by count
        while (dq.size() > PRICE_HISTORY_MAX) dq.removeFirst();
        // Trim by age
        long cutoff = epochMs - PRICE_HISTORY_WINDOW_MS;
        while (!dq.isEmpty() && dq.peekFirst()[0] < cutoff) dq.removeFirst();
    }

    /**
     * Returns the oldest entry whose timestamp is within the last {@code PRICE_HISTORY_WINDOW_MS},
     * or null if none exists. Entry format: {epochMs, Double.doubleToRawLongBits(price)}.
     */
    private long[] historyOldest(Map<String, Deque<long[]>> map, String key, long nowMs) {
        Deque<long[]> dq = map.get(key);
        if (dq == null || dq.isEmpty()) return null;
        long cutoff = nowMs - PRICE_HISTORY_WINDOW_MS;
        for (long[] entry : dq) {
            if (entry[0] >= cutoff) return entry;
        }
        return null;
    }

    /** Returns the most-recent price in history, or null if none. */
    private Double historyLatestPrice(Map<String, Deque<long[]>> map, String key) {
        Deque<long[]> dq = map.get(key);
        if (dq == null || dq.isEmpty()) return null;
        return Double.longBitsToDouble(dq.peekLast()[1]);
    }

    // -------------------------------------------------------------------------
    // DB helpers
    // -------------------------------------------------------------------------

    private BigDecimal loadSpot(String underlying) {
        BigDecimal spot = null;

        // 1. Try in-memory first (most current in sim/live mode)
        LiveSessionState state = liveSessionState;
        if (state != null) {
            LiveSessionState.SpotQuote sq = state.getLatestSpot(underlying);
            if (sq != null && sq.price() != null && sq.price().signum() > 0) {
                spot = sq.price();
            }
        }

        // 2. DB fallback (when no live state, e.g. pure research mode)
        if (spot == null) {
            try (Connection conn = QuestDbConnectionFactory.open(jdbcUrl);
                 PreparedStatement ps = conn.prepareStatement(SQL_SPOT_NOW)) {
                ps.setString(1, underlying);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        double p = rs.getDouble("last_price");
                        if (!rs.wasNull() && p > 0) spot = BigDecimal.valueOf(p);
                    }
                }
            } catch (Exception e) {
                LOG.log(Level.FINE, "[Monitor] spot DB query failed", e);
            }
        }

        // Push to rolling history for delta window computation
        if (spot != null) {
            pushHistory(spotPriceHistory, underlying, Instant.now().toEpochMilli(), spot.doubleValue());
        }

        return spot;
    }

    // -------------------------------------------------------------------------
    // Session helpers
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Phase 1.5: Execution-session consistency
    // -------------------------------------------------------------------------

    /**
     * Per-tick invariant guard: compares session openQuantity against the execution ledger lots
     * for each leg that carries an executionId. Logs SEVERE on any mismatch so the operator
     * can investigate before the next reduce fires.
     *
     * <p>Uses the lightweight {@link OptionOrderService#loadExecutionLots()} (disk-only,
     * no enrichment) to keep the overhead of the 15-second tick low.
     */
    private void verifySessionExecutionConsistency(PositionSessionSnapshot session) {
        OptionOrderService localOrderService = this.orderService;
        if (localOrderService == null) return;
        try {
            java.util.Map<String, Integer> executionLots = localOrderService.loadExecutionLots();
            for (PositionSessionSnapshot.PositionLegSnapshot leg : session.legs()) {
                if (leg.openQuantity() <= 0) continue; // Closed legs need not match
                String execId = leg.executionId();
                if (execId == null || execId.isBlank()) continue; // Pure-sim legs have no execId
                if (!executionLots.containsKey(execId)) continue; // Not today's execution — skip
                int execLots = executionLots.get(execId);
                if (execLots != leg.openQuantity()) {
                    LOG.severe(String.format(
                            "[INVARIANT VIOLATION] session.openQty=%d != execution.lots=%d"
                            + " | execId=%s | legId=%s | sessionId=%s",
                            leg.openQuantity(), execLots,
                            execId, leg.legId(), session.sessionId()));
                }
            }
        } catch (Exception ex) {
            LOG.warning("[Monitor] Per-tick invariant check failed: " + ex.getMessage());
        }
    }

    /**
     * Returns a per-leg consistency snapshot for the debug endpoint.
     * Compares execution ledger lots against session openQuantity for all sessions loaded today.
     */
    public List<ConsistencyCheck> checkPositionConsistency() {
        List<ConsistencyCheck> result = new ArrayList<>();
        OptionOrderService localOrderService = this.orderService;
        if (localOrderService == null) return result;
        try {
            java.util.Map<String, Integer> executionLots = localOrderService.loadExecutionLots();
            List<PositionSessionSnapshot> sessions = sessionService.loadAll();
            for (PositionSessionSnapshot session : sessions) {
                for (PositionSessionSnapshot.PositionLegSnapshot leg : session.legs()) {
                    String execId = leg.executionId();
                    if (execId == null || execId.isBlank()) continue;
                    Integer execLots = executionLots.get(execId);
                    int diff = execLots == null ? leg.openQuantity() : (leg.openQuantity() - execLots);
                    result.add(new ConsistencyCheck(
                            session.sessionId(), leg.legId(), execId,
                            leg.openQuantity(),
                            execLots == null ? -1 : execLots,
                            diff,
                            execLots != null && diff == 0));
                }
            }
        } catch (Exception ex) {
            LOG.warning("[Monitor] checkPositionConsistency failed: " + ex.getMessage());
        }
        return result;
    }

    /** Per-leg consistency record returned by the /debug/position-consistency endpoint. */
    public record ConsistencyCheck(
            String sessionId,
            String legId,
            String executionId,
            int sessionOpenQty,
            int executionLots,
            int diff,
            boolean consistent) {}

    /**
     * Finds the active open session from the file store.
     * Returns the most recently created session with status "OPEN",
     * created today (IST). Returns null if none found.
     */
    PositionSessionSnapshot findActiveSession() {
        try {
            ZoneId ist = ZoneId.of("Asia/Kolkata");
            List<PositionSessionSnapshot> todaysSessions = sessionService.loadForTradingDay(LocalDate.now(ist), ist);
            return todaysSessions.stream()
                    .filter(AdjustmentMonitorLoop::isActiveSession)
                    .filter(s -> s.createdAt() != null)
                    .max(java.util.Comparator.comparing(PositionSessionSnapshot::createdAt))
                    .orElse(null);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[Monitor] Failed to load sessions", e);
            return null;
        }
    }

    static boolean isActiveSession(PositionSessionSnapshot session) {
        return session != null
                && isActiveSessionStatus(session.status())
                && session.legs() != null
                && session.legs().stream().anyMatch(leg -> leg != null && leg.openQuantity() > 0);
    }

    static boolean isActiveSessionStatus(String status) {
        return "OPEN".equalsIgnoreCase(status) || "PARTIALLY_EXITED".equalsIgnoreCase(status);
    }

    private String findLegIdByLabel(PositionSessionSnapshot session, String label) {
        return session.legs().stream()
                .filter(l -> label.equals(l.label()))
                .map(PositionSessionSnapshot.PositionLegSnapshot::legId)
                .findFirst()
                .orElse(null);
    }

    // -------------------------------------------------------------------------
    // Terminal result helpers
    // -------------------------------------------------------------------------

    private MonitorTickResult noSession(Instant now) {
        return new MonitorTickResult(now, null, null, null, null,
                List.of(), List.of(),
                "NO_SESSION", "NO_ACTIVE_SESSION",
                "No open position session found for today",
                false, null, null, null);
    }

    private MonitorTickResult noData(Instant now, String sessionId, String underlying,
                                     BigDecimal spot, String reason) {
        return new MonitorTickResult(now, sessionId, underlying, spot, null,
                List.of(), List.of(),
                "NO_DATA", "NO_DATA",
                reason, false, null, null, null);
    }
}
