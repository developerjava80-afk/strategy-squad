package com.strategysquad.agentic;

import com.strategysquad.agentic.decision.DecisionCommand;
import com.strategysquad.agentic.liveassist.LiveAssistConfirmationGate;
import com.strategysquad.agentic.liveassist.LiveAssistConfirmationGate.GateResult;
import com.strategysquad.agentic.liveassist.LiveAssistConfirmationGate.GateStatus;
import com.strategysquad.agentic.risk.RiskGuardDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LiveAssistConfirmationGate}.
 *
 * <p>All tests are pure in-memory — no database required. A controllable
 * clock ({@code AtomicReference<Instant>}) is injected so expiry behaviour
 * can be tested deterministically without {@code Thread.sleep}.
 */
class LiveAssistConfirmationGateTest {

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    private static DecisionCommand enterCommand(DecisionCommand.Mode mode) {
        return new DecisionCommand(
                UUID.randomUUID(),
                Instant.now(),
                mode,
                DecisionCommand.CommandType.ENTER,
                List.of(),
                Optional.empty(),
                "THETA_CAPTURE",
                "Enter short straddle at 24800",
                RiskGuardDecision.ALLOW,
                false
        );
    }

    private static DecisionCommand monitorCommand(DecisionCommand.Mode mode, DecisionCommand.CommandType type) {
        return new DecisionCommand(
                UUID.randomUUID(),
                Instant.now(),
                mode,
                type,
                List.of(),
                Optional.empty(),
                "THETA_TARGET_REACHED",
                "Theta target met — executing " + type,
                RiskGuardDecision.ALLOW,
                false
        );
    }

    /** Controllable clock supplier backed by an AtomicReference. */
    private static AtomicReference<Instant> controlledClock(Instant initial) {
        return new AtomicReference<>(initial);
    }

    private static LiveAssistConfirmationGate liveAssistGate(long timeoutSeconds,
                                                              AtomicReference<Instant> clock) {
        return new LiveAssistConfirmationGate(
                DecisionCommand.Mode.LIVE_ASSIST,
                timeoutSeconds,
                clock::get,
                null,    // no DB — in-memory only
                "test-run-id"
        );
    }

    // -------------------------------------------------------------------------
    // SIMULATION mode — auto-approve
    // -------------------------------------------------------------------------

    @Test
    void simulation_submit_autoApproves() {
        LiveAssistConfirmationGate gate = LiveAssistConfirmationGate.simulation();
        GateResult result = gate.submit(enterCommand(DecisionCommand.Mode.SIMULATION));
        assertEquals(GateStatus.APPROVED, result.status());
        assertNotNull(result.commandId());
    }

    @Test
    void simulation_nullCommandRejected() {
        LiveAssistConfirmationGate gate = LiveAssistConfirmationGate.simulation();
        assertThrows(NullPointerException.class, () -> gate.submit(null));
    }

    // -------------------------------------------------------------------------
    // PAPER mode — auto-approve
    // -------------------------------------------------------------------------

    @Test
    void paper_submit_autoApproves() {
        LiveAssistConfirmationGate gate = new LiveAssistConfirmationGate(
                DecisionCommand.Mode.PAPER, 60L, Instant::now, null, "test");
        GateResult result = gate.submit(enterCommand(DecisionCommand.Mode.PAPER));
        assertEquals(GateStatus.APPROVED, result.status());
    }

    // -------------------------------------------------------------------------
    // LIVE_ASSIST mode — pending
    // -------------------------------------------------------------------------

    @Test
    void liveAssist_submit_returnsPending() {
        AtomicReference<Instant> clock = controlledClock(Instant.parse("2026-04-26T09:00:00Z"));
        LiveAssistConfirmationGate gate = liveAssistGate(60L, clock);

        DecisionCommand cmd = enterCommand(DecisionCommand.Mode.LIVE_ASSIST);
        GateResult result = gate.submit(cmd);

        assertEquals(GateStatus.PENDING, result.status());
        assertEquals(cmd.commandId().toString(), result.commandId());
    }

    @Test
    void liveAssist_checkStatus_pendingBeforeTimeout() {
        AtomicReference<Instant> clock = controlledClock(Instant.parse("2026-04-26T09:00:00Z"));
        LiveAssistConfirmationGate gate = liveAssistGate(60L, clock);

        DecisionCommand cmd = enterCommand(DecisionCommand.Mode.LIVE_ASSIST);
        gate.submit(cmd);

        // Advance clock but stay within timeout
        clock.set(Instant.parse("2026-04-26T09:00:59Z")); // 59 seconds later

        GateResult status = gate.checkStatus(cmd.commandId().toString());
        assertEquals(GateStatus.PENDING, status.status());
    }

    // -------------------------------------------------------------------------
    // LIVE_ASSIST — confirm
    // -------------------------------------------------------------------------

