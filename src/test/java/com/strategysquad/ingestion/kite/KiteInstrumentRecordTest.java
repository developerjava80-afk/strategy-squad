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

    @Test
    void normalizesUnderlyingAliasesFromKiteDump() {
        String niftyAlias = "16239106,63434,NIFTY2642124350CE,\"NIFTY 50\",0,2026-04-21,24350,0.05,65,CE,NFO-OPT,NFO";
        String bankAlias = "26239106,73434,BANKNIFTY2642156000CE,\"NIFTY BANK\",0,2026-04-21,56000,0.05,30,CE,NFO-OPT,NFO";

        KiteInstrumentRecord niftyRecord = KiteInstrumentRecord.parseOrNull(niftyAlias);
        KiteInstrumentRecord bankRecord = KiteInstrumentRecord.parseOrNull(bankAlias);

        assertNotNull(niftyRecord);
        assertNotNull(bankRecord);
        assertEquals("NIFTY", niftyRecord.name());
        assertEquals("BANKNIFTY", bankRecord.name());
        assertTrue(niftyRecord.isNiftyOrBankNiftyOption());
        assertTrue(bankRecord.isNiftyOrBankNiftyOption());
    }
}
