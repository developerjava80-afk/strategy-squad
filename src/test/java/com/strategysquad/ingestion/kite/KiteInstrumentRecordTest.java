package com.strategysquad.ingestion.kite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KiteInstrumentRecordTest {

    @Test
    void parsesQuotedIndexOptionRowsFromCurrentKiteDumpFormat() {
        String line = "16239106,63434,NIFTY2642124350CE,\"NIFTY\",0,2026-04-21,24350,0.05,65,CE,NFO-OPT,NFO";

        KiteInstrumentRecord record = KiteInstrumentRecord.parseOrNull(line);

        assertNotNull(record);
        assertEquals("NIFTY", record.name());
        assertEquals("NIFTY2642124350CE", record.tradingSymbol());
        assertEquals("CE", record.instrumentType());
        assertEquals("NFO-OPT", record.segment());
        assertTrue(record.isNiftyOrBankNiftyOption());
    }
}
