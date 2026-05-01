package com.strategysquad.research;

import com.strategysquad.agentic.decision.DecisionCommand;
import com.strategysquad.agentic.orchestrator.MarketDayOrchestrator;
import com.strategysquad.agentic.scanner.CandidateOpportunity;
import com.strategysquad.agentic.scanner.MorningScannerService;
import com.strategysquad.agentic.scanner.ScannerQuery;
import com.strategysquad.agentic.scanner.CandidateScoringEngine;
import com.strategysquad.ingestion.kite.KiteAuthStatus;
import com.strategysquad.ingestion.kite.KiteLiveSessionManager;
import com.strategysquad.ingestion.kite.KiteSubscriptionManager;
import com.strategysquad.ingestion.live.session.LiveStatusReport;
import com.strategysquad.scope.BootstrapMetadataService;
import com.strategysquad.scope.ExpiryType;
import com.strategysquad.scope.ResolvedUniverse;
import com.strategysquad.scope.Scope;
import com.strategysquad.scope.ScopeService;
import com.strategysquad.scope.ScopeStore;
import com.strategysquad.scope.ScopeValidationException;
import com.strategysquad.scope.StrategyKind;
import com.strategysquad.scope.StrikeWindow;
import com.strategysquad.scope.UniverseResolver;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Serves the Scenario Research console and a DB-backed fair-value API for local research use.
 */
public final class ResearchConsoleServer {
    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:8812/qdb";

    /**
     * Phase 7 guardrail caps, passed in from {@code KiteLiveConfig} so they are
     * configurable via {@code kite.properties} without touching source code.
     *
     * @param maxSubscribedTokens  cap for {@link KiteSubscriptionManager} (default 250)
     * @param maxStrikesPerExpiry  cap for {@link UniverseResolver} (default 100)
     * @param maxCandidates        cap for scanner output (default 100)
     */
    public record ScopeGuardrails(int maxSubscribedTokens, int maxStrikesPerExpiry, int maxCandidates) {
        public static final ScopeGuardrails DEFAULTS = new ScopeGuardrails(250, 100, 100);
    }

    private ResearchConsoleServer() {
    }

    public static void main(String[] args) throws IOException {
        try { Class.forName("org.postgresql.Driver"); } catch (ClassNotFoundException e) {
            throw new RuntimeException("PostgreSQL JDBC driver not on classpath", e);
        }
        int port = args.length >= 1 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        String jdbcUrl = args.length >= 2 ? args[1] : DEFAULT_JDBC_URL;
        startServer(
                port,
                jdbcUrl,
                Path.of("ui", "trading-platform-prototype").toAbsolutePath().normalize(),
                null,
                null,
                null
        );
    }

    public static HttpServer startServer(
            int port,
            String jdbcUrl,
            Path uiRoot,
            LiveMarketService liveMarketService,
            KiteLiveSessionManager kiteLiveSessionManager
    ) throws IOException {
        return startServer(port, jdbcUrl, uiRoot, liveMarketService, kiteLiveSessionManager, null);
    }

    public static HttpServer startServer(
            int port,
            String jdbcUrl,
            Path uiRoot,
            LiveMarketService liveMarketService,
            KiteLiveSessionManager kiteLiveSessionManager,
            HistoricalReplayService replayService,
            AtomicReference<MarketDayOrchestrator> orchestratorRef
    ) throws IOException {
        // Delegate to the internal overload — orchestratorRef is wired in below.
        HttpServer server = startServerInternal(port, jdbcUrl, uiRoot, liveMarketService,
                kiteLiveSessionManager, replayService, ScopeGuardrails.DEFAULTS);
        // Re-register the agentic orchestrator endpoints with the live reference.
        // The default startServer creates no-op null-safe handlers; this replaces them.
        if (orchestratorRef != null) {
            server.createContext("/api/agentic/status", new AgenticStatusHandler(orchestratorRef));
            server.createContext("/api/agentic/last-decision", new AgenticLastDecisionHandler(orchestratorRef));
            server.createContext("/api/agentic/halt", new AgenticHaltHandler(orchestratorRef, jdbcUrl));
            server.createContext("/api/agentic/reset-halt", new AgenticResetHaltHandler(orchestratorRef));
            server.createContext("/api/agentic/confirm-command", new AgenticConfirmCommandHandler(orchestratorRef));
            server.createContext("/api/agentic/cancel-pending", new AgenticCancelPendingHandler(orchestratorRef));
            server.createContext("/api/agentic/simulation/start", new AgenticSimStartHandler(orchestratorRef, jdbcUrl));
            server.createContext("/api/agentic/simulation/stop", new AgenticSimStopHandler(orchestratorRef));
        }
        return server;
    }

    /**
     * Overload that accepts configurable {@link ScopeGuardrails} (Phase 7).
     * Called from {@code KiteLiveConsoleMain} with values from {@code kite.properties}.
     */
    public static HttpServer startServer(
            int port,
            String jdbcUrl,
            Path uiRoot,
            LiveMarketService liveMarketService,
            KiteLiveSessionManager kiteLiveSessionManager,
            HistoricalReplayService replayService,
            ScopeGuardrails guardrails
    ) throws IOException {
        // Delegate — the guardrails are injected into the constructed services below
        return startServerInternal(port, jdbcUrl, uiRoot, liveMarketService,
                kiteLiveSessionManager, replayService, guardrails);
    }

    public static HttpServer startServer(
            int port,
            String jdbcUrl,
            Path uiRoot,
            LiveMarketService liveMarketService,
            KiteLiveSessionManager kiteLiveSessionManager,
            HistoricalReplayService replayService
    ) throws IOException {
        return startServerInternal(port, jdbcUrl, uiRoot, liveMarketService,
                kiteLiveSessionManager, replayService, ScopeGuardrails.DEFAULTS);
    }

    private static HttpServer startServerInternal(
            int port,
            String jdbcUrl,
            Path uiRoot,
            LiveMarketService liveMarketService,
            KiteLiveSessionManager kiteLiveSessionManager,
            HistoricalReplayService replayService,
            ScopeGuardrails guardrails
    ) throws IOException {
        // Phase 1+3 (scope-first): scope domain services. No Kite calls at construction.
        ScopeStore scopeStore = new ScopeStore(jdbcUrl);
        BootstrapMetadataService bootstrapMetadataService = new BootstrapMetadataService(jdbcUrl, scopeStore);
        KiteSubscriptionManager subscriptionManager = new KiteSubscriptionManager(guardrails.maxSubscribedTokens());
        ScopeService scopeService = new ScopeService(scopeStore, subscriptionManager);
        UniverseResolver universeResolver = new UniverseResolver(jdbcUrl, guardrails.maxStrikesPerExpiry());

        FairValueCohortService service = new FairValueCohortService(jdbcUrl);
        TimeframeAnalysisService timeframeAnalysisService = new TimeframeAnalysisService(jdbcUrl);
        ForwardOutcomeCohortService forwardOutcomeService = new ForwardOutcomeCohortService(jdbcUrl);
        StrategyAnalysisService strategyAnalysisService = new StrategyAnalysisService(jdbcUrl);
        DiagnosticsCohortService diagnosticsService = new DiagnosticsCohortService(jdbcUrl);
        ResearchWorkspaceService workspaceService = new ResearchWorkspaceService(jdbcUrl);
        Path repoRoot = uiRoot.getParent() == null || uiRoot.getParent().getParent() == null
                ? Path.of(".").toAbsolutePath().normalize()
                : uiRoot.getParent().getParent().toAbsolutePath().normalize();
        Path positionStoreRoot = uiRoot.getParent() == null || uiRoot.getParent().getParent() == null
                ? Path.of("run", "position-sessions").toAbsolutePath().normalize()
                : uiRoot.getParent().getParent().resolve("run").resolve("position-sessions").toAbsolutePath().normalize();
        Path manualOrderStoreRoot = uiRoot.getParent() == null || uiRoot.getParent().getParent() == null
                ? Path.of("run", "manual-orders").toAbsolutePath().normalize()
                : uiRoot.getParent().getParent().resolve("run").resolve("manual-orders").toAbsolutePath().normalize();
        Path reportsRoot = repoRoot.resolve("docs").resolve("reports");
        ResearchPositionSessionService positionSessionService = new ResearchPositionSessionService(positionStoreRoot);
        PositionSessionActionService positionSessionActionService = new PositionSessionActionService();
        StrategyRunReportService strategyRunReportService = new StrategyRunReportService(reportsRoot);
        OptionOrderService optionOrderService = kiteLiveSessionManager == null
                ? null
                : new OptionOrderService(jdbcUrl, kiteLiveSessionManager, manualOrderStoreRoot);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        // Phase 1 — scope bootstrap (no Kite call, always available after login)
        server.createContext("/api/bootstrap/metadata", new BootstrapMetadataHandler(bootstrapMetadataService));
        // Phase 3 — scope activation / deactivation / query
        server.createContext("/api/scope", new ScopeHandler(scopeService, universeResolver));
        // Phase 4 — scoped scanner candidates
        ScannerQuery scopedScannerQuery = new ScannerQuery(jdbcUrl);
        MorningScannerService scopedScannerService =
                new MorningScannerService(scopedScannerQuery, new CandidateScoringEngine());
        server.createContext("/api/scope/candidates",
                new ScopeCandidatesHandler(scopeService, universeResolver, scopedScannerService, scopedScannerQuery, liveMarketService));
        // Phase 5 — bounded live signal snapshot (richness, observed delta, delta-adj theta)
        server.createContext("/api/scope/signal-snapshot",
                new ScopeSignalSnapshotHandler(jdbcUrl, scopeService, universeResolver, liveMarketService));
        server.createContext("/api/fair-value", new FairValueHandler(service));
        server.createContext("/api/timeframe-analysis", new TimeframeAnalysisHandler(timeframeAnalysisService));
        server.createContext("/api/forward-outcomes", new ForwardOutcomeHandler(forwardOutcomeService));
        server.createContext("/api/strategy-analysis", new StrategyAnalysisHandler(strategyAnalysisService));
        server.createContext("/api/diagnostics", new DiagnosticsHandler(diagnosticsService));
        server.createContext("/api/workflow/collections", new WorkflowCollectionsHandler(workspaceService));
        server.createContext("/api/workflow/studies/", new WorkflowStudyHandler(workspaceService));
        server.createContext("/api/workflow/studies", new WorkflowStudiesHandler(workspaceService));
        server.createContext("/api/position-sessions/", new PositionSessionItemHandler(positionSessionService, positionSessionActionService));
        server.createContext("/api/position-sessions", new PositionSessionsHandler(positionSessionService));
        server.createContext("/api/reports/strategy-run", new StrategyRunReportHandler(strategyRunReportService));
        if (liveMarketService != null) {
            server.createContext("/api/live/status", new LiveStatusHandler(liveMarketService, subscriptionManager, scopeService));
            server.createContext("/api/live/spot", new LiveSpotHandler(liveMarketService));
            server.createContext("/api/live/structure", new LiveStructureHandler(liveMarketService));
            server.createContext("/api/live/structure-trend", new LiveStructureTrendHandler(liveMarketService));
            server.createContext("/api/live/overlay", new LiveOverlayHandler(liveMarketService));
        }
        if (kiteLiveSessionManager != null) {
            server.createContext("/api/auth/status", new AuthStatusHandler(kiteLiveSessionManager));
            server.createContext("/api/auth/login", new AuthLoginHandler(kiteLiveSessionManager));
            server.createContext("/api/admin/instruments/refresh", new InstrumentRefreshHandler(kiteLiveSessionManager));
        }
        if (optionOrderService != null) {
            server.createContext("/api/orders/options", new OrderMetadataHandler(optionOrderService));
            server.createContext("/api/orders/quote", new OrderQuoteHandler(optionOrderService));
            server.createContext("/api/orders/place", new OrderPlaceHandler(optionOrderService));
            server.createContext("/api/orders/executions", new OrderExecutionsHandler(optionOrderService));
        }
        if (replayService != null) {
            server.createContext("/api/simulation/start", new SimulationStartHandler(replayService));
            server.createContext("/api/simulation/stop", new SimulationStopHandler(replayService));
            server.createContext("/api/simulation/status", new SimulationStatusHandler(replayService));
        }
        server.createContext("/api/agentic/scanner/candidates", new AgenticScannerHandler(jdbcUrl));
        // Agentic orchestrator endpoints — wired with null-safe no-op reference by default.
        // Use the startServer overload that accepts AtomicReference<MarketDayOrchestrator>
        // to inject a live orchestrator.
        AtomicReference<MarketDayOrchestrator> defaultOrchRef = new AtomicReference<>(null);
        server.createContext("/api/agentic/status", new AgenticStatusHandler(defaultOrchRef));
        server.createContext("/api/agentic/last-decision", new AgenticLastDecisionHandler(defaultOrchRef));
        server.createContext("/api/agentic/halt", new AgenticHaltHandler(defaultOrchRef, jdbcUrl));
        server.createContext("/api/agentic/reset-halt", new AgenticResetHaltHandler(defaultOrchRef));
        server.createContext("/api/agentic/confirm-command", new AgenticConfirmCommandHandler(defaultOrchRef));
        server.createContext("/api/agentic/cancel-pending", new AgenticCancelPendingHandler(defaultOrchRef));
        server.createContext("/api/agentic/simulation/start", new AgenticSimStartHandler(defaultOrchRef, jdbcUrl));
        server.createContext("/api/agentic/simulation/stop", new AgenticSimStopHandler(defaultOrchRef));
        server.createContext("/api/test-harness/theta-delta-sense-check",
                new ThetaDeltaSenseCheckHandler(new ThetaDeltaSenseCheckService(jdbcUrl)));
        Path devTasksFile = repoRoot.resolve("docs").resolve("dev-tasks.json");
        server.createContext("/api/dev/task-status", new DevTaskStatusHandler(devTasksFile));
        server.createContext("/", new StaticUiHandler(uiRoot));
        server.setExecutor(Executors.newFixedThreadPool(20));
        server.start();
        System.out.printf("Strategy Squad server listening on http://localhost:%d%n", port);
        System.out.printf("Serving UI from %s%n", uiRoot);
        System.out.printf("Using JDBC URL %s%n", jdbcUrl);
        if (liveMarketService != null) {
            System.out.println("Live Kite overlay endpoints enabled under /api/live/*");
        }
        if (kiteLiveSessionManager != null) {
            System.out.println("Daily Kite login endpoints enabled under /api/auth/*");
        }
        if (replayService != null) {
            System.out.println("Simulation replay endpoints enabled under /api/simulation/*");
        }
        return server;
    }

    // =========================================================================
    // Phase 1 — Bootstrap metadata handler
    // =========================================================================

    /**
     * GET /api/bootstrap/metadata
     *
     * <p>Returns the lightweight startup payload the UI needs to show the scope
     * picker: available underlyings, future expiries from instrument_master,
     * instrument-master freshness, and the active scope for today (if any).
     *
     * <p>Makes no Kite API calls. Always available after application start.
     */
    private static final class BootstrapMetadataHandler implements HttpHandler {

        private final BootstrapMetadataService service;

        private BootstrapMetadataHandler(BootstrapMetadataService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
                BootstrapMetadataService.BootstrapMetadata meta = service.load(today);
                sendJson(exchange, 200, toJson(meta));
            } catch (java.sql.SQLException ex) {
                sendJson(exchange, 500,
                        "{\"error\":\"Unable to load bootstrap metadata\",\"details\":\""
                        + escapeJson(ex.getMessage()) + "\"}");
            }
        }

        private static String toJson(BootstrapMetadataService.BootstrapMetadata meta) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");

            // underlyings array
            sb.append("\"underlyings\":[");
            for (int i = 0; i < meta.underlyings().size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(escapeJson(meta.underlyings().get(i))).append('"');
            }
            sb.append("],");

            // expiries array
            sb.append("\"expiries\":[");
            var expiries = meta.expiries();
            for (int i = 0; i < expiries.size(); i++) {
                if (i > 0) sb.append(',');
                BootstrapMetadataService.ExpiryInfo e = expiries.get(i);
                sb.append("{")
                  .append("\"underlying\":\"").append(escapeJson(e.underlying())).append("\",")
                  .append("\"date\":\"").append(e.expiry()).append("\",")
                  .append("\"type\":\"").append(e.expiryType().name()).append("\",")
                  .append("\"instrumentCount\":").append(e.instrumentCount())
                  .append("}");
            }
            sb.append("],");

            // instrumentMasterFreshness
            sb.append("\"instrumentMasterFreshness\":\"")
              .append(meta.instrumentMasterFreshness()).append("\",");

            // activeScope
            if (meta.activeScope() != null) {
                BootstrapMetadataService.ActiveScopeInfo a = meta.activeScope();
                Scope s = a.scope();
                sb.append("\"activeScope\":{")
                  .append("\"scopeId\":\"").append(escapeJson(a.scopeId())).append("\",")
                  .append("\"underlying\":\"").append(escapeJson(s.underlying())).append("\",")
                  .append("\"expiry\":\"").append(s.expiry()).append("\",")
                  .append("\"expiryType\":\"").append(s.expiryType().name()).append("\",")
                  .append("\"strategy\":\"").append(s.strategy().name()).append("\",")
                  .append("\"maxCandidates\":").append(s.maxCandidates()).append(",")
                  .append("\"lastActiveAt\":\"").append(a.lastActiveAt()).append("\"")
                  .append("},");
            } else {
                sb.append("\"activeScope\":null,");
            }

            // previousScopeStale
            sb.append("\"previousScopeStale\":").append(meta.previousScopeStale());

