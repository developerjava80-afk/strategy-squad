package com.strategysquad.agentic.decision;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes a {@link DecisionCommand} to the {@code agentic_decision_audit} QuestDB table.
 *
 * <h2>Audit-before-action contract</h2>
 * <p>The caller must invoke {@link #write} <em>before</em> the command is applied to any
 * position state. This satisfies the "audit before action" invariant from
 * {@code developer-notes.md}.
 *
 * <h2>Null-safety for optional fields</h2>
 * <p>The following fields are nullable in the schema and may be absent from a valid
 * {@link DecisionCommand}. This writer maps each to SQL NULL without throwing:
 * <ul>
 *   <li>{@code position_session_id} — absent on ENTER and SKIP when no session exists</li>
 *   <li>{@code live_spot} — absent when market data is unavailable</li>
 *   <li>{@code theta_state} — absent when no signal snapshot exists</li>
 *   <li>{@code theta_progress_ratio} — absent when no signal snapshot exists</li>
 *   <li>{@code liquidity_score} — absent when no candidates were available</li>
 * </ul>
 *
 * <h2>Connection ownership</h2>
 * <p>{@code DecisionAuditWriter} does not own the JDBC {@link Connection}. The caller
 * is responsible for lifecycle management (open, commit, close). The writer does not
 * call {@code commit()} — this keeps the audit write within the caller's transaction
 * boundary if one exists.
 *
 * <h2>Thread safety</h2>
 * <p>This class is stateless (no instance fields other than {@code log}). It is safe
 * to share a single instance across threads, provided each call receives its own
 * {@link Connection}.
 */
public class DecisionAuditWriter {

    private static final Logger LOG = Logger.getLogger(DecisionAuditWriter.class.getName());

    // -------------------------------------------------------------------------
    // INSERT statement
    // -------------------------------------------------------------------------

    private static final String INSERT_SQL =
            "INSERT INTO agentic_decision_audit (" +
            "  command_id, timestamp, mode, state_machine_state, command_type," +
            "  selected_candidate_ids, position_session_id," +
            "  live_spot, net_delta_before, net_delta_after," +
            "  theta_state, theta_progress_ratio," +
            "  live_pnl, booked_pnl, liquidity_score," +
            "  risk_guard_decision, reason_code, explanation, overridden_by_risk_guard" +
            ") VALUES (?,?,?,?,?, ?,?, ?,?,?, ?,?, ?,?,?, ?,?,?,?)";

    private static final String SELECT_BY_ID_SQL =
            "SELECT command_id, timestamp, mode, state_machine_state, command_type," +
            "       selected_candidate_ids, position_session_id," +
            "       live_spot, net_delta_before, net_delta_after," +
            "       theta_state, theta_progress_ratio," +
            "       live_pnl, booked_pnl, liquidity_score," +
            "       risk_guard_decision, reason_code, explanation, overridden_by_risk_guard" +
            "  FROM agentic_decision_audit" +
            " WHERE command_id = ?";

    // -------------------------------------------------------------------------
    // Write API
    // -------------------------------------------------------------------------

    /**
     * Writes the supplied {@link DecisionCommand} to {@code agentic_decision_audit}.
     *
     * <p>The context snapshot (live spot, theta state, theta progress ratio, liquidity
     * score, net delta before/after, live PnL, booked PnL) must be supplied via the
     * {@link AuditContext} parameter because {@link DecisionCommand} itself carries only
     * the decision output, not the full market snapshot.
     *
     * @param conn    open JDBC connection to QuestDB; caller retains ownership
     * @param cmd     the command to write; must not be null
     * @param ctx     market snapshot captured at decision time; must not be null
     * @throws IllegalArgumentException if {@code cmd} or {@code ctx} is null
     * @throws SQLException             if the INSERT fails at the database level
     */
    public void write(Connection conn, DecisionCommand cmd, AuditContext ctx) throws SQLException {
        Objects.requireNonNull(conn, "conn must not be null");
        Objects.requireNonNull(cmd, "cmd must not be null");
        Objects.requireNonNull(ctx, "ctx must not be null");

        LOG.fine(() -> "Writing audit record for command " + cmd.commandId() +
                       " type=" + cmd.commandType() + " mode=" + cmd.mode());

        try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            // 1. command_id
            ps.setString(1, cmd.commandId().toString());

            // 2. timestamp (UTC, microsecond precision for QuestDB)
            ps.setTimestamp(2, Timestamp.from(cmd.issuedTs()));

            // 3. mode
            ps.setString(3, cmd.mode().name());

            // 4. state_machine_state
            ps.setString(4, ctx.stateMachineState());

            // 5. command_type
            ps.setString(5, cmd.commandType().name());

            // 6. selected_candidate_ids — join to comma-separated string
            ps.setString(6, String.join(",", cmd.selectedCandidateIds()));

            // 7. position_session_id — Optional<String>, may be absent
            if (cmd.positionSessionId().isPresent()) {
                ps.setString(7, cmd.positionSessionId().get());
            } else {
                ps.setNull(7, Types.VARCHAR);
            }

            // 8. live_spot — may be null
            if (ctx.liveSpot() != null) {
                ps.setDouble(8, ctx.liveSpot());
            } else {
                ps.setNull(8, Types.DOUBLE);
            }

            // 9. net_delta_before — required
            ps.setDouble(9, ctx.netDeltaBefore());

            // 10. net_delta_after — required
            ps.setDouble(10, ctx.netDeltaAfter());

            // 11. theta_state — may be null
            if (ctx.thetaState() != null) {
                ps.setString(11, ctx.thetaState());
            } else {
                ps.setNull(11, Types.VARCHAR);
            }

            // 12. theta_progress_ratio — may be null
            if (ctx.thetaProgressRatio() != null) {
                ps.setDouble(12, ctx.thetaProgressRatio());
            } else {
                ps.setNull(12, Types.DOUBLE);
            }

            // 13. live_pnl — required
            ps.setDouble(13, ctx.livePnl());

            // 14. booked_pnl — required
            ps.setDouble(14, ctx.bookedPnl());

            // 15. liquidity_score — may be null
            if (ctx.liquidityScore() != null) {
                ps.setDouble(15, ctx.liquidityScore());
            } else {
                ps.setNull(15, Types.DOUBLE);
            }

            // 16. risk_guard_decision
            ps.setString(16, cmd.riskGuardDecision().name());

            // 17. reason_code
            ps.setString(17, cmd.reasonCode());

            // 18. explanation
            ps.setString(18, cmd.explanation());

            // 19. overridden_by_risk_guard
            ps.setBoolean(19, cmd.overriddenByRiskGuard());

            int rows = ps.executeUpdate();
            if (rows != 1) {
                LOG.warning("Expected 1 row inserted for command " + cmd.commandId() +
                            " but got " + rows);
            }
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Failed to write audit record for command " + cmd.commandId(), e);
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // Read API (used by tests for round-trip verification)
    // -------------------------------------------------------------------------

    /**
     * Reads the audit row for the given {@code commandId} and returns it as an
     * {@link AuditRecord}. Returns {@code null} if no row exists with that ID.
     *
     * <p>This method is provided for test round-trip verification and should not
     * be used in hot production paths.
     *
     * @param conn      open JDBC connection; caller retains ownership
     * @param commandId UUID string of the command to read; must not be null or blank
     * @return the matching record, or {@code null} if not found
     * @throws SQLException if the SELECT fails at the database level
     */
    public AuditRecord read(Connection conn, String commandId) throws SQLException {
        Objects.requireNonNull(conn, "conn must not be null");
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId must not be null or blank");
        }

        try (PreparedStatement ps = conn.prepareStatement(SELECT_BY_ID_SQL)) {
            ps.setString(1, commandId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new AuditRecord(
                        rs.getString("command_id"),
                        rs.getTimestamp("timestamp").toInstant(),
                        rs.getString("mode"),
                        rs.getString("state_machine_state"),
                        rs.getString("command_type"),
                        rs.getString("selected_candidate_ids"),
                        rs.getString("position_session_id"),       // nullable
                        nullableDouble(rs, "live_spot"),           // nullable
                        rs.getDouble("net_delta_before"),
                        rs.getDouble("net_delta_after"),
                        rs.getString("theta_state"),               // nullable
                        nullableDouble(rs, "theta_progress_ratio"), // nullable
                        rs.getDouble("live_pnl"),
                        rs.getDouble("booked_pnl"),
                        nullableDouble(rs, "liquidity_score"),     // nullable
                        rs.getString("risk_guard_decision"),
                        rs.getString("reason_code"),
                        rs.getString("explanation"),
                        rs.getBoolean("overridden_by_risk_guard")
                );
            }
        }
    }

    /** Returns null if the column value was SQL NULL, otherwise the double value. */
    private static Double nullableDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    // -------------------------------------------------------------------------
    // Supporting types
    // -------------------------------------------------------------------------

    /**
     * Market-state snapshot captured at the moment the Decision Agent emits a command.
     *
     * <p>These fields are not part of {@link DecisionCommand} (which carries only the
     * decision output). They are assembled by {@link DecisionAgent} from the
     * {@link com.strategysquad.agentic.decision.DecisionContext} and passed here
     * alongside the command.
     *
     * <h2>Units</h2>
     * <ul>
     *   <li>{@link #livePnl()} and {@link #bookedPnl()} — NSE index points.</li>
     *   <li>{@link #netDeltaBefore()} and {@link #netDeltaAfter()} — dimensionless ratio.</li>
     *   <li>{@link #thetaProgressRatio()} — dimensionless 0.0–1.0+ fraction.</li>
     *   <li>{@link #liquidityScore()} — 0.0–1.0 score.</li>
     *   <li>{@link #liveSpot()} — NSE index points.</li>
     * </ul>
     *
     * <h2>Null-safety</h2>
     * <p>{@link #liveSpot()}, {@link #thetaState()}, {@link #thetaProgressRatio()}, and
     * {@link #liquidityScore()} may be {@code null} when the corresponding data is absent.
     * All other fields must not be null.
     */
    public record AuditContext(

            /** State machine state active at decision time; never null or blank. */
            String stateMachineState,

            /**
             * Live spot price of the underlying at decision time (NSE index points).
             * Null if market data was unavailable.
             */
            Double liveSpot,

            /**
             * Net signed delta of the active position before the command is applied.
             * Zero when no session is active.
             */
            double netDeltaBefore,

            /**
             * Net signed delta expected after the command is applied.
             * Equal to {@link #netDeltaBefore()} for HOLD and SKIP.
             */
            double netDeltaAfter,

            /**
             * ThetaState string of the most relevant signal at decision time.
             * One of PROFIT_BOOK, HOLD, DEFENSIVE_EXIT — or null when no signal exists.
             */
            String thetaState,

            /**
             * Theta progress ratio at decision time (dimensionless 0.0–1.0+).
             * Null when no signal is available.
             */
            Double thetaProgressRatio,

            /**
             * Unrealised PnL of the active session at decision time (NSE index points).
             * Zero when no session is active.
             */
            double livePnl,

            /**
             * Cumulative realised (booked) PnL for the trading day (NSE index points).
             * Zero at the start of the day.
             */
            double bookedPnl,

            /**
             * Liquidity score of the top-ranked candidate at decision time (0.0–1.0).
             * Null when no candidates were available.
             */
            Double liquidityScore

    ) {
        /** Compact constructor: stateMachineState must not be null or blank. */
        public AuditContext {
            if (stateMachineState == null || stateMachineState.isBlank()) {
                throw new IllegalArgumentException("stateMachineState must not be null or blank");
            }
        }
    }

    /**
     * Flat representation of one row from {@code agentic_decision_audit}.
     * Used exclusively by test round-trip verification — not a production API.
     */
    public record AuditRecord(
            String commandId,
            java.time.Instant timestamp,
            String mode,
            String stateMachineState,
            String commandType,
            String selectedCandidateIds,
            String positionSessionId,       // nullable
            Double liveSpot,               // nullable
            double netDeltaBefore,
            double netDeltaAfter,
            String thetaState,             // nullable
            Double thetaProgressRatio,     // nullable
            double livePnl,
            double bookedPnl,
            Double liquidityScore,         // nullable
            String riskGuardDecision,
            String reasonCode,
            String explanation,
            boolean overriddenByRiskGuard
    ) {}
}
