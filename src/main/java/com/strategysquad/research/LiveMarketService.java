package com.strategysquad.research;

import com.google.gson.Gson;
import com.strategysquad.ingestion.kite.KiteOptionCloseQuoteService;
import com.strategysquad.ingestion.kite.LiveStructureSnapshotService;
import com.strategysquad.ingestion.live.session.Live15mAggregator;
import com.strategysquad.ingestion.live.session.Live15mBucket;
import com.strategysquad.ingestion.live.session.LiveSessionState;
import com.strategysquad.ingestion.live.session.LiveStatusReport;
import com.strategysquad.ingestion.live.session.LiveStructureSnapshotWriter;
import com.strategysquad.support.QuestDbConnectionFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Live-session overlay service.
 *
 * <p>Keeps live data isolated from canonical historical truth while making current
 * market state consumable by the UI through one explicit overlay layer.
 */
public final class LiveMarketService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String SELECT_ACTIVE_INSTRUMENTS_SQL =
            "SELECT instrument_id, trading_symbol, expiry_date, lot_size, updated_at"
                    + " FROM instrument_master"
                    + " WHERE is_active = true"
                    + "   AND underlying = ?"
                    + "   AND option_type = ?"
                    + "   AND expiry_type = ?"
                    + "   AND strike = ?";

    private static final String SELECT_LIVE_15M_SQL =
            "SELECT bucket_ts, session_date, instrument_id, time_bucket_15m,"
                    + " avg_price, min_price, max_price, volume_sum, sample_count, last_updated_ts"
                    + " FROM options_live_15m"
                    + " WHERE session_date = ?"
                    + "   AND instrument_id IN (%s)"
                    + " ORDER BY bucket_ts ASC";
    private static final String SELECT_CONTEXT_AVG_VOLUME_SQL =
            "SELECT avg_volume"
                    + " FROM options_context_buckets"
                    + " WHERE underlying = ?"
                    + "   AND option_type = ?"
                    + "   AND time_bucket_15m = ?"
                    + "   AND moneyness_bucket = ?"
                    + " ORDER BY bucket_ts DESC"
                    + " LIMIT 1";
    private static final String SELECT_CONTEXT_AVG_VOLUME_FALLBACK_SQL =
            "SELECT SUM(avg_volume * sample_count) / SUM(sample_count) AS weighted_avg_volume"
                    + " FROM options_context_buckets"
                    + " WHERE underlying = ?"
                    + "   AND option_type = ?"
                    + "   AND time_bucket_15m BETWEEN ? AND ?"
                    + "   AND moneyness_bucket BETWEEN ? AND ?"
                    + "   AND avg_volume > 0"
                    + "   AND sample_count > 0";
    private static final String SELECT_STRUCTURE_PNL_WINDOW_SQL =
            "SELECT net_premium"
                    + " FROM live_structure_snapshot"
                    + " WHERE session_date = ?"
                    + "   AND structure_key = ?"
                    + "   AND snapshot_ts >= ?"
                    + "   AND snapshot_ts < ?"
                    + " ORDER BY snapshot_ts ASC"
                    + " LIMIT 1";
    private static final int VOLUME_BASELINE_TIME_BUCKET_WINDOW = 96;
    private static final int VOLUME_BASELINE_MONEYNESS_WINDOW = 50;

    private final String jdbcUrl;
    private final LiveSessionState sessionState;
    private final Live15mAggregator live15mAggregator;
    private final LiveStructureSnapshotService liveStructureSnapshotService;
    private final LiveStructureSnapshotWriter snapshotWriter;
    private final StrategyAnalysisService strategyAnalysisService;
    private final MarketSessionStateResolver sessionStateResolver;
    private final CanonicalPriceResolverService canonicalPriceResolverService;
    private final EmpiricalDeltaResponseService empiricalDeltaResponseService;
    private final DeltaAdjustmentService deltaAdjustmentService;
    private final LiveMarketReadinessService liveMarketReadinessService;
    private final Gson gson;
    /** Non-null; defaults to a no-op (system) clock when no simulation is active. */
    private final SimulationClock simulationClock;

    public LiveMarketService(
            String jdbcUrl,
            LiveSessionState sessionState,
            Live15mAggregator live15mAggregator,
            StrategyAnalysisService strategyAnalysisService
    ) {
        this(
                jdbcUrl,
                sessionState,
                live15mAggregator,
                null,
                new MarketSessionStateResolver(
                        new CanonicalPriceResolverService.JdbcPriceDataSource(jdbcUrl, null)
                ),
                new CanonicalPriceResolverService(jdbcUrl, sessionState, null),
                new EmpiricalDeltaResponseService(jdbcUrl),
                new DeltaAdjustmentService(),
                new LiveMarketReadinessService(jdbcUrl),
                new LiveStructureSnapshotService(sessionState),
                new LiveStructureSnapshotWriter(),
                strategyAnalysisService,
                new Gson(),
                null
        );
    }

            public LiveMarketService(
                String jdbcUrl,
                LiveSessionState sessionState,
                Live15mAggregator live15mAggregator,
                KiteOptionCloseQuoteService optionCloseQuoteService,
                StrategyAnalysisService strategyAnalysisService
            ) {
            this(
                jdbcUrl,
                sessionState,
                live15mAggregator,
                optionCloseQuoteService,
                new MarketSessionStateResolver(
                        new CanonicalPriceResolverService.JdbcPriceDataSource(jdbcUrl, optionCloseQuoteService)
                ),
                new CanonicalPriceResolverService(jdbcUrl, sessionState, optionCloseQuoteService),
                new EmpiricalDeltaResponseService(jdbcUrl),
                new DeltaAdjustmentService(),
                new LiveMarketReadinessService(jdbcUrl),
                new LiveStructureSnapshotService(sessionState),
                new LiveStructureSnapshotWriter(),
                strategyAnalysisService,
                new Gson(),
                null
            );
            }

    /**
     * Simulation-aware constructor.  Pass a {@link SimulationClock} so that
     * {@link MarketSessionStateResolver} uses the replayed date rather than the
     * system clock, making the service behave as if the market is currently open
     * on the replay date.
     */
    public LiveMarketService(
            String jdbcUrl,
            LiveSessionState sessionState,
            Live15mAggregator live15mAggregator,
            KiteOptionCloseQuoteService optionCloseQuoteService,
            StrategyAnalysisService strategyAnalysisService,
            SimulationClock simulationClock
    ) {
        this(
                jdbcUrl,
                sessionState,
                live15mAggregator,
                optionCloseQuoteService,
                new MarketSessionStateResolver(
                        new CanonicalPriceResolverService.JdbcPriceDataSource(jdbcUrl, optionCloseQuoteService),
                        simulationClock != null ? simulationClock : new SimulationClock()
                ),
                new CanonicalPriceResolverService(jdbcUrl, sessionState, optionCloseQuoteService),
                new EmpiricalDeltaResponseService(jdbcUrl),
                new DeltaAdjustmentService(),
                new LiveMarketReadinessService(jdbcUrl),
                new LiveStructureSnapshotService(sessionState),
                new LiveStructureSnapshotWriter(),
                strategyAnalysisService,
                new Gson(),
                simulationClock
        );
    }

    LiveMarketService(
            String jdbcUrl,
            LiveSessionState sessionState,
            Live15mAggregator live15mAggregator,
            KiteOptionCloseQuoteService optionCloseQuoteService,
            MarketSessionStateResolver sessionStateResolver,
            CanonicalPriceResolverService canonicalPriceResolverService,
            EmpiricalDeltaResponseService empiricalDeltaResponseService,
            DeltaAdjustmentService deltaAdjustmentService,
            LiveMarketReadinessService liveMarketReadinessService,
            LiveStructureSnapshotService liveStructureSnapshotService,
            LiveStructureSnapshotWriter snapshotWriter,
            StrategyAnalysisService strategyAnalysisService,
            Gson gson,
            SimulationClock simulationClock
    ) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
        this.sessionState = Objects.requireNonNull(sessionState, "sessionState must not be null");
        this.live15mAggregator = Objects.requireNonNull(live15mAggregator, "live15mAggregator must not be null");
        this.sessionStateResolver = Objects.requireNonNull(sessionStateResolver, "sessionStateResolver must not be null");
        this.canonicalPriceResolverService = Objects.requireNonNull(canonicalPriceResolverService, "canonicalPriceResolverService must not be null");
        this.empiricalDeltaResponseService = Objects.requireNonNull(empiricalDeltaResponseService, "empiricalDeltaResponseService must not be null");
        this.deltaAdjustmentService = Objects.requireNonNull(deltaAdjustmentService, "deltaAdjustmentService must not be null");
        this.liveMarketReadinessService = Objects.requireNonNull(liveMarketReadinessService, "liveMarketReadinessService must not be null");
        this.liveStructureSnapshotService = Objects.requireNonNull(liveStructureSnapshotService, "liveStructureSnapshotService must not be null");
        this.snapshotWriter = Objects.requireNonNull(snapshotWriter, "snapshotWriter must not be null");
        this.strategyAnalysisService = Objects.requireNonNull(strategyAnalysisService, "strategyAnalysisService must not be null");
        this.gson = Objects.requireNonNull(gson, "gson must not be null");
        this.simulationClock = simulationClock != null ? simulationClock : new SimulationClock();
    }

    public LiveStatusReport loadStatus() {
        return LiveStatusReport.from(
                sessionState,
                liveMarketReadinessService.load(sessionState.getLastTickTs())
        );
    }

    public List<LiveSpotSnapshot> loadSpots() {
        return sessionState.getAllSpot().values().stream()
                .sorted(Comparator.comparing(LiveSessionState.SpotQuote::underlying))
                .map(spot -> new LiveSpotSnapshot(
                        spot.underlying(),
                        spot.price(),
                        spot.ts(),
                        spot.ts() == null ? null : spot.ts().atZone(MarketSessionStateResolver.EXCHANGE_ZONE).toLocalDate(),
                        isStale(spot.ts()),
                        "LIVE_LTP",
                        "live_cache",
                        MarketSessionStateResolver.SessionState.LIVE_MARKET.name(),
                        "Live spot snapshot"
                ))
                .toList();
    }

    public LiveSpotSnapshot loadSpot(String underlying) {
        MarketSessionStateResolver.SessionState sessionPhase = sessionStateResolver.resolveNow();
        CanonicalPriceResolverService.CanonicalInstrumentPrice canonicalPrice =
                canonicalPriceResolverService.getCanonicalInstrumentPrice(
                        CanonicalPriceResolverService.InstrumentKey.spot(underlying),
                        sessionPhase,
                        currentInstant()
                );
        if (canonicalPrice.price() == null) {
            return null;
        }
        return new LiveSpotSnapshot(
                underlying,
                canonicalPrice.price(),
                canonicalPrice.asOf(),
                canonicalPrice.tradeDate(),
                canonicalPrice.isStale(),
                canonicalPrice.priceType().name(),
                canonicalPrice.source(),
                canonicalPrice.sessionState().name(),
                canonicalPrice.diagnosticReason()
        );
    }

    public LiveStructureSnapshot loadStructure(StrategyStructureDefinition definition) throws SQLException {
        return loadStructure(definition, null);
    }

    public LiveStructureSnapshot loadStructure(
            StrategyStructureDefinition definition,
            DeltaAdjustmentService.LastAdjustment lastAdjustment
    ) throws SQLException {
        try (Connection connection = QuestDbConnectionFactory.open(jdbcUrl)) {
            return loadStructure(connection, definition, lastAdjustment, true);
        }
    }

    public LiveStructureTrendSnapshot loadStructureTrend(StrategyStructureDefinition definition) throws SQLException {
        LocalDate sessionDate = simulationClock.isSimulating()
                ? simulationClock.simulatedDate()
                : LocalDate.now(IST);
        try (Connection connection = QuestDbConnectionFactory.open(jdbcUrl)) {
            ResolvedStructure resolved = resolveStructure(connection, definition);
            List<Live15mBucket> persisted = loadPersistedBuckets(connection, sessionDate, resolved.legs());
            List<Live15mBucket> openBuckets = live15mAggregator.openBuckets(sessionDate).stream()
                    .filter(bucket -> resolved.instrumentIds().contains(bucket.instrumentId()))
                    .toList();

            Map<String, Live15mBucket> latestOpenByInstrument = new HashMap<>();
            for (Live15mBucket bucket : openBuckets) {
                latestOpenByInstrument.put(bucket.instrumentId(), bucket);
            }

            Map<String, Live15mBucket> persistedByKey = new LinkedHashMap<>();
            for (Live15mBucket bucket : persisted) {
                persistedByKey.put(bucket.instrumentId() + "|" + bucket.timeBucket15m(), bucket);
            }
            for (Live15mBucket bucket : latestOpenByInstrument.values()) {
                persistedByKey.put(bucket.instrumentId() + "|" + bucket.timeBucket15m(), bucket);
            }

            Map<Integer, List<LegBucketValue>> grouped = new HashMap<>();
            for (Live15mBucket bucket : persistedByKey.values()) {
                grouped.computeIfAbsent(bucket.timeBucket15m(), ignored -> new ArrayList<>())
                        .add(new LegBucketValue(bucket, resolved.byInstrumentId().get(bucket.instrumentId())));
            }

            List<LiveStructureTrendPoint> points = grouped.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.reverseOrder()))
                    .map(entry -> toTrendPoint(entry.getKey(), entry.getValue(), definition.orientation(), resolved.legs().size()))
                    .toList();

            return new LiveStructureTrendSnapshot(
                    structureKey(definition),
                    definition.mode(),
                    definition.orientation(),
                    definition.underlying(),
                    definition.expiryType(),
                    sessionDate,
                    points
            );
        }
    }

    public LiveHistoricalOverlaySnapshot loadOverlay(
            StrategyStructureDefinition definition,
            String timeframe,
            LocalDate customFrom,
            LocalDate customTo
    ) throws SQLException {
        LiveStatusReport status = loadStatus();
        LiveSpotSnapshot liveSpot = loadSpot(definition.underlying());
        LiveStructureSnapshot structure = loadStructure(definition);
        BigDecimal historicalSpot = liveSpot == null || liveSpot.price() == null ? definition.spot() : liveSpot.price();
        StrategyStructureDefinition liveDefinition = definitionWithSnapshotQuantities(definition, historicalSpot, structure);
        LiveStructureTrendSnapshot trend = loadStructureTrend(liveDefinition);

        EconomicMetrics historicalComparison = null;
        if (liveSpot != null && !structure.partialData()) {
            StrategyStructureDefinition historicalDefinition = new StrategyStructureDefinition(
                    liveDefinition.mode(),
                    liveDefinition.orientation(),
                    liveDefinition.underlying(),
                    liveDefinition.expiryType(),
                    liveDefinition.dte(),
                    historicalSpot,
                    structure.lastDeltaAdjustmentTs(),
                    structure.pendingAdjustmentSinceTs(),
                    structure.legs().stream()
                            .filter(leg -> leg.lastPrice() != null)
                            .map(leg -> new StrategyStructureDefinition.StrategyLeg(
                                    leg.label(),
                                    leg.optionType(),
                                    leg.side(),
                                    leg.strike(),
                                    leg.lastPrice(),
                                    leg.quantity()
                            ))
                            .toList()
            );
            if (historicalDefinition.legs().size() == definition.legs().size()) {
                historicalComparison = strategyAnalysisService.loadSnapshot(
                        historicalDefinition,
                        timeframe,
                        customFrom,
                        customTo
                );
            }
        }

        LiveEconomicMetrics liveEconomic = LiveEconomicMetricsTransformer.transform(
                liveDefinition,
                structure,
                historicalComparison
        );

        return new LiveHistoricalOverlaySnapshot(
                status,
                liveSpot,
                structure,
                trend,
                historicalComparison,
                liveEconomic
        );
    }

    private LiveStructureSnapshot loadStructure(
            Connection connection,
            StrategyStructureDefinition definition,
            DeltaAdjustmentService.LastAdjustment lastAdjustment,
            boolean persistSnapshot
    ) throws SQLException {
        ResolvedStructure resolved = resolveStructure(connection, definition);
        MarketSessionStateResolver.SessionState sessionPhase = sessionStateResolver.resolveNow();
        Instant resolutionAsOf = currentInstant();
        LiveSpotSnapshot liveSpot = loadSpot(definition.underlying());
        BigDecimal cohortSpot = liveSpot != null && liveSpot.price() != null ? liveSpot.price() : definition.spot();
        List<LiveStructureSnapshotService.LegRequest> requests = resolved.legs().stream()
                .map(leg -> new LiveStructureSnapshotService.LegRequest(leg.instrumentId(), leg.side()))
                .toList();
        LiveStructureSnapshotService.Snapshot rawSnapshot = liveStructureSnapshotService.compute(requests);
        List<ResolvedLeg> addResolved = resolveAddCandidates(connection, definition, resolved.legs(), cohortSpot);
        Map<String, ResolvedLeg> deltaResolvedByInstrument = new LinkedHashMap<>();
        for (ResolvedLeg leg : resolved.legs()) {
            deltaResolvedByInstrument.put(leg.instrumentId(), leg);
        }
        for (ResolvedLeg leg : addResolved) {
            deltaResolvedByInstrument.putIfAbsent(leg.instrumentId(), leg);
        }
        Map<String, EmpiricalDeltaResponseService.ContractDeltaResponse> deltaResponses =
                empiricalDeltaResponseService.loadResponses(
                        definition.underlying(),
                        deltaResolvedByInstrument.values().stream()
                                .map(leg -> CanonicalPriceResolverService.InstrumentKey.option(
                                        leg.instrumentId(),
                                        definition.underlying(),
                                        leg.tradingSymbol(),
                                        leg.expiryDate(),
                                        leg.strike(),
                                        leg.optionType()
                                ))
                                .toList(),
                        resolutionAsOf
                );

        boolean anyMissingLeg = false;
        List<LiveLegSnapshot> preliminaryLegs = new ArrayList<>(resolved.legs().size());
        for (ResolvedLeg leg : resolved.legs()) {
            LiveLegSnapshot liveLeg = buildLiveLegSnapshot(
                    connection,
                    definition,
                    sessionPhase,
                    resolutionAsOf,
                    cohortSpot,
                    rawSnapshot,
                    deltaResponses,
                    leg
            );
            if (liveLeg.lastPrice() == null) {
                anyMissingLeg = true;
            }
            preliminaryLegs.add(liveLeg);
        }
        List<LiveLegSnapshot> addCandidateLegs = new ArrayList<>(addResolved.size());
        for (ResolvedLeg candidate : addResolved) {
            addCandidateLegs.add(buildLiveLegSnapshot(
                    connection,
                    definition,
                    sessionPhase,
                    resolutionAsOf,
                    cohortSpot,
                    null,
                    deltaResponses,
                    candidate
            ));
        }
        // Underlying direction: signed 2-minute spot price slope from actual spot_live ticks.
        // Never inferred from option-leg delta responses (those values are unsigned ranges).
        DeltaAdjustmentService.UnderlyingDirection underlyingDirection;
        try {
            underlyingDirection = empiricalDeltaResponseService.loadUnderlyingDirection(
                    definition.underlying(), resolutionAsOf);
        } catch (Exception ignored) {
            underlyingDirection = DeltaAdjustmentService.UnderlyingDirection.NEUTRAL;
        }

        // Current economic net premium from live prices.
        BigDecimal prelimRawNet = BigDecimal.ZERO;
        for (LiveLegSnapshot pleg : preliminaryLegs) {
            if (pleg.lastPrice() == null) continue;
            int lc = lotCountForQuantity(pleg.quantity(), pleg.lotSize());
            BigDecimal contrib = pleg.lastPrice().multiply(BigDecimal.valueOf(lc));
            prelimRawNet = prelimRawNet.add("SHORT".equalsIgnoreCase(pleg.side()) ? contrib.negate() : contrib);
        }
        BigDecimal prelimEconomicNet = toEconomicPremium(prelimRawNet, definition.orientation());

        // Entry economic net premium from the user-supplied entry prices on definition.legs().
        // Matched to resolved legs (for correct lot size) by label.
        Map<String, ResolvedLeg> resolvedByLabel = resolved.legs().stream()
                .collect(java.util.stream.Collectors.toMap(ResolvedLeg::label, l -> l));
        BigDecimal entryRawNet = BigDecimal.ZERO;
        for (StrategyStructureDefinition.StrategyLeg defLeg : definition.legs()) {
            ResolvedLeg rleg = resolvedByLabel.get(defLeg.label());
            if (rleg == null || defLeg.entryPrice() == null) continue;
            int lc = lotCountForQuantity(rleg.quantity(), rleg.lotSize());
            BigDecimal contrib = defLeg.entryPrice().multiply(BigDecimal.valueOf(lc));
            entryRawNet = entryRawNet.add("SHORT".equalsIgnoreCase(rleg.side()) ? contrib.negate() : contrib);
        }
        BigDecimal entryEconomicNet = toEconomicPremium(entryRawNet, definition.orientation());

        BigDecimal livePnlPoints = livePnlPoints(definition.orientation(), entryEconomicNet, prelimEconomicNet);
        LivePnlSignal livePnlSignal = loadLivePnlSignal(
                connection,
                structureKey(definition),
                resolutionAsOf,
                definition.orientation(),
                entryEconomicNet,
                prelimEconomicNet
        );
        Map<String, StrategyStructureDefinition.StrategyLeg> definitionLegByLabel = definition.legs().stream()
                .collect(Collectors.toMap(
                        StrategyStructureDefinition.StrategyLeg::label,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        DeltaAdjustmentService.AdjustmentOutcome deltaAdjustment = deltaAdjustmentService.evaluate(
                new DeltaAdjustmentService.AdjustmentContext(
                        resolutionAsOf,
                        definition.lastDeltaAdjustmentTs(),
                        definition.pendingAdjustmentSinceTs(),
                        preliminaryLegs.stream()
                                .map(leg -> toAdjustmentLegState(
                                        definitionLegByLabel.get(leg.label()),
                                        leg,
                                        liveLegPnlPoints(definition, leg)
                                ))
                                .toList(),
                        addCandidateLegs.stream()
                                .map(leg -> toAdjustmentLegState(
                                        definitionLegByLabel.get(leg.label()),
                                        leg,
                                        liveLegPnlPoints(definition, leg)
                                ))
                                .toList(),
                        underlyingDirection,
                        livePnlPoints,
                        livePnlSignal.change2mPoints(),
                        livePnlSignal.change5mPoints(),
                        DeltaAdjustmentService.MAX_TOTAL_LOTS,
                        lastAdjustment
                )
        );
        Instant lastDeltaAdjustmentTs = deltaAdjustment == null
                ? definition.lastDeltaAdjustmentTs()
                : deltaAdjustment.updatedLastAdjustmentTs();
        Instant pendingAdjustmentSinceTs = deltaAdjustment == null
                ? definition.pendingAdjustmentSinceTs()
                : deltaAdjustment.updatedPendingAdjustmentSinceTs();
        List<LiveLegSnapshot> legs = applyAdjustmentToLegs(preliminaryLegs, addCandidateLegs, deltaAdjustment);
        BigDecimal rawNet = BigDecimal.ZERO;
        for (LiveLegSnapshot leg : legs) {
            if (leg.lastPrice() == null) {
                continue;
            }
            int lotCount = lotCountForQuantity(leg.quantity(), leg.lotSize());
            BigDecimal contribution = leg.lastPrice().multiply(BigDecimal.valueOf(lotCount));
            rawNet = rawNet.add("SHORT".equalsIgnoreCase(leg.side()) ? contribution.negate() : contribution);
        }
        boolean partialData = anyMissingLeg || liveSpot == null;
        BigDecimal economicNet = toEconomicPremium(rawNet, definition.orientation());
        Instant asOf = legs.stream()
                .map(LiveLegSnapshot::asOf)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(rawSnapshot == null ? null : rawSnapshot.asOf());
        StrategyStructureDefinition adjustedDefinition = new StrategyStructureDefinition(
                definition.mode(),
                definition.orientation(),
                definition.underlying(),
                definition.expiryType(),
                definition.dte(),
                definition.spot(),
                lastDeltaAdjustmentTs,
                pendingAdjustmentSinceTs,
                legs.stream()
                        .map(leg -> new StrategyStructureDefinition.StrategyLeg(
                                leg.label(),
                                leg.optionType(),
                                leg.side(),
                                leg.strike(),
                                leg.lastPrice() == null ? BigDecimal.ZERO : leg.lastPrice(),
                                leg.quantity()
                        ))
                        .toList()
        );

        LiveStructureSnapshot snapshot = new LiveStructureSnapshot(
                structureKey(adjustedDefinition),
                definition.mode(),
                definition.orientation(),
                definition.underlying(),
                definition.expiryType(),
                sessionPhase.name(),
                liveSpot == null ? null : liveSpot.price(),
                liveSpot == null ? null : liveSpot.asOf(),
                liveSpot == null ? null : liveSpot.tradeDate(),
                liveSpot == null ? null : liveSpot.priceType(),
                liveSpot == null ? null : liveSpot.source(),
                liveSpot != null && liveSpot.stale(),
                liveSpot == null ? null : liveSpot.diagnosticReason(),
                economicNet,
                premiumLabel(definition.orientation()),
                resolved.effectiveLotSize(),
                lastDeltaAdjustmentTs,
                pendingAdjustmentSinceTs,
                deltaAdjustment,
                partialData,
                asOf,
                legs
        );

        if (persistSnapshot && !simulationClock.isSimulating()) {
            persistStructureSnapshot(connection, snapshot);
        }
        return snapshot;
    }

    private LiveLegSnapshot buildLiveLegSnapshot(
            Connection connection,
            StrategyStructureDefinition definition,
            MarketSessionStateResolver.SessionState sessionPhase,
            Instant resolutionAsOf,
            BigDecimal cohortSpot,
            LiveStructureSnapshotService.Snapshot rawSnapshot,
            Map<String, EmpiricalDeltaResponseService.ContractDeltaResponse> deltaResponses,
            ResolvedLeg leg
    ) throws SQLException {
        CanonicalPriceResolverService.CanonicalInstrumentPrice canonicalPrice;
        try {
            canonicalPrice = canonicalPriceResolverService.getCanonicalInstrumentPrice(
                    CanonicalPriceResolverService.InstrumentKey.option(
                            leg.instrumentId(),
                            definition.underlying(),
                            leg.tradingSymbol(),
                            leg.expiryDate(),
                            leg.strike(),
                            leg.optionType()
                    ),
                    sessionPhase,
                    resolutionAsOf
            );
        } catch (RuntimeException exception) {
            canonicalPrice = CanonicalPriceResolverService.CanonicalInstrumentPrice.unavailable(
                    leg.instrumentId(),
                    sessionPhase,
                    "resolver_error",
                    resolutionAsOf,
                    "Canonical resolver failure: " + shortReason(exception)
            );
        }

        LiveStructureSnapshotService.LegQuote liveQuote = rawSnapshot == null || rawSnapshot.legs() == null
                ? null
                : rawSnapshot.legs().stream()
                .filter(item -> leg.instrumentId().equals(item.instrumentId()))
                .findFirst()
                .orElse(null);
        LiveSessionState.OptionQuote sessionQuote = sessionState.getLatestQuote(leg.instrumentId());
        EmpiricalDeltaResponseService.ContractDeltaResponse deltaResponse = deltaResponses.get(leg.instrumentId());

        return new LiveLegSnapshot(
                leg.label(),
                leg.instrumentId(),
                leg.optionType(),
                leg.side(),
                leg.strike(),
                canonicalPrice.price(),
                liveQuote != null
                        ? liveQuote.bidPrice()
                        : sessionQuote == null ? canonicalPrice.price() : sessionQuote.bidPrice(),
                liveQuote != null
                        ? liveQuote.askPrice()
                        : sessionQuote == null ? canonicalPrice.price() : sessionQuote.askPrice(),
                canonicalPrice.priceType().name(),
                canonicalPrice.source(),
                canonicalPrice.asOf(),
                canonicalPrice.isStale(),
                canonicalPrice.diagnosticReason(),
                canonicalPrice.price() == null,
                leg.lotSize(),
                leg.quantity(),
                sessionQuote == null ? null : sessionQuote.volume(),
                loadDayAverageVolume(connection, definition, leg, cohortSpot),
                slopeOrNull(deltaResponse == null ? null : deltaResponse.deltaResponse2m()),
                slopeOrNull(deltaResponse == null ? null : deltaResponse.deltaResponse5m()),
                slopeOrNull(deltaResponse == null ? null : deltaResponse.deltaResponseSod()),
                observationCountOrNull(deltaResponse == null ? null : deltaResponse.deltaResponse2m()),
                observationCountOrNull(deltaResponse == null ? null : deltaResponse.deltaResponse5m()),
                observationCountOrNull(deltaResponse == null ? null : deltaResponse.deltaResponseSod()),
                underlyingMoveOrNull(deltaResponse == null ? null : deltaResponse.deltaResponse2m()),
                underlyingMoveOrNull(deltaResponse == null ? null : deltaResponse.deltaResponse5m()),
                underlyingMoveOrNull(deltaResponse == null ? null : deltaResponse.deltaResponseSod()),
                deltaResponse == null ? null : deltaResponse.calculatedAt()
        );
    }

    private DeltaAdjustmentService.LegState toAdjustmentLegState(
            StrategyStructureDefinition.StrategyLeg definitionLeg,
            LiveLegSnapshot liveLeg,
            BigDecimal liveLegPnlPoints
    ) {
        return new DeltaAdjustmentService.LegState(
                liveLeg.label(),
                liveLeg.optionType(),
                liveLeg.side(),
                liveLeg.strike(),
                liveLeg.quantity(),
                liveLeg.lotSize(),
                definitionLeg == null ? null : definitionLeg.entryPrice(),
                liveLeg.lastPrice(),
                liveLeg.deltaResponse2m(),
                liveLeg.deltaResponse5m(),
                liveLeg.deltaResponseSod(),
                liveLeg.currentVolume(),
                liveLeg.dayAverageVolume(),
                liveLegPnlPoints,
                liveLeg.instrumentId(),
                liveLeg.instrumentId(),
                resolveLiveLegExpiryDate(liveLeg),
                liveLeg.stale() || liveLeg.missing()
        );
    }

    private List<LiveLegSnapshot> applyAdjustmentToLegs(
            List<LiveLegSnapshot> currentLegs,
            List<LiveLegSnapshot> addCandidateLegs,
            DeltaAdjustmentService.AdjustmentOutcome deltaAdjustment
    ) {
        if (deltaAdjustment == null || !deltaAdjustment.applied()) {
            return currentLegs;
        }
        List<LiveLegSnapshot> updated = new ArrayList<>(currentLegs.size() + 1);
        boolean matched = false;
        for (LiveLegSnapshot leg : currentLegs) {
            if (matchesAdjustment(leg, deltaAdjustment)) {
                updated.add(withQuantity(leg, deltaAdjustment.newQuantity()));
                matched = true;
            } else {
                updated.add(leg);
            }
        }
        if (!matched && "ADD".equalsIgnoreCase(deltaAdjustment.actionType())) {
            for (LiveLegSnapshot candidate : addCandidateLegs) {
                if (matchesAdjustment(candidate, deltaAdjustment)) {
                    updated.add(withQuantity(candidate, deltaAdjustment.newQuantity()));
                    matched = true;
                    break;
                }
            }
        }
        return matched ? updated : currentLegs;
    }

    private static boolean matchesAdjustment(
            LiveLegSnapshot leg,
            DeltaAdjustmentService.AdjustmentOutcome adjustment
    ) {
        if (adjustment.instrumentId() != null && !adjustment.instrumentId().isBlank()) {
            return adjustment.instrumentId().equals(leg.instrumentId());
        }
        return Objects.equals(adjustment.leg(), leg.label())
                && Objects.equals(adjustment.optionType(), leg.optionType())
                && Objects.equals(adjustment.side(), leg.side())
                && adjustment.strike() != null
                && leg.strike() != null
                && adjustment.strike().compareTo(leg.strike()) == 0;
    }

    private static LiveLegSnapshot withQuantity(LiveLegSnapshot leg, int quantity) {
        return new LiveLegSnapshot(
                leg.label(),
                leg.instrumentId(),
                leg.optionType(),
                leg.side(),
                leg.strike(),
                leg.lastPrice(),
                leg.bidPrice(),
                leg.askPrice(),
                leg.priceType(),
                leg.source(),
                leg.asOf(),
                leg.stale(),
                leg.diagnosticReason(),
                leg.missing(),
                leg.lotSize(),
                quantity,
                leg.currentVolume(),
                leg.dayAverageVolume(),
                leg.deltaResponse2m(),
                leg.deltaResponse5m(),
                leg.deltaResponseSod(),
                leg.deltaResponse2mObservationCount(),
                leg.deltaResponse5mObservationCount(),
                leg.deltaResponseSodObservationCount(),
                leg.deltaResponse2mUnderlyingMove(),
                leg.deltaResponse5mUnderlyingMove(),
                leg.deltaResponseSodUnderlyingMove(),
                leg.deltaResponseCalculatedAt()
        );
    }

    private List<ResolvedLeg> resolveAddCandidates(
            Connection connection,
            StrategyStructureDefinition definition,
            List<ResolvedLeg> currentLegs,
            BigDecimal spot
    ) throws SQLException {
        if (currentLegs.isEmpty() || spot == null) {
            return List.of();
        }
        int bucket = strikeBucket(definition.underlying(), currentLegs);
        BigDecimal atmStrike = roundToBucket(spot, bucket);
        Map<String, ResolvedLeg> currentByKey = currentLegs.stream()
                .collect(Collectors.toMap(this::identityKey, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<String, ResolvedLeg> resolved = new LinkedHashMap<>();

        for (ResolvedLeg baseLeg : currentLegs) {
            for (BigDecimal strike : List.of(
                    atmStrike.subtract(BigDecimal.valueOf(bucket)),
                    atmStrike,
                    atmStrike.add(BigDecimal.valueOf(bucket)),
                    baseLeg.strike().subtract(BigDecimal.valueOf(bucket)),
                    baseLeg.strike().add(BigDecimal.valueOf(bucket))
            )) {
                if (strike == null || strike.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                String candidateLabel = "Adj " + baseLeg.side() + " " + baseLeg.optionType() + " "
                        + strike.setScale(0, java.math.RoundingMode.HALF_UP).toPlainString();
                StrategyStructureDefinition.StrategyLeg syntheticLeg = new StrategyStructureDefinition.StrategyLeg(
                        candidateLabel,
                        baseLeg.optionType(),
                        baseLeg.side(),
                        strike,
                        BigDecimal.ZERO,
                        baseLeg.lotSize()
                );
                ResolvedLeg candidate = resolveLeg(connection, definition, syntheticLeg);
                if (candidate == null) {
                    continue;
                }
                String identityKey = identityKey(candidate);
                if (currentByKey.containsKey(identityKey)) {
                    continue;
                }
                resolved.putIfAbsent(identityKey, candidate);
            }
        }
        return new ArrayList<>(resolved.values());
    }

    private int strikeBucket(String underlying, List<ResolvedLeg> currentLegs) {
        String normalized = underlying == null ? "" : underlying.trim().toUpperCase(java.util.Locale.ROOT);
        if ("BANKNIFTY".equals(normalized)) {
            return 100;
        }
        return 50;
    }

    private BigDecimal roundToBucket(BigDecimal value, int bucket) {
        if (value == null || bucket <= 0) {
            return value;
        }
        return value.divide(BigDecimal.valueOf(bucket), 0, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(bucket));
    }

    private String identityKey(ResolvedLeg leg) {
        return leg.side() + "|" + leg.optionType() + "|" + leg.strike().stripTrailingZeros().toPlainString();
    }

    private static String resolveLiveLegExpiryDate(LiveLegSnapshot liveLeg) {
        String instrumentId = liveLeg.instrumentId();
        if (instrumentId == null) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("_(\\d{8})_").matcher(instrumentId);
        if (!matcher.find()) {
            return "";
        }
        String raw = matcher.group(1);
        return raw.substring(0, 4) + "-" + raw.substring(4, 6) + "-" + raw.substring(6, 8);
    }

    private void persistStructureSnapshot(Connection connection, LiveStructureSnapshot snapshot) throws SQLException {
        LocalDate sessionDate = simulationClock.isSimulating()
                ? simulationClock.simulatedDate()
                : LocalDate.now(IST);
                String detailJson;
                try {
                        detailJson = gson.toJson(snapshot.legs());
                } catch (RuntimeException exception) {
                        // Persistence failure must not break the live API response path.
                        detailJson = "[]";
                }
        snapshotWriter.write(connection, List.of(new LiveStructureSnapshotWriter.SnapshotRow(
                snapshot.asOf() == null ? Instant.now() : snapshot.asOf(),
                sessionDate,
                snapshot.structureKey(),
                snapshot.underlying(),
                snapshot.expiryType(),
                snapshot.economicNetPremiumPoints().doubleValue(),
                snapshot.legs().size(),
                detailJson
        )));
    }

    private ResolvedStructure resolveStructure(Connection connection, StrategyStructureDefinition definition) throws SQLException {
        List<ResolvedLeg> resolved = new ArrayList<>(definition.legs().size());
        for (StrategyStructureDefinition.StrategyLeg leg : definition.legs()) {
            ResolvedLeg match = resolveLeg(connection, definition, leg);
            if (match == null) {
                throw new IllegalArgumentException(
                        "No active live contract found for %s %s %.2f %s".formatted(
                                definition.underlying(),
                                leg.optionType(),
                                leg.strike().doubleValue(),
                                definition.expiryType()
                        ));
            }
            resolved.add(match);
        }
        Map<String, ResolvedLeg> byInstrumentId = resolved.stream()
                .collect(Collectors.toMap(ResolvedLeg::instrumentId, leg -> leg));
        return new ResolvedStructure(resolved, byInstrumentId);
    }

    private ResolvedLeg resolveLeg(
            Connection connection,
            StrategyStructureDefinition definition,
            StrategyStructureDefinition.StrategyLeg leg
    ) throws SQLException {
        List<ResolvedLeg> candidates = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(SELECT_ACTIVE_INSTRUMENTS_SQL)) {
            stmt.setString(1, definition.underlying());
            stmt.setString(2, leg.optionType());
            stmt.setString(3, definition.expiryType());
            stmt.setDouble(4, leg.strike().doubleValue());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Timestamp expiryTs = rs.getTimestamp("expiry_date");
                                        if (expiryTs == null) {
                                                continue;
                                        }
                    LocalDate expiryDate = expiryTs.toInstant().atZone(IST).toLocalDate();
                    candidates.add(new ResolvedLeg(
                            leg.label(),
                            leg.optionType(),
                            leg.side(),
                            leg.strike(),
                            rs.getString("instrument_id"),
                            rs.getString("trading_symbol"),
                            expiryDate,
                            rs.getInt("lot_size"),
                            definition.normalizedQuantity(leg),
                            timestampToInstant(rs.getTimestamp("updated_at"))
                    ));
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        LocalDate today = simulationClock.isSimulating()
                ? simulationClock.simulatedDate()
                : LocalDate.now(IST);
        return candidates.stream()
                .sorted(Comparator
                        .comparing((ResolvedLeg candidate) ->
                                Math.abs(ChronoUnit.DAYS.between(today, candidate.expiryDate()) - definition.dte()))
                        .thenComparing(ResolvedLeg::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst()
                .orElse(null);
    }

    private static Instant timestampToInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String shortReason(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable == null ? "unknown" : throwable.getClass().getSimpleName();
        }
        return message;
    }

    private BigDecimal livePnlPoints(
            String orientation,
            BigDecimal entryEconomicNet,
            BigDecimal currentEconomicNet
    ) {
        if ("SELLER".equalsIgnoreCase(orientation)) {
            return entryEconomicNet.subtract(currentEconomicNet);
        }
        return currentEconomicNet.subtract(entryEconomicNet);
    }

    private LivePnlSignal loadLivePnlSignal(
            Connection connection,
            String structureKey,
            Instant asOf,
            String orientation,
            BigDecimal entryEconomicNet,
            BigDecimal currentEconomicNet
    ) throws SQLException {
        BigDecimal currentPoints = livePnlPoints(orientation, entryEconomicNet, currentEconomicNet);
        if (simulationClock.isSimulating() || structureKey == null || structureKey.isBlank() || asOf == null) {
            return new LivePnlSignal(currentPoints, null, null);
        }
        LocalDate sessionDate = currentSessionDate();
        BigDecimal change2mPoints = loadLivePnlWindowChange(
                connection,
                structureKey,
                sessionDate,
                asOf.minus(Duration.ofMinutes(2)),
                asOf,
                orientation,
                entryEconomicNet,
                currentPoints
        );
        BigDecimal change5mPoints = loadLivePnlWindowChange(
                connection,
                structureKey,
                sessionDate,
                asOf.minus(Duration.ofMinutes(5)),
                asOf,
                orientation,
                entryEconomicNet,
                currentPoints
        );
        return new LivePnlSignal(currentPoints, change2mPoints, change5mPoints);
    }

    private BigDecimal loadLivePnlWindowChange(
            Connection connection,
            String structureKey,
            LocalDate sessionDate,
            Instant windowStart,
            Instant asOf,
            String orientation,
            BigDecimal entryEconomicNet,
            BigDecimal currentPnlPoints
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_STRUCTURE_PNL_WINDOW_SQL)) {
            statement.setTimestamp(1, Timestamp.from(sessionDate.atStartOfDay(IST).toInstant()));
            statement.setString(2, structureKey);
            statement.setTimestamp(3, Timestamp.from(windowStart));
            statement.setTimestamp(4, Timestamp.from(asOf));
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                BigDecimal priorEconomicNet = rs.getBigDecimal("net_premium");
                if (priorEconomicNet == null) {
                    return null;
                }
                BigDecimal priorPnlPoints = livePnlPoints(orientation, entryEconomicNet, priorEconomicNet);
                return currentPnlPoints.subtract(priorPnlPoints);
            }
        }
    }

    private BigDecimal liveLegPnlPoints(
            StrategyStructureDefinition definition,
            LiveLegSnapshot leg
    ) {
        if (leg == null || leg.lastPrice() == null) {
            return null;
        }
        StrategyStructureDefinition.StrategyLeg definitionLeg = definition.legs().stream()
                .filter(item -> Objects.equals(item.label(), leg.label()))
                .findFirst()
                .orElse(null);
        if (definitionLeg == null || definitionLeg.entryPrice() == null) {
            return null;
        }
        BigDecimal perLotPnl = "SHORT".equalsIgnoreCase(leg.side())
                ? definitionLeg.entryPrice().subtract(leg.lastPrice())
                : leg.lastPrice().subtract(definitionLeg.entryPrice());
        return perLotPnl.multiply(BigDecimal.valueOf(lotCountForQuantity(leg.quantity(), leg.lotSize())));
    }

    private LocalDate currentSessionDate() {
        return simulationClock.isSimulating()
                ? simulationClock.simulatedDate()
                : LocalDate.now(IST);
    }

    private BigDecimal loadDayAverageVolume(
            Connection connection,
            StrategyStructureDefinition definition,
            ResolvedLeg leg,
            BigDecimal spot
    ) throws SQLException {
        CanonicalCohortKey cohort = CanonicalScenarioResolver.resolve(
                definition.underlying(),
                leg.optionType(),
                spot,
                leg.strike(),
                definition.dte()
        );
        try (PreparedStatement statement = connection.prepareStatement(SELECT_CONTEXT_AVG_VOLUME_SQL)) {
            statement.setString(1, definition.underlying());
            statement.setString(2, leg.optionType());
            statement.setInt(3, cohort.timeBucket15m());
            statement.setInt(4, cohort.moneynessBucket());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return loadNearbyAverageVolume(connection, definition.underlying(), leg.optionType(), cohort);
                }
                BigDecimal avgVolume = rs.getBigDecimal("avg_volume");
                if (avgVolume == null || avgVolume.compareTo(BigDecimal.ZERO) <= 0) {
                    return loadNearbyAverageVolume(connection, definition.underlying(), leg.optionType(), cohort);
                }
                return avgVolume;
            }
        }
    }

    private BigDecimal loadNearbyAverageVolume(
            Connection connection,
            String underlying,
            String optionType,
            CanonicalCohortKey cohort
    ) throws SQLException {
        int bucketLo = Math.max(0, cohort.timeBucket15m() - VOLUME_BASELINE_TIME_BUCKET_WINDOW);
        int bucketHi = cohort.timeBucket15m() + VOLUME_BASELINE_TIME_BUCKET_WINDOW;
        int moneynessLo = cohort.moneynessBucket() - VOLUME_BASELINE_MONEYNESS_WINDOW;
        int moneynessHi = cohort.moneynessBucket() + VOLUME_BASELINE_MONEYNESS_WINDOW;
        try (PreparedStatement statement = connection.prepareStatement(SELECT_CONTEXT_AVG_VOLUME_FALLBACK_SQL)) {
            statement.setString(1, underlying);
            statement.setString(2, optionType);
            statement.setInt(3, bucketLo);
            statement.setInt(4, bucketHi);
            statement.setInt(5, moneynessLo);
            statement.setInt(6, moneynessHi);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                BigDecimal avgVolume = rs.getBigDecimal("weighted_avg_volume");
                return avgVolume == null || avgVolume.compareTo(BigDecimal.ZERO) <= 0 ? null : avgVolume;
            }
        }
    }

    private static int lotCountForQuantity(int quantity, int lotSize) {
        if (lotSize <= 0) {
            return 1;
        }
        if (quantity <= 0) {
            return 0;
        }
        return Math.max(1, quantity / lotSize);
    }

    private static StrategyStructureDefinition definitionWithSnapshotQuantities(
            StrategyStructureDefinition definition,
            BigDecimal spot,
            LiveStructureSnapshot structure
    ) {
        return new StrategyStructureDefinition(
                definition.mode(),
                definition.orientation(),
                definition.underlying(),
                definition.expiryType(),
                definition.dte(),
                spot,
                structure == null ? definition.lastDeltaAdjustmentTs() : structure.lastDeltaAdjustmentTs(),
                structure == null ? definition.pendingAdjustmentSinceTs() : structure.pendingAdjustmentSinceTs(),
                structure == null
                        ? definition.legs()
                        : structure.legs().stream()
                        .map(leg -> new StrategyStructureDefinition.StrategyLeg(
                                leg.label(),
                                leg.optionType(),
                                leg.side(),
                                leg.strike(),
                                leg.lastPrice() == null ? BigDecimal.ZERO : leg.lastPrice(),
                                leg.quantity()
                        ))
                        .toList()
        );
    }

    private List<Live15mBucket> loadPersistedBuckets(
            Connection connection,
            LocalDate sessionDate,
            List<ResolvedLeg> resolvedLegs
    ) throws SQLException {
        if (resolvedLegs.isEmpty()) {
            return List.of();
        }
        String placeholders = resolvedLegs.stream().map(ignored -> "?").collect(Collectors.joining(","));
        String sql = SELECT_LIVE_15M_SQL.formatted(placeholders);
        List<Live15mBucket> buckets = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.from(sessionDate.atStartOfDay(IST).toInstant()));
            for (int i = 0; i < resolvedLegs.size(); i++) {
                stmt.setString(i + 2, resolvedLegs.get(i).instrumentId());
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    buckets.add(new Live15mBucket(
                            rs.getTimestamp("bucket_ts").toInstant(),
                            rs.getTimestamp("session_date").toInstant().atZone(IST).toLocalDate(),
                            rs.getString("instrument_id"),
                            rs.getInt("time_bucket_15m"),
                            rs.getDouble("avg_price"),
                            rs.getDouble("min_price"),
                            rs.getDouble("max_price"),
                            rs.getLong("volume_sum"),
                            rs.getLong("sample_count"),
                            rs.getTimestamp("last_updated_ts").toInstant()
                    ));
                }
            }
        }
        return buckets;
    }

    private static LiveStructureTrendPoint toTrendPoint(
            int timeBucket15m,
            List<LegBucketValue> legs,
            String orientation,
            int expectedLegCount
    ) {
        BigDecimal rawNet = BigDecimal.ZERO;
        long sampleCount = Long.MAX_VALUE;
        long volumeSum = 0L;
        Instant bucketTs = null;

        for (LegBucketValue legValue : legs) {
            int lotCount = lotCountForQuantity(legValue.resolvedLeg().quantity(), legValue.resolvedLeg().lotSize());
            BigDecimal signed = BigDecimal.valueOf("SHORT".equalsIgnoreCase(legValue.resolvedLeg().side())
                            ? -legValue.bucket().avgPrice()
                            : legValue.bucket().avgPrice())
                    .multiply(BigDecimal.valueOf(lotCount));
            rawNet = rawNet.add(signed);
            sampleCount = Math.min(sampleCount, legValue.bucket().sampleCount());
            volumeSum += legValue.bucket().volumeSum();
            if (bucketTs == null || legValue.bucket().bucketTs().isBefore(bucketTs)) {
                bucketTs = legValue.bucket().bucketTs();
            }
        }

        return new LiveStructureTrendPoint(
                bucketTs,
                timeBucket15m,
                toEconomicPremium(rawNet, orientation),
                volumeSum,
                sampleCount == Long.MAX_VALUE ? 0L : sampleCount,
                legs.size() == expectedLegCount
        );
    }

    private static BigDecimal toEconomicPremium(BigDecimal rawNet, String orientation) {
        return "SELLER".equalsIgnoreCase(orientation) ? rawNet.negate() : rawNet;
    }

    private static String premiumLabel(String orientation) {
        return "SELLER".equalsIgnoreCase(orientation) ? "Net credit" : "Net debit";
    }

    private static String structureKey(StrategyStructureDefinition definition) {
        String legs = definition.legs().stream()
                .map(leg -> "%s:%s:%s:%d".formatted(
                        leg.optionType(),
                        leg.side(),
                        leg.strike().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                        definition.normalizedQuantity(leg)))
                .collect(Collectors.joining("|"));
        return "%s|%s|%s|%d|%s".formatted(
                definition.mode(),
                definition.underlying(),
                definition.expiryType(),
                definition.dte(),
                legs
        );
    }

    private static boolean isStale(Instant asOf) {
        return asOf == null || ChronoUnit.SECONDS.between(asOf, Instant.now()) >= 30;
    }

    private static BigDecimal slopeOrNull(EmpiricalDeltaResponseService.DeltaWindowResponse response) {
        return response == null ? null : response.slope();
    }

    private static Integer observationCountOrNull(EmpiricalDeltaResponseService.DeltaWindowResponse response) {
        return response == null ? null : response.observationCount();
    }

    private static BigDecimal underlyingMoveOrNull(EmpiricalDeltaResponseService.DeltaWindowResponse response) {
        return response == null ? null : response.underlyingMove();
    }

    private Instant currentInstant() {
        return simulationClock.isSimulating() ? simulationClock.instant() : Instant.now();
    }

    private record LivePnlSignal(
            BigDecimal currentPoints,
            BigDecimal change2mPoints,
            BigDecimal change5mPoints
    ) {
    }

    private record ResolvedStructure(List<ResolvedLeg> legs, Map<String, ResolvedLeg> byInstrumentId) {
        private List<String> instrumentIds() {
            return legs.stream().map(ResolvedLeg::instrumentId).toList();
        }

        private int effectiveLotSize() {
            return legs.stream()
                    .mapToInt(ResolvedLeg::lotSize)
                    .filter(value -> value > 0)
                    .min()
                    .orElse(1);
        }
    }

    private record ResolvedLeg(
            String label,
            String optionType,
            String side,
            BigDecimal strike,
            String instrumentId,
            String tradingSymbol,
            LocalDate expiryDate,
            int lotSize,
            int quantity,
            Instant updatedAt
    ) {
    }

    private record LegBucketValue(Live15mBucket bucket, ResolvedLeg resolvedLeg) {
    }

    public record LiveSpotSnapshot(
            String underlying,
            BigDecimal price,
            Instant asOf,
            LocalDate tradeDate,
            boolean stale,
            String priceType,
            String source,
            String sessionState,
            String diagnosticReason
    ) {
    }

    public record LiveLegSnapshot(
            String label,
            String instrumentId,
            String optionType,
            String side,
            BigDecimal strike,
            BigDecimal lastPrice,
            BigDecimal bidPrice,
            BigDecimal askPrice,
            String priceType,
            String source,
            Instant asOf,
            boolean stale,
            String diagnosticReason,
            boolean missing,
            int lotSize,
            int quantity,
            Long currentVolume,
            BigDecimal dayAverageVolume,
            BigDecimal deltaResponse2m,
            BigDecimal deltaResponse5m,
            BigDecimal deltaResponseSod,
            Integer deltaResponse2mObservationCount,
            Integer deltaResponse5mObservationCount,
            Integer deltaResponseSodObservationCount,
            BigDecimal deltaResponse2mUnderlyingMove,
            BigDecimal deltaResponse5mUnderlyingMove,
            BigDecimal deltaResponseSodUnderlyingMove,
            Instant deltaResponseCalculatedAt
    ) {
    }

    public record LiveStructureSnapshot(
            String structureKey,
            String mode,
            String orientation,
            String underlying,
            String expiryType,
            String sessionState,
            BigDecimal liveSpot,
            Instant liveSpotAsOf,
            LocalDate liveSpotTradeDate,
            String liveSpotPriceType,
            String liveSpotSource,
            boolean liveSpotStale,
            String liveSpotDiagnosticReason,
            BigDecimal economicNetPremiumPoints,
            String premiumLabel,
            int effectiveLotSize,
            Instant lastDeltaAdjustmentTs,
            Instant pendingAdjustmentSinceTs,
            DeltaAdjustmentService.AdjustmentOutcome deltaAdjustment,
            boolean partialData,
            Instant asOf,
            List<LiveLegSnapshot> legs
    ) {
    }

    public record LiveStructureTrendPoint(
            Instant bucketTs,
            int timeBucket15m,
            BigDecimal economicNetPremiumPoints,
            long volumeSum,
            long sampleCount,
            boolean completeStructure
    ) {
    }

    public record LiveStructureTrendSnapshot(
            String structureKey,
            String mode,
            String orientation,
            String underlying,
            String expiryType,
            LocalDate sessionDate,
            List<LiveStructureTrendPoint> points
    ) {
    }

    public record LiveHistoricalOverlaySnapshot(
            LiveStatusReport status,
            LiveSpotSnapshot spot,
            LiveStructureSnapshot structure,
            LiveStructureTrendSnapshot trend,
            EconomicMetrics historicalComparison,
            LiveEconomicMetrics liveEconomic
    ) {
    }
}
