package com.strategysquad.research;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * User-defined strategy structure for multi-leg historical testing.
 */
public record StrategyStructureDefinition(
        String mode,
        String orientation,
        String underlying,
        String expiryType,
        int dte,
        BigDecimal spot,
        Instant lastDeltaAdjustmentTs,
        Instant pendingAdjustmentSinceTs,
        List<StrategyLeg> legs
) {
    public StrategyStructureDefinition {
        legs = List.copyOf(legs);
    }

    public StrategyStructureDefinition(
            String mode,
            String orientation,
            String underlying,
            String expiryType,
            int dte,
            BigDecimal spot,
            List<StrategyLeg> legs
    ) {
        this(mode, orientation, underlying, expiryType, dte, spot, null, null, legs);
    }

    public int lotSize() {
        return LotSizingRules.lotSizeForUnderlying(underlying);
    }

    public int normalizedQuantity(StrategyLeg leg) {
        return LotSizingRules.normalizeOpenQuantity(underlying, leg == null ? null : leg.quantity());
    }

    public int lotCount(StrategyLeg leg) {
        return LotSizingRules.lotCount(underlying, leg == null ? null : leg.quantity());
    }

    public record StrategyLeg(
            String label,
            String optionType,
            String side,
            BigDecimal strike,
            BigDecimal entryPrice,
            Integer quantity
    ) {
        public StrategyLeg(
                String label,
                String optionType,
                String side,
                BigDecimal strike,
                BigDecimal entryPrice
        ) {
            this(label, optionType, side, strike, entryPrice, null);
        }
    }
}
