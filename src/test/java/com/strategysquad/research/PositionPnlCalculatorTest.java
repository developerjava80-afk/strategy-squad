package com.strategysquad.research;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PositionPnlCalculatorTest {
    @Test
    void bookedPnlForShortUsesExitedQuantity() {
        BigDecimal booked = PositionPnlCalculator.bookedPnl(
                "SHORT",
                new BigDecimal("100"),
                new BigDecimal("120"),
                65
        );

        assertEquals(new BigDecimal("-1300"), booked);
    }

    @Test
    void bookedPnlForLongUsesExitedQuantity() {
        BigDecimal booked = PositionPnlCalculator.bookedPnl(
                "LONG",
                new BigDecimal("100"),
                new BigDecimal("120"),
                65
        );

        assertEquals(new BigDecimal("1300"), booked);
    }

    @Test
    void totalPnlAddsBookedAndLive() {
        BigDecimal booked = new BigDecimal("800");
        BigDecimal live = PositionPnlCalculator.livePnl(
                "SHORT",
                new BigDecimal("100"),
                new BigDecimal("95"),
                65
        );

        assertEquals(new BigDecimal("1125"), PositionPnlCalculator.totalPnl(booked, live));
    }
}
