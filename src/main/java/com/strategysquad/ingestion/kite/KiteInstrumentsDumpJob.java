package com.strategysquad.ingestion.kite;

import com.strategysquad.ingestion.bhavcopy.InstrumentKey;
import com.strategysquad.ingestion.bhavcopy.InstrumentResolver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Downloads the Kite NFO instruments dump, filters for NIFTY/BANKNIFTY options,
 * inserts new instruments into instrument_master, and returns a token-to-id mapping
 * for use by the WebSocket ticker.
 *
 * <p>The raw NFO CSV is cached in-process for the calendar day so that repeated login
 * attempts do not re-hit the Kite instruments endpoint and trigger HTTP 429 rate limiting.
 */
public final class KiteInstrumentsDumpJob {

    private static final String NFO_INSTRUMENTS_URL = "https://api.kite.trade/instruments/NFO";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final AtomicReference<CachedDump> RAW_DUMP_CACHE = new AtomicReference<>(null);
    private record CachedDump(LocalDate date, List<KiteInstrumentRecord> records) {}

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 5_000;

    private static final String INSERT_INSTRUMENT_SQL =
            "INSERT INTO instrument_master"
                    + " (instrument_id, underlying, symbol, expiry_date, strike, option_type,"
                    + "  lot_size, tick_size, exchange_token, trading_symbol,"
                    + "  is_active, expiry_type, created_at, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_INSTRUMENT_SQL =
            "UPDATE instrument_master"
                    + " SET underlying = ?, symbol = ?, expiry_date = ?, strike = ?, option_type = ?,"
                    + "     lot_size = ?, tick_size = ?, exchange_token = ?, trading_symbol = ?,"
                    + "     is_active = ?, expiry_type = ?, updated_at = ?"
                    + " WHERE instrument_id = ?";

    private static final String SELECT_EXISTING_SQL =
            "SELECT instrument_id FROM instrument_master";

    private final InstrumentResolver instrumentResolver;
    private final HttpClient httpClient;

    public KiteInstrumentsDumpJob() {
        this(new InstrumentResolver(), HttpClient.newHttpClient());
    }

    public KiteInstrumentsDumpJob(InstrumentResolver instrumentResolver, HttpClient httpClient) {
        this.instrumentResolver = instrumentResolver;
        this.httpClient = httpClient;
    }

    /**
     * Downloads (or retrieves from the day-scoped cache) the full NFO instrument list
     * filtered to the configured NIFTY/BANKNIFTY strike window.
     *
     * <p>This is the only instrument-loading path in the scope-first design (Phase 6).
     * The old ATM-only Phase-1 method has been removed.
     */
    public List<KiteInstrumentRecord> downloadFull(
            KiteLiveConfig config,
            LocalDate referenceDate,
            double niftyAtm,
            double bankNiftyAtm
    ) throws Exception {
        List<KiteInstrumentRecord> raw = downloadAndParse(config);
        return KiteInstrumentFilter.filter(raw, referenceDate,
                niftyAtm, bankNiftyAtm,
                config.niftyStrikeWindowPoints(),
                config.bankNiftyStrikeWindowPoints(),
                config.subscribeNextWeekly(),
                config.subscribeNextMonthly());
    }

    /** Build the token to instrument_id map for a filtered instrument list. */
    public Map<Long, String> buildTokenMap(List<KiteInstrumentRecord> filtered) {
        return buildTokenMapping(filtered);
    }

    /** Insert any instruments not already in instrument_master. Returns count of newly inserted rows. */
    public int insertNew(
            Connection connection,
            List<KiteInstrumentRecord> filtered,
            Map<Long, String> tokenToId,
            LocalDate referenceDate
    ) throws SQLException {
        return upsertNewInstruments(connection, filtered, tokenToId, referenceDate);
    }