    @Test
    void liveAssist_confirm_succeeds() {
        AtomicReference<Instant> clock = controlledClock(Instant.parse("2026-04-26T09:00:00Z"));
        LiveAssistConfirmationGate gate = liveAssistGate(60L, clock);

        DecisionCommand cmd = enterCommand(DecisionCommand.Mode.LIVE_ASSIST);
        GateResult submitted = gate.submit(cmd);

        GateResult confirmed = gate.confirm(submitted.commandId());
        assertEquals(GateStatus.CONFIRMED, confirmed.status());
        assertEquals(submitted.commandId(), confirmed.commandId());
    }

    @Test
    void liveAssist_confirmIdempotent() {
        AtomicReference<Instant> clock = controlledClock(Instant.parse("2026-04-26T09:00:00Z"));
        LiveAssistConfirmationGate gate = liveAssistGate(60L, clock);

        DecisionCommand cmd = enterCommand(DecisionCommand.Mode.LIVE_ASSIST);
        GateResult submitted = gate.submit(cmd);

        gate.confirm(submitted.commandId());
        GateResult second = gate.confirm(submitted.commandId());
        assertEquals(GateStatus.CONFIRMED, second.status());
    }

    @Test
    void liveAssist_getPendingCommand_returnsOriginal() {
        AtomicReference<Instant> clock = controlledClock(Instant.parse("2026-04-26T09:00:00Z"));
        LiveAssistConfirmationGate gate = liveAssistGate(60L, clock);

        DecisionCommand cmd = enterCommand(DecisionCommand.Mode.LIVE_ASSIST);
        gate.submit(cmd);

        DecisionCommand retrieved = gate.getPendingCommand(cmd.commandId().toString());
        assertNotNull(retrieved);
        assertEquals(cmd.commandId(), retrieved.commandId());
        assertEquals(DecisionCommand.CommandType.ENTER, retrieved.commandType());
    }

    // -------------------------------------------------------------------------
    // LIVE_ASSIST — expiry (deterministic with controlled clock)
    // -------------------------------------------------------------------------

    @Test
    void liveAssist_checkStatus_expiredAfterTimeout() {
        Instant start = Instant.parse("2026-04-26T09:00:00Z");
        AtomicReference<Instant> clock = controlledClock(start);
        LiveAssistConfirmationGate gate = liveAssistGate(30L, clock); // 30-second timeout

        DecisionCommand cmd = enterCommand(DecisionCommand.Mode.LIVE_ASSIST);
        gate.submit(cmd);

        // Advance clock PAST the 30-second window
        clock.set(start.plusSeconds(31));

        GateResult status = gate.checkStatus(cmd.commandId().toString());
        assertEquals(GateStatus.EXPIRED, status.status());
    }

    @Test
    void liveAssist_confirmAfterExpiry_returnsExpired() {
        Instant start = Instant.parse("2026-04-26T09:00:00Z");
        AtomicReference<Instant> clock = controlledClock(start);
        LiveAssistConfirmationGate gate = liveAssistGate(30L, clock);

        DecisionCommand cmd = enterCommand(DecisionCommand.Mode.LIVE_ASSIST);
        GateResult submitted = gate.submit(cmd);

        // Advance clock past timeout
        clock.set(start.plusSeconds(31));

        GateResult confirmed = gate.confirm(submitted.commandId());
        assertEquals(GateStatus.EXPIRED, confirmed.status());
    }

    // -------------------------------------------------------------------------
    // LIVE_ASSIST — cancel
    // -------------------------------------------------------------------------

    @Test
    void liveAssist_cancel_succeeds() {
        AtomicReference<Instant> clock = controlledClock(Instant.parse("2026-04-26T09:00:00Z"));
        LiveAssistConfirmationGate gate = liveAssistGate(60L, clock);

        DecisionCommand cmd = enterCommand(DecisionCommand.Mode.LIVE_ASSIST);
        GateResult submitted = gate.submit(cmd);

        GateResult cancelled = gate.cancel(submitted.commandId());
        assertEquals(GateStatus.CANCELLED, cancelled.status());
    }

    @Test
    void liveAssist_checkStatus_cancelledAfterCancel() {
        AtomicReference<Instant> clock = controlledClock(Instant.parse("2026-04-26T09:00:00Z"));
        LiveAssistConfirmationGate gate = liveAssistGate(60L, clock);

        DecisionCommand cmd = enterCommand(DecisionCommand.Mode.LIVE_ASSIST);
        GateResult submitted = gate.submit(cmd);
        gate.cancel(submitted.commandId());

        GateResult status = gate.checkStatus(submitted.commandId());
        assertEquals(GateStatus.CANCELLED, status.status());
    }

