package com.strategysquad.ingestion.bhavcopy;

import com.strategysquad.enrichment.OptionEnrichedTick;
import com.strategysquad.enrichment.OptionInstrument;
import com.strategysquad.enrichment.OptionsEnricher;
import com.strategysquad.ingestion.live.OptionLiveTick;
import com.strategysquad.ingestion.live.SpotLiveTick;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

final class HistoricalDerivedSupport {
    private static final ZoneOffset IST_OFFSET = ZoneOffset.ofHoursMinutes(5, 30);

    private static final String HISTORICAL_JOIN_SQL =
            "SELECT oh.trade_ts, oh.instrument_id, oh.close_price, oh.volume, "
                    + "im.underlying, im.option_type, im.strike, im.expiry_date, sh.close_price AS spot_close "
                    + "FROM options_historical oh "
                    + "JOIN instrument_master im ON im.instrument_id = oh.instrument_id "
                    + "JOIN spot_historical sh ON sh.trade_date = oh.trade_date AND sh.underlying = im.underlying ";

    private HistoricalDerivedSupport() {
    }

    static List<OptionEnrichedTick> buildEnrichedTicks(Connection connection, LocalDate tradeDate) throws Exception {
        List<OptionEnrichedTick> enrichedTicks = new ArrayList<>();
        OptionsEnricher enricher = new OptionsEnricher();

        try (PreparedStatement statement = connection.prepareStatement(
                HISTORICAL_JOIN_SQL + " WHERE oh.trade_ts >= ? AND oh.trade_ts < ? ORDER BY oh.trade_ts, oh.instrument_id")) {
            statement.setTimestamp(1, tradeStartTimestampUtc(tradeDate));
            statement.setTimestamp(2, tradeEndTimestampUtc(tradeDate));
            statement.setFetchSize(2048);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Instant exchangeTs = rs.getTimestamp("trade_ts").toInstant();
                    String instrumentId = rs.getString("instrument_id");
                    String underlying = rs.getString("underlying");
                    String optionType = rs.getString("option_type");
                    BigDecimal strike = BigDecimal.valueOf(rs.getDouble("strike"));
                    Instant expiryTs = rs.getTimestamp("expiry_date").toInstant();
                    BigDecimal lastPrice = BigDecimal.valueOf(rs.getDouble("close_price"));
                    BigDecimal spotPrice = BigDecimal.valueOf(rs.getDouble("spot_close"));
                    long volume = rs.getLong("volume");

                    OptionInstrument instrument = new OptionInstrument(
                            instrumentId,
                            underlying,
                            optionType,
                            strike,
                            expiryTs
                    );
                    OptionLiveTick optionTick = new OptionLiveTick(
                            exchangeTs,
                            exchangeTs,
                            instrumentId,
                            underlying,
                            lastPrice,
                            lastPrice,
                            lastPrice,
                            volume,
                            0
                    );
                    SpotLiveTick spotTick = new SpotLiveTick(
                            exchangeTs,
                            exchangeTs,
                            underlying,
                            spotPrice
                    );

                    enrichedTicks.add(enricher.enrich(optionTick, instrument, spotTick));
                }
            }
        }

        return enrichedTicks;
    }

    static Timestamp tradeStartTimestampUtc(LocalDate tradeDate) {
        LocalDateTime startOfDayIst = tradeDate.atStartOfDay();
        return Timestamp.from(startOfDayIst.toInstant(IST_OFFSET));
    }

    static Timestamp tradeEndTimestampUtc(LocalDate tradeDate) {
        LocalDateTime startOfNextDayIst = tradeDate.plusDays(1).atStartOfDay();
        return Timestamp.from(startOfNextDayIst.toInstant(IST_OFFSET));
    }
}