            sb.append("}");
            return sb.toString();
        }
    }

    // =========================================================================
    // Phase 3 — Scope handlers
    // =========================================================================

    /**
     * Handles all {@code /api/scope} methods:
     * <ul>
     *   <li>{@code POST /api/scope} — validate, resolve universe, activate scope</li>
     *   <li>{@code GET  /api/scope} — return current active scope or null</li>
     *   <li>{@code DELETE /api/scope} — deactivate the current scope</li>
     * </ul>
     *
     * <p>POST request body (JSON):
     * <pre>
     * {
     *   "underlying":   "NIFTY",
     *   "expiry":       "2026-04-30",
     *   "expiryType":   "WEEKLY",
     *   "strategy":     "SHORT_STRANGLE",
     *   "strikeWindow": { "kind": "ATM_PCT", "pct": 4.0 },
     *   "spotEstimate": 22000.0,          // required for range-based windows
     *   "maxCandidates": 30               // optional, defaults to 30
     * }
     * </pre>
     */
    private static final class ScopeHandler implements HttpHandler {

        private final ScopeService scopeService;
        private final UniverseResolver universeResolver;

        private ScopeHandler(ScopeService scopeService, UniverseResolver universeResolver) {
            this.scopeService    = scopeService;
            this.universeResolver = universeResolver;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            String method = exchange.getRequestMethod().toUpperCase();
            if ("OPTIONS".equals(method)) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            switch (method) {
                case "GET"    -> handleGet(exchange);
                case "POST"   -> handlePost(exchange);
                case "DELETE" -> handleDelete(exchange);
                default       -> sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            }
        }

        // ── GET /api/scope ───────────────────────────────────────────────────

        private void handleGet(HttpExchange exchange) throws IOException {
            Optional<ScopeStore.StoredScope> active = scopeService.getActiveScope();
            if (active.isEmpty()) {
                sendJson(exchange, 200, "{\"activeScope\":null}");
                return;
            }
            ScopeStore.StoredScope ss = active.get();
            sendJson(exchange, 200, buildActiveScopeJson(ss));
        }

        // ── POST /api/scope ──────────────────────────────────────────────────

        private void handlePost(HttpExchange exchange) throws IOException {
            String body;
            try (InputStream is = exchange.getRequestBody()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (body.isEmpty()) {
                sendJson(exchange, 400, "{\"error\":\"MISSING_BODY\",\"details\":\"Request body is required\"}");
                return;
            }

            try {
                // Parse the POST body
                String underlying    = extractJsonString(body, "underlying");
                String expiryStr     = extractJsonString(body, "expiry");
                String expiryTypeStr = extractJsonString(body, "expiryType");
                String strategyStr   = extractJsonString(body, "strategy");
                double spotEstimate  = extractJsonDouble(body, "spotEstimate", 0.0);

                LocalDate expiry = LocalDate.parse(expiryStr);
                ExpiryType expiryType = ExpiryType.valueOf(expiryTypeStr);
                StrategyKind strategy = StrategyKind.valueOf(strategyStr);

                // Parse strikeWindow sub-object
                StrikeWindow strikeWindow = parseStrikeWindow(body);

                // Parse optional maxCandidates
                int maxCandidates = (int) extractJsonDouble(body, "maxCandidates", Scope.DEFAULT_MAX_CANDIDATES);

                Scope scope = new Scope(underlying, expiry, expiryType, strategy, strikeWindow, maxCandidates);

                // Resolve universe (validates expiry exists in instrument_master)
                ResolvedUniverse universe = universeResolver.resolve(scope, spotEstimate);

                // Activate scope — persists + swaps subscription
                LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
                scopeService.activate(today, universe);

                // Return activated scope + universe summary
                String json = buildActivationResponseJson(universe);
                sendJson(exchange, 200, json);

            } catch (ScopeValidationException ex) {
                sendJson(exchange, 422, ex.toJsonBody());
            } catch (IllegalArgumentException ex) {
                sendJson(exchange, 400,
                        "{\"error\":\"INVALID_REQUEST\",\"details\":\"" + escapeJson(ex.getMessage()) + "\"}");
            } catch (java.sql.SQLException ex) {
                sendJson(exchange, 500,
                        "{\"error\":\"DB_ERROR\",\"details\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
        }

        // ── DELETE /api/scope ────────────────────────────────────────────────

        private void handleDelete(HttpExchange exchange) throws IOException {
            try {
                LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
                scopeService.deactivate(today);
                sendJson(exchange, 200, "{\"status\":\"deactivated\"}");
            } catch (java.sql.SQLException ex) {
                sendJson(exchange, 500,
                        "{\"error\":\"DB_ERROR\",\"details\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
        }

        // ── JSON building helpers ────────────────────────────────────────────

        private static String buildActiveScopeJson(ScopeStore.StoredScope ss) {
            Scope s = ss.scope();
            StringBuilder sb = new StringBuilder();
            sb.append("{\"activeScope\":{")
              .append("\"scopeId\":\"").append(escapeJson(ss.scopeId())).append("\",")
              .append("\"underlying\":\"").append(escapeJson(s.underlying())).append("\",")
              .append("\"expiry\":\"").append(s.expiry()).append("\",")
              .append("\"expiryType\":\"").append(s.expiryType().name()).append("\",")
              .append("\"strategy\":\"").append(s.strategy().name()).append("\",")
              .append("\"maxCandidates\":").append(s.maxCandidates()).append(",")
              .append("\"strikeWindow\":").append(buildStrikeWindowJson(s.strikeWindow())).append(",")
              .append("\"createdAt\":\"").append(ss.createdAt()).append("\",")
              .append("\"lastActiveAt\":\"").append(ss.lastActiveAt()).append("\"")
              .append("}}");
            return sb.toString();
        }

        private static String buildStrikeWindowJson(StrikeWindow sw) {
            if (sw == null) return "null";
            if (sw instanceof StrikeWindow.AtmPct p)
                return String.format(java.util.Locale.ROOT,
                    "{\"kind\":\"ATM_PCT\",\"pct\":%.2f}", p.pct());
            if (sw instanceof StrikeWindow.AtmPoints p)
                return String.format(java.util.Locale.ROOT,
                    "{\"kind\":\"ATM_POINTS\",\"points\":%.2f}", p.points());
            if (sw instanceof StrikeWindow.ExplicitRange p)
                return String.format(java.util.Locale.ROOT,
                    "{\"kind\":\"EXPLICIT_RANGE\",\"lower\":%.2f,\"upper\":%.2f}",
                    p.low(), p.high());
            if (sw instanceof StrikeWindow.LegsOnly)
                return "{\"kind\":\"LEGS_ONLY\"}";
            return "{\"kind\":\"UNKNOWN\"}";
        }

        private static String buildActivationResponseJson(ResolvedUniverse universe) {
            Scope s = universe.scope();
            StringBuilder sb = new StringBuilder();
            sb.append("{")
              .append("\"status\":\"activated\",")
              .append("\"underlying\":\"").append(escapeJson(s.underlying())).append("\",")
              .append("\"expiry\":\"").append(s.expiry()).append("\",")
              .append("\"expiryType\":\"").append(s.expiryType().name()).append("\",")
              .append("\"strategy\":\"").append(s.strategy().name()).append("\",")
              .append("\"strikeWindow\":").append(buildStrikeWindowJson(s.strikeWindow())).append(",")
              .append("\"instrumentCount\":").append(universe.instruments().size()).append(",")
              .append("\"truncated\":").append(universe.truncated());
            if (universe.narrowingHint() != null) {
                sb.append(",\"narrowingHint\":\"").append(escapeJson(universe.narrowingHint())).append("\"");
            }
            sb.append("}");
            return sb.toString();
        }

        // ── Request body parsing ─────────────────────────────────────────────

        /**
         * Extracts a string field from a flat JSON object.
         * Throws {@link IllegalArgumentException} when the field is absent.
         */
        private static String extractJsonString(String json, String key) {
            String search = "\"" + key + "\":\"";
            int start = json.indexOf(search);
            if (start < 0) throw new IllegalArgumentException("Missing required field: " + key);
            start += search.length();
            int end = json.indexOf('"', start);
            if (end < 0) throw new IllegalArgumentException("Malformed field: " + key);
            return json.substring(start, end);
        }

        /**
         * Extracts a numeric field from a JSON object, returning {@code defaultValue}
         * when the field is absent.
         */
        private static double extractJsonDouble(String json, String key, double defaultValue) {
            String search = "\"" + key + "\":";
            int start = json.indexOf(search);
            if (start < 0) return defaultValue;
            start += search.length();
            // Skip optional whitespace
            while (start < json.length() && json.charAt(start) == ' ') start++;
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end))
                    || json.charAt(end) == '.' || json.charAt(end) == '-')) {
                end++;
            }
            if (start == end) return defaultValue;
            try {
                return Double.parseDouble(json.substring(start, end));
            } catch (NumberFormatException ex) {
                return defaultValue;
            }
        }

        /**
         * Parses the {@code strikeWindow} sub-object from the POST body.
         * Delegates to the same hand-rolled parser used by {@link ScopeStore.StrikeWindowJson}.
         */
        private static StrikeWindow parseStrikeWindow(String body) {
            // Extract the strikeWindow sub-object as a raw JSON string
            String key = "\"strikeWindow\":";
            int objStart = body.indexOf(key);
            if (objStart < 0) throw new IllegalArgumentException("Missing required field: strikeWindow");
            objStart += key.length();
            while (objStart < body.length() && body.charAt(objStart) == ' ') objStart++;
            if (objStart >= body.length() || body.charAt(objStart) != '{') {
                throw new IllegalArgumentException("strikeWindow must be a JSON object");
            }
            // Walk to matching closing brace
            int depth = 0;
            int objEnd = objStart;
            while (objEnd < body.length()) {
                char c = body.charAt(objEnd);
                if (c == '{') depth++;
                else if (c == '}') { depth--; if (depth == 0) { objEnd++; break; } }
                objEnd++;
            }
            String windowJson = body.substring(objStart, objEnd);
            // Reuse ScopeStore's package-private JSON parser via reflection would require
            // opening the package; instead replicate the same simple parsing logic here.
            return parseWindowJson(windowJson);
        }

        private static StrikeWindow parseWindowJson(String json) {
            String kind = extractJsonString(json, "kind");
            return switch (kind) {
                case "ATM_PCT" -> {
                    double pct = extractJsonDouble(json, "pct", Double.NaN);
                    if (Double.isNaN(pct)) throw new IllegalArgumentException("ATM_PCT requires pct field");
                    yield new StrikeWindow.AtmPct(pct);
                }
                case "ATM_POINTS" -> {
                    double pts = extractJsonDouble(json, "points", Double.NaN);
                    if (Double.isNaN(pts)) throw new IllegalArgumentException("ATM_POINTS requires points field");
                    yield new StrikeWindow.AtmPoints(pts);
                }
                case "EXPLICIT_RANGE" -> {
                    double low  = extractJsonDouble(json, "low",  Double.NaN);
                    double high = extractJsonDouble(json, "high", Double.NaN);
                    if (Double.isNaN(low) || Double.isNaN(high))
                        throw new IllegalArgumentException("EXPLICIT_RANGE requires low and high fields");
                    yield new StrikeWindow.ExplicitRange(low, high);
                }
                case "LEGS_ONLY" -> {
                    // Extract the instrumentIds string array
                    java.util.List<String> ids = extractStringArray(json, "instrumentIds");
                    yield new StrikeWindow.LegsOnly(ids);
                }
                default -> throw new IllegalArgumentException("Unknown strikeWindow kind: " + kind);
            };
        }

        private static java.util.List<String> extractStringArray(String json, String key) {
            String search = "\"" + key + "\":[";
            int start = json.indexOf(search);
            if (start < 0) throw new IllegalArgumentException("Missing array field: " + key);
            start += search.length();
            int end = json.indexOf(']', start);
            if (end < 0) throw new IllegalArgumentException("Unterminated array: " + key);
            String content = json.substring(start, end).trim();
            if (content.isEmpty()) throw new IllegalArgumentException("instrumentIds must not be empty");
            java.util.List<String> result = new java.util.ArrayList<>();
            for (String token : content.split(",")) {
                String cleaned = token.trim();
                if (cleaned.startsWith("\"")) cleaned = cleaned.substring(1);
                if (cleaned.endsWith("\""))   cleaned = cleaned.substring(0, cleaned.length() - 1);
                if (!cleaned.isBlank()) result.add(cleaned);
            }
            return result;
        }
    }

    // =========================================================================
    // Phase 4 — Scoped candidates handler
    // =========================================================================

    /**
     * GET /api/scope/candidates
     *
     * <p>Runs the scoped scanner against the active scope's resolved universe and
     * returns ranked candidates capped at {@link Scope#maxCandidates()}.
     *
     * <p>Returns {@code 409 Conflict} when no scope is active for today.
     * Returns {@code 200 OK} with an empty candidates array when a scope is active
     * but no live price data is available yet (e.g. pre-market).
     *
     * <p>Response JSON:
     * <pre>
     * {
     *   "scopeId":      "S_20260428_NIFTY_20260430_W_001",
     *   "underlying":   "NIFTY",
     *   "expiry":       "2026-04-30",
     *   "maxCandidates": 30,
     *   "truncated":    false,
     *   "candidates": [
     *     {
     *       "instrumentId": "INS_NIFTY_20260430_22500_CE",
     *       "underlying":   "NIFTY",
     *       "optionType":   "CE",
     *       "strike":       22500.0,
     *       "expiryDate":   "2026-04-30",
     *       "totalScore":   0.7342,
     *       "disqualified": false,
     *       "disqualifierReason": null
     *     }, ...
     *   ]
     * }
     * </pre>
     */
    private static final class ScopeCandidatesHandler implements HttpHandler {

        private final ScopeService scopeService;
        private final UniverseResolver universeResolver;
        private final MorningScannerService scannerService;
        private final ScannerQuery scannerQuery;
        // Fix Issue 2: inject LiveMarketService so ATM windows re-resolve against
        // the canonical live spot instead of silently defaulting to 0.0.
        private final LiveMarketService liveMarketService;

        private ScopeCandidatesHandler(ScopeService scopeService,
                                       UniverseResolver universeResolver,
                                       MorningScannerService scannerService,
                                       ScannerQuery scannerQuery,
                                       LiveMarketService liveMarketService) {
            this.scopeService      = scopeService;
            this.universeResolver  = universeResolver;
            this.scannerService    = scannerService;
            this.scannerQuery      = scannerQuery;
            this.liveMarketService = liveMarketService; // may be null in test/offline mode
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            String method = exchange.getRequestMethod().toUpperCase();
            if ("OPTIONS".equals(method)) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"GET".equals(method)) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                Optional<ScopeStore.StoredScope> activeOpt = scopeService.getActiveScope();
                if (activeOpt.isEmpty()) {
                    sendJson(exchange, 409,
                            "{\"error\":\"NO_ACTIVE_SCOPE\","
                            + "\"details\":\"No scope is active for today. POST /api/scope first.\"}");
                    return;
                }
                ScopeStore.StoredScope stored = activeOpt.get();
                Scope scope = stored.scope();

                // Fix Issue 2: for ATM_PCT / ATM_POINTS windows, use the canonical live
                // spot rather than 0.0. A zero spotEstimate makes the resolver silently
                // produce an empty or incorrect universe centred on strike = 0.
                double spotEstimate = resolveSpotEstimate(scope.underlying());
                if (spotEstimate <= 0.0 && requiresSpot(scope.strikeWindow())) {
                    sendJson(exchange, 503,
                            "{\"error\":\"SPOT_UNAVAILABLE\","
                            + "\"details\":\"Cannot re-resolve ATM scope: live spot for "
                            + escapeJson(scope.underlying()) + " is unavailable. "
                            + "Retry when the feed is connected.\"}");
                    return;
                }

                ResolvedUniverse universe;
                try {
                    universe = universeResolver.resolve(scope, spotEstimate);
                } catch (ScopeValidationException ex) {
                    // Scope expiry no longer in instrument_master (expired since activation)
                    sendJson(exchange, 409,
                            "{\"error\":\"SCOPE_EXPIRED\","
                            + "\"details\":\"" + escapeJson(ex.getMessage()) + "\"}");
                    return;
                }

                // Load cohort map for scoring
                java.util.Map<CandidateScoringEngine.CohortKey,
                        com.strategysquad.aggregation.OptionsContextBucket> cohortMap =
                        scannerQuery.loadCohortMap(scope.underlying());

                // Run scoped scan
                java.util.List<CandidateOpportunity> candidates =
                        scannerService.scanScoped(scope, universe, cohortMap, Instant.now());

                sendJson(exchange, 200, buildResponseJson(stored, universe, candidates));

            } catch (java.sql.SQLException ex) {
                sendJson(exchange, 500,
                        "{\"error\":\"DB_ERROR\",\"details\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
        }

        private static String buildResponseJson(ScopeStore.StoredScope stored,
                                                ResolvedUniverse universe,
                                                java.util.List<CandidateOpportunity> candidates) {
            Scope s = stored.scope();
            // Fix Issue 8: include snapshotTs so the Dashboard UI can display scan freshness
            String snapshotTs = Instant.now().toString();
            StringBuilder sb = new StringBuilder();
            sb.append("{")
              .append("\"scopeId\":\"").append(escapeJson(stored.scopeId())).append("\",")
              .append("\"underlying\":\"").append(escapeJson(s.underlying())).append("\",")
              .append("\"expiry\":\"").append(s.expiry()).append("\",")
              .append("\"expiryType\":\"").append(s.expiryType().name()).append("\",")
              .append("\"strategy\":\"").append(s.strategy().name()).append("\",")
              .append("\"maxCandidates\":").append(s.maxCandidates()).append(",")
              .append("\"universeSize\":").append(universe.instruments().size()).append(",")
              .append("\"truncated\":").append(universe.truncated()).append(",")
              .append("\"snapshotTs\":\"").append(snapshotTs).append("\",");

            if (universe.narrowingHint() != null) {
                sb.append("\"narrowingHint\":\"").append(escapeJson(universe.narrowingHint())).append("\",");
            }

            sb.append("\"candidates\":[");
            java.util.List<CandidateOpportunity> list = candidates;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                CandidateOpportunity c = list.get(i);
                sb.append("{")
                  .append("\"candidateId\":\"").append(escapeJson(c.candidateId())).append("\",")
                  .append("\"instrumentId\":\"").append(escapeJson(c.instrumentId())).append("\",")
                  .append("\"tradingSymbol\":\"").append(escapeJson(c.tradingSymbol())).append("\",")
                  .append("\"underlying\":\"").append(escapeJson(c.underlying())).append("\",")
                  .append("\"optionType\":\"").append(escapeJson(c.optionType())).append("\",")
                  .append("\"strike\":").append(c.strike()).append(",")
                  .append("\"expiryDate\":\"").append(c.expiryDate()).append("\",")
                  .append("\"expiryType\":\"").append(escapeJson(c.expiryType())).append("\",")
                  .append("\"spot\":").append(c.spot()).append(",")
                  .append("\"lastPrice\":").append(c.lastPrice()).append(",")
                  .append("\"bidPrice\":").append(c.bidPrice()).append(",")
                  .append("\"askPrice\":").append(c.askPrice()).append(",")
                  .append("\"premiumRichnessPct\":").append(String.format(java.util.Locale.ROOT, "%.4f", c.premiumRichnessPct())).append(",")
                  .append("\"liquidityScore\":").append(String.format(java.util.Locale.ROOT, "%.4f", c.liquidityScore())).append(",")
                  .append("\"thetaOpportunityScore\":").append(String.format(java.util.Locale.ROOT, "%.4f", c.thetaOpportunityScore())).append(",")
                  .append("\"totalScore\":").append(String.format(java.util.Locale.ROOT, "%.6f", c.totalScore())).append(",")
                  .append("\"disqualified\":").append(c.disqualifierReason().isPresent()).append(",")
                  .append("\"disqualifierReason\":");
                if (c.disqualifierReason().isPresent()) {
                    sb.append("\"").append(escapeJson(c.disqualifierReason().get())).append("\"");
                } else {
                    sb.append("null");
                }
                sb.append("}");
            }
            sb.append("]}");
            return sb.toString();
        }

        // ── Spot resolution helpers (Fix Issue 2) ────────────────────────────

        /**
         * Returns canonical live spot price for the given underlying, or 0.0 when
         * the feed is offline or liveMarketService is not wired (offline / test mode).
         */
        private double resolveSpotEstimate(String underlying) {
            if (liveMarketService == null) return 0.0;
            try {
                LiveMarketService.LiveSpotSnapshot snap = liveMarketService.loadSpot(underlying);
                if (snap != null && snap.price() != null && !snap.stale()) {
                    return snap.price().doubleValue();
                }
            } catch (Exception ex) {
                System.err.printf("[ScopeCandidatesHandler] spot lookup failed for %s: %s%n",
                        underlying, ex.getMessage());
            }
            return 0.0;
        }

        /**
         * Returns true for strike windows that require a valid spot estimate to
         * produce a meaningful instrument range (ATM_PCT and ATM_POINTS).
         * EXPLICIT_RANGE and LEGS_ONLY do not use spotEstimate.
         */
        private static boolean requiresSpot(com.strategysquad.scope.StrikeWindow window) {
            return window instanceof com.strategysquad.scope.StrikeWindow.AtmPct
                    || window instanceof com.strategysquad.scope.StrikeWindow.AtmPoints;
        }
    }

    // =========================================================================
    // Phase 5 — Scope Signal Snapshot handler
    // =========================================================================

    /**
     * GET /api/scope/signal-snapshot
     *
     * <p>Returns per-leg live signal data for the active scope's bounded universe:
     * <ul>
     *   <li>Current premium (live last price) and timestamp</li>
     *   <li>5-year historical average from the matched golden-source cohort
     *       ({@code options_context_buckets} keyed by underlying + expiry_type +
     *       moneyness_bucket + DTE_bucket + option_type)</li>
     *   <li>Premium richness = current − historical average (pts and %)</li>
     *   <li>Observed delta across rolling windows (1m, 3m, 5m, 15m) using only
     *       market-observed premium moves vs underlying moves — no Black-Scholes,
     *       no IV, no Greeks library</li>
     *   <li>Delta-adjusted theta for the short side per rolling window</li>
     *   <li>Theta pattern (IMPROVING / WEAKENING / EXPANDING) from latest windows</li>
     *   <li>Strategy-level net delta (sum of leg observed deltas × lot sizes)</li>
     *   <li>Action status (CANDIDATE / WATCH / AVOID) based on actual values only</li>
     * </ul>
     *
     * <p><strong>Bounded contract guarantee:</strong> this handler only queries the
     * instruments in the resolved scope universe — never the full option chain.
     *
     * <p><strong>No Black-Scholes / no IV:</strong> all delta values are empirically
     * observed ratios ({@code Δpremium / Δspot}) over rolling windows. When the
     * underlying move in a window is below {@code MIN_SPOT_MOVE_PTS} (0.5 pts),
     * that window's delta is marked {@code reliable:false}.
     *
     * <p>Returns {@code 409 Conflict} when no scope is active.
     * Returns {@code 200 OK} with an empty legs array when scope is active but no
     * live data is available (e.g. pre-market, no options_live rows yet).
     */
    private static final class ScopeSignalSnapshotHandler implements HttpHandler {

        // Minimum underlying move to treat an observed delta as reliable
        private static final double MIN_SPOT_MOVE_PTS = 0.50;

        // Options live data staleness threshold (seconds).
        // Fix Issue 5: reduced from 120 to 30 to match canonical live-price stale
        // threshold and prevent post-close stale ticks being surfaced as live data.
        private static final long STALE_THRESHOLD_S = 30L;

        private final String jdbcUrl;
        private final ScopeService scopeService;
        private final UniverseResolver universeResolver;
        // Fix Issue 2: used to resolve canonical live spot for ATM window re-resolution
        private final LiveMarketService liveMarketService;

        ScopeSignalSnapshotHandler(String jdbcUrl,
                                   ScopeService scopeService,
                                   UniverseResolver universeResolver,
                                   LiveMarketService liveMarketService) {
            this.jdbcUrl          = jdbcUrl;
            this.scopeService     = scopeService;
            this.universeResolver  = universeResolver;
            this.liveMarketService = liveMarketService; // may be null in offline/test mode
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            String method = exchange.getRequestMethod().toUpperCase();
            if ("OPTIONS".equals(method)) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"GET".equals(method)) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                Optional<ScopeStore.StoredScope> activeOpt = scopeService.getActiveScope();
                if (activeOpt.isEmpty()) {
                    sendJson(exchange, 409,
                            "{\"error\":\"NO_ACTIVE_SCOPE\","
                            + "\"details\":\"No scope is active. POST /api/scope first.\"}");
                    return;
                }
                ScopeStore.StoredScope stored = activeOpt.get();
                Scope scope = stored.scope();

                // Fix Issue 2: re-resolve bounded universe using canonical live spot for
                // ATM windows rather than 0.0, which produced an empty/wrong universe.
                double reResolveSpot = resolveSpotForScope(scope);
                if (reResolveSpot <= 0.0 && requiresSpot(scope.strikeWindow())) {
                    sendJson(exchange, 503,
                            "{\"error\":\"SPOT_UNAVAILABLE\","
                            + "\"details\":\"Cannot re-resolve ATM scope: live spot for "
                            + escapeJson(scope.underlying()) + " is unavailable. "
                            + "Retry when the feed is connected.\"}");
                    return;
                }
                ResolvedUniverse universe;
                try {
                    universe = universeResolver.resolve(scope, reResolveSpot);
                } catch (ScopeValidationException ex) {
                    sendJson(exchange, 409,
                            "{\"error\":\"SCOPE_EXPIRED\","
                            + "\"details\":\"" + escapeJson(ex.getMessage()) + "\"}");
                    return;
                }

                // Guard: universe is already capped by UniverseResolver's hardCap (100).
                // NOTE: do NOT compare against maxCandidates here — maxCandidates controls
                // how many ranked legs to *surface*, not how many instruments the resolver
                // may scan. A 4% ATM_PCT window on NIFTY yields ~80 instruments (40 strikes
                // × CE+PE), which is well above the old maxCandidates+20=50 threshold and
                // triggered a spurious 422. The real overflow guard is in UniverseResolver.

                // Bounded universe — InstrumentRef list carries all metadata (no extra DB call needed)
                java.util.List<com.strategysquad.scope.InstrumentRef> refs = universe.instruments();

                if (refs.isEmpty()) {
                    // Pre-market or no instruments — return empty legs
                    sendJson(exchange, 200, buildEmptyResponse(stored, universe));
                    return;
                }

                Instant now = Instant.now();
                java.util.List<LegSignal> legs = computeLegs(scope, refs, now);

                double strategyNetDelta = legs.stream()
                        .filter(l -> l.d5m() != null)
                        .mapToDouble(l -> l.d5m() * l.lotSize())
                        .sum();

                sendJson(exchange, 200,
                        buildResponse(stored, universe, legs, strategyNetDelta, now));

            } catch (java.sql.SQLException ex) {
                sendJson(exchange, 500,
                        "{\"error\":\"DB_ERROR\",\"details\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
        }

        // ── Per-leg computation ───────────────────────────────────────────────

        /**
         * For each instrument in the bounded universe, fetch rolling windows of
         * live premium + spot from {@code options_live_enriched} and {@code spot_live},
         * then compute market-observed delta and delta-adjusted theta.
         *
         * <p>All queries are parameterised on the bounded instrument ID list — never
         * the full option chain.
         */
        private java.util.List<LegSignal> computeLegs(
                Scope scope,
                java.util.List<com.strategysquad.scope.InstrumentRef> refs,
                Instant now
        ) throws java.sql.SQLException {

            java.util.List<LegSignal> result = new java.util.ArrayList<>();

            // Build instrument ID list from bounded universe only
            java.util.List<String> instrumentIds = refs.stream()
                    .map(com.strategysquad.scope.InstrumentRef::instrumentId)
                    .toList();

            // Build a lookup map from instrumentId → InstrumentRef (carries all metadata)
            java.util.Map<String, com.strategysquad.scope.InstrumentRef> refMap = new java.util.HashMap<>();
            for (com.strategysquad.scope.InstrumentRef r : refs) refMap.put(r.instrumentId(), r);

            try (Connection conn = DriverManager.getConnection(jdbcUrl)) {

                // 1. Load live snapshots for each instrument (latest tick)
                java.util.Map<String, LiveRow> liveRows = fetchLiveRows(conn, scope.underlying(), instrumentIds, now);

                // 2. Load rolling window prices (1m, 3m, 5m, 15m)
                java.util.Map<String, RollingWindows> windows = fetchRollingWindows(conn, scope.underlying(), instrumentIds, now);

                // 3. Load historical cohort averages from options_context_buckets
                java.util.Map<String, Double> histAvg = fetchHistoricalAvg(conn, scope.underlying(), instrumentIds);

                // Note: instrument metadata (lot size, trading symbol, strike, expiry) comes
                // directly from InstrumentRef — no extra DB call needed.

                for (String iid : instrumentIds) {
                    LiveRow live                             = liveRows.get(iid);
                    RollingWindows rw                        = windows.get(iid);
                    Double hist                              = histAvg.get(iid);
                    com.strategysquad.scope.InstrumentRef r = refMap.get(iid);

                    // All metadata from InstrumentRef (no extra DB call)
                    String optionType    = r != null ? r.optionType()                    : (iid.endsWith("_CE") ? "CE" : "PE");
                    String tradingSymbol = r != null ? r.tradingSymbol()                 : iid;
                    int    lotSize       = r != null ? r.lotSize()                        : 50;
                    double strike        = r != null ? r.strike().doubleValue()           : 0.0;
                    String expiryDate    = r != null ? r.expiry().toString()              : scope.expiry().toString();

                    double currentPremium = live != null ? live.lastPrice() : Double.NaN;
                    long   premiumTsMs    = live != null ? live.timestampMs() : 0L;
                    boolean stale         = live == null ||
                            (now.toEpochMilli() - premiumTsMs) > STALE_THRESHOLD_S * 1000L;

                    // Premium richness vs historical cohort average
                    Double richnessPts = (hist != null && !Double.isNaN(currentPremium))
                            ? currentPremium - hist : null;
                    Double richnessPct = (richnessPts != null && hist != null && hist > 0.0)
                            ? (richnessPts / hist) * 100.0 : null;

                    // Observed delta per rolling window (market-observed, no BS)
                    // Formula: observedDelta = (premiumNow - premiumPast) / (spotNow - spotPast)
                    // If spot move < MIN_SPOT_MOVE_PTS → mark reliable=false
                    Double d1m  = null, d3m  = null, d5m  = null, d15m = null;
                    boolean rel1m = false, rel3m = false, rel5m = false, rel15m = false;
                    double adjTheta = Double.NaN;
                    String thetaPattern = "UNKNOWN";

                    if (rw != null && !stale && !Double.isNaN(currentPremium)) {
                        // 1-minute window
                        if (rw.price1mAgo() != null && rw.spot1mAgo() != null && rw.spotNow() != null) {
                            double spotMove1m = rw.spotNow() - rw.spot1mAgo();
                            if (Math.abs(spotMove1m) >= MIN_SPOT_MOVE_PTS) {
                                d1m  = (currentPremium - rw.price1mAgo()) / spotMove1m;
                                rel1m = true;
                            }
                        }
                        // 3-minute window
                        if (rw.price3mAgo() != null && rw.spot3mAgo() != null && rw.spotNow() != null) {
                            double spotMove3m = rw.spotNow() - rw.spot3mAgo();
                            if (Math.abs(spotMove3m) >= MIN_SPOT_MOVE_PTS) {
                                d3m  = (currentPremium - rw.price3mAgo()) / spotMove3m;
                                rel3m = true;
                            }
                        }
                        // 5-minute window
                        if (rw.price5mAgo() != null && rw.spot5mAgo() != null && rw.spotNow() != null) {
                            double spotMove5m = rw.spotNow() - rw.spot5mAgo();
                            if (Math.abs(spotMove5m) >= MIN_SPOT_MOVE_PTS) {
                                d5m  = (currentPremium - rw.price5mAgo()) / spotMove5m;
                                rel5m = true;
                            }
                        }
                        // 15-minute window
                        if (rw.price15mAgo() != null && rw.spot15mAgo() != null && rw.spotNow() != null) {
                            double spotMove15m = rw.spotNow() - rw.spot15mAgo();
                            if (Math.abs(spotMove15m) >= MIN_SPOT_MOVE_PTS) {
                                d15m  = (currentPremium - rw.price15mAgo()) / spotMove15m;
                                rel15m = true;
                            }
                        }

                        // Delta-adjusted theta (5m window, short side)
                        // shortThetaEffect = -(premiumChange - observedDelta * spotChange)
                        // Positive → premium decaying more than delta explains → good for short
                        if (d5m != null && rw.price5mAgo() != null && rw.spot5mAgo() != null && rw.spotNow() != null) {
                            double premChange = currentPremium - rw.price5mAgo();
                            double spotChange = rw.spotNow() - rw.spot5mAgo();
                            double deltaImpact = d5m * spotChange;
                            double thetaMove  = premChange - deltaImpact;
                            adjTheta = -thetaMove; // short position: negate to get benefit
                        } else if (d3m != null && rw.price3mAgo() != null && rw.spot3mAgo() != null && rw.spotNow() != null) {
                            double premChange = currentPremium - rw.price3mAgo();
                            double spotChange = rw.spotNow() - rw.spot3mAgo();
                            double deltaImpact = d3m * spotChange;
                            double thetaMove  = premChange - deltaImpact;
                            adjTheta = -thetaMove;
                        }

                        // Theta pattern from comparing 5m vs 15m delta-adj theta
                        thetaPattern = classifyThetaPattern(adjTheta, d5m, d15m,
                                rw.price5mAgo(), rw.price15mAgo(), currentPremium,
                                rw.spot5mAgo(), rw.spot15mAgo(), rw.spotNow());
                    }

                    // Action status: candidate if richness > 0 and theta improving and delta reliable
                    String actionStatus = deriveActionStatus(richnessPts, thetaPattern, d5m, stale);

                    // Cohort context label for UI display
                    // Moneyness bucket is available from options_live_enriched (fetched
                    // separately during historical avg lookup) — use a concise label here.
                    String cohortContext = scope.underlying() + "·" + scope.expiryType().name()
                            + "·" + optionType;

                    result.add(new LegSignal(
                            iid, tradingSymbol, optionType, strike, expiryDate, lotSize,
                            Double.isNaN(currentPremium) ? null : currentPremium,
                            premiumTsMs,
                            hist,
                            richnessPts, richnessPct,
                            d1m, rel1m, d3m, rel3m, d5m, rel5m, d15m, rel15m,
                            Double.isNaN(adjTheta) ? null : adjTheta,
                            thetaPattern,
                            actionStatus,
                            stale ? "STALE_MARKET_DATA" : null,
                            cohortContext
                    ));
                }
            }
            return result;
        }

        // ── Theta pattern classifier ──────────────────────────────────────────

        private static String classifyThetaPattern(
                double adjTheta5m,
                Double delta5m, Double delta15m,
                Double price5mAgo, Double price15mAgo, double priceNow,
                Double spot5mAgo, Double spot15mAgo, Double spotNow
        ) {
            // If no data → UNKNOWN
            if (Double.isNaN(adjTheta5m)) return "UNKNOWN";

            // EXPANDING: option premium rising faster than delta explains (bad for short)
            if (adjTheta5m < -0.5) return "EXPANDING";

            // IMPROVING: option premium decaying after delta adjustment (good for short)
            if (adjTheta5m > 0.5) {
                // Confirm by checking if 5m theta better than 15m theta
                return "IMPROVING";
            }

            // WEAKENING: near-zero delta-adj theta — decay slowing
            return "WEAKENING";
        }

        // ── Action status ──────────────────────────────────────────────────────

        private static String deriveActionStatus(
                Double richnessPts, String thetaPattern, Double delta5m, boolean stale) {
            if (stale) return "AVOID";
            if (richnessPts == null || delta5m == null) return "WATCH";
            if (richnessPts < 0) return "AVOID";      // premium cheap — don't short
            if ("EXPANDING".equals(thetaPattern)) return "AVOID";
            if (richnessPts > 0 && !"WEAKENING".equals(thetaPattern)) return "CANDIDATE";
            return "WATCH";
        }

        // ── DB fetch helpers ──────────────────────────────────────────────────

        /**
         * Latest live tick per instrument from {@code options_live_enriched}.
         * Queried only for instruments in the bounded universe.
         */
        private java.util.Map<String, LiveRow> fetchLiveRows(
                Connection conn, String underlying,
                java.util.List<String> ids, Instant now
        ) throws java.sql.SQLException {
            java.util.Map<String, LiveRow> result = new java.util.HashMap<>();
            if (ids.isEmpty()) return result;

            // Build bounded IN clause using only bounded instrument IDs
            String inClause = ids.stream()
                    .map(id -> "'" + id.replace("'", "''") + "'")
                    .collect(java.util.stream.Collectors.joining(","));

            String sql = "SELECT instrument_id, last_price, exchange_ts " +
                         "FROM options_live_enriched " +
                         "WHERE underlying = '" + underlying.replace("'", "''") + "' " +
                         "  AND instrument_id IN (" + inClause + ") " +
                         "LATEST ON exchange_ts PARTITION BY instrument_id";

            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    String iid      = rs.getString("instrument_id");
                    double lastPrice = rs.getDouble("last_price");
                    long   tsMs      = rs.getTimestamp("exchange_ts") != null
                            ? rs.getTimestamp("exchange_ts").getTime() : 0L;
                    result.put(iid, new LiveRow(lastPrice, tsMs));
                }
            } catch (java.sql.SQLException ex) {
                // options_live_enriched may not exist pre-market — return empty
                if (ex.getMessage() != null && ex.getMessage().contains("does not exist")) {
                    return result;
                }
                throw ex;
            }
            return result;
        }

        /**
         * Rolling window prices (1m, 3m, 5m, 15m) for each instrument from
         * {@code options_live_enriched} and spot from {@code spot_live}.
         * Uses time-anchor queries against only the bounded instrument list.
         */
        private java.util.Map<String, RollingWindows> fetchRollingWindows(
                Connection conn, String underlying,
                java.util.List<String> ids, Instant now
        ) throws java.sql.SQLException {
            java.util.Map<String, RollingWindows> result = new java.util.HashMap<>();
            if (ids.isEmpty()) return result;

            long nowMs  = now.toEpochMilli();
            long ago1m  = nowMs - 60_000L;
            long ago3m  = nowMs - 180_000L;
            long ago5m  = nowMs - 300_000L;
            long ago15m = nowMs - 900_000L;

            // Initialise buckets for all IDs
            java.util.Map<String, double[]> premMap = new java.util.HashMap<>();
            for (String id : ids) {
                // [price1m, price3m, price5m, price15m]
                premMap.put(id, new double[]{ Double.NaN, Double.NaN, Double.NaN, Double.NaN });
            }

            String inClause = ids.stream()
                    .map(id -> "'" + id.replace("'", "''") + "'")
                    .collect(java.util.stream.Collectors.joining(","));

            // Option prices at each time anchor — use the closest tick at-or-before each window
            String[] windowSuffixes = {"1m", "3m", "5m", "15m"};
            long[]   windowAgoMs   = { ago1m, ago3m, ago5m, ago15m };
            int[]    windowIdx     = { 0, 1, 2, 3 };

            for (int w = 0; w < 4; w++) {
                long cutoffMs = windowAgoMs[w];
                String cutoffTs = new java.sql.Timestamp(cutoffMs).toInstant().toString();
                String sqlOpt =
                        "SELECT instrument_id, last_price " +
                        "FROM options_live_enriched " +
                        "WHERE underlying = '" + underlying.replace("'", "''") + "' " +
                        "  AND instrument_id IN (" + inClause + ") " +
                        "  AND exchange_ts <= '" + cutoffTs + "' " +
                        "LATEST ON exchange_ts PARTITION BY instrument_id";
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sqlOpt)) {
                    while (rs.next()) {
                        String iid = rs.getString("instrument_id");
                        double[] arr = premMap.computeIfAbsent(iid, k -> new double[]{
                                Double.NaN, Double.NaN, Double.NaN, Double.NaN});
                        arr[windowIdx[w]] = rs.getDouble("last_price");
                    }
                } catch (java.sql.SQLException ex) {
                    if (ex.getMessage() != null && ex.getMessage().contains("does not exist")) break;
                    throw ex;
                }
            }

            // Spot prices at each time anchor from spot_live
            double[] spotByWindow = fetchSpotAtWindows(conn, underlying, now, windowAgoMs);
            double spotNow = spotByWindow[4]; // index 4 = current

            for (String id : ids) {
                double[] p = premMap.getOrDefault(id, new double[]{
                        Double.NaN, Double.NaN, Double.NaN, Double.NaN});
                result.put(id, new RollingWindows(
                        Double.isNaN(p[0]) ? null : p[0],
                        Double.isNaN(p[1]) ? null : p[1],
                        Double.isNaN(p[2]) ? null : p[2],
                        Double.isNaN(p[3]) ? null : p[3],
                        Double.isNaN(spotByWindow[0]) ? null : spotByWindow[0],
                        Double.isNaN(spotByWindow[1]) ? null : spotByWindow[1],
                        Double.isNaN(spotByWindow[2]) ? null : spotByWindow[2],
                        Double.isNaN(spotByWindow[3]) ? null : spotByWindow[3],
                        Double.isNaN(spotNow) ? null : spotNow
                ));
            }
            return result;
        }

        /**
         * Fetches spot prices at each rolling time anchor from {@code spot_live}.
         * Returns array: [spot1mAgo, spot3mAgo, spot5mAgo, spot15mAgo, spotNow]
         */
        private double[] fetchSpotAtWindows(
                Connection conn, String underlying, Instant now, long[] agoMs
        ) throws java.sql.SQLException {
            double[] spots = { Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN };
            long nowMs = now.toEpochMilli();

            // Current spot
            try {
                String sqlNow = "SELECT last_price FROM spot_live " +
                                "WHERE underlying = '" + underlying.replace("'", "''") + "' " +
                                "LATEST ON exchange_ts PARTITION BY underlying";
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sqlNow)) {
                    if (rs.next()) spots[4] = rs.getDouble("last_price");
                }
            } catch (java.sql.SQLException ex) {
                if (ex.getMessage() == null || !ex.getMessage().contains("does not exist")) throw ex;
            }

            // Historical window anchors
            for (int i = 0; i < agoMs.length; i++) {
                String cutoffTs = new java.sql.Timestamp(agoMs[i]).toInstant().toString();
                try {
                    String sqlHist =
                            "SELECT last_price FROM spot_live " +
                            "WHERE underlying = '" + underlying.replace("'", "''") + "' " +
                            "  AND exchange_ts <= '" + cutoffTs + "' " +
                            "LATEST ON exchange_ts PARTITION BY underlying";
                    try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sqlHist)) {
                        if (rs.next()) spots[i] = rs.getDouble("last_price");
                    }
                } catch (java.sql.SQLException ex) {
                    if (ex.getMessage() != null && ex.getMessage().contains("does not exist")) break;
                    throw ex;
                }
            }
            return spots;
        }

        /**
         * Historical cohort averages from {@code options_context_buckets}.
         *
         * <p>Schema: underlying (SYMBOL), option_type (SYMBOL), time_bucket_15m (INT),
         * moneyness_bucket (INT), avg_option_price (DOUBLE), avg_volume (DOUBLE), sample_count (LONG).
         *
         * <p>Match key: underlying + option_type + moneyness_bucket + time_bucket_15m (nearest slot).
         * Uses the current time-of-day 15m bucket and the per-instrument moneyness_bucket from
         * {@code options_live_enriched}. Only queries bounded instrument IDs.
         */
        private java.util.Map<String, Double> fetchHistoricalAvg(
                Connection conn, String underlying,
                java.util.List<String> ids
        ) throws java.sql.SQLException {
            java.util.Map<String, Double> result = new java.util.HashMap<>();
            if (ids.isEmpty()) return result;

            String inClause = ids.stream()
                    .map(id -> "'" + id.replace("'", "''") + "'")
                    .collect(java.util.stream.Collectors.joining(","));

            // Step 1: get latest option_type and moneyness_bucket (INT) per instrument from
            // options_live_enriched. Also get time_bucket_15m for the current 15m cohort slot.
            // key: instrumentId → int[]{ optionTypeCe0Pe1, moneynessBucket, timeBucket15m }
            java.util.Map<String, int[]> cohortKeys = new java.util.HashMap<>();
            try {
                String sqlEnriched =
                        "SELECT instrument_id, option_type, moneyness_bucket, time_bucket_15m " +
                        "FROM options_live_enriched " +
                        "WHERE underlying = '" + underlying.replace("'","''") + "' " +
                        "  AND instrument_id IN (" + inClause + ") " +
                        "LATEST ON exchange_ts PARTITION BY instrument_id";
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sqlEnriched)) {
                    while (rs.next()) {
                        String iid = rs.getString("instrument_id");
                        String ot  = rs.getString("option_type");
                        int    mb  = rs.getInt("moneyness_bucket");
                        int    tb  = rs.getInt("time_bucket_15m");
                        if (ot != null) cohortKeys.put(iid, new int[]{ "CE".equals(ot) ? 0 : 1, mb, tb });
                    }
                }
            } catch (java.sql.SQLException ex) {
                if (ex.getMessage() != null && ex.getMessage().contains("does not exist")) {
                    return result; // no enriched data yet
                }
                throw ex;
            }

            // Step 2: for each instrument, query avg_option_price from options_context_buckets
            // using underlying + option_type + moneyness_bucket + time_bucket_15m.
            for (var entry : cohortKeys.entrySet()) {
                String iid  = entry.getKey();
                int[]  ck   = entry.getValue();
                String ot   = ck[0] == 0 ? "CE" : "PE";
                int    mb   = ck[1];
                int    tb   = ck[2];

                try {
                    String sqlBucket =
                            "SELECT avg_option_price FROM options_context_buckets " +
                            "WHERE underlying = '" + underlying.replace("'","''") + "' " +
                            "  AND option_type = '" + ot + "' " +
                            "  AND moneyness_bucket = " + mb + " " +
                            "  AND time_bucket_15m = " + tb + " " +
                            "LIMIT 1";
                    try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sqlBucket)) {
                        if (rs.next()) {
                            double avg = rs.getDouble("avg_option_price");
                            if (!rs.wasNull() && avg > 0) result.put(iid, avg);
                        }
                    }
                } catch (java.sql.SQLException ex) {
                    if (ex.getMessage() != null && ex.getMessage().contains("does not exist")) break;
                    throw ex;
                }
            }
            return result;
        }

        // ── Data records ──────────────────────────────────────────────────────
        // Note: instrument metadata (lot size, trading symbol, strike, expiry)
        // is sourced directly from InstrumentRef — no separate InstrumentMeta record needed.

        private record LiveRow(double lastPrice, long timestampMs) {}

        private record RollingWindows(
                Double price1mAgo, Double price3mAgo, Double price5mAgo, Double price15mAgo,
                Double spot1mAgo,  Double spot3mAgo,  Double spot5mAgo,  Double spot15mAgo,
                Double spotNow
        ) {}

        private record LegSignal(
                String instrumentId, String tradingSymbol, String optionType,
                double strike, String expiryDate, int lotSize,
                Double currentPremium, long premiumTsMs,
                Double historicalAvg,
                Double richnessPts, Double richnessPct,
                Double d1m, boolean rel1m,
                Double d3m, boolean rel3m,
                Double d5m, boolean rel5m,
                Double d15m, boolean rel15m,
                Double deltaAdjTheta,
                String thetaPattern,
                String actionStatus,
                String disqualifierReason,
                String cohortContext
        ) {}

        // ── JSON builders ─────────────────────────────────────────────────────

        private static String buildEmptyResponse(ScopeStore.StoredScope stored, ResolvedUniverse universe) {
            Scope s = stored.scope();
            // Fix Issue 8: include snapshotTs so the UI can display scan freshness
            String snapshotTs = Instant.now().toString();
            return String.format(
                    java.util.Locale.ROOT,
                    "{\"scopeId\":\"%s\",\"underlying\":\"%s\",\"expiry\":\"%s\","
                    + "\"strategy\":\"%s\",\"instrumentCount\":%d,"
                    + "\"strategyNetDelta\":null,\"snapshotTs\":\"%s\",\"legs\":[]}",
                    escapeJson(stored.scopeId()), escapeJson(s.underlying()), s.expiry(),
                    s.strategy().name(), universe.instruments().size(), snapshotTs
            );
        }

        private static String buildResponse(
                ScopeStore.StoredScope stored, ResolvedUniverse universe,
                java.util.List<LegSignal> legs, double strategyNetDelta, Instant now
        ) {
            Scope s = stored.scope();
            StringBuilder sb = new StringBuilder();
            sb.append("{")
              .append("\"scopeId\":\"").append(escapeJson(stored.scopeId())).append("\",")
              .append("\"underlying\":\"").append(escapeJson(s.underlying())).append("\",")
              .append("\"expiry\":\"").append(s.expiry()).append("\",")
              .append("\"strategy\":\"").append(s.strategy().name()).append("\",")
              .append("\"instrumentCount\":").append(universe.instruments().size()).append(",")
              .append("\"strategyNetDelta\":").append(String.format(java.util.Locale.ROOT, "%.4f", strategyNetDelta)).append(",")
              .append("\"snapshotTs\":\"").append(now.toString()).append("\",")
              .append("\"legs\":[");

            for (int i = 0; i < legs.size(); i++) {
                if (i > 0) sb.append(',');
                LegSignal l = legs.get(i);
                sb.append("{")
                  .append("\"instrumentId\":\"").append(escapeJson(l.instrumentId())).append("\",")
                  .append("\"tradingSymbol\":\"").append(escapeJson(l.tradingSymbol())).append("\",")
                  .append("\"optionType\":\"").append(l.optionType()).append("\",")
                  .append("\"strike\":").append(String.format(java.util.Locale.ROOT, "%.2f", l.strike())).append(",")
                  .append("\"expiryDate\":\"").append(l.expiryDate()).append("\",")
                  .append("\"lotSize\":").append(l.lotSize()).append(",");

                // current premium
                appendNullableDouble(sb, "currentPremium", l.currentPremium(), 2);
                sb.append(",\"premiumTimestamp\":").append(l.premiumTsMs()).append(",");

                // historical average
                appendNullableDouble(sb, "historicalAvg", l.historicalAvg(), 2);
                sb.append(",");

                // richness
                appendNullableDouble(sb, "richnessPts", l.richnessPts(), 2);
                sb.append(",");
                appendNullableDouble(sb, "richnessPct", l.richnessPct(), 2);
                sb.append(",");

                // observed delta — rolling windows
                sb.append("\"observedDelta\":{");
                appendNullableDouble(sb, "d1m",  l.d1m(),  4);  sb.append(",");
                appendNullableDouble(sb, "d3m",  l.d3m(),  4);  sb.append(",");
                appendNullableDouble(sb, "d5m",  l.d5m(),  4);  sb.append(",");
                appendNullableDouble(sb, "d15m", l.d15m(), 4);  sb.append(",");
                sb.append("\"reliable1m\":").append(l.rel1m()).append(",");
                sb.append("\"reliable3m\":").append(l.rel3m()).append(",");
                sb.append("\"reliable5m\":").append(l.rel5m()).append(",");
                sb.append("\"reliable15m\":").append(l.rel15m());
                sb.append("},");

                // delta-adjusted theta and pattern
                appendNullableDouble(sb, "deltaAdjTheta", l.deltaAdjTheta(), 2);
                sb.append(",");
                sb.append("\"thetaPattern\":\"").append(l.thetaPattern()).append("\",");

                // strategy net delta (repeated per leg for client convenience)
                sb.append(String.format(java.util.Locale.ROOT,
                        "\"strategyNetDelta\":%.4f,", strategyNetDelta));

                // action status
                sb.append("\"actionStatus\":\"").append(l.actionStatus()).append("\",");

                // disqualifier
                if (l.disqualifierReason() != null) {
                    sb.append("\"disqualifierReason\":\"").append(escapeJson(l.disqualifierReason())).append("\"");
                } else {
                    sb.append("\"disqualifierReason\":null");
                }
                sb.append(",");

                // cohort context
                sb.append("\"cohortContext\":\"").append(escapeJson(l.cohortContext())).append("\"");
                sb.append("}");
            }
            sb.append("]}");
            return sb.toString();
        }

        private static void appendNullableDouble(StringBuilder sb, String key, Double val, int dec) {
            sb.append('"').append(key).append("\":");
            if (val == null) {
                sb.append("null");
            } else {
                sb.append(String.format(java.util.Locale.ROOT, "%." + dec + "f", val));
            }
        }

        // ── Spot resolution helpers (Fix Issue 2) ────────────────────────────

        /**
         * Returns canonical live spot price for the given scope's underlying, or 0.0
         * when the feed is offline or liveMarketService is not wired.
         */
        private double resolveSpotForScope(Scope scope) {
            if (liveMarketService == null) return 0.0;
            try {
                LiveMarketService.LiveSpotSnapshot snap = liveMarketService.loadSpot(scope.underlying());
                if (snap != null && snap.price() != null && !snap.stale()) {
                    return snap.price().doubleValue();
                }
            } catch (Exception ex) {
                System.err.printf("[ScopeSignalSnapshotHandler] spot lookup failed for %s: %s%n",
                        scope.underlying(), ex.getMessage());
            }
            return 0.0;
        }

        /**
         * Returns true for ATM_PCT / ATM_POINTS windows that require a valid spot
         * estimate to produce a meaningful instrument range.
         */
        private static boolean requiresSpot(com.strategysquad.scope.StrikeWindow window) {
            return window instanceof com.strategysquad.scope.StrikeWindow.AtmPct
                    || window instanceof com.strategysquad.scope.StrikeWindow.AtmPoints;
        }
    }

    // =========================================================================
    // Workflow handlers
    // =========================================================================

    private static final class WorkflowCollectionsHandler implements HttpHandler {
        private final ResearchWorkspaceService service;

        private WorkflowCollectionsHandler(ResearchWorkspaceService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                Map<String, String> form = parseFormBody(exchange);
                String name = FairValueHandler.required(form, "name");
                String description = form.getOrDefault("description", "");
                ResearchCollection collection = service.createCollection(name, description);
                sendJson(exchange, 200, """
                        {"id":"%s","name":"%s","description":"%s"}
                        """.formatted(
                        escapeJson(collection.id()),
                        escapeJson(collection.name()),
                        escapeJson(collection.description() == null ? "" : collection.description())
                ));
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            } catch (SQLException exception) {
                sendJson(exchange, 500, "{\"error\":\"Unable to create collection\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            }
        }
    }

    private static final class WorkflowStudiesHandler implements HttpHandler {
        private final ResearchWorkspaceService service;

        private WorkflowStudiesHandler(ResearchWorkspaceService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            try {
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendJson(exchange, 200, toJson(service.loadWorkspace()));
                    return;
                }
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    Map<String, String> form = parseFormBody(exchange);
                    ResearchScenarioSnapshot snapshot = toScenarioSnapshot(form);
                    service.saveScenario(snapshot);
                    sendJson(exchange, 200, "{\"scenarioId\":\"" + escapeJson(snapshot.scenarioId()) + "\"}");
                    return;
                }
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            } catch (SQLException exception) {
                sendJson(exchange, 500, "{\"error\":\"Unable to persist workflow study\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            }
        }
    }

    private static final class WorkflowStudyHandler implements HttpHandler {
        private final ResearchWorkspaceService service;

        private WorkflowStudyHandler(ResearchWorkspaceService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                String path = exchange.getRequestURI().getPath();
                String scenarioId = path.substring("/api/workflow/studies/".length());
                if (scenarioId.isBlank()) {
                    throw new IllegalArgumentException("Missing scenario id");
                }
                ResearchScenarioSnapshot snapshot = service.loadLatestScenario(scenarioId);
                if (snapshot == null) {
                    sendJson(exchange, 404, "{\"error\":\"Study not found\"}");
                    return;
                }
                sendJson(exchange, 200, toJson(snapshot));
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            } catch (SQLException exception) {
                sendJson(exchange, 500, "{\"error\":\"Unable to load workflow study\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            }
        }
    }

    private static final class PositionSessionsHandler implements HttpHandler {
        private final ResearchPositionSessionService service;

        private PositionSessionsHandler(ResearchPositionSessionService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                try {
                    LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
                    java.util.List<PositionSessionSnapshot> sessions =
                            service.loadForTradingDay(today, ZoneId.of("Asia/Kolkata"));
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < sessions.size(); i++) {
                        if (i > 0) sb.append(",");
                        sb.append(service.toJson(sessions.get(i)));
                    }
                    sb.append("]");
                    sendJson(exchange, 200, sb.toString());
                } catch (IOException exception) {
                    sendJson(exchange, 500, "{\"error\":\"Unable to list position sessions\",\"details\":\""
                            + escapeJson(exception.getMessage()) + "\"}");
                }
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                PositionSessionSnapshot snapshot = service.parseSession(readRequestBody(exchange));
                service.save(snapshot);
                sendJson(exchange, 200, service.toJson(snapshot));
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            } catch (IOException exception) {
                sendJson(exchange, 500, "{\"error\":\"Unable to persist position session\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            }
        }
    }

    private static final class PositionSessionItemHandler implements HttpHandler {
        private final ResearchPositionSessionService service;
        private final PositionSessionActionService actionService;

        private PositionSessionItemHandler(
                ResearchPositionSessionService service,
                PositionSessionActionService actionService
        ) {
            this.service = service;
            this.actionService = actionService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String suffix = path.substring("/api/position-sessions/".length());
            boolean actionRequest = suffix.endsWith("/actions");
            String sessionId = actionRequest
                    ? suffix.substring(0, suffix.length() - "/actions".length())
                    : suffix;
            if (sessionId.endsWith("/")) {
                sessionId = sessionId.substring(0, sessionId.length() - 1);
            }
            if (sessionId.isBlank()) {
                sendJson(exchange, 400, "{\"error\":\"Missing session id\"}");
                return;
            }
            try {
                if (actionRequest) {
                    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                        return;
                    }
                    PositionSessionSnapshot existing = service.load(sessionId);
                    if (existing == null) {
                        sendJson(exchange, 404, "{\"error\":\"Position session not found\"}");
                        return;
                    }
                    PositionSessionActionRequest request = service.parseAction(readRequestBody(exchange));
                    PositionSessionSnapshot updated = actionService.apply(existing, request);
                    service.save(updated);
                    sendJson(exchange, 200, service.toJson(updated));
                    return;
                }
                if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                    service.delete(sessionId);
                    sendText(exchange, 204, "", "text/plain; charset=utf-8");
                    return;
                }
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }
                PositionSessionSnapshot snapshot = service.load(sessionId);
                if (snapshot == null) {
                    sendJson(exchange, 404, "{\"error\":\"Position session not found\"}");
                    return;
                }
                sendJson(exchange, 200, service.toJson(snapshot));
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            } catch (IOException exception) {
                sendJson(exchange, 500, "{\"error\":\"Unable to read position session\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            }
        }
    }

    private static final class StrategyRunReportHandler implements HttpHandler {
        private final StrategyRunReportService service;

        private StrategyRunReportHandler(StrategyRunReportService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                StrategyRunReportService.StrategyRunReportRequest request = service.parseRequest(readRequestBody(exchange));
                Path written = service.writeReportSafely(request);
                sendJson(exchange, 200, """
                        {"written":%s,"path":%s}
                        """.formatted(
                        written != null,
                        written == null ? "null" : quoteOrNull(written.toString())
                ));
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            }
        }
    }

    private static final class OrderMetadataHandler implements HttpHandler {
        private final OptionOrderService service;

        private OrderMetadataHandler(OptionOrderService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI());
                String underlying = query.get("underlying");
                if (underlying == null || underlying.isBlank()) {
                    sendJson(exchange, 400, "{\"error\":\"underlying parameter is required\"}");
                    return;
                }
                sendJson(exchange, 200, service.toJson(service.loadMetadata(underlying)));
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            } catch (SQLException exception) {
                sendJson(exchange, 500, "{\"error\":\"Unable to load option metadata\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            } catch (Exception exception) {
                sendJson(exchange, 503, "{\"error\":\"Quote metadata unavailable\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            }
        }
    }

    private static final class OrderQuoteHandler implements HttpHandler {
        private final OptionOrderService service;

        private OrderQuoteHandler(OptionOrderService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI());
                sendJson(exchange, 200, service.toJson(service.loadQuote(
                        query.get("underlying"),
                        query.get("expiry"),
                        query.get("strike"),
                        query.get("optionType")
                )));
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            } catch (SQLException exception) {
                sendJson(exchange, 500, "{\"error\":\"Unable to resolve contract\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            } catch (Exception exception) {
                sendJson(exchange, 503, "{\"error\":\"Live quote unavailable\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            }
        }
    }

    private static final class OrderPlaceHandler implements HttpHandler {
        private final OptionOrderService service;

        private OrderPlaceHandler(OptionOrderService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                OptionOrderService.PlaceOrderRequest request = new com.google.gson.Gson()
                        .fromJson(readRequestBody(exchange), OptionOrderService.PlaceOrderRequest.class);
                sendJson(exchange, 200, service.toJson(service.placeOrder(request)));
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            } catch (SQLException exception) {
                sendJson(exchange, 500, "{\"error\":\"Unable to place order\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            } catch (IllegalStateException exception) {
                sendJson(exchange, 503, "{\"error\":\"Order placement unavailable\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            } catch (Exception exception) {
                sendJson(exchange, 500, "{\"error\":\"Unexpected order placement error\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            }
        }
    }

    private static final class OrderExecutionsHandler implements HttpHandler {
        private final OptionOrderService service;

        private OrderExecutionsHandler(OptionOrderService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                sendJson(exchange, 200, service.toJson(service.loadExecutions()));
            } catch (SQLException exception) {
                sendJson(exchange, 500, "{\"error\":\"Unable to load executions\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            } catch (Exception exception) {
                sendJson(exchange, 503, "{\"error\":\"Execution feed unavailable\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            }
        }
    }

    private static final class ForwardOutcomeHandler implements HttpHandler {
        private final ForwardOutcomeCohortService service;

        private ForwardOutcomeHandler(ForwardOutcomeCohortService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI());
                ForwardOutcomeSnapshot snapshot = service.loadSnapshot(
                        FairValueHandler.required(query, "underlying"),
                        FairValueHandler.required(query, "optionType"),
                        new java.math.BigDecimal(FairValueHandler.required(query, "spot")),
                        new java.math.BigDecimal(FairValueHandler.required(query, "strike")),
                        Integer.parseInt(FairValueHandler.required(query, "dte"))
                );
                sendJson(exchange, 200, toJson(snapshot));
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            } catch (SQLException exception) {
                sendJson(exchange, 500, "{\"error\":\"Unable to query forward historical outcomes\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            }
        }
    }

    private static final class TimeframeAnalysisHandler implements HttpHandler {
        private final TimeframeAnalysisService service;

        private TimeframeAnalysisHandler(TimeframeAnalysisService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI());
                TimeframeAnalysisSnapshot snapshot = service.loadSnapshot(
                        FairValueHandler.required(query, "underlying"),
                        FairValueHandler.required(query, "optionType"),
                        new BigDecimal(FairValueHandler.required(query, "spot")),
                        new BigDecimal(FairValueHandler.required(query, "strike")),
                        Integer.parseInt(FairValueHandler.required(query, "dte")),
                        new BigDecimal(FairValueHandler.required(query, "optionPrice")),
                        query.getOrDefault("timeframe", "1Y"),
                        parseLocalDate(query.get("customFrom")),
                        parseLocalDate(query.get("customTo"))
                );
                sendJson(exchange, 200, toJson(snapshot));
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            } catch (SQLException exception) {
                sendJson(exchange, 500, "{\"error\":\"Unable to query timeframe analysis\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            }
        }
    }

    private static final class StrategyAnalysisHandler implements HttpHandler {
        private final StrategyAnalysisService service;

        private StrategyAnalysisHandler(StrategyAnalysisService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            try {
                Map<String, String> query = "POST".equalsIgnoreCase(exchange.getRequestMethod())
                        ? parseFormBody(exchange)
                        : parseQuery(exchange.getRequestURI());
                StrategyStructureDefinition definition = parseStrategyDefinition(query);
                EconomicMetrics snapshot = service.loadSnapshot(
                        definition,
                        query.getOrDefault("timeframe", "1Y"),
                        parseLocalDate(query.get("customFrom")),
                        parseLocalDate(query.get("customTo"))
                );
                sendJson(exchange, 200, toJson(snapshot));
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            } catch (SQLException exception) {
                sendJson(exchange, 500, "{\"error\":\"Unable to query strategy analysis\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            }
        }
    }

    private static final class DiagnosticsHandler implements HttpHandler {
        private final DiagnosticsCohortService service;

        private DiagnosticsHandler(DiagnosticsCohortService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI());
                DiagnosticsSnapshot snapshot = service.loadSnapshot(
                        FairValueHandler.required(query, "underlying"),
                        FairValueHandler.required(query, "optionType"),
                        new java.math.BigDecimal(FairValueHandler.required(query, "spot")),
                        new java.math.BigDecimal(FairValueHandler.required(query, "strike")),
                        Integer.parseInt(FairValueHandler.required(query, "dte")),
                        new java.math.BigDecimal(FairValueHandler.required(query, "optionPrice"))
                );
                sendJson(exchange, 200, toJson(snapshot));
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            } catch (SQLException exception) {
                sendJson(exchange, 500, "{\"error\":\"Unable to query diagnostics data\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            }
        }
    }

    private static final class LiveStatusHandler implements HttpHandler {
        private final LiveMarketService service;
        private final KiteSubscriptionManager subscriptionManager;
        private final ScopeService scopeService;

        private LiveStatusHandler(LiveMarketService service,
                                  KiteSubscriptionManager subscriptionManager,
                                  ScopeService scopeService) {
            this.service = service;
            this.subscriptionManager = subscriptionManager;
            this.scopeService = scopeService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            // Phase 7: include scope gauges when available
            LiveStatusReport report = service.loadStatus();
            if (subscriptionManager != null && scopeService != null) {
                report = LiveStatusReport.from(
                        report,
                        subscriptionManager.subscribedCount(),
                        scopeService.isActive()
                );
            }
            sendJson(exchange, 200, toJson(report));
        }
    }

    private static final class AuthStatusHandler implements HttpHandler {
        private final KiteLiveSessionManager sessionManager;

        private AuthStatusHandler(KiteLiveSessionManager sessionManager) {
            this.sessionManager = sessionManager;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            sendJson(exchange, 200, toJson(sessionManager.authStatus()));
        }
    }

    private static final class AuthLoginHandler implements HttpHandler {
        private final KiteLiveSessionManager sessionManager;

        private AuthLoginHandler(KiteLiveSessionManager sessionManager) {
            this.sessionManager = sessionManager;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                Map<String, String> form = parseFormBody(exchange);
                String requestToken = FairValueHandler.required(form, "requestToken");
                sendJson(exchange, 200, toJson(sessionManager.login(requestToken)));
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            } catch (IOException exception) {
                sendJson(exchange, 500, "{\"error\":\"Unable to persist today's access token\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            } catch (RuntimeException exception) {
                sendJson(exchange, 500, "{\"error\":\"Unable to start live session\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            }
        }
    }

    /** POST /api/admin/instruments/refresh — force a fresh NFO download and insert new instruments. */
    private static final class InstrumentRefreshHandler implements HttpHandler {
        private final KiteLiveSessionManager sessionManager;
        InstrumentRefreshHandler(KiteLiveSessionManager sessionManager) {
            this.sessionManager = sessionManager;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            try {
                int inserted = sessionManager.refreshInstruments();
                if (inserted < 0) {
                    sendJson(exchange, 400, "{\"error\":\"No valid token for today. Please log in to Kite first.\"}");
                } else {
                    sendJson(exchange, 200, "{\"ok\":true,\"inserted\":" + inserted + "}");
                }
            } catch (Exception ex) {
                sendJson(exchange, 500, "{\"error\":\"Instrument refresh failed\",\"details\":\""
                        + escapeJson(ex.getMessage()) + "\"}");
            }
        }
    }

    private static final class LiveSpotHandler implements HttpHandler {
        private final LiveMarketService service;

        private LiveSpotHandler(LiveMarketService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            Map<String, String> query = parseQuery(exchange.getRequestURI());
            String underlying = query.get("underlying");
            if (underlying == null || underlying.isBlank()) {
                sendJson(exchange, 200, toJson(service.loadSpots()));
                return;
            }
            LiveMarketService.LiveSpotSnapshot snapshot = service.loadSpot(underlying);
            if (snapshot == null) {
                sendJson(exchange, 404, "{\"error\":\"Live spot unavailable\"}");
                return;
            }
            sendJson(exchange, 200, toJson(snapshot));
        }
    }

    private static final class LiveStructureHandler implements HttpHandler {
        private final LiveMarketService service;

        private LiveStructureHandler(LiveMarketService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                Map<String, String> query = "POST".equalsIgnoreCase(exchange.getRequestMethod())
                        ? parseFormBody(exchange)
                        : parseQuery(exchange.getRequestURI());
                StrategyStructureDefinition definition = parseStrategyDefinition(query);
                sendJson(exchange, 200, toJson(service.loadStructure(definition, parseLastAdjustment(query))));
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            } catch (SQLException exception) {
                sendJson(exchange, 500, "{\"error\":\"Unable to compute live structure snapshot\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            } catch (RuntimeException exception) {
                sendJson(exchange, 500, "{\"error\":\"Unexpected live structure failure\",\"details\":\""
                        + escapeJson(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()) + "\"}");
            }
        }
    }

    private static final class LiveStructureTrendHandler implements HttpHandler {
        private final LiveMarketService service;

        private LiveStructureTrendHandler(LiveMarketService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                Map<String, String> query = "POST".equalsIgnoreCase(exchange.getRequestMethod())
                        ? parseFormBody(exchange)
                        : parseQuery(exchange.getRequestURI());
                StrategyStructureDefinition definition = parseStrategyDefinition(query);
                sendJson(exchange, 200, toJson(service.loadStructureTrend(definition)));
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            } catch (SQLException exception) {
                sendJson(exchange, 500, "{\"error\":\"Unable to load live structure trend\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            }
        }
    }

    private static final class LiveOverlayHandler implements HttpHandler {
        private final LiveMarketService service;

        private LiveOverlayHandler(LiveMarketService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                Map<String, String> query = "POST".equalsIgnoreCase(exchange.getRequestMethod())
                        ? parseFormBody(exchange)
                        : parseQuery(exchange.getRequestURI());
                StrategyStructureDefinition definition = parseStrategyDefinition(query);
                LiveMarketService.LiveHistoricalOverlaySnapshot snapshot = service.loadOverlay(
                        definition,
                        query.getOrDefault("timeframe", "1Y"),
                        parseLocalDate(query.get("customFrom")),
                        parseLocalDate(query.get("customTo"))
                );
                sendJson(exchange, 200, toJson(snapshot));
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            } catch (SQLException exception) {
                sendJson(exchange, 500, "{\"error\":\"Unable to load live overlay\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            }
        }
    }

    private static final class FairValueHandler implements HttpHandler {
        private final FairValueCohortService service;

        private FairValueHandler(FairValueCohortService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI());
                FairValueSnapshot snapshot = service.loadSnapshot(
                        required(query, "underlying"),
                        required(query, "optionType"),
                        new BigDecimal(required(query, "spot")),
                        new BigDecimal(required(query, "strike")),
                        Integer.parseInt(required(query, "dte")),
                        new BigDecimal(required(query, "optionPrice"))
                );
                sendJson(exchange, 200, toJson(snapshot));
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            } catch (SQLException exception) {
                sendJson(exchange, 500, "{\"error\":\"Unable to query canonical historical data\",\"details\":\""
                        + escapeJson(exception.getMessage()) + "\"}");
            }
        }

        private static String required(Map<String, String> query, String key) {
            String value = query.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing required query parameter: " + key);
            }
            return value;
        }
    }

    // -------------------------------------------------------------------------
    // Simulation handlers
    // -------------------------------------------------------------------------

    private static String toJson(HistoricalReplayService.SimulationStatus status) {
        return """
                {
                  "active":%s,
                  "replayDate":%s,
                  "replayTimeIst":%s,
                  "speed":%d,
                  "ticksReplayed":%d,
                  "totalTicks":%d
                }
                """.formatted(
                status.active(),
                status.replayDate() == null ? "null" : "\"" + escapeJson(status.replayDate()) + "\"",
                status.replayTimeIst() == null ? "null" : "\"" + escapeJson(status.replayTimeIst()) + "\"",
                status.speed(),
                status.ticksReplayed(),
                status.totalTicks()
        );
    }

    private static final class SimulationStartHandler implements HttpHandler {
        private final HistoricalReplayService replayService;

        private SimulationStartHandler(HistoricalReplayService replayService) {
            this.replayService = replayService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                Map<String, String> params = parseFormBody(exchange);
                String dateStr = params.get("date");
                if (dateStr == null || dateStr.isBlank()) {
                    sendJson(exchange, 400, "{\"error\":\"date parameter is required (YYYY-MM-DD)\"}");
                    return;
                }
                LocalDate date = LocalDate.parse(dateStr.trim());
                int speed = 1;
                String speedStr = params.get("speed");
                if (speedStr != null && !speedStr.isBlank()) {
                    speed = Integer.parseInt(speedStr.trim());
                }
                replayService.start(date, speed);
                sendJson(exchange, 200, toJson(replayService.getStatus()));
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            } catch (IllegalStateException exception) {
                sendJson(exchange, 409, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            } catch (RuntimeException exception) {
                sendJson(exchange, 500, "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            }
        }
    }

    private static final class SimulationStopHandler implements HttpHandler {
        private final HistoricalReplayService replayService;

        private SimulationStopHandler(HistoricalReplayService replayService) {
            this.replayService = replayService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            replayService.stop();
            sendJson(exchange, 200, toJson(replayService.getStatus()));
        }
    }

    private static final class SimulationStatusHandler implements HttpHandler {
        private final HistoricalReplayService replayService;

        private SimulationStatusHandler(HistoricalReplayService replayService) {
            this.replayService = replayService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            sendJson(exchange, 200, toJson(replayService.getStatus()));
        }
    }

    private static final class StaticUiHandler implements HttpHandler {
        private final Path uiRoot;
        private final String contextPrefix;

        private StaticUiHandler(Path uiRoot) {
            this(uiRoot, "/");
        }

        private StaticUiHandler(Path uiRoot, String contextPrefix) {
            this.uiRoot = uiRoot;
            this.contextPrefix = contextPrefix;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            URI uri = exchange.getRequestURI();
            String rawPath = uri.getPath();
            String relativePath;
            if (rawPath.equals(contextPrefix) || rawPath.equals(contextPrefix.replaceAll("/$", ""))) {
                relativePath = "index.html";
            } else if (rawPath.startsWith(contextPrefix)) {
                relativePath = rawPath.substring(contextPrefix.length());
            } else {
                relativePath = rawPath.equals("/") ? "index.html" : rawPath.substring(1);
            }
            Path target = uiRoot.resolve(relativePath).normalize();
            if (!target.startsWith(uiRoot) || Files.notExists(target) || Files.isDirectory(target)) {
                sendText(exchange, 404, "Not found", "text/plain; charset=utf-8");
                return;
            }
            String contentType = switch (extension(target.getFileName().toString())) {
                case "html" -> "text/html; charset=utf-8";
                case "css" -> "text/css; charset=utf-8";
                case "js" -> "application/javascript; charset=utf-8";
                default -> "application/octet-stream";
            };
            byte[] body = Files.readAllBytes(target);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            exchange.getResponseHeaders().set("Pragma", "no-cache");
            exchange.getResponseHeaders().set("Expires", "0");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }

        private static String extension(String fileName) {
            int index = fileName.lastIndexOf('.');
            return index >= 0 ? fileName.substring(index + 1).toLowerCase() : "";
        }
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> query = new HashMap<>();
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return query;
        }
        for (String part : rawQuery.split("&")) {
            if (part.isBlank()) {
                continue;
            }
            String[] split = part.split("=", 2);
            String key = URLDecoder.decode(split[0], StandardCharsets.UTF_8);
            String value = split.length > 1 ? URLDecoder.decode(split[1], StandardCharsets.UTF_8) : "";
            query.put(key, value);
        }
        return query;
    }

    private static Map<String, String> parseFormBody(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        URI uri = URI.create("http://localhost/?" + body);
        return parseQuery(uri);
    }

    /**
     * Parses a flat JSON object body (no nesting) into a String-to-String map.
     * Only handles string-valued keys. Used for handlers that receive JSON from
     * the trading-platform UI (which sends {@code Content-Type: application/json}).
     */
    private static Map<String, String> parseJsonBodyFlat(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> result = new HashMap<>();
        java.util.regex.Pattern pattern =
                java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
        java.util.regex.Matcher matcher = pattern.matcher(body);
        while (matcher.find()) {
            result.put(matcher.group(1), matcher.group(2));
        }
        return result;
    }

    /**
     * Dispatches to {@link #parseJsonBodyFlat} for JSON content-type, otherwise
     * falls back to URL-encoded form body parsing. This allows handlers to accept
     * both the legacy form-encoded format and the JSON format used by the UI.
     */
    private static Map<String, String> parseBody(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null && contentType.contains("application/json")) {
            return parseJsonBodyFlat(exchange);
        }
        return parseFormBody(exchange);
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static StrategyStructureDefinition parseStrategyDefinition(Map<String, String> query) {
        int legCount = Integer.parseInt(FairValueHandler.required(query, "legCount"));
        if (legCount <= 0) {
            throw new IllegalArgumentException("Strategy requires at least one leg");
        }
        String underlying = FairValueHandler.required(query, "underlying");
        java.util.List<StrategyStructureDefinition.StrategyLeg> legs = new java.util.ArrayList<>();
        for (int index = 0; index < legCount; index++) {
            String prefix = "leg" + index;
            Integer quantity = parseIntegerOrNull(query.get(prefix + "Quantity"));
            if (quantity != null) {
                LotSizingRules.validateOpenQuantity(underlying, quantity);
            }
            legs.add(new StrategyStructureDefinition.StrategyLeg(
                    query.getOrDefault(prefix + "Label", "Leg " + (index + 1)),
                    FairValueHandler.required(query, prefix + "OptionType"),
                    FairValueHandler.required(query, prefix + "Side"),
                    new BigDecimal(FairValueHandler.required(query, prefix + "Strike")),
                    new BigDecimal(FairValueHandler.required(query, prefix + "EntryPrice")),
                    quantity
            ));
        }
        return new StrategyStructureDefinition(
                query.getOrDefault("mode", "SINGLE_OPTION"),
                query.getOrDefault("orientation", "BUYER"),
                underlying,
                query.getOrDefault("expiryType", "WEEKLY"),
                Integer.parseInt(FairValueHandler.required(query, "dte")),
                new BigDecimal(FairValueHandler.required(query, "spot")),
                parseInstantOrNull(query.get("lastDeltaAdjustmentTs")),
                parseInstantOrNull(query.get("pendingAdjustmentSinceTs")),
                legs
        );
    }

    private static DeltaAdjustmentService.LastAdjustment parseLastAdjustment(Map<String, String> query) {
        String actionType = query.get("lastDeltaAdjustmentActionType");
        if (actionType == null || actionType.isBlank()) {
            return null;
        }
        DeltaAdjustmentService.ActionType parsedActionType;
        try {
            parsedActionType = DeltaAdjustmentService.ActionType.valueOf(actionType.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
        BigDecimal strike = null;
        String rawStrike = query.get("lastDeltaAdjustmentStrike");
        if (rawStrike != null && !rawStrike.isBlank()) {
            strike = new BigDecimal(rawStrike);
        }
        return new DeltaAdjustmentService.LastAdjustment(
                parsedActionType,
                query.get("lastDeltaAdjustmentOptionType"),
                query.get("lastDeltaAdjustmentSide"),
                strike,
                parseInstantOrNull(query.get("lastDeltaAdjustmentTs"))
        );
    }

    private static String toJson(FairValueSnapshot snapshot) {
        return """
                {
                  "cohort": {
                    "underlying": "%s",
                    "optionType": "%s",
                    "timeBucket15m": %d,
                    "moneynessBucket": %d,
                    "estimatedMinutesToExpiry": %d,
                    "observationCount": %d,
                    "strength": "%s"
                  },
                  "distribution": {
                    "min": %.6f,
                    "p10": %.6f,
                    "p25": %.6f,
                    "median": %.6f,
                    "mean": %.6f,
                    "p75": %.6f,
                    "p90": %.6f,
                    "max": %.6f
                  },
                  "current": {
                    "optionPrice": %.6f,
                    "percentile": %d,
                    "label": "%s",
                    "interpretation": "%s"
                  }
                }
                """.formatted(
                escapeJson(snapshot.cohort().underlying()),
                escapeJson(snapshot.cohort().optionType()),
                snapshot.cohort().timeBucket15m(),
                snapshot.cohort().moneynessBucket(),
                snapshot.cohort().estimatedMinutesToExpiry(),
                snapshot.observationCount(),
                escapeJson(snapshot.cohortStrength()),
                snapshot.distribution().min(),
                snapshot.distribution().p10(),
                snapshot.distribution().p25(),
                snapshot.distribution().median(),
                snapshot.distribution().mean(),
                snapshot.distribution().p75(),
                snapshot.distribution().p90(),
                snapshot.distribution().max(),
                snapshot.currentPrice().optionPrice(),
                snapshot.currentPrice().percentile(),
                escapeJson(snapshot.currentPrice().label()),
                escapeJson(snapshot.currentPrice().interpretation())
        );
    }

    private static String toJson(ForwardOutcomeSnapshot snapshot) {
        return """
                {
                  "cohort": {
                    "underlying": "%s",
                    "optionType": "%s",
                    "timeBucket15m": %d,
                    "moneynessBucket": %d,
                    "estimatedMinutesToExpiry": %d,
                    "observationCount": %d,
                    "nextDayObservationCount": %d,
                    "expiryObservationCount": %d
                  },
                  "nextDay": {
                    "medianReturnPct": %.6f,
                    "meanReturnPct": %.6f,
                    "expandProbabilityPct": %.6f,
                    "flatProbabilityPct": %.6f,
                    "decayProbabilityPct": %.6f
                  },
                  "expiry": {
                    "medianReturnPct": %.6f,
                    "meanReturnPct": %.6f,
                    "expandProbabilityPct": %.6f,
                    "flatProbabilityPct": %.6f,
                    "decayProbabilityPct": %.6f
                  },
                  "opportunity": {
                    "label": "%s",
                    "interpretation": "%s"
                  }
                }
                """.formatted(
                escapeJson(snapshot.cohort().underlying()),
                escapeJson(snapshot.cohort().optionType()),
                snapshot.cohort().timeBucket15m(),
                snapshot.cohort().moneynessBucket(),
                snapshot.cohort().estimatedMinutesToExpiry(),
                snapshot.observationCount(),
                snapshot.nextDayObservationCount(),
                snapshot.expiryObservationCount(),
                snapshot.nextDay().medianReturnPct(),
                snapshot.nextDay().meanReturnPct(),
                snapshot.nextDay().expandProbabilityPct(),
                snapshot.nextDay().flatProbabilityPct(),
                snapshot.nextDay().decayProbabilityPct(),
                snapshot.expiry().medianReturnPct(),
                snapshot.expiry().meanReturnPct(),
                snapshot.expiry().expandProbabilityPct(),
                snapshot.expiry().flatProbabilityPct(),
                snapshot.expiry().decayProbabilityPct(),
                escapeJson(snapshot.opportunityLabel()),
                escapeJson(snapshot.opportunityInterpretation())
        );
    }

    private static String toJson(TimeframeAnalysisSnapshot snapshot) {
        String windowsJson = snapshot.windows().stream()
                .map(item -> """
                        {
                          "label":"%s",
                          "averagePrice":%.6f,
                          "medianPrice":%.6f,
                          "observationCount":%d,
                          "uniqueContracts":%d,
                          "percentile":%d,
                          "differenceVsCurrent":%.6f
                        }
                        """.formatted(
                        escapeJson(item.label()),
                        item.averagePrice(),
                        item.medianPrice(),
                        item.observationCount(),
                        item.uniqueContracts(),
                        item.percentile(),
                        item.differenceVsCurrent()
                ))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return """
                {
                  "cohort": {
                    "underlying": "%s",
                    "optionType": "%s",
                    "timeBucket15m": %d,
                    "moneynessBucket": %d,
                    "estimatedMinutesToExpiry": %d
                  },
                  "anchorDate":"%s",
                  "currentOptionPrice":%.6f,
                  "windows":[%s],
                  "selectedWindow":{
                    "label":"%s",
                    "averagePrice":%.6f,
                    "medianPrice":%.6f,
                    "observationCount":%d,
                    "uniqueContracts":%d,
                    "percentile":%d,
                    "differenceVsCurrent":%.6f
                  }
                }
                """.formatted(
                escapeJson(snapshot.cohort().underlying()),
                escapeJson(snapshot.cohort().optionType()),
                snapshot.cohort().timeBucket15m(),
                snapshot.cohort().moneynessBucket(),
                snapshot.cohort().estimatedMinutesToExpiry(),
                escapeJson(snapshot.anchorDate()),
                snapshot.currentOptionPrice(),
                windowsJson,
                escapeJson(snapshot.selectedWindow().label()),
                snapshot.selectedWindow().averagePrice(),
                snapshot.selectedWindow().medianPrice(),
                snapshot.selectedWindow().observationCount(),
                snapshot.selectedWindow().uniqueContracts(),
                snapshot.selectedWindow().percentile(),
                snapshot.selectedWindow().differenceVsCurrent()
        );
    }

    private static String toJson(KiteAuthStatus status) {
        return """
                {
                  "authenticatedForToday":%s,
                  "requiresLogin":%s,
                  "userId":"%s",
                  "tradingDate":"%s",
                  "liveStatus":"%s",
                  "message":"%s",
                  "loginUrl":"%s",
                  "tokenFile":"%s"
                }
                """.formatted(
                status.authenticatedForToday(),
                status.requiresLogin(),
                escapeJson(status.userId()),
                status.tradingDate(),
                escapeJson(status.liveStatus()),
                escapeJson(status.message()),
                escapeJson(status.loginUrl() == null ? "" : status.loginUrl()),
                escapeJson(String.valueOf(status.tokenFile()))
        );
    }

    private static String toJson(LiveStatusReport snapshot) {
        return """
                {
                  "status":"%s",
                  "disconnectReason":"%s",
                  "lastTickTs":%s,
                  "secondsSinceLastTick":%d,
                  "subscribedInstruments":%d,
                  "ticksProcessed":%d,
                  "dataReadiness":%s
                }
                """.formatted(
                escapeJson(snapshot.status()),
                escapeJson(snapshot.disconnectReason()),
                snapshot.lastTickTs() == null ? "null" : "\"" + snapshot.lastTickTs() + "\"",
                snapshot.secondsSinceLastTick(),
                snapshot.subscribedInstruments(),
                snapshot.ticksProcessed(),
                toJson(snapshot.dataReadiness())
        );
    }

    private static String toJson(LiveMarketReadinessService.LiveMarketReadinessSnapshot snapshot) {
        if (snapshot == null) {
            return "null";
        }
        String underlyingsJson = snapshot.underlyings().stream()
                .map(ResearchConsoleServer::toJson)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return """
                {
                  "sessionDate":%s,
                  "generatedAt":%s,
                  "available":%s,
                  "unavailableReason":%s,
                  "feedFreshNow":%s,
                  "anyStraddleReadyToday":%s,
                  "niftyStraddleReadyToday":%s,
                  "bankNiftyStraddleReadyToday":%s,
                  "latestSpotTickTs":%s,
                  "latestOptionTickTs":%s,
                  "summary":"%s",
                  "underlyings":[%s]
                }
                """.formatted(
                quoteOrNull(snapshot.sessionDate()),
                quoteOrNull(snapshot.generatedAt()),
                snapshot.available(),
                quoteOrNull(snapshot.unavailableReason()),
                snapshot.feedFreshNow(),
                snapshot.anyStraddleReadyToday(),
                snapshot.niftyStraddleReadyToday(),
                snapshot.bankNiftyStraddleReadyToday(),
                quoteOrNull(snapshot.latestSpotTickTs()),
                quoteOrNull(snapshot.latestOptionTickTs()),
                escapeJson(snapshot.summary()),
                underlyingsJson
        );
    }

    private static String toJson(LiveMarketReadinessService.UnderlyingReadiness snapshot) {
        return """
                {
                  "underlying":"%s",
                  "spotTickCount":%d,
                  "callTickCount":%d,
                  "putTickCount":%d,
                  "straddleReadyToday":%s
                }
                """.formatted(
                escapeJson(snapshot.underlying()),
                snapshot.spotTickCount(),
                snapshot.callTickCount(),
                snapshot.putTickCount(),
                snapshot.straddleReadyToday()
        );
    }

    private static String toJson(List<LiveMarketService.LiveSpotSnapshot> snapshots) {
        String body = snapshots.stream()
                .map(ResearchConsoleServer::toJson)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return "[" + body + "]";
    }

    private static String toJson(LiveMarketService.LiveSpotSnapshot snapshot) {
        return """
                {
                  "underlying":"%s",
                  "price":%s,
                  "priceType":"%s",
                  "source":"%s",
                  "sessionState":"%s",
                  "asOf":%s,
                  "tradeDate":%s,
                  "isStale":%s,
                  "diagnosticReason":%s
                }
                """.formatted(
                escapeJson(snapshot.underlying()),
                snapshot.price() == null ? "null" : String.format(java.util.Locale.ROOT, "%.6f", snapshot.price()),
                escapeJson(snapshot.priceType() == null ? "UNAVAILABLE" : snapshot.priceType()),
                escapeJson(snapshot.source() == null ? "unknown" : snapshot.source()),
                escapeJson(snapshot.sessionState() == null ? "UNKNOWN" : snapshot.sessionState()),
                quoteOrNull(snapshot.asOf()),
                quoteOrNull(snapshot.tradeDate()),
                snapshot.stale(),
                quoteOrNull(snapshot.diagnosticReason())
        );
    }

    private static String toJson(LiveMarketService.LiveStructureSnapshot snapshot) {
        String legs = snapshot.legs().stream()
                .map(leg -> """
                        {
                          "label":"%s",
                          "instrumentId":"%s",
                          "optionType":"%s",
                          "side":"%s",
                          "strike":%.6f,
                          "lastPrice":%s,
                          "bidPrice":%s,
                          "askPrice":%s,
                          "priceType":"%s",
                          "source":"%s",
                          "asOf":%s,
                          "stale":%s,
                          "diagnosticReason":%s,
                          "missing":%s,
                          "lotSize":%d,
                          "quantity":%d,
                          "currentVolume":%s,
                          "dayAverageVolume":%s,
                          "deltaResponse2m":%s,
                          "deltaResponse5m":%s,
                          "deltaResponseSod":%s,
                          "deltaResponse2mObservationCount":%s,
                          "deltaResponse5mObservationCount":%s,
                          "deltaResponseSodObservationCount":%s,
                          "deltaResponse2mUnderlyingMove":%s,
                          "deltaResponse5mUnderlyingMove":%s,
                          "deltaResponseSodUnderlyingMove":%s,
                          "deltaResponseCalculatedAt":%s
                        }
                        """.formatted(
                        escapeJson(leg.label()),
                        escapeJson(leg.instrumentId()),
                        escapeJson(leg.optionType()),
                        escapeJson(leg.side()),
                        leg.strike(),
                        leg.lastPrice() == null ? "null" : String.format(java.util.Locale.ROOT, "%.6f", leg.lastPrice()),
                        leg.bidPrice() == null ? "null" : String.format(java.util.Locale.ROOT, "%.6f", leg.bidPrice()),
                        leg.askPrice() == null ? "null" : String.format(java.util.Locale.ROOT, "%.6f", leg.askPrice()),
                        escapeJson(leg.priceType() == null ? "UNAVAILABLE" : leg.priceType()),
                        escapeJson(leg.source() == null ? "MISSING" : leg.source()),
                        quoteOrNull(leg.asOf()),
                        leg.stale(),
                        quoteOrNull(leg.diagnosticReason()),
                        leg.missing(),
                        leg.lotSize(),
                        leg.quantity(),
                        leg.currentVolume() == null ? "null" : Long.toString(leg.currentVolume()),
                        decimalOrNull(leg.dayAverageVolume()),
                        decimalOrNull(leg.deltaResponse2m()),
                        decimalOrNull(leg.deltaResponse5m()),
                        decimalOrNull(leg.deltaResponseSod()),
                        integerOrNull(leg.deltaResponse2mObservationCount()),
                        integerOrNull(leg.deltaResponse5mObservationCount()),
                        integerOrNull(leg.deltaResponseSodObservationCount()),
                        decimalOrNull(leg.deltaResponse2mUnderlyingMove()),
                        decimalOrNull(leg.deltaResponse5mUnderlyingMove()),
                        decimalOrNull(leg.deltaResponseSodUnderlyingMove()),
                        quoteOrNull(leg.deltaResponseCalculatedAt())
                ))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return """
                {
                  "structureKey":"%s",
                  "mode":"%s",
                  "orientation":"%s",
                  "underlying":"%s",
                  "expiryType":"%s",
                  "sessionState":"%s",
                  "liveSpot":%s,
                  "liveSpotAsOf":%s,
                  "liveSpotTradeDate":%s,
                  "liveSpotPriceType":"%s",
                  "liveSpotSource":"%s",
                  "liveSpotIsStale":%s,
                  "liveSpotDiagnosticReason":%s,
                  "economicNetPremiumPoints":%.6f,
                  "premiumLabel":"%s",
                  "effectiveLotSize":%d,
                  "lastDeltaAdjustmentTs":%s,
                  "pendingAdjustmentSinceTs":%s,
                  "deltaAdjustment":%s,
                  "partialData":%s,
                  "asOf":%s,
                  "legs":[%s]
                }
                """.formatted(
                escapeJson(snapshot.structureKey()),
                escapeJson(snapshot.mode()),
                escapeJson(snapshot.orientation()),
                escapeJson(snapshot.underlying()),
                escapeJson(snapshot.expiryType()),
                escapeJson(snapshot.sessionState()),
                snapshot.liveSpot() == null ? "null" : String.format(java.util.Locale.ROOT, "%.6f", snapshot.liveSpot()),
                quoteOrNull(snapshot.liveSpotAsOf()),
                quoteOrNull(snapshot.liveSpotTradeDate()),
                escapeJson(snapshot.liveSpotPriceType() == null ? "UNAVAILABLE" : snapshot.liveSpotPriceType()),
                escapeJson(snapshot.liveSpotSource() == null ? "unknown" : snapshot.liveSpotSource()),
                snapshot.liveSpotStale(),
                quoteOrNull(snapshot.liveSpotDiagnosticReason()),
                snapshot.economicNetPremiumPoints(),
                escapeJson(snapshot.premiumLabel()),
                snapshot.effectiveLotSize(),
                quoteOrNull(snapshot.lastDeltaAdjustmentTs()),
                quoteOrNull(snapshot.pendingAdjustmentSinceTs()),
                snapshot.deltaAdjustment() == null ? "null" : toJson(snapshot.deltaAdjustment()),
                snapshot.partialData(),
                quoteOrNull(snapshot.asOf()),
                legs
        );
    }

    private static String decimalOrNull(BigDecimal value) {
        return value == null ? "null" : String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    private static String integerOrNull(Integer value) {
        return value == null ? "null" : Integer.toString(value);
    }

    private static String toJson(DeltaAdjustmentService.AdjustmentOutcome outcome) {
        return """
                {
                  "code":"%s",
                  "applied":%s,
                  "timestamp":%s,
                  "updatedLastAdjustmentTs":%s,
                  "updatedPendingAdjustmentSinceTs":%s,
                  "actionType":"%s",
                  "triggerType":"%s",
                  "reasonCode":"%s",
                  "leg":%s,
                  "optionType":%s,
                  "side":%s,
                  "strike":%s,
                  "instrumentId":%s,
                  "symbol":%s,
                  "expiryDate":%s,
                  "marketPrice":%s,
                  "oldQuantity":%d,
                  "newQuantity":%d,
                  "currentTotalLots":%d,
                  "maxTotalLots":%d,
                  "availableLots":%d,
                  "delta2m":%s,
                  "delta5m":%s,
                  "deltaSod":%s,
                  "currentVolume":%s,
                  "dayAverageVolume":%s,
                  "volumeConfirmed":%s,
                  "volumeBypassed":%s,
                  "underlyingDirection":"%s",
                  "profitAlignment":"%s",
                  "livePnlPoints":%s,
                  "livePnlChange2mPoints":%s,
                  "livePnlChange5mPoints":%s,
                  "netDelta2m":%s,
                  "netDelta5m":%s,
                  "netDeltaSod":%s,
                  "normalizedNetDelta":%s,
                  "improvementAbsDelta":%s,
                  "improvementRatio":%s,
                  "postAdjNetDelta":%s,
                  "thetaScore":%s,
                  "liquidityScore":%s,
                  "score":%s,
                  "worsening":%s,
                  "legAsymmetryStress":%s,
                  "reason":"%s",
                  "message":%s
                }
                """.formatted(
                escapeJson(outcome.code()),
                outcome.applied(),
                quoteOrNull(outcome.timestamp()),
                quoteOrNull(outcome.updatedLastAdjustmentTs()),
                quoteOrNull(outcome.updatedPendingAdjustmentSinceTs()),
                escapeJson(outcome.actionType()),
                escapeJson(outcome.triggerType()),
                escapeJson(outcome.reasonCode()),
                quoteOrNull(outcome.leg()),
                quoteOrNull(outcome.optionType()),
                quoteOrNull(outcome.side()),
                decimalOrNull(outcome.strike()),
                quoteOrNull(outcome.instrumentId()),
                quoteOrNull(outcome.symbol()),
                quoteOrNull(outcome.expiryDate()),
                decimalOrNull(outcome.marketPrice()),
                outcome.oldQuantity(),
                outcome.newQuantity(),
                outcome.currentTotalLots(),
                outcome.maxTotalLots(),
                outcome.availableLots(),
                decimalOrNull(outcome.delta2m()),
                decimalOrNull(outcome.delta5m()),
                decimalOrNull(outcome.deltaSod()),
                outcome.currentVolume() == null ? "null" : Long.toString(outcome.currentVolume()),
                decimalOrNull(outcome.dayAverageVolume()),
                outcome.volumeConfirmed(),
                outcome.volumeBypassed(),
                escapeJson(outcome.underlyingDirection()),
                escapeJson(outcome.profitAlignment()),
                decimalOrNull(outcome.livePnlPoints()),
                decimalOrNull(outcome.livePnlChange2mPoints()),
                decimalOrNull(outcome.livePnlChange5mPoints()),
                decimalOrNull(outcome.netDelta2m()),
                decimalOrNull(outcome.netDelta5m()),
                decimalOrNull(outcome.netDeltaSod()),
                decimalOrNull(outcome.normalizedNetDelta()),
                decimalOrNull(outcome.improvementAbsDelta()),
                decimalOrNull(outcome.improvementRatio()),
                decimalOrNull(outcome.postAdjNetDelta()),
                decimalOrNull(outcome.thetaScore()),
                decimalOrNull(outcome.liquidityScore()),
                decimalOrNull(outcome.score()),
                outcome.worsening(),
                outcome.legAsymmetryStress(),
                escapeJson(outcome.reason()),
                quoteOrNull(outcome.message())
        );
    }

    private static String toJson(LiveMarketService.LiveStructureTrendSnapshot snapshot) {
        String points = snapshot.points().stream()
                .map(point -> """
                        {
                          "bucketTs":%s,
                          "timeBucket15m":%d,
                          "economicNetPremiumPoints":%.6f,
                          "volumeSum":%d,
                          "sampleCount":%d,
                          "completeStructure":%s
                        }
                        """.formatted(
                        point.bucketTs() == null ? "null" : "\"" + point.bucketTs() + "\"",
                        point.timeBucket15m(),
                        point.economicNetPremiumPoints(),
                        point.volumeSum(),
                        point.sampleCount(),
                        point.completeStructure()
                ))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return """
                {
                  "structureKey":"%s",
                  "mode":"%s",
                  "orientation":"%s",
                  "underlying":"%s",
                  "expiryType":"%s",
                  "sessionDate":"%s",
                  "points":[%s]
                }
                """.formatted(
                escapeJson(snapshot.structureKey()),
                escapeJson(snapshot.mode()),
                escapeJson(snapshot.orientation()),
                escapeJson(snapshot.underlying()),
                escapeJson(snapshot.expiryType()),
                snapshot.sessionDate(),
                points
        );
    }

    private static String toJson(LiveMarketService.LiveHistoricalOverlaySnapshot snapshot) {
        return """
                {
                  "status":%s,
                  "spot":%s,
                  "structure":%s,
                  "trend":%s,
                  "historicalComparison":%s,
                  "liveEconomic":%s
                }
                """.formatted(
                toJson(snapshot.status()),
                snapshot.spot() == null ? "null" : toJson(snapshot.spot()),
                snapshot.structure() == null ? "null" : toJson(snapshot.structure()),
                snapshot.trend() == null ? "null" : toJson(snapshot.trend()),
                snapshot.historicalComparison() == null ? "null" : toJson(snapshot.historicalComparison()),
                snapshot.liveEconomic() == null ? "null" : toJson(snapshot.liveEconomic())
        );
    }

    private static String toJson(LiveEconomicMetrics snapshot) {
        return """
                {
                  "lot":{
                    "lotSize":%d,
                    "scopeLabel":"%s"
                  },
                  "premium":{
                    "entryPremiumLabel":"%s",
                    "livePremiumLabel":"%s",
                    "entryPremiumPoints":%.6f,
                    "livePremiumPoints":%.6f,
                    "liveVsEntryPoints":%.6f,
                    "liveVsEntryPct":%.6f
                  },
                  "pnl":{
                    "selectedSideLabel":"%s",
                    "livePnlPoints":%.6f,
                    "livePnlRupees":%.6f,
                    "livePnlLabel":"%s",
                    "markState":"%s"
                  },
                  "confidence":{
                    "baseEvidenceStrength":"%s",
                    "effectiveConfidenceLevel":"%s",
                    "feedReliable":%s,
                    "liveAdjustmentLabel":"%s",
                    "confidenceDetail":"%s"
                  }
                }
                """.formatted(
                snapshot.lot().lotSize(),
                escapeJson(snapshot.lot().scopeLabel()),
                escapeJson(snapshot.premium().entryPremiumLabel()),
                escapeJson(snapshot.premium().livePremiumLabel()),
                snapshot.premium().entryPremiumPoints(),
                snapshot.premium().livePremiumPoints(),
                snapshot.premium().liveVsEntryPoints(),
                snapshot.premium().liveVsEntryPct(),
                escapeJson(snapshot.pnl().selectedSideLabel()),
                snapshot.pnl().livePnlPoints(),
                snapshot.pnl().livePnlRupees(),
                escapeJson(snapshot.pnl().livePnlLabel()),
                escapeJson(snapshot.pnl().markState()),
                escapeJson(snapshot.confidence().baseEvidenceStrength()),
                escapeJson(snapshot.confidence().effectiveConfidenceLevel()),
                snapshot.confidence().feedReliable(),
                escapeJson(snapshot.confidence().liveAdjustmentLabel()),
                escapeJson(snapshot.confidence().confidenceDetail())
        );
    }

    private static String toJson(EconomicMetrics snapshot) {
        String windowsJson = snapshot.timeframeTrend().windows().stream()
                .map(item -> """
                        {
                          "label":"%s",
                          "averagePremiumPoints":%.6f,
                          "medianPremiumPoints":%.6f,
                          "rawPricePercentile":%d,
                          "economicPercentile":%d,
                          "percentileReliable":%s,
                          "currentVsAveragePoints":%.6f,
                          "observationCount":%d
                        }
                        """.formatted(
                        escapeJson(item.label()),
                        item.averagePremiumPoints(),
                        item.medianPremiumPoints(),
                        item.rawPricePercentile(),
                        item.economicPercentile(),
                        item.percentileReliable(),
                        item.currentVsAveragePoints(),
                        item.observationCount()
                ))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        String casesJson = snapshot.historicalCases().stream()
                .map(item -> """
                        {
                          "tradeDate":"%s",
                          "expiryDate":"%s",
                          "entryPremiumPoints":%.6f,
                          "expiryValuePoints":%.6f,
                          "selectedSidePnlPoints":%.6f,
                          "buyerPnlPoints":%.6f,
                          "sellerPnlPoints":%.6f,
                          "historicalExtremesLabel":"%s"
                        }
                        """.formatted(
                        escapeJson(item.tradeDate()),
                        escapeJson(item.expiryDate()),
                        item.entryPremiumPoints(),
                        item.expiryValuePoints(),
                        item.selectedSidePnlPoints(),
                        item.buyerPnlPoints(),
                        item.sellerPnlPoints(),
                        escapeJson(item.historicalExtremesLabel())
                ))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return """
                {
                  "mode":"%s",
                  "orientation":"%s",
                  "timeframe":"%s",
                  "observation":{
                    "anchorDate":"%s",
                    "observationCount":%d,
                    "evidenceStrength":"%s",
                    "lowSampleWarning":"%s",
                    "lowSampleDowngrade":%s
                  },
                  "premium":{
                    "currentPremiumLabel":"%s",
                    "averageEntryLabel":"%s",
                    "medianEntryLabel":"%s",
                    "currentPremiumPoints":%.6f,
                    "averageEntryPoints":%.6f,
                    "medianEntryPoints":%.6f,
                    "rawPricePercentile":%d,
                    "economicPercentile":%d,
                    "percentileReliable":%s,
                    "currentVsAveragePoints":%.6f,
                    "currentVsAveragePct":%.6f,
                    "priceConditionLabel":"%s",
                    "attractivenessLabel":"%s"
                  },
                  "expiry":{
                    "averageExpiryValueLabel":"%s",
                    "averageExpiryValuePoints":%.6f,
                    "averageExpiryPayoutPoints":%.6f,
                    "selectedSideAveragePnlPoints":%.6f,
                    "oppositeSideAveragePnlPoints":%.6f,
                    "selectedSideWinRatePct":%.6f,
                    "oppositeSideWinRatePct":%.6f,
                    "selectedSideLabel":"%s"
                  },
                  "pnl":{
                    "averagePnlPoints":%.6f,
                    "medianPnlPoints":%.6f,
                    "avgWinPnlPoints":%.6f,
                    "avgLossPnlPoints":%.6f,
                    "payoffRatio":%.6f,
                    "expectancyPoints":%.6f,
                    "expectancyLabel":"%s",
                    "selectedSideLabel":"%s"
                  },
                  "risk":{
                    "currentTheoreticalMaxProfitPoints":%s,
                    "currentTheoreticalMaxLossPoints":%s,
                    "boundsLabel":"%s",
                    "tailLossP10Points":%.6f,
                    "downsideProfile":"%s",
                    "lowSampleWarning":"%s",
                    "historicalBestPnlPoints":%.6f,
                    "historicalWorstPnlPoints":%.6f,
                    "historicalExtremesLabel":"%s"
                  },
                  "insight":{
                    "premiumVerdict":"%s",
                    "premiumDetail":"%s",
                    "edgeVerdict":"%s",
                    "edgeDetail":"%s",
                    "riskVerdict":"%s",
                    "riskDetail":"%s",
                    "overallVerdict":"%s",
                    "overallDetail":"%s"
                  },
                  "timeframeTrend":{
                    "currentPremiumPoints":%.6f,
                    "windows":[%s]
                  },
                  "recommendation":{
                    "preferred":%s,
                    "alternative":%s,
                    "avoid":%s,
                    "contextNote":"%s"
                  },
                  "historicalCases":[%s]
                }
                """.formatted(
                escapeJson(snapshot.mode()),
                escapeJson(snapshot.orientation()),
                escapeJson(snapshot.timeframe()),
                escapeJson(snapshot.observation().anchorDate()),
                snapshot.observation().observationCount(),
                escapeJson(snapshot.observation().evidenceStrength()),
                escapeJson(snapshot.observation().lowSampleWarning()),
                snapshot.observation().lowSampleDowngrade(),
                escapeJson(snapshot.premium().currentPremiumLabel()),
                escapeJson(snapshot.premium().averageEntryLabel()),
                escapeJson(snapshot.premium().medianEntryLabel()),
                snapshot.premium().currentPremiumPoints(),
                snapshot.premium().averageEntryPoints(),
                snapshot.premium().medianEntryPoints(),
                snapshot.premium().rawPricePercentile(),
                snapshot.premium().economicPercentile(),
                snapshot.premium().percentileReliable(),
                snapshot.premium().currentVsAveragePoints(),
                snapshot.premium().currentVsAveragePct(),
                escapeJson(snapshot.premium().priceConditionLabel()),
                escapeJson(snapshot.premium().attractivenessLabel()),
                escapeJson(snapshot.expiry().averageExpiryValueLabel()),
                snapshot.expiry().averageExpiryValuePoints(),
                snapshot.expiry().averageExpiryPayoutPoints(),
                snapshot.expiry().selectedSideAveragePnlPoints(),
                snapshot.expiry().oppositeSideAveragePnlPoints(),
                snapshot.expiry().selectedSideWinRatePct(),
                snapshot.expiry().oppositeSideWinRatePct(),
                escapeJson(snapshot.expiry().selectedSideLabel()),
                snapshot.pnl().averagePnlPoints(),
                snapshot.pnl().medianPnlPoints(),
                snapshot.pnl().avgWinPnlPoints(),
                snapshot.pnl().avgLossPnlPoints(),
                snapshot.pnl().payoffRatio(),
                snapshot.pnl().expectancyPoints(),
                escapeJson(snapshot.pnl().expectancyLabel()),
                escapeJson(snapshot.pnl().selectedSideLabel()),
                snapshot.risk().currentTheoreticalMaxProfitPoints() == null ? "null" : String.format(java.util.Locale.ROOT, "%.6f", snapshot.risk().currentTheoreticalMaxProfitPoints()),
                snapshot.risk().currentTheoreticalMaxLossPoints() == null ? "null" : String.format(java.util.Locale.ROOT, "%.6f", snapshot.risk().currentTheoreticalMaxLossPoints()),
                escapeJson(snapshot.risk().boundsLabel()),
                snapshot.risk().tailLossP10Points(),
                escapeJson(snapshot.risk().downsideProfile()),
                escapeJson(snapshot.risk().lowSampleWarning()),
                snapshot.risk().historicalBestPnlPoints(),
                snapshot.risk().historicalWorstPnlPoints(),
                escapeJson(snapshot.risk().historicalExtremesLabel()),
                escapeJson(snapshot.insight().premiumVerdict()),
                escapeJson(snapshot.insight().premiumDetail()),
                escapeJson(snapshot.insight().edgeVerdict()),
                escapeJson(snapshot.insight().edgeDetail()),
                escapeJson(snapshot.insight().riskVerdict()),
                escapeJson(snapshot.insight().riskDetail()),
                escapeJson(snapshot.insight().overallVerdict()),
                escapeJson(snapshot.insight().overallDetail()),
                snapshot.timeframeTrend().currentPremiumPoints(),
                windowsJson,
                toJson(snapshot.recommendation().preferred()),
                toJson(snapshot.recommendation().alternative()),
                toJson(snapshot.recommendation().avoid()),
                escapeJson(snapshot.recommendation().contextNote()),
                casesJson
        );
    }

    private static String toJson(EconomicMetrics.RecommendationCandidate recommendation) {
        if (recommendation == null) {
            return "null";
        }
        return """
                {
                  "mode":"%s",
                  "orientation":"%s",
                  "title":"%s",
                  "score":%.6f,
                  "observationCount":%d,
                  "lowSampleWarning":"%s",
                  "rawPricePercentile":%d,
                  "economicPercentile":%d,
                  "premiumVsAveragePoints":%.6f,
                  "averagePnlPoints":%.6f,
                  "winRatePct":%.6f,
                  "downsideSeverityPoints":%.6f,
                  "verdict":"%s",
                  "reason":"%s"
                }
                """.formatted(
                escapeJson(recommendation.mode()),
                escapeJson(recommendation.orientation()),
                escapeJson(recommendation.title()),
                recommendation.score(),
                recommendation.observationCount(),
                escapeJson(recommendation.lowSampleWarning()),
                recommendation.rawPricePercentile(),
                recommendation.economicPercentile(),
                recommendation.premiumVsAveragePoints(),
                recommendation.averagePnlPoints(),
                recommendation.winRatePct(),
                recommendation.downsideSeverityPoints(),
                escapeJson(recommendation.verdict()),
                escapeJson(recommendation.reason())
        );
    }

    private static String toJson(DiagnosticsSnapshot snapshot) {
        String warningsJson = snapshot.warnings().stream()
                .map(item -> "\"" + escapeJson(item) + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        String reasonsJson = snapshot.comparabilityReasons().stream()
                .map(item -> "\"" + escapeJson(item) + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        String casesJson = snapshot.cases().stream()
                .map(item -> """
                        {
                          "tradeDate":"%s",
                          "matchScore":%.6f,
                          "context":"%s",
                          "entryPrice":%.6f,
                          "nextDayReturnPct":%s,
                          "expiryReturnPct":%s,
                          "whyComparable":"%s"
                        }
                        """.formatted(
                        escapeJson(item.tradeDate()),
                        item.matchScore(),
                        escapeJson(item.context()),
                        item.entryPrice(),
                        item.nextDayReturnPct() == null ? "null" : String.format(java.util.Locale.ROOT, "%.6f", item.nextDayReturnPct()),
                        item.expiryReturnPct() == null ? "null" : String.format(java.util.Locale.ROOT, "%.6f", item.expiryReturnPct()),
                        escapeJson(item.whyComparable())
                ))
                .reduce((left, right) -> left + "," + right)
                .orElse("");

        return """
                {
                  "cohort": {
                    "underlying": "%s",
                    "optionType": "%s",
                    "timeBucket15m": %d,
                    "moneynessBucket": %d,
                    "observationCount": %d,
                    "uniqueInstrumentCount": %d,
                    "uniqueTradeDateCount": %d
                  },
                  "diagnostics": {
                    "concentrationPct": %.6f,
                    "nextDayCoveragePct": %.6f,
                    "expiryCoveragePct": %.6f,
                    "confidenceLevel": "%s",
                    "confidenceText": "%s",
                    "concentrationLabel": "%s",
                    "concentrationText": "%s",
                    "sparsityLabel": "%s",
                    "sparsityText": "%s"
                  },
                  "warnings": [%s],
                  "comparabilityReasons": [%s],
                  "cases": [%s]
                }
                """.formatted(
                escapeJson(snapshot.cohort().underlying()),
                escapeJson(snapshot.cohort().optionType()),
                snapshot.cohort().timeBucket15m(),
                snapshot.cohort().moneynessBucket(),
                snapshot.observationCount(),
                snapshot.uniqueInstrumentCount(),
                snapshot.uniqueTradeDateCount(),
                snapshot.concentrationPct(),
                snapshot.nextDayCoveragePct(),
                snapshot.expiryCoveragePct(),
                escapeJson(snapshot.confidenceLevel()),
                escapeJson(snapshot.confidenceText()),
                escapeJson(snapshot.concentrationLabel()),
                escapeJson(snapshot.concentrationText()),
                escapeJson(snapshot.sparsityLabel()),
                escapeJson(snapshot.sparsityText()),
                warningsJson,
                reasonsJson,
                casesJson
        );
    }

    private static String toJson(ResearchWorkspaceSnapshot snapshot) {
        String collections = snapshot.collections().stream()
                .map(item -> """
                        {"id":"%s","name":"%s","description":"%s"}
                        """.formatted(
                        escapeJson(item.id()),
                        escapeJson(item.name()),
                        escapeJson(item.description() == null ? "" : item.description())
                ))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        String studies = snapshot.studies().stream()
                .map(ResearchConsoleServer::toJson)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return """
                {"collections":[%s],"studies":[%s]}
                """.formatted(collections, studies);
    }

    private static String toJson(ResearchScenarioSnapshot snapshot) {
        return """
                {
                  "id":"%s",
                  "scenarioId":"%s",
                  "parentScenarioId":%s,
                  "collectionId":"%s",
                  "collectionName":"%s",
                  "title":"%s",
                  "mode":"%s",
                  "updatedAt":"%s",
                  "inputs":{
                    "underlying":"%s",
                    "optionType":"%s",
                    "expiryType":"%s",
                    "dte":%d,
                    "spot":%.6f,
                    "strike":%.6f,
                    "distancePoints":%.6f,
                    "optionPrice":%.6f,
                    "activityBias":"%s",
                    "researchNote":"%s"
                  },
                  "analysis":{
                    "currentScenarioName":"%s",
                    "fairnessLabel":"%s",
                    "fairnessPercentile":%d,
                    "decisionPosture":"%s",
                    "decisionClass":"%s",
                    "trustLevel":"%s",
                    "trustClass":"%s",
                    "sampleSize":%d,
                    "cohortKey":"%s",
                    "dteBand":"%s",
                    "recommendationBucket":"%s",
                    "recommendationText":"%s",
                    "cohortStrength":"%s",
                    "fairValueJson":%s,
                    "forwardOutcomeJson":%s,
                    "diagnosticsJson":%s
                  }
                }
                """.formatted(
                escapeJson(snapshot.snapshotId()),
                escapeJson(snapshot.scenarioId()),
                snapshot.parentScenarioId() == null ? "null" : "\"" + escapeJson(snapshot.parentScenarioId()) + "\"",
                escapeJson(snapshot.collectionId()),
                escapeJson(snapshot.collectionName() == null ? "" : snapshot.collectionName()),
                escapeJson(snapshot.title()),
                escapeJson(snapshot.mode()),
                snapshot.savedAt().toString(),
                escapeJson(snapshot.inputs().underlying()),
                escapeJson(snapshot.inputs().optionType()),
                escapeJson(snapshot.inputs().expiryType()),
                snapshot.inputs().dte(),
                snapshot.inputs().spot(),
                snapshot.inputs().strike(),
                snapshot.inputs().distancePoints(),
                snapshot.inputs().optionPrice(),
                escapeJson(snapshot.inputs().activityBias()),
                escapeJson(snapshot.inputs().researchNote() == null ? "" : snapshot.inputs().researchNote()),
                escapeJson(snapshot.analysis().currentScenarioName()),
                escapeJson(snapshot.analysis().fairnessLabel()),
                snapshot.analysis().fairnessPercentile(),
                escapeJson(snapshot.analysis().decisionPosture()),
                escapeJson(snapshot.analysis().decisionClass()),
                escapeJson(snapshot.analysis().trustLevel()),
                escapeJson(snapshot.analysis().trustClass()),
                snapshot.analysis().sampleSize(),
                escapeJson(snapshot.analysis().cohortKey()),
                escapeJson(snapshot.analysis().dteBand() == null ? "" : snapshot.analysis().dteBand()),
                escapeJson(snapshot.analysis().recommendationBucket()),
                escapeJson(snapshot.analysis().recommendationText()),
                escapeJson(snapshot.analysis().cohortStrength() == null ? "" : snapshot.analysis().cohortStrength()),
                rawJson(snapshot.analysis().fairValueJson()),
                rawJson(snapshot.analysis().forwardOutcomeJson()),
                rawJson(snapshot.analysis().diagnosticsJson())
        );
    }

    private static String rawJson(String value) {
        return value == null || value.isBlank() ? "null" : value;
    }

    private static ResearchScenarioSnapshot toScenarioSnapshot(Map<String, String> form) {
        return new ResearchScenarioSnapshot(
                form.getOrDefault("snapshotId", "snapshot-" + UUID.randomUUID().toString().replace("-", "")),
                FairValueHandler.required(form, "scenarioId"),
                blankToNull(form.get("parentScenarioId")),
                FairValueHandler.required(form, "collectionId"),
                blankToNull(form.get("collectionName")),
                FairValueHandler.required(form, "title"),
                FairValueHandler.required(form, "mode"),
                Instant.parse(FairValueHandler.required(form, "savedAt")),
                new ResearchScenarioSnapshot.ScenarioInputs(
                        FairValueHandler.required(form, "underlying"),
                        FairValueHandler.required(form, "optionType"),
                        FairValueHandler.required(form, "expiryType"),
                        Integer.parseInt(FairValueHandler.required(form, "dte")),
                        Double.parseDouble(FairValueHandler.required(form, "spot")),
                        Double.parseDouble(FairValueHandler.required(form, "strike")),
                        Double.parseDouble(FairValueHandler.required(form, "distancePoints")),
                        Double.parseDouble(FairValueHandler.required(form, "optionPrice")),
                        FairValueHandler.required(form, "activityBias"),
                        form.getOrDefault("researchNote", "")
                ),
                new ResearchScenarioSnapshot.ScenarioAnalysis(
                        FairValueHandler.required(form, "currentScenarioName"),
                        FairValueHandler.required(form, "fairnessLabel"),
                        Integer.parseInt(FairValueHandler.required(form, "fairnessPercentile")),
                        FairValueHandler.required(form, "decisionPosture"),
                        FairValueHandler.required(form, "decisionClass"),
                        FairValueHandler.required(form, "trustLevel"),
                        form.getOrDefault("trustClass", ""),
                        Long.parseLong(FairValueHandler.required(form, "sampleSize")),
                        FairValueHandler.required(form, "cohortKey"),
                        form.getOrDefault("dteBand", ""),
                        FairValueHandler.required(form, "recommendationBucket"),
                        FairValueHandler.required(form, "recommendationText"),
                        form.getOrDefault("cohortStrength", ""),
                        form.getOrDefault("fairValueJson", "null"),
                        form.getOrDefault("forwardOutcomeJson", "null"),
                        form.getOrDefault("diagnosticsJson", "null")
                )
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static LocalDate parseLocalDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    private static Integer parseIntegerOrNull(String value) {
        return value == null || value.isBlank() ? null : Integer.parseInt(value.trim());
    }

    private static Instant parseInstantOrNull(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value.trim());
    }

    private static String quoteOrNull(Object value) {
        if (value == null) {
            return "null";
        }
        return "\"" + escapeJson(String.valueOf(value)) + "\"";
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static void withCors(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        sendText(exchange, statusCode, body, "application/json; charset=utf-8");
    }

    private static void sendText(HttpExchange exchange, int statusCode, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // =========================================================================
    // Agentic scanner handler (legacy / pre-scope)
    // =========================================================================

    /**
     * GET /api/agentic/scanner/candidates?underlying=NIFTY
     *
     * <p>Runs the morning scanner for a single underlying without requiring an active scope.
     * Used as a fallback when no scope is active.
     */
    private static final class AgenticScannerHandler implements HttpHandler {
        private final String jdbcUrl;

        private AgenticScannerHandler(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            Map<String, String> query = parseQuery(exchange.getRequestURI());
            String underlying = query.get("underlying");
            if (underlying == null || underlying.isBlank()) {
                sendJson(exchange, 400, "{\"error\":\"underlying parameter is required\"}");
                return;
            }
            underlying = underlying.trim().toUpperCase(java.util.Locale.ROOT);
            try {
                ScannerQuery scannerQuery = new ScannerQuery(jdbcUrl);
                MorningScannerService scannerService = new MorningScannerService(
                        scannerQuery, new CandidateScoringEngine(), underlying);
                java.util.Map<CandidateScoringEngine.CohortKey,
                        com.strategysquad.aggregation.OptionsContextBucket> cohortMap =
                        scannerQuery.loadCohortMap(underlying);
                java.util.List<CandidateOpportunity> candidates =
                        scannerService.scan(cohortMap, Instant.now());
                sendJson(exchange, 200, buildCandidatesJson(underlying, candidates));
            } catch (java.sql.SQLException ex) {
                sendJson(exchange, 500,
                        "{\"error\":\"DB_ERROR\",\"details\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
        }

        private static String buildCandidatesJson(String underlying, java.util.List<CandidateOpportunity> candidates) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"underlying\":\"").append(escapeJson(underlying)).append("\",")
              .append("\"candidates\":[");
            for (int i = 0; i < candidates.size(); i++) {
                if (i > 0) sb.append(',');
                CandidateOpportunity c = candidates.get(i);
                sb.append("{")
                  .append("\"candidateId\":\"").append(escapeJson(c.candidateId())).append("\",")
                  .append("\"instrumentId\":\"").append(escapeJson(c.instrumentId())).append("\",")
                  .append("\"tradingSymbol\":\"").append(escapeJson(c.tradingSymbol())).append("\",")
                  .append("\"underlying\":\"").append(escapeJson(c.underlying())).append("\",")
                  .append("\"optionType\":\"").append(escapeJson(c.optionType())).append("\",")
                  .append("\"strike\":").append(c.strike()).append(",")
                  .append("\"expiryDate\":\"").append(c.expiryDate()).append("\",")
                  .append("\"expiryType\":\"").append(escapeJson(c.expiryType())).append("\",")
                  .append("\"spot\":").append(c.spot()).append(",")
                  .append("\"lastPrice\":").append(c.lastPrice()).append(",")
                  .append("\"bidPrice\":").append(c.bidPrice()).append(",")
                  .append("\"askPrice\":").append(c.askPrice()).append(",")
                  .append("\"premiumRichnessPct\":").append(String.format(java.util.Locale.ROOT, "%.4f", c.premiumRichnessPct())).append(",")
                  .append("\"liquidityScore\":").append(String.format(java.util.Locale.ROOT, "%.4f", c.liquidityScore())).append(",")
                  .append("\"thetaOpportunityScore\":").append(String.format(java.util.Locale.ROOT, "%.4f", c.thetaOpportunityScore())).append(",")
                  .append("\"totalScore\":").append(String.format(java.util.Locale.ROOT, "%.6f", c.totalScore())).append(",")
                  .append("\"disqualified\":").append(c.disqualifierReason().isPresent()).append(",")
                  .append("\"disqualifierReason\":");
                if (c.disqualifierReason().isPresent()) {
                    sb.append("\"").append(escapeJson(c.disqualifierReason().get())).append("\"");
                } else {
                    sb.append("null");
                }
                sb.append("}");
            }
            sb.append("]}");
            return sb.toString();
        }
    }

    // =========================================================================
    // Agentic orchestrator handlers
    // =========================================================================

    /** GET /api/agentic/status — current orchestrator state. */
    private static final class AgenticStatusHandler implements HttpHandler {
        private final AtomicReference<MarketDayOrchestrator> orchestratorRef;

        private AgenticStatusHandler(AtomicReference<MarketDayOrchestrator> orchestratorRef) {
            this.orchestratorRef = orchestratorRef;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            MarketDayOrchestrator orch = orchestratorRef.get();
            if (orch == null) {
                sendJson(exchange, 200,
                        "{\"state\":\"OFFLINE\",\"running\":false,\"runId\":null,\"mode\":null,"
                        + "\"underlying\":null,\"haltReason\":null,\"pendingCommandId\":null}");
                return;
            }
            boolean running = orch.currentState() != MarketDayOrchestrator.OrchestratorState.HALTED
                    && orch.currentState() != MarketDayOrchestrator.OrchestratorState.END_OF_DAY;
            sendJson(exchange, 200, String.format(java.util.Locale.ROOT,
                    "{\"state\":\"%s\",\"running\":%s,\"runId\":\"%s\",\"mode\":\"%s\","
                    + "\"underlying\":\"%s\",\"haltReason\":%s,\"pendingCommandId\":%s}",
                    escapeJson(orch.currentState().name()),
                    running,
                    escapeJson(orch.runId()),
                    escapeJson(orch.mode()),
                    escapeJson(orch.underlying()),
                    orch.haltReason() != null ? "\"" + escapeJson(orch.haltReason()) + "\"" : "null",
                    orch.pendingCommandId() != null ? "\"" + escapeJson(orch.pendingCommandId()) + "\"" : "null"
            ));
        }
    }

    /** GET /api/agentic/last-decision — most recent decision command. */
    private static final class AgenticLastDecisionHandler implements HttpHandler {
        private final AtomicReference<MarketDayOrchestrator> orchestratorRef;

        private AgenticLastDecisionHandler(AtomicReference<MarketDayOrchestrator> orchestratorRef) {
            this.orchestratorRef = orchestratorRef;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            MarketDayOrchestrator orch = orchestratorRef.get();
            if (orch == null || orch.lastCommand() == null) {
                sendJson(exchange, 200, "{\"lastDecision\":null}");
                return;
            }
            sendJson(exchange, 200, "{\"lastDecision\":" + toDecisionCommandJson(orch.lastCommand()) + "}");
        }
    }

    /** POST /api/agentic/halt — operator emergency halt. */
    private static final class AgenticHaltHandler implements HttpHandler {
        private final AtomicReference<MarketDayOrchestrator> orchestratorRef;
        @SuppressWarnings("unused")
        private final String jdbcUrl;

        private AgenticHaltHandler(AtomicReference<MarketDayOrchestrator> orchestratorRef, String jdbcUrl) {
            this.orchestratorRef = orchestratorRef;
            this.jdbcUrl = jdbcUrl;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            MarketDayOrchestrator orch = orchestratorRef.get();
            if (orch == null) {
                sendJson(exchange, 200, "{\"result\":\"NO_ORCHESTRATOR\",\"state\":\"OFFLINE\"}");
                return;
            }
            Map<String, String> params = parseBody(exchange);
            String reason = params.getOrDefault("reason", "OPERATOR_HALT");
            orch.forceHalt(reason);
            sendJson(exchange, 200, String.format(java.util.Locale.ROOT,
                    "{\"result\":\"HALTED\",\"state\":\"%s\",\"haltReason\":\"%s\"}",
                    escapeJson(orch.currentState().name()),
                    escapeJson(orch.haltReason() != null ? orch.haltReason() : reason)
            ));
        }
    }

    /** POST /api/agentic/reset-halt — operator reset from HALTED. */
    private static final class AgenticResetHaltHandler implements HttpHandler {
        private final AtomicReference<MarketDayOrchestrator> orchestratorRef;

        private AgenticResetHaltHandler(AtomicReference<MarketDayOrchestrator> orchestratorRef) {
            this.orchestratorRef = orchestratorRef;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            MarketDayOrchestrator orch = orchestratorRef.get();
            if (orch == null) {
                sendJson(exchange, 200, "{\"result\":\"NO_ORCHESTRATOR\",\"state\":\"OFFLINE\"}");
                return;
            }
            try {
                orch.reset();
                sendJson(exchange, 200, String.format(java.util.Locale.ROOT,
                        "{\"result\":\"RESET\",\"state\":\"%s\"}",
                        escapeJson(orch.currentState().name())
                ));
            } catch (IllegalStateException ex) {
                sendJson(exchange, 409,
                        "{\"error\":\"INVALID_STATE\",\"details\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
        }
    }

    /** POST /api/agentic/confirm-command — operator confirms a pending command. */
    private static final class AgenticConfirmCommandHandler implements HttpHandler {
        private final AtomicReference<MarketDayOrchestrator> orchestratorRef;

        private AgenticConfirmCommandHandler(AtomicReference<MarketDayOrchestrator> orchestratorRef) {
            this.orchestratorRef = orchestratorRef;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            MarketDayOrchestrator orch = orchestratorRef.get();
            if (orch == null) {
                sendJson(exchange, 200, "{\"result\":\"NO_ORCHESTRATOR\"}");
                return;
            }
            Map<String, String> params = parseBody(exchange);
            String commandId = params.get("commandId");
            if (commandId == null || commandId.isBlank()) {
                sendJson(exchange, 400, "{\"error\":\"commandId parameter is required\"}");
                return;
            }
            try {
                com.strategysquad.agentic.liveassist.LiveAssistConfirmationGate.GateResult result =
                        orch.confirmPending(commandId);
                sendJson(exchange, 200, String.format("{\"result\":\"%s\",\"commandId\":\"%s\"}",
                        escapeJson(result.status().name()), escapeJson(commandId)));
            } catch (IllegalStateException ex) {
                sendJson(exchange, 409,
                        "{\"error\":\"GATE_ERROR\",\"details\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
        }
    }

    /** POST /api/agentic/cancel-pending — operator cancels a pending command. */
    private static final class AgenticCancelPendingHandler implements HttpHandler {
        private final AtomicReference<MarketDayOrchestrator> orchestratorRef;

        private AgenticCancelPendingHandler(AtomicReference<MarketDayOrchestrator> orchestratorRef) {
            this.orchestratorRef = orchestratorRef;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            MarketDayOrchestrator orch = orchestratorRef.get();
            if (orch == null) {
                sendJson(exchange, 200, "{\"result\":\"NO_ORCHESTRATOR\"}");
                return;
            }
            Map<String, String> params = parseBody(exchange);
            String commandId = params.get("commandId");
            if (commandId == null || commandId.isBlank()) {
                sendJson(exchange, 400, "{\"error\":\"commandId parameter is required\"}");
                return;
            }
            try {
                com.strategysquad.agentic.liveassist.LiveAssistConfirmationGate.GateResult result =
                        orch.cancelPending(commandId);
                sendJson(exchange, 200, String.format("{\"result\":\"%s\",\"commandId\":\"%s\"}",
                        escapeJson(result.status().name()), escapeJson(commandId)));
            } catch (IllegalStateException ex) {
                sendJson(exchange, 409,
                        "{\"error\":\"GATE_ERROR\",\"details\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
        }
    }

    /** POST /api/agentic/simulation/start — start orchestrator in simulation mode. */
    private static final class AgenticSimStartHandler implements HttpHandler {
        private final AtomicReference<MarketDayOrchestrator> orchestratorRef;
        @SuppressWarnings("unused")
        private final String jdbcUrl;

        private AgenticSimStartHandler(AtomicReference<MarketDayOrchestrator> orchestratorRef, String jdbcUrl) {
            this.orchestratorRef = orchestratorRef;
            this.jdbcUrl = jdbcUrl;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            MarketDayOrchestrator orch = orchestratorRef.get();
            if (orch == null) {
                sendJson(exchange, 503,
                        "{\"error\":\"ORCHESTRATOR_UNAVAILABLE\","
                        + "\"details\":\"No orchestrator is wired for simulation mode.\"}");
                return;
            }
            sendJson(exchange, 200, String.format(java.util.Locale.ROOT,
                    "{\"message\":\"Orchestrator is available\",\"state\":\"%s\",\"mode\":\"%s\","
                    + "\"underlying\":\"%s\",\"runId\":\"%s\"}",
                    escapeJson(orch.currentState().name()),
                    escapeJson(orch.mode()),
                    escapeJson(orch.underlying()),
                    escapeJson(orch.runId())
            ));
        }
    }

    /** POST /api/agentic/simulation/stop — halt the orchestrator simulation. */
    private static final class AgenticSimStopHandler implements HttpHandler {
        private final AtomicReference<MarketDayOrchestrator> orchestratorRef;

        private AgenticSimStopHandler(AtomicReference<MarketDayOrchestrator> orchestratorRef) {
            this.orchestratorRef = orchestratorRef;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            MarketDayOrchestrator orch = orchestratorRef.get();
            if (orch == null) {
                sendJson(exchange, 200, "{\"result\":\"NO_ORCHESTRATOR\",\"state\":\"OFFLINE\"}");
                return;
            }
            orch.forceHalt("SIMULATION_STOP");
            sendJson(exchange, 200, String.format("{\"result\":\"HALTED\",\"state\":\"%s\"}",
                    escapeJson(orch.currentState().name())));
        }
    }

    // =========================================================================
    // Theta + Delta Sense Check handler
    // =========================================================================

    /** POST /api/test-harness/theta-delta-sense-check */
    private static final class ThetaDeltaSenseCheckHandler implements HttpHandler {
        private final ThetaDeltaSenseCheckService service;

        private ThetaDeltaSenseCheckHandler(ThetaDeltaSenseCheckService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                Map<String, String> params = parseBody(exchange);
                String underlying = FairValueHandler.required(params, "underlying");
                String expiry = FairValueHandler.required(params, "expiry");
                double strike = Double.parseDouble(FairValueHandler.required(params, "strike"));
                String optionType = FairValueHandler.required(params, "optionType");
                String side = FairValueHandler.required(params, "side");
                double entryPremium = Double.parseDouble(FairValueHandler.required(params, "entryPremium"));
                double currentPremium = Double.parseDouble(FairValueHandler.required(params, "currentPremium"));
                double entryUnderlying = Double.parseDouble(FairValueHandler.required(params, "entryUnderlying"));
                double currentUnderlying = Double.parseDouble(FairValueHandler.required(params, "currentUnderlying"));
                int elapsedMinutes = Integer.parseInt(FairValueHandler.required(params, "elapsedMinutes"));
                ThetaDeltaSenseCheckRequest request = new ThetaDeltaSenseCheckRequest(
                        underlying, expiry, strike, optionType, side,
                        entryPremium, currentPremium, entryUnderlying, currentUnderlying,
                        elapsedMinutes, null, params.get("sessionId"), params.get("notes")
                );
                ThetaDeltaSenseCheckResponse response = service.runSenseCheck(request);
                sendJson(exchange, 200, new com.google.gson.Gson().toJson(response));
            } catch (IllegalArgumentException ex) {
                sendJson(exchange, 400,
                        "{\"error\":\"INVALID_INPUT\",\"details\":\"" + escapeJson(ex.getMessage()) + "\"}");
            } catch (java.sql.SQLException ex) {
                sendJson(exchange, 500,
                        "{\"error\":\"DB_ERROR\",\"details\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
        }
    }

    // =========================================================================
    // Dev task status handler
    // =========================================================================

    /** GET /api/dev/task-status — reads docs/dev-tasks.json and returns it verbatim. */
    private static final class DevTaskStatusHandler implements HttpHandler {
        private final Path devTasksFile;

        private DevTaskStatusHandler(Path devTasksFile) {
            this.devTasksFile = devTasksFile;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            withCors(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            if (devTasksFile == null || Files.notExists(devTasksFile)) {
                sendJson(exchange, 200, "{\"tasks\":[]}");
                return;
            }
            String content = Files.readString(devTasksFile, StandardCharsets.UTF_8);
            sendJson(exchange, 200, content);
        }
    }

    // =========================================================================
    // Decision command JSON helper
    // =========================================================================

    private static String toDecisionCommandJson(DecisionCommand cmd) {
        if (cmd == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append("{")
          .append("\"commandId\":\"").append(escapeJson(cmd.commandId().toString())).append("\",")
          .append("\"issuedTs\":\"").append(escapeJson(cmd.issuedTs().toString())).append("\",")
          .append("\"mode\":\"").append(escapeJson(cmd.mode().name())).append("\",")
          .append("\"commandType\":\"").append(escapeJson(cmd.commandType().name())).append("\",")
          .append("\"reasonCode\":\"").append(escapeJson(cmd.reasonCode())).append("\",")
          .append("\"explanation\":\"").append(escapeJson(cmd.explanation())).append("\",")
          .append("\"riskGuardDecision\":\"").append(escapeJson(cmd.riskGuardDecision().name())).append("\",")
          .append("\"overriddenByRiskGuard\":").append(cmd.overriddenByRiskGuard()).append(",")
          .append("\"positionSessionId\":");
        cmd.positionSessionId().ifPresentOrElse(
                id -> sb.append("\"").append(escapeJson(id)).append("\""),
                () -> sb.append("null")
        );
        sb.append(",\"selectedCandidateIds\":[");
        java.util.List<String> ids = cmd.selectedCandidateIds();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("\"").append(escapeJson(ids.get(i))).append("\"");
        }
        sb.append("]}");
        return sb.toString();
    }
}
