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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Downloads the Kite NFO instruments dump, filters for NIFTY/BANKNIFTY options,
 * inserts new instruments into instrument_master, and returns a token-to-id mapping
 * for use by the WebSocket ticker.
 *
 * <p>Run once before market open. Re-run on weekly expiry roll.
 */
public final class KiteInstrumentsDumpJob {

    private static final String NFO_INSTRUMENTS_URL = "https://api.kite.trade/instruments/NFO";

    private static final String INSERT_INSTRUMENT_SQL =
            "INSERT INTO instrument_master"
                    + " (instrument_id, underlying, symbol, expiry_date, strike, option_type,"
                    + "  lot_size, tick_size, exchange_token, trading_symbol,"
                    + "  is_active, expiry_type, created_at, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_INSTRUMENT_SQL =
            "UPDATE instrument_master"
                    + " SET underlying = ?,"
                    + "     symbol = ?,"
                    + "     expiry_date = ?,"
                    + "     strike = ?,"
                    + "     option_type = ?,"
                    + "     lot_size = ?,"
                    + "     tick_size = ?,"
                    + "     exchange_token = ?,"
                    + "     trading_symbol = ?,"
                    + "     is_active = ?,"
                    + "     expiry_type = ?,"
                    + "     updated_at = ?"
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
     * Downloads the NFO instruments dump and returns the filtered + loaded result.
     *
     * @param connection           JDBC connection to QuestDB
     * @param referenceDate        today's date (determines current expiries)
     * @param niftyAtm             approximate NIFTY spot (for strike window)
     * @param bankNiftyAtm         approximate BANKNIFTY spot
     * @param config               live config for window sizes and expiry flags
     * @return mapping of Kite instrument_token → instrument_id (for ticker adapter)
     */
    public DumpResult run(
            Connection connection,
            LocalDate referenceDate,
            double niftyAtm,
            double bankNiftyAtm,
            KiteLiveConfig config
    ) throws Exception {
        List<KiteInstrumentRecord> raw = downloadAndParse(config);
        List<KiteInstrumentRecord> filtered = KiteInstrumentFilter.filter(
                raw,
                referenceDate,
                niftyAtm,
                bankNiftyAtm,
                config.niftyStrikeWindowPoints(),
                config.bankNiftyStrikeWindowPoints(),
                config.subscribeNextWeekly(),
                config.subscribeNextMonthly()
        );

        Map<Long, String> tokenToId = buildTokenMapping(filtered);
        int inserted = upsertNewInstruments(connection, filtered, tokenToId, referenceDate);

        System.out.printf("[kite-dump] Downloaded %d NFO rows, filtered to %d, inserted %d new into instrument_master%n",
                raw.size(), filtered.size(), inserted);
        return new DumpResult(filtered, tokenToId, inserted);
    }

    private List<KiteInstrumentRecord> downloadAndParse(KiteLiveConfig config) throws Exception {
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
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to download NFO instruments dump, HTTP status: " + response.statusCode());
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
        return records;
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
                    continue;
                }

                bindInsert(insertStmt, instrumentId, rec, expiry, referenceDate, now);
                insertStmt.addBatch();
                existing.add(instrumentId);
                inserted++;
            }
            if (inserted > 0) {
                insertStmt.executeBatch();
            }
            if (updated > 0) {
                updateStmt.executeBatch();
            }
        }
        if (updated > 0) {
            System.out.printf("[kite-dump] Refreshed %d existing instrument_master rows with current Kite contract metadata%n", updated);
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
        stmt.setTimestamp(4, Timestamp.valueOf(expiry.atStartOfDay()));
        stmt.setDouble(5, rec.strike().doubleValue());
        stmt.setString(6, rec.instrumentType());
        stmt.setInt(7, rec.lotSize());
        stmt.setDouble(8, rec.tickSize());
        stmt.setString(9, String.valueOf(rec.instrumentToken()));
        stmt.setString(10, rec.tradingSymbol());
        stmt.setBoolean(11, !expiry.isBefore(referenceDate));
        stmt.setString(12, deriveExpiryType(expiry));
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
        stmt.setTimestamp(3, Timestamp.valueOf(expiry.atStartOfDay()));
        stmt.setDouble(4, rec.strike().doubleValue());
        stmt.setString(5, rec.instrumentType());
        stmt.setInt(6, rec.lotSize());
        stmt.setDouble(7, rec.tickSize());
        stmt.setString(8, String.valueOf(rec.instrumentToken()));
        stmt.setString(9, rec.tradingSymbol());
        stmt.setBoolean(10, !expiry.isBefore(referenceDate));
        stmt.setString(11, deriveExpiryType(expiry));
        stmt.setTimestamp(12, now);
        stmt.setString(13, instrumentId);
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

    private static String deriveExpiryType(LocalDate expiryDate) {
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
    ) {
    }
}
