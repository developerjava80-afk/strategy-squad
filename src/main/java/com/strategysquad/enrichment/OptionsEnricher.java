package com.strategysquad.enrichment;

import com.strategysquad.ingestion.live.OptionLiveTick;
import com.strategysquad.ingestion.live.SpotLiveTick;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;

/**
 * Computes the canonical enriched representation for option live ticks.
 */
public class OptionsEnricher {
    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal POINT_BUCKET_SIZE = BigDecimal.valueOf(50);
    private static final Set<String> POINT_BUCKET_UNDERLYINGS = Set.of("NIFTY", "BANKNIFTY");
    private static final LocalTime MARKET_CLOSE_IST = LocalTime.of(15, 30);

    public OptionEnrichedTick enrich(
            OptionLiveTick optionTick,
            OptionInstrument instrument,
            SpotLiveTick spotTick
    ) {
        Objects.requireNonNull(optionTick, "optionTick must not be null");
        Objects.requireNonNull(instrument, "instrument must not be null");
        Objects.requireNonNull(spotTick, "spotTick must not be null");
        if (!optionTick.instrumentId().equals(instrument.instrumentId())) {
            throw new IllegalArgumentException("optionTick instrumentId must match instrument metadata");
        }
        if (!optionTick.underlying().equals(instrument.underlying())) {
            throw new IllegalArgumentException("optionTick underlying must match instrument metadata");
        }
        if (!optionTick.underlying().equals(spotTick.underlying())) {
            throw new IllegalArgumentException("spotTick underlying must match optionTick underlying");
        }
        if (spotTick.lastPrice().signum() <= 0) {
            throw new IllegalArgumentException("spotTick lastPrice must be greater than zero");
        }
        if (spotTick.exchangeTs().isAfter(optionTick.exchangeTs())) {
            throw new IllegalArgumentException("spotTick exchangeTs must not be after optionTick exchangeTs");
        }

        Instant effectiveExpiryTs = normalizeExpiryTs(instrument.expiryTs());
        int minutesToExpiry = calculateMinutesToExpiry(optionTick.exchangeTs(), effectiveExpiryTs);
        int timeBucket15m = minutesToExpiry / 15;
        BigDecimal moneynessPoints = instrument.strike().subtract(spotTick.lastPrice(), MATH_CONTEXT);
        BigDecimal moneynessPct = moneynessPoints
                .multiply(ONE_HUNDRED, MATH_CONTEXT)
                .divide(spotTick.lastPrice(), 8, RoundingMode.HALF_UP);

        return new OptionEnrichedTick(
                optionTick.exchangeTs(),
                optionTick.instrumentId(),
                optionTick.underlying(),
                instrument.optionType(),
                instrument.strike(),
                effectiveExpiryTs,
                optionTick.lastPrice(),
                spotTick.lastPrice(),
                minutesToExpiry,
                timeBucket15m,
                moneynessPct,
                moneynessPoints,
                deriveMoneynessBucket(optionTick.underlying(), moneynessPct, moneynessPoints)
        );
    }

    static Instant normalizeExpiryTs(Instant expiryTs) {
        LocalDateTime dateTime = LocalDateTime.ofInstant(expiryTs, ZoneOffset.UTC);
        if (dateTime.toLocalTime().equals(LocalTime.MIDNIGHT)) {
            return dateTime.toLocalDate().atTime(MARKET_CLOSE_IST).toInstant(ZoneOffset.UTC);
        }
        return expiryTs;
    }

    static int calculateMinutesToExpiry(Instant exchangeTs, Instant expiryTs) {
        long rawMinutes = ChronoUnit.MINUTES.between(exchangeTs, expiryTs);
        long clamped = Math.max(0, rawMinutes);
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, clamped));
    }

    static int deriveMoneynessBucket(String underlying, BigDecimal moneynessPct, BigDecimal moneynessPoints) {
        if (POINT_BUCKET_UNDERLYINGS.contains(underlying)) {
            return moneynessPoints
                    .divide(POINT_BUCKET_SIZE, 0, RoundingMode.HALF_UP)
                    .multiply(POINT_BUCKET_SIZE)
                    .intValueExact();
        }
        return moneynessPct.setScale(0, RoundingMode.HALF_UP).intValueExact();
    }
}
