package com.strategysquad.research;

import com.strategysquad.agentic.adjustment.AdjustmentAgent;
import com.strategysquad.agentic.booking.ProfitBookingAgent;
import com.strategysquad.agentic.decision.DecisionAgent;
import com.strategysquad.agentic.decision.DecisionAuditWriter;
import com.strategysquad.agentic.decision.DecisionPolicy;
import com.strategysquad.agentic.orchestrator.MarketDayOrchestrator;
import com.strategysquad.agentic.orchestrator.MarketDayOrchestrator.StatePersister;

import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.Collections;

/**
 * Constructs a minimal {@link MarketDayOrchestrator} suitable for simulation mode.
 *
 * <p>All agents are wired with safe no-op stubs that return empty results and never
 * contact a broker. Full agent wiring (live signals, real scanner, position builder)
 * is deferred to Phase 5D.
 *
 * <h2>Design rationale</h2>
 * <p>This factory is not part of the public API and must never be used outside the
 * simulation-start endpoint. It exists solely to keep {@code ResearchConsoleServer}
 * free of complex multi-line wiring logic.
 */
final class AgenticSimulationFactory {

    private AgenticSimulationFactory() {}

    /**
     * Builds a SIMULATION-mode orchestrator with stub agents.
     *
     * @param underlying underlying name ({@code NIFTY} or {@code BANKNIFTY})
     * @param clock      simulation clock; set the date before calling this method
     * @param persister  state persister (may be no-op)
     * @param jdbcUrl    QuestDB JDBC URL used for connection pooling in audit writes
     * @return a freshly initialised orchestrator in {@code PRE_OPEN_SCAN} state
     */
    static MarketDayOrchestrator buildSimulationOrchestrator(
            String underlying,
            SimulationClock clock,
            StatePersister persister,
            String jdbcUrl) {

        // No-op stubs for agents — return safe empty results
        DecisionAgent decisionAgent = new DecisionAgent(
                com.strategysquad.agentic.decision.DecisionCommand.Mode.SIMULATION,
                underlying,
                /* candidateLoader */ _ts -> Collections.emptyList(),
                /* signalLoader    */ (_session, _ts) -> Collections.emptyMap(),
                /* sessionLoader   */ () -> java.util.Optional.empty(),
                new DecisionPolicy(),
                new DecisionAuditWriter(),
                /* connectionSupplier */ () -> DriverManager.getConnection(jdbcUrl),
                /* maxLotCap */ 4
        );

        PositionSessionActionService actionService = new PositionSessionActionService();
        ProfitBookingAgent profitBookingAgent = new ProfitBookingAgent(actionService);
        AdjustmentAgent adjustmentAgent = new AdjustmentAgent(actionService);

        return new MarketDayOrchestrator(
                "SIMULATION",
                underlying,
                decisionAgent,
                profitBookingAgent,
                adjustmentAgent,
                clock,
                persister
        );
    }
}