    /** @deprecated Use {@link #downloadFull} + {@link #insertNew} directly. Kept for CLI entry points only. */
    @Deprecated
    public DumpResult run(
            Connection connection,
            LocalDate referenceDate,
            double niftyAtm,
            double bankNiftyAtm,
            KiteLiveConfig config
    ) throws Exception {
        List<KiteInstrumentRecord> raw = downloadAndParse(config);
        List<KiteInstrumentRecord> filtered = KiteInstrumentFilter.filter(
                raw, referenceDate,
                niftyAtm, bankNiftyAtm,
                config.niftyStrikeWindowPoints(),
                config.bankNiftyStrikeWindowPoints(),
                config.subscribeNextWeekly(),
                config.subscribeNextMonthly()
        );
        Map<Long, String> tokenToId = buildTokenMapping(filtered);
        int inserted = upsertNewInstruments(connection, filtered, tokenToId, referenceDate);
        System.out.printf("[kite-dump] NFO rows: %d total, %d after filter, %d newly inserted into instrument_master%n",
                raw.size(), filtered.size(), inserted);
        return new DumpResult(filtered, tokenToId, inserted);
    }

    private List<KiteInstrumentRecord> downloadAndParse(KiteLiveConfig config) throws Exception {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));

        CachedDump cached = RAW_DUMP_CACHE.get();
        if (cached != null && cached.date().equals(today)) {
            System.out.printf("[kite-dump] Using cached NFO dump from today (%s), %d records%n",
                    today, cached.records().size());
            return cached.records();
        }

        long backoffMs = INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(NFO_INSTRUMENTS_URL))
                    .header("X-Kite-Version", "3")
                    .header("Authorization",
                            "token " + config.credentials().apiKey()
                                    + ":" + config.credentials().accessToken())
                    .GET()
                    .build();
            HttpResponse<java.io.InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() == 429) {
                if (attempt == MAX_RETRIES) {
                    throw new RuntimeException(
                            "NFO instruments endpoint rate-limited (HTTP 429) after " + MAX_RETRIES
                                    + " attempts. Wait a minute and try logging in again.");
                }
                System.out.printf("[kite-dump] HTTP 429 rate limit on attempt %d/%d — retrying in %ds%n",
                        attempt, MAX_RETRIES, backoffMs / 1000);
                Thread.sleep(backoffMs);
                backoffMs *= 2;
                continue;
            }

            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        "Failed to download NFO instruments dump, HTTP status: " + response.statusCode());
            }

            List<KiteInstrumentRecord> records = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    KiteInstrumentRecord rec = KiteInstrumentRecord.parseOrNull(line);
                    if (rec != null && rec.isNiftyOrBankNiftyOption()) {
                        records.add(rec);
                    }
                }
            }

            RAW_DUMP_CACHE.set(new CachedDump(today, records));
            System.out.printf("[kite-dump] Downloaded and cached NFO dump for %s, %d NIFTY/BANKNIFTY option records%n",
                    today, records.size());
            return records;
        }

        throw new IllegalStateException("Unexpected exit from download retry loop");
    }

    private Map<Long, String> buildTokenMapping(List<KiteInstrumentRecord> filtered) {
        Map<Long, String> map = new HashMap<>(filtered.size() * 2);
        for (KiteInstrumentRecord rec : filtered) {
            String instrumentId = resolveInstrumentId(rec);
            map.put(rec.instrumentToken(), instrumentId);
        }
        return Collections.unmodifiableMap(map);
    }

    private String resolveInstrumentId(KiteInstrumentRecord rec) {
        return instrumentResolver.resolveInstrumentId(new InstrumentKey(
                rec.name(),
                rec.expiry(),
                rec.strike(),
                rec.instrumentType()
        ));
    }

    private int upsertNewInstruments(
            Connection connection,
            List<KiteInstrumentRecord> filtered,
            Map<Long, String> tokenToId,
            LocalDate referenceDate
    ) throws SQLException {
        Set<String> existing = loadExistingInstrumentIds(connection);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        int inserted = 0;
        int updated = 0;

        try (PreparedStatement insertStmt = connection.prepareStatement(INSERT_INSTRUMENT_SQL);
             PreparedStatement updateStmt = connection.prepareStatement(UPDATE_INSTRUMENT_SQL)) {
            for (KiteInstrumentRecord rec : filtered) {
                String instrumentId = tokenToId.get(rec.instrumentToken());
                LocalDate expiry = rec.expiry();
                if (existing.contains(instrumentId)) {
                    bindUpdate(updateStmt, instrumentId, rec, expiry, referenceDate, now);
                    updateStmt.addBatch();
                    updated++;
                } else {
                    bindInsert(insertStmt, instrumentId, rec, expiry, referenceDate, now);
                    insertStmt.addBatch();
                    existing.add(instrumentId);
                    inserted++;
                }
            }
            if (inserted > 0) {
                insertStmt.executeBatch();
            }
            if (updated > 0) {
                updateStmt.executeBatch();
            }
        }
        return inserted;
    }

    private static void bindInsert(
            PreparedStatement stmt,
            String instrumentId,
            KiteInstrumentRecord rec,
            LocalDate expiry,
            LocalDate referenceDate,
            Timestamp now
    ) throws SQLException {
        stmt.setString(1, instrumentId);
        stmt.setString(2, rec.name());
        stmt.setString(3, rec.name());
        stmt.setTimestamp(4, expiryTimestamp(expiry));
        stmt.setDouble(5, rec.strike().doubleValue());
        stmt.setString(6, rec.instrumentType());
        stmt.setInt(7, rec.lotSize());
        stmt.setDouble(8, rec.tickSize());
        stmt.setString(9, String.valueOf(rec.instrumentToken()));
        stmt.setString(10, rec.tradingSymbol());
        stmt.setBoolean(11, !expiry.isBefore(referenceDate));
        stmt.setString(12, deriveExpiryType(expiry, rec.name()));
        stmt.setTimestamp(13, now);
        stmt.setTimestamp(14, now);
    }

    private static void bindUpdate(
            PreparedStatement stmt,
            String instrumentId,
            KiteInstrumentRecord rec,
            LocalDate expiry,
            LocalDate referenceDate,
            Timestamp now
    ) throws SQLException {
        stmt.setString(1, rec.name());
        stmt.setString(2, rec.name());
        stmt.setTimestamp(3, expiryTimestamp(expiry));
        stmt.setDouble(4, rec.strike().doubleValue());
        stmt.setString(5, rec.instrumentType());
        stmt.setInt(6, rec.lotSize());
        stmt.setDouble(7, rec.tickSize());
        stmt.setString(8, String.valueOf(rec.instrumentToken()));
        stmt.setString(9, rec.tradingSymbol());
        stmt.setBoolean(10, !expiry.isBefore(referenceDate));
        stmt.setString(11, deriveExpiryType(expiry, rec.name()));
        stmt.setTimestamp(12, now);
        stmt.setString(13, instrumentId);
    }

    private static Timestamp expiryTimestamp(LocalDate expiry) {
        return Timestamp.from(expiry.atStartOfDay(IST).toInstant());
    }

    private Set<String> loadExistingInstrumentIds(Connection connection) throws SQLException {
        Set<String> ids = new HashSet<>();
        try (PreparedStatement stmt = connection.prepareStatement(SELECT_EXISTING_SQL);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
        }
        return ids;
    }

    private static String deriveExpiryType(LocalDate expiryDate, String underlying) {
        // BankNifty stopped weekly expiry; all BankNifty contracts are monthly.
        if ("BANKNIFTY".equals(underlying)) return "MONTHLY";
        LocalDate lastDay = expiryDate.withDayOfMonth(expiryDate.lengthOfMonth());
        while (lastDay.getDayOfWeek() != java.time.DayOfWeek.THURSDAY) {
            lastDay = lastDay.minusDays(1);
        }
        return expiryDate.equals(lastDay) ? "MONTHLY" : "WEEKLY";
    }

    public record DumpResult(
            List<KiteInstrumentRecord> filteredInstruments,
            Map<Long, String> tokenToInstrumentId,
            int newInstrumentsInserted
    ) {}
}
