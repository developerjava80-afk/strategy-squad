package com.strategysquad.ingestion.kite;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KiteInstrumentFilterTest {

    @Test
    void derivesWeeklyAndMonthlyExpiriesFromLiveDumpDates() {
        List<KiteInstrumentRecord> records = List.of(
                option("NIFTY", LocalDate.of(2026, 4, 21), "24350", "CE"),
                option("NIFTY", LocalDate.of(2026, 4, 28), "24350", "CE"),
                option("NIFTY", LocalDate.of(2026, 5, 26), "24350", "CE"),
                option("BANKNIFTY", LocalDate.of(2026, 4, 21), "56000", "CE"),
                option("BANKNIFTY", LocalDate.of(2026, 4, 28), "56000", "CE"),
                option("BANKNIFTY", LocalDate.of(2026, 5, 26), "56000", "CE")
        );

        List<KiteInstrumentRecord> filtered = KiteInstrumentFilter.filter(
                records,
                LocalDate.of(2026, 4, 20),
                24400.0,
                56000.0,
                500.0,
                500.0,
                true,
                true
        );

        assertEquals(6, filtered.size());
    }

    private static KiteInstrumentRecord option(String name, LocalDate expiry, String strike, String optionType) {
        return new KiteInstrumentRecord(
                1L,
                "1",
                name + expiry + strike + optionType,
                name,
                expiry,
                new BigDecimal(strike),
                0.05,
                50,
                optionType,
                "NFO-OPT",
                "NFO"
        );
    }
}
