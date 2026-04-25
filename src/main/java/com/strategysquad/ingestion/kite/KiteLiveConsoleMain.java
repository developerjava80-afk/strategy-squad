package com.strategysquad.ingestion.kite;

import com.strategysquad.ingestion.live.session.Live15mAggregator;
import com.strategysquad.ingestion.live.session.Live15mWriter;
import com.strategysquad.ingestion.live.session.LiveSessionState;
import com.strategysquad.research.HistoricalReplayService;
import com.strategysquad.research.LiveMarketService;
import com.strategysquad.research.ResearchConsoleServer;
import com.strategysquad.research.SimulationClock;
import com.strategysquad.research.StrategyAnalysisService;
import com.sun.net.httpserver.HttpServer;

import java.nio.file.Path;

/**
 * Boots the Strategy Squad console with live Kite ingestion enabled.
 *
 * <p>Runtime sequence:
 * <ol>
 *   <li>Load Kite config from {@code kite.properties}</li>
 *   <li>Refresh today's NFO instruments into {@code instrument_master}</li>
 *   <li>Start the existing research console plus live overlay and login endpoints</li>
 *   <li>Start live quote polling only after a valid day-scoped token is available</li>
 * </ol>
 */
public final class KiteLiveConsoleMain {

    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:8812/qdb";

    private KiteLiveConsoleMain() {
    }

    public static void main(String[] args) throws Exception {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException exception) {
            throw new RuntimeException("PostgreSQL JDBC driver not on classpath", exception);
        }

        Path propertiesFile = args.length >= 1
                ? Path.of(args[0]).toAbsolutePath().normalize()
                : Path.of("kite.properties").toAbsolutePath().normalize();
        Path localTokenFile = propertiesFile.resolveSibling("kite.local.properties");
        KiteLiveConfig config;
        try {
            config = KiteLiveConfig.loadFromFile(propertiesFile);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid kite.properties. The daily Zerodha login flow requires kite.api.key, kite.api.secret, and kite.user.id. "
                            + exception.getMessage(),
                    exception
            );
        }

        String jdbcUrl = config.jdbcUrl() == null || config.jdbcUrl().isBlank()
                ? DEFAULT_JDBC_URL
                : config.jdbcUrl();
        int port = config.consolePort();
        new LiveSchemaBootstrapper(jdbcUrl).ensureLiveTables();
        KiteDailyTokenStore tokenStore = new KiteDailyTokenStore(localTokenFile);
        KiteOptionCloseQuoteService optionCloseQuoteService = new KiteOptionCloseQuoteService(
            config.apiKey(),
            tokenStore
        );

        LiveSessionState sessionState = new LiveSessionState();
        Live15mAggregator live15mAggregator = new Live15mAggregator(new Live15mWriter());
        StrategyAnalysisService strategyAnalysisService = new StrategyAnalysisService(jdbcUrl);
        SimulationClock simulationClock = new SimulationClock();
        LiveMarketService liveMarketService = new LiveMarketService(
                jdbcUrl,
                sessionState,
                live15mAggregator,
            optionCloseQuoteService,
                strategyAnalysisService,
                simulationClock
        );
        HistoricalReplayService replayService = new HistoricalReplayService(jdbcUrl, sessionState, simulationClock);

        KiteLiveSessionManager sessionManager = new KiteLiveSessionManager(
                config,
            tokenStore,
                sessionState,
                live15mAggregator,
                jdbcUrl
        );
        sessionManager.initialize();

        HttpServer server = ResearchConsoleServer.startServer(
                port,
                jdbcUrl,
                Path.of("ui", "scenario-research").toAbsolutePath().normalize(),
                liveMarketService,
                sessionManager,
                replayService
        );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sessionManager.shutdown();
            server.stop(0);
        }, "kite-live-shutdown"));
        System.out.printf("Live console initialized from %s on http://localhost:%d%n", propertiesFile, port);
        System.out.printf("Daily token file: %s%n", localTokenFile);
    }
}
