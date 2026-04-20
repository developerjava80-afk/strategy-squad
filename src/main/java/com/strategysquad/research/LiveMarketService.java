package com.strategysquad.research;

import com.google.gson.Gson;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
            "SELECT instrument_id, expiry_date"
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

    private final String jdbcUrl;
    private final LiveSessionState sessionState;
    private final Live15mAggregator live15mAggregator;
    private final LiveStructureSnapshotService liveStructureSnapshotService;
    private final LiveStructureSnapshotWriter snapshotWriter;
    private final StrategyAnalysisService strategyAnalysisService;
    private final Gson gson;

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
                new LiveStructureSnapshotService(sessionState),
                new LiveStructureSnapshotWriter(),
                strategyAnalysisService,
                new Gson()
        );
    }

    LiveMarketService(
            String jdbcUrl,
            LiveSessionState sessionState,
            Live15mAggregator live15mAggregator,
            LiveStructureSnapshotService liveStructureSnapshotService,
            LiveStructureSnapshotWriter snapshotWriter,
            StrategyAnalysisService strategyAnalysisService,
            Gson gson
    ) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
        this.sessionState = Objects.requireNonNull(sessionState, "sessionState must not be null");
        this.live15mAggregator = Objects.requireNonNull(live15mAggregator, "live15mAggregator must not be null");
        this.liveStructureSnapshotService = Objects.requireNonNull(liveStructureSnapshotService, "liveStructureSnapshotService must not be null");
        this.snapshotWriter = Objects.requireNonNull(snapshotWriter, "snapshotWriter must not be null");
        this.strategyAnalysisService = Objects.requireNonNull(strategyAnalysisService, "strategyAnalysisService must not be null");
        this.gson = Objects.requireNonNull(gson, "gson must not be null");
    }

    public LiveStatusReport loadStatus() {
        return LiveStatusReport.from(sessionState);
    }

    public List<LiveSpotSnapshot> loadSpots() {
        return sessionState.getAllSpot().values().stream()
                .sorted(Comparator.comparing(LiveSessionState.SpotQuote::underlying))
                .map(spot -> new LiveSpotSnapshot(
                        spot.underlying(),
                        spot.price(),
                        spot.ts(),
                        isStale(spot.ts())
                ))
                .toList();
    }

    public LiveSpotSnapshot loadSpot(String underlying) {
        LiveSessionState.SpotQuote quote = sessionState.getLatestSpot(underlying);
        if (quote == null) {
            return null;
        }
        return new LiveSpotSnapshot(quote.underlying(), quote.price(), quote.ts(), isStale(quote.ts()));
    }

    public LiveStructureSnapshot loadStructure(StrategyStructureDefinition definition) throws SQLException {
        try (Connection connection = QuestDbConnectionFactory.open(jdbcUrl)) {
            return loadStructure(connection, definition, true);
        }
    }

    public LiveStructureTrendSnapshot loadStructureTrend(StrategyStructureDefinition definition) throws SQLException {
        LocalDate sessionDate = LocalDate.now(IST);
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
        LiveStructureTrendSnapshot trend = loadStructureTrend(definition);

        EconomicMetrics historicalComparison = null;
        if (liveSpot != null && !structure.partialData()) {
            StrategyStructureDefinition historicalDefinition = new StrategyStructureDefinition(
                    definition.mode(),
                    definition.orientation(),
                    definition.underlying(),
                    definition.expiryType(),
                    definition.dte(),
                    liveSpot.price(),
                    structure.legs().stream()
                            .filter(leg -> leg.lastPrice() != null)
                            .map(leg -> new StrategyStructureDefinition.StrategyLeg(
                                    leg.label(),
                                    leg.optionType(),
                                    leg.side(),
                                    leg.strike(),
                                    leg.lastPrice()
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

        return new LiveHistoricalOverlaySnapshot(
                status,
                liveSpot,
                structure,
                trend,
                historicalComparison
        );
    }

    private LiveStructureSnapshot loadStructure(
            Connection connection,
            StrategyStructureDefinition definition,
            boolean persistSnapshot
    ) throws SQLException {
        ResolvedStructure resolved = resolveStructure(connection, definition);
        LiveSpotSnapshot liveSpot = loadSpot(definition.underlying());
        List<LiveStructureSnapshotService.LegRequest> requests = resolved.legs().stream()
                .map(leg -> new LiveStructureSnapshotService.LegRequest(leg.instrumentId(), leg.side()))
                .toList();
        LiveStructureSnapshotService.Snapshot rawSnapshot = liveStructureSnapshotService.compute(requests);

        boolean partialData = rawSnapshot == null || rawSnapshot.partialData() || liveSpot == null;
        BigDecimal rawNet = rawSnapshot == null ? BigDecimal.ZERO : rawSnapshot.netPremium();
        BigDecimal economicNet = toEconomicPremium(rawNet, definition.orientation());
        Instant asOf = rawSnapshot == null ? null : rawSnapshot.asOf();
        List<LiveLegSnapshot> legs = mergeLegs(resolved.legs(), rawSnapshot);

        LiveStructureSnapshot snapshot = new LiveStructureSnapshot(
                structureKey(definition),
                definition.mode(),
                definition.orientation(),
                definition.underlying(),
                definition.expiryType(),
                liveSpot == null ? null : liveSpot.price(),
                liveSpot == null ? null : liveSpot.asOf(),
                economicNet,
                premiumLabel(definition.orientation()),
                partialData,
                asOf,
                legs
        );

        if (persistSnapshot) {
            persistStructureSnapshot(connection, snapshot);
        }
        return snapshot;
    }

    private void persistStructureSnapshot(Connection connection, LiveStructureSnapshot snapshot) throws SQLException {
        LocalDate sessionDate = LocalDate.now(IST);
        String detailJson = gson.toJson(snapshot.legs());
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
                    LocalDate expiryDate = expiryTs.toInstant().atZone(IST).toLocalDate();
                    candidates.add(new ResolvedLeg(
                            leg.label(),
                            leg.optionType(),
                            leg.side(),
                            leg.strike(),
                            rs.getString("instrument_id"),
                            expiryDate
                    ));
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        LocalDate today = LocalDate.now(IST);
        return candidates.stream()
                .min(Comparator.comparingLong(candidate ->
                        Math.abs(ChronoUnit.DAYS.between(today, candidate.expiryDate()) - definition.dte())))
                .orElse(null);
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
            double signed = "SHORT".equalsIgnoreCase(legValue.resolvedLeg().side())
                    ? -legValue.bucket().avgPrice()
                    : legValue.bucket().avgPrice();
            rawNet = rawNet.add(BigDecimal.valueOf(signed));
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

    private static List<LiveLegSnapshot> mergeLegs(
            List<ResolvedLeg> resolvedLegs,
            LiveStructureSnapshotService.Snapshot rawSnapshot
    ) {
        Map<String, LiveStructureSnapshotService.LegQuote> byInstrumentId = rawSnapshot == null
                ? Map.of()
                : rawSnapshot.legs().stream()
                .collect(Collectors.toMap(LiveStructureSnapshotService.LegQuote::instrumentId, leg -> leg));

        List<LiveLegSnapshot> rows = new ArrayList<>(resolvedLegs.size());
        for (ResolvedLeg leg : resolvedLegs) {
            LiveStructureSnapshotService.LegQuote liveQuote = byInstrumentId.get(leg.instrumentId());
            rows.add(new LiveLegSnapshot(
                    leg.label(),
                    leg.instrumentId(),
                    leg.optionType(),
                    leg.side(),
                    leg.strike(),
                    liveQuote == null ? null : liveQuote.lastPrice(),
                    liveQuote == null ? null : liveQuote.bidPrice(),
                    liveQuote == null ? null : liveQuote.askPrice(),
                    liveQuote == null
            ));
        }
        return rows;
    }

    private static BigDecimal toEconomicPremium(BigDecimal rawNet, String orientation) {
        return "SELLER".equalsIgnoreCase(orientation) ? rawNet.negate() : rawNet;
    }

    private static String premiumLabel(String orientation) {
        return "SELLER".equalsIgnoreCase(orientation) ? "Net credit" : "Net debit";
    }

    private static String structureKey(StrategyStructureDefinition definition) {
        String legs = definition.legs().stream()
                .map(leg -> "%s:%s:%s".formatted(
                        leg.optionType(),
                        leg.side(),
                        leg.strike().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()))
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

    private record ResolvedStructure(List<ResolvedLeg> legs, Map<String, ResolvedLeg> byInstrumentId) {
        private List<String> instrumentIds() {
            return legs.stream().map(ResolvedLeg::instrumentId).toList();
        }
    }

    private record ResolvedLeg(
            String label,
            String optionType,
            String side,
            BigDecimal strike,
            String instrumentId,
            LocalDate expiryDate
    ) {
    }

    private record LegBucketValue(Live15mBucket bucket, ResolvedLeg resolvedLeg) {
    }

    public record LiveSpotSnapshot(
            String underlying,
            BigDecimal price,
            Instant asOf,
            boolean stale
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
            boolean missing
    ) {
    }

    public record LiveStructureSnapshot(
            String structureKey,
            String mode,
            String orientation,
            String underlying,
            String expiryType,
            BigDecimal liveSpot,
            Instant liveSpotAsOf,
            BigDecimal economicNetPremiumPoints,
            String premiumLabel,
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
            EconomicMetrics historicalComparison
    ) {
    }
}
