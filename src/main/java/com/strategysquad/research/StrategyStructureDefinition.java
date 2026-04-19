package com.strategysquad.research;

import java.math.BigDecimal;
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
        List<StrategyLeg> legs
) {
    public StrategyStructureDefinition {
        legs = List.copyOf(legs);
    }

    public record StrategyLeg(
            String label,
            String optionType,
            String side,
            BigDecimal strike,
            BigDecimal entryPrice
    ) {
    }
}
