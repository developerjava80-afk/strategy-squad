package com.strategysquad.ingestion.bhavcopy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BhavcopyRelevantCsvFilterTest {

    @Test
    void keepsOnlyNiftyBankNiftyOptionAndSpotRows(@TempDir Path tempDir) throws Exception {
        Path csvFile = tempDir.resolve("sample.csv");
        Files.writeString(csvFile, """
                INSTRUMENT,SYMBOL,OPTION_TYP
                OPTIDX,NIFTY,CE
                OPTIDX,BANKNIFTY,PE
                ,BANKNIFTY,-
                FUTIDX,NIFTY,-
                OPTIDX,RELIANCE,CE
                FUTIDX,RELIANCE,-
                """);

        BhavcopyRelevantCsvFilter.FilterResult result = new BhavcopyRelevantCsvFilter().filterInPlace(csvFile);

        assertEquals(6, result.totalRows());
        assertEquals(4, result.keptRows());
        assertEquals("""
                INSTRUMENT,SYMBOL,OPTION_TYP
                OPTIDX,NIFTY,CE
                OPTIDX,BANKNIFTY,PE
                ,BANKNIFTY,-
                FUTIDX,NIFTY,-
                """.replace("\n", System.lineSeparator()), Files.readString(csvFile));
    }

    @Test
    void keepsOnlyNiftyBankNiftyRowsForUdiffFormat(@TempDir Path tempDir) throws Exception {
        Path csvFile = tempDir.resolve("udiff.csv");
        Files.writeString(csvFile, """
                TradDt,FinInstrmTp,TckrSymb,OptnTp,FinInstrmNm
                2026-04-16,IDO,NIFTY,CE,NIFTY26APR24800CE
                2026-04-16,IDO,BANKNIFTY,PE,BANKNIFTY26APR52000PE
                2026-04-16,IDF,NIFTY,,NIFTY26APRFUT
                2026-04-16,IDO,RELIANCE,CE,RELIANCE26APR2500CE
                2026-04-16,IDF,FINNIFTY,,FINNIFTY26APRFUT
                """);

        BhavcopyRelevantCsvFilter.FilterResult result = new BhavcopyRelevantCsvFilter().filterInPlace(csvFile);

        assertEquals(5, result.totalRows());
        assertEquals(3, result.keptRows());
        assertEquals("""
                TradDt,FinInstrmTp,TckrSymb,OptnTp,FinInstrmNm
                2026-04-16,IDO,NIFTY,CE,NIFTY26APR24800CE
                2026-04-16,IDO,BANKNIFTY,PE,BANKNIFTY26APR52000PE
                2026-04-16,IDF,NIFTY,,NIFTY26APRFUT
                """.replace("\n", System.lineSeparator()), Files.readString(csvFile));
    }
}
