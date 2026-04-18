package com.strategysquad.research;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Persists and retrieves DB-backed research workspace artifacts.
 */
public class ResearchWorkspaceService {
    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:8812/qdb";
    private static final String DEFAULT_COLLECTION_ID = "collection-core-research";
    private static final String DEFAULT_COLLECTION_NAME = "Core Research";

    private static final String INSERT_COLLECTION_SQL = """
            INSERT INTO research_collections (created_at, collection_id, name, description)
            VALUES (?, ?, ?, ?)
            """;
    private static final String LIST_COLLECTIONS_SQL = """
            SELECT created_at, collection_id, name, description
            FROM research_collections
            ORDER BY created_at ASC
            """;
    private static final String INSERT_SCENARIO_SQL = """
            INSERT INTO research_scenario_snapshots (
              saved_at, snapshot_id, scenario_id, parent_scenario_id, collection_id, title, mode,
              underlying, option_type, expiry_type, dte, spot, strike, distance_points, option_price,
              activity_bias, research_note, time_bucket_15m, moneyness_bucket, cohort_key,
              fairness_label, fairness_percentile, observation_count, cohort_strength, opportunity_label,
              confidence_level, recommendation_bucket, recommendation_text,
              fair_value_json, forward_outcome_json, diagnostics_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String LIST_SCENARIOS_SQL = """
            SELECT saved_at, snapshot_id, scenario_id, parent_scenario_id, collection_id, title, mode,
                   underlying, option_type, expiry_type, dte, spot, strike, distance_points, option_price,
                   activity_bias, research_note, time_bucket_15m, moneyness_bucket, cohort_key,
                   fairness_label, fairness_percentile, observation_count, cohort_strength, opportunity_label,
                   confidence_level, recommendation_bucket, recommendation_text,
                   fair_value_json, forward_outcome_json, diagnostics_json
            FROM research_scenario_snapshots
            ORDER BY saved_at DESC
            """;

    private final String jdbcUrl;

    public ResearchWorkspaceService() {
        this(DEFAULT_JDBC_URL);
    }

    public ResearchWorkspaceService(String jdbcUrl) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
    }

    public ResearchWorkspaceSnapshot loadWorkspace() throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            ensureDefaultCollection(connection);
            List<ResearchCollection> collections = listCollections(connection);
            Map<String, String> collectionNames = new LinkedHashMap<>();
            for (ResearchCollection collection : collections) {
                collectionNames.put(collection.id(), collection.name());
            }
            List<ResearchScenarioSnapshot> studies = latestStudies(connection, collectionNames);
            return new ResearchWorkspaceSnapshot(collections, studies);
        }
    }

    public ResearchCollection createCollection(String name, String description) throws SQLException {
        String collectionId = "collection-" + UUID.randomUUID().toString().replace("-", "");
        Instant createdAt = Instant.now();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(INSERT_COLLECTION_SQL)) {
            statement.setTimestamp(1, Timestamp.from(createdAt));
            statement.setString(2, collectionId);
            statement.setString(3, name);
            statement.setString(4, description);
            statement.executeUpdate();
        }
        return new ResearchCollection(collectionId, name, description, createdAt);
    }

    public void saveScenario(ResearchScenarioSnapshot snapshot) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(INSERT_SCENARIO_SQL)) {
            statement.setTimestamp(1, Timestamp.from(snapshot.savedAt()));
            statement.setString(2, snapshot.snapshotId());
            statement.setString(3, snapshot.scenarioId());
            statement.setString(4, snapshot.parentScenarioId());
            statement.setString(5, snapshot.collectionId());
            statement.setString(6, snapshot.title());
            statement.setString(7, snapshot.mode());
            statement.setString(8, snapshot.inputs().underlying());
            statement.setString(9, snapshot.inputs().optionType());
            statement.setString(10, snapshot.inputs().expiryType());
            statement.setInt(11, snapshot.inputs().dte());
            statement.setDouble(12, snapshot.inputs().spot());
            statement.setDouble(13, snapshot.inputs().strike());
            statement.setDouble(14, snapshot.inputs().distancePoints());
            statement.setDouble(15, snapshot.inputs().optionPrice());
            statement.setString(16, snapshot.inputs().activityBias());
            statement.setString(17, snapshot.inputs().researchNote());
            statement.setInt(18, parseTimeBucket(snapshot.analysis().cohortKey()));
            statement.setInt(19, parseMoneynessBucket(snapshot.analysis().cohortKey()));
            statement.setString(20, snapshot.analysis().cohortKey());
            statement.setString(21, snapshot.analysis().fairnessLabel());
            statement.setInt(22, snapshot.analysis().fairnessPercentile());
            statement.setLong(23, snapshot.analysis().sampleSize());
            statement.setString(24, snapshot.analysis().cohortStrength());
            statement.setString(25, snapshot.analysis().decisionPosture());
            statement.setString(26, snapshot.analysis().trustLevel());
            statement.setString(27, snapshot.analysis().recommendationBucket());
            statement.setString(28, snapshot.analysis().recommendationText());
            statement.setString(29, snapshot.analysis().fairValueJson());
            statement.setString(30, snapshot.analysis().forwardOutcomeJson());
            statement.setString(31, snapshot.analysis().diagnosticsJson());
            statement.executeUpdate();
        }
    }

    public ResearchScenarioSnapshot loadLatestScenario(String scenarioId) throws SQLException {
        ResearchWorkspaceSnapshot workspace = loadWorkspace();
        return workspace.studies().stream()
                .filter(study -> study.scenarioId().equals(scenarioId))
                .findFirst()
                .orElse(null);
    }

    private void ensureDefaultCollection(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LIST_COLLECTIONS_SQL);
             ResultSet rs = statement.executeQuery()) {
            if (rs.next()) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(INSERT_COLLECTION_SQL)) {
            insert.setTimestamp(1, Timestamp.from(Instant.now()));
            insert.setString(2, DEFAULT_COLLECTION_ID);
            insert.setString(3, DEFAULT_COLLECTION_NAME);
            insert.setString(4, "Default collection for canonical scenario research.");
            insert.executeUpdate();
        }
    }

    private List<ResearchCollection> listCollections(Connection connection) throws SQLException {
        List<ResearchCollection> collections = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(LIST_COLLECTIONS_SQL);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                collections.add(new ResearchCollection(
                        rs.getString("collection_id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getTimestamp("created_at").toInstant()
                ));
            }
        }
        return collections;
    }

    private List<ResearchScenarioSnapshot> latestStudies(Connection connection, Map<String, String> collectionNames) throws SQLException {
        Map<String, ResearchScenarioSnapshot> latest = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(LIST_SCENARIOS_SQL);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                String scenarioId = rs.getString("scenario_id");
                if (latest.containsKey(scenarioId)) {
                    continue;
                }
                String collectionId = rs.getString("collection_id");
                latest.put(scenarioId, new ResearchScenarioSnapshot(
                        rs.getString("snapshot_id"),
                        scenarioId,
                        rs.getString("parent_scenario_id"),
                        collectionId,
                        collectionNames.getOrDefault(collectionId, "Collection"),
                        rs.getString("title"),
                        rs.getString("mode"),
                        rs.getTimestamp("saved_at").toInstant(),
                        new ResearchScenarioSnapshot.ScenarioInputs(
                                rs.getString("underlying"),
                                rs.getString("option_type"),
                                rs.getString("expiry_type"),
                                rs.getInt("dte"),
                                rs.getDouble("spot"),
                                rs.getDouble("strike"),
                                rs.getDouble("distance_points"),
                                rs.getDouble("option_price"),
                                rs.getString("activity_bias"),
                                rs.getString("research_note")
                        ),
                        new ResearchScenarioSnapshot.ScenarioAnalysis(
                                rs.getString("title"),
                                rs.getString("fairness_label"),
                                rs.getInt("fairness_percentile"),
                                rs.getString("opportunity_label"),
                                mapDecisionClass(rs.getString("opportunity_label")),
                                rs.getString("confidence_level"),
                                mapTrustClass(rs.getString("confidence_level")),
                                rs.getLong("observation_count"),
                                rs.getString("cohort_key"),
                                "",
                                rs.getString("recommendation_bucket"),
                                rs.getString("recommendation_text"),
                                rs.getString("cohort_strength"),
                                rs.getString("fair_value_json"),
                                rs.getString("forward_outcome_json"),
                                rs.getString("diagnostics_json")
                        )
                ));
            }
        }
        return new ArrayList<>(latest.values());
    }

    private static int parseMoneynessBucket(String cohortKey) {
        String[] parts = cohortKey.split("/");
        return Integer.parseInt(parts[parts.length - 1].trim());
    }

    private static int parseTimeBucket(String cohortKey) {
        for (String part : cohortKey.split("/")) {
            String normalized = part.trim();
            if (normalized.startsWith("TB")) {
                return Integer.parseInt(normalized.substring(2).trim());
            }
        }
        throw new IllegalArgumentException("Unable to parse time bucket from cohort key: " + cohortKey);
    }

    private static String mapDecisionClass(String opportunityLabel) {
        if ("Long premium favored".equalsIgnoreCase(opportunityLabel)) {
            return "long-premium";
        }
        if ("Short premium favored".equalsIgnoreCase(opportunityLabel)) {
            return "short-premium";
        }
        return "no-trade";
    }

    private static String mapTrustClass(String confidenceLevel) {
        if ("Strong".equalsIgnoreCase(confidenceLevel)) {
            return "strong";
        }
        if ("Weak".equalsIgnoreCase(confidenceLevel)) {
            return "fragile";
        }
        return "";
    }
}