    @Test
    void liveAssist_confirmAfterCancel_returnsCancelled() {
        AtomicReference<Instant> clock = controlledClock(Instant.parse("2026-04-26T09:00:00Z"));
        LiveAssistConfirmationGate gate = liveAssistGate(60L, clock);

        DecisionCommand cmd = enterCommand(DecisionCommand.Mode.LIVE_ASSIST);
        GateResult submitted = gate.submit(cmd);
        gate.cancel(submitted.commandId());

        GateResult confirmAttempt = gate.confirm(submitted.commandId());
        assertEquals(GateStatus.CANCELLED, confirmAttempt.status());
    }

    @Test
    void liveAssist_cancelAfterConfirm_returnsConfirmed() {
        AtomicReference<Instant> clock = controlledClock(Instant.parse("2026-04-26T09:00:00Z"));
        LiveAssistConfirmationGate gate = liveAssistGate(60L, clock);

        DecisionCommand cmd = enterCommand(DecisionCommand.Mode.LIVE_ASSIST);
        GateResult submitted = gate.submit(cmd);
        gate.confirm(submitted.commandId());

        // Attempting to cancel after confirmation returns CONFIRMED (already confirmed)
        GateResult cancelAttempt = gate.cancel(submitted.commandId());
        assertEquals(GateStatus.CONFIRMED, cancelAttempt.status());
    }

    @Test
    void liveAssist_cancelIdempotent() {
        AtomicReference<Instant> clock = controlledClock(Instant.parse("2026-04-26T09:00:00Z"));
        LiveAssistConfirmationGate gate = liveAssistGate(60L, clock);

        DecisionCommand cmd = enterCommand(DecisionCommand.Mode.LIVE_ASSIST);
        GateResult submitted = gate.submit(cmd);
        gate.cancel(submitted.commandId());
        GateResult second = gate.cancel(submitted.commandId());
        assertEquals(GateStatus.CANCELLED, second.status());
    }

    // -------------------------------------------------------------------------
    // Unknown / not-found command IDs
    // -------------------------------------------------------------------------

    @Test
    void checkStatus_unknownCommandId_returnsNotFound() {
        LiveAssistConfirmationGate gate = liveAssistGate(60L, controlledClock(Instant.now()));
        GateResult result = gate.checkStatus("unknown-id-xyz-" + UUID.randomUUID());
        assertEquals(GateStatus.NOT_FOUND, result.status());
    }

    @Test
    void confirm_unknownCommandId_returnsNotFound() {
        LiveAssistConfirmationGate gate = liveAssistGate(60L, controlledClock(Instant.now()));
        GateResult result = gate.confirm("unknown-id-" + UUID.randomUUID());
        assertEquals(GateStatus.NOT_FOUND, result.status());
    }

    @Test
    void cancel_unknownCommandId_returnsNotFound() {
        LiveAssistConfirmationGate gate = liveAssistGate(60L, controlledClock(Instant.now()));
        GateResult result = gate.cancel("unknown-id-" + UUID.randomUUID());
        assertEquals(GateStatus.NOT_FOUND, result.status());
    }

    @Test
    void getPendingCommand_unknownId_returnsNull() {
        LiveAssistConfirmationGate gate = liveAssistGate(60L, controlledClock(Instant.now()));
        assertNull(gate.getPendingCommand("unknown-id-" + UUID.randomUUID()));
    }

    // -------------------------------------------------------------------------
    // Multiple concurrent pending commands
    // -------------------------------------------------------------------------

    @Test
    void liveAssist_multipleCommandsTrackedIndependently() {
        AtomicReference<Instant> clock = controlledClock(Instant.parse("2026-04-26T09:00:00Z"));
        LiveAssistConfirmationGate gate = liveAssistGate(60L, clock);

        DecisionCommand cmd1 = enterCommand(DecisionCommand.Mode.LIVE_ASSIST);
        DecisionCommand cmd2 = monitorCommand(DecisionCommand.Mode.LIVE_ASSIST,
                DecisionCommand.CommandType.BOOK_PROFIT);

        gate.submit(cmd1);
        gate.submit(cmd2);

        // Cancel cmd1, confirm cmd2
        gate.cancel(cmd1.commandId().toString());
        gate.confirm(cmd2.commandId().toString());

        assertEquals(GateStatus.CANCELLED, gate.checkStatus(cmd1.commandId().toString()).status());
        assertEquals(GateStatus.CONFIRMED, gate.checkStatus(cmd2.commandId().toString()).status());
    }

    // -------------------------------------------------------------------------
    // LIVE_ASSIST — accessor methods
    // -------------------------------------------------------------------------

    @Test
    void mode_returnsConstructedMode() {
        LiveAssistConfirmationGate gate = liveAssistGate(60L, controlledClock(Instant.now()));
        assertEquals(DecisionCommand.Mode.LIVE_ASSIST, gate.mode());
    }

    @Test
    void timeoutSeconds_returnsConstructedTimeout() {
        LiveAssistConfirmationGate gate = liveAssistGate(45L, controlledClock(Instant.now()));
        assertEquals(45L, gate.timeoutSeconds());
    }
}
