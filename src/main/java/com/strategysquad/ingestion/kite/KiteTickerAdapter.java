package com.strategysquad.ingestion.kite;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.strategysquad.ingestion.live.OptionLiveTick;
import com.strategysquad.ingestion.live.SpotLiveTick;
import com.strategysquad.ingestion.live.session.LiveSessionState;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts a Kite REST {@code /quote} API response (parsed JSON) into canonical
 * {@link OptionLiveTick} and {@link SpotLiveTick} DTOs, and updates the session state.
 *
 * <p>Kite quote response key format:
 * <ul>
 *   <li>Index spot: {@code NSE:NIFTY 50}, {@code NSE:NIFTY BANK}</li>
 *   <li>Options: {@code NFO:<tradingSymbol>} (e.g. {@code NFO:NIFTY26APR22500CE})</li>
 * </ul>
 */
public final class KiteTickerAdapter {

    public static final String NIFTY_QUOTE_KEY = "NSE:NIFTY 50";
    public static final String BANKNIFTY_QUOTE_KEY = "NSE:NIFTY BANK";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter KITE_TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Maps "NFO:<tradingSymbol>" → instrumentId (e.g. INS_NIFTY_20260424_22500_CE)
    private final Map<String, String> quoteKeyToInstrumentId;
    private final LiveSessionState sessionState;

    public KiteTickerAdapter(Map<String, String> quoteKeyToInstrumentId, LiveSessionState sessionState) {
        this.quoteKeyToInstrumentId = Objects.requireNonNull(quoteKeyToInstrumentId);
        this.sessionState = Objects.requireNonNull(sessionState);
    }

    /**
     * Converts the {@code data} object from a Kite {@code /quote} JSON response
     * into option and spot tick lists.
     *
     * @param data the {@code data} field from the parsed Kite response JSON
     */
    public AdaptedTicks adapt(JsonObject data) {
        Objects.requireNonNull(data, "data must not be null");
        List<SpotLiveTick> spotTicks = new ArrayList<>();
        List<OptionLiveTick> optionTicks = new ArrayList<>();
        Instant ingestTs = Instant.now();

        for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
            String key = entry.getKey();
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject q = entry.getValue().getAsJsonObject();

            Instant exchangeTs = parseTimestamp(q, ingestTs);
            BigDecimal lastPrice = getDouble(q, "last_price");

            if (NIFTY_QUOTE_KEY.equals(key) || BANKNIFTY_QUOTE_KEY.equals(key)) {
                String underlying = NIFTY_QUOTE_KEY.equals(key) ? "NIFTY" : "BANKNIFTY";
                if (lastPrice.signum() > 0) {
                    spotTicks.add(new SpotLiveTick(exchangeTs, ingestTs, underlying, lastPrice));
                    sessionState.updateSpot(underlying, lastPrice, exchangeTs);
                    sessionState.recordTick(exchangeTs);
                }
                continue;
            }

            String instrumentId = quoteKeyToInstrumentId.get(key);
            if (instrumentId == null) continue;

            String underlying = deriveUnderlying(instrumentId);
            BigDecimal bidPrice = getBidPrice(q, lastPrice);
            BigDecimal askPrice = getAskPrice(q, lastPrice);
            if (askPrice.compareTo(bidPrice) < 0) askPrice = bidPrice;

            long volume = getLong(q, "volume");
            long oi = getLong(q, "oi");

            try {
                OptionLiveTick tick = new OptionLiveTick(
                        exchangeTs, ingestTs, instrumentId, underlying,
                        lastPrice, bidPrice, askPrice, volume, oi);
                optionTicks.add(tick);
                sessionState.updateQuote(instrumentId, lastPrice, bidPrice, askPrice, volume, oi, exchangeTs);
                sessionState.recordTick(exchangeTs);
            } catch (IllegalArgumentException ignored) {
                // Skip ticks that fail validation (e.g. zero price on illiquid contracts)
            }
        }
        return new AdaptedTicks(optionTicks, spotTicks);
    }

    private Instant parseTimestamp(JsonObject q, Instant fallback) {
        try {
            JsonElement ts = q.get("timestamp");
            if (ts != null && !ts.isJsonNull()) {
                return LocalDateTime.parse(ts.getAsString(), KITE_TS_FORMAT)
                        .atZone(IST).toInstant();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static BigDecimal getDouble(JsonObject q, String field) {
        try {
            JsonElement el = q.get(field);
            if (el != null && !el.isJsonNull()) {
                double v = el.getAsDouble();
                return v > 0 ? BigDecimal.valueOf(v) : BigDecimal.ZERO;
            }
        } catch (Exception ignored) {
        }
        return BigDecimal.ZERO;
    }

    private static long getLong(JsonObject q, String field) {
        try {
            JsonElement el = q.get(field);
            if (el != null && !el.isJsonNull()) return Math.max(0L, el.getAsLong());
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private static BigDecimal getBidPrice(JsonObject q, BigDecimal fallback) {
        try {
            JsonObject depth = q.getAsJsonObject("depth");
            if (depth != null) {
                com.google.gson.JsonArray buy = depth.getAsJsonArray("buy");
                if (buy != null && buy.size() > 0) {
                    double price = buy.get(0).getAsJsonObject().get("price").getAsDouble();
                    if (price > 0) return BigDecimal.valueOf(price);
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static BigDecimal getAskPrice(JsonObject q, BigDecimal fallback) {
        try {
            JsonObject depth = q.getAsJsonObject("depth");
            if (depth != null) {
                com.google.gson.JsonArray sell = depth.getAsJsonArray("sell");
                if (sell != null && sell.size() > 0) {
                    double price = sell.get(0).getAsJsonObject().get("price").getAsDouble();
                    if (price > 0) return BigDecimal.valueOf(price);
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static String deriveUnderlying(String instrumentId) {
        String[] parts = instrumentId.split("_", 3);
        return parts.length >= 2 ? parts[1] : "UNKNOWN";
    }

    public record AdaptedTicks(List<OptionLiveTick> optionTicks, List<SpotLiveTick> spotTicks) {
        public boolean isEmpty() { return optionTicks.isEmpty() && spotTicks.isEmpty(); }
    }
}
