package com.strategysquad.research;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Serves the Scenario Research console and a DB-backed fair-value API for local research use.
 */
public final class ResearchConsoleServer {
    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:8812/qdb";

    private ResearchConsoleServer() {
    }

    public static void main(String[] args) throws IOException {
        try { Class.forName("org.postgresql.Driver"); } catch (ClassNotFoundException e) {
            throw new RuntimeException("PostgreSQL JDBC driver not on classpath", e);
        }
        int port = args.length >= 1 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        String jdbcUrl = args.length >= 2 ? args[1] : DEFAULT_JDBC_URL;
        Path uiRoot = Path.of("ui", "scenario-research").toAbsolutePath().normalize();
        FairValueCohortService service = new FairValueCohortService(jdbcUrl);
        TimeframeAnalysisService timeframeAnalysisService = new TimeframeAnalysisService(jdbcUrl);
        ForwardOutcomeCohortService forwardOutcomeService = new ForwardOutcomeCohortService(jdbcUrl);
        StrategyAnalysisService strategyAnalysisService = new StrategyAnalysisService(jdbcUrl);
        DiagnosticsCohortService diagnosticsService = new DiagnosticsCohortService(jdbcUrl);
        ResearchWorkspaceService workspaceService = new ResearchWorkspaceService(jdbcUrl);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/fair-value", new FairValueHandler(service));
        server.createContext("/api/timeframe-analysis", new TimeframeAnalysisHandler(timeframeAnalysisService));
        server.createContext("/api/forward-outcomes", new ForwardOutcomeHandler(forwardOutcomeService));
        server.createContext("/api/strategy-analysis", new StrategyAnalysisHandler(strategyAnalysisService));
        server.createContext("/api/diagnostics", new DiagnosticsHandler(diagnosticsService));
        server.createContext("/api/workflow/collections", new WorkflowCollectionsHandler(workspaceService));
        server.createContext("/api/workflow/studies/", new WorkflowStudyHandler(workspaceService));
        server.createContext("/api/workflow/studies", new WorkflowStudiesHandler(workspaceService));
        server.createContext("/", new StaticUiHandler(uiRoot));
        server.setExecutor(Executors.newFixedThreadPool(6));
        server.start();
        System.out.printf("Scenario Research server listening on http://localhost:%d%n", port);
        System.out.printf("Serving UI from %s%n", uiRoot);
        System.out.printf("Using JDBC URL %s%n", jdbcUrl);
    }

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

    private static final class StaticUiHandler implements HttpHandler {
        private final Path uiRoot;

        private StaticUiHandler(Path uiRoot) {
            this.uiRoot = uiRoot;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            URI uri = exchange.getRequestURI();
            String rawPath = uri.getPath();
            String relativePath = rawPath.equals("/") ? "index.html" : rawPath.substring(1);
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

    private static StrategyStructureDefinition parseStrategyDefinition(Map<String, String> query) {
        int legCount = Integer.parseInt(FairValueHandler.required(query, "legCount"));
        if (legCount <= 0) {
            throw new IllegalArgumentException("Strategy requires at least one leg");
        }
        java.util.List<StrategyStructureDefinition.StrategyLeg> legs = new java.util.ArrayList<>();
        for (int index = 0; index < legCount; index++) {
            String prefix = "leg" + index;
            legs.add(new StrategyStructureDefinition.StrategyLeg(
                    query.getOrDefault(prefix + "Label", "Leg " + (index + 1)),
                    FairValueHandler.required(query, prefix + "OptionType"),
                    FairValueHandler.required(query, prefix + "Side"),
                    new BigDecimal(FairValueHandler.required(query, prefix + "Strike")),
                    new BigDecimal(FairValueHandler.required(query, prefix + "EntryPrice"))
            ));
        }
        return new StrategyStructureDefinition(
                query.getOrDefault("mode", "SINGLE_OPTION"),
                query.getOrDefault("orientation", "BUYER"),
                FairValueHandler.required(query, "underlying"),
                query.getOrDefault("expiryType", "WEEKLY"),
                Integer.parseInt(FairValueHandler.required(query, "dte")),
                new BigDecimal(FairValueHandler.required(query, "spot")),
                legs
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
}
