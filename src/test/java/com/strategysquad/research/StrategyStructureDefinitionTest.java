package com.strategysquad.research;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StrategyStructureDefinitionTest {
    @Test
    void keepsPerLegQuantitiesAndAllowsClosedOpenQuantity() {
        StrategyStructureDefinition definition = new StrategyStructureDefinition(
                "SHORT_STRANGLE",
                "SELLER",
                "NIFTY",
                "WEEKLY",
                3,
                new BigDecimal("22350"),
                List.of(
                        new StrategyStructureDefinition.StrategyLeg(
                                "Short call",
                                "CE",
                                "SHORT",
                                new BigDecimal("22500"),
                                new BigDecimal("100"),
                                130
                        ),
                        new StrategyStructureDefinition.StrategyLeg(
                                "Long put",
                                "PE",
                                "LONG",
                                new BigDecimal("22200"),
                                new BigDecimal("80"),
                                0
                        )
                )
        );

        assertEquals(130, definition.normalizedQuantity(definition.legs().get(0)));
        assertEquals(2, definition.lotCount(definition.legs().get(0)));
        assertEquals(0, definition.normalizedQuantity(definition.legs().get(1)));
        assertEquals(0, definition.lotCount(definition.legs().get(1)));
    }
}
