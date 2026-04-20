package com.strategysquad.ingestion.kite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KiteDailyTokenStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsTokenOnlyForMatchingDate() throws Exception {
        KiteDailyTokenStore store = new KiteDailyTokenStore(tempDir.resolve("kite.local.properties"));
        store.saveForDate("today-token", LocalDate.of(2026, 4, 20));

        assertTrue(store.loadForDate(LocalDate.of(2026, 4, 19)).isEmpty());
        assertEquals("today-token", store.loadForDate(LocalDate.of(2026, 4, 20)).orElseThrow());
    }
}
