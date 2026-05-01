# Live-Assist Production Readiness Checklist

**Purpose:** Gate-control document that must be fully signed off before the
system is promoted from simulation/paper mode to live-assist mode on a real
Kite session.

Each item must be verified independently by a human operator. Leave the
*Status* as `[ ]` until verified; mark `[x]` and fill in *Checked By* and
*Date* when the criterion is met.

---

| # | Checklist Item | Status | Checked By | Date |
|---|---|:---:|---|---|
| 1 | **Deterministic simulation replay** — run the full Phase 6 `Phase6AuditTrailTest` twice with identical fixed inputs and confirm `DecisionCommand` sequences are bit-identical (same types, same reason codes, same explanations). No UUID-level variance should affect downstream logic. | [ ] | | |
| 2 | **Risk Guard unit coverage** — confirm `RiskGuardServiceTest` passes with all 5 hard stops (delta breach, max loss, premium expansion, zero bid, stale data) exercised and each producing a non-empty `triggeredConditions` list. | [ ] | | |
| 3 | **LiveAssistConfirmationGate integration** — manually test or confirm via `LiveAssistConfirmationGateTest` that the PENDING → CONFIRMED, PENDING → CANCELLED, and PENDING → EXPIRED paths all function correctly, and that SIMULATION mode always returns APPROVED without writing to the DB. | [ ] | | |
| 4 | **Operator halt and reset controls** — verify that `POST /api/agentic/halt` transitions the orchestrator to `HALTED`, persists an audit record in `agentic_decision_audit`, and that `POST /api/agentic/reset-halt` correctly re-arms the orchestrator. | [ ] | | |
| 5 | **Cancel-pending control** — verify that `POST /api/agentic/cancel-pending` marks the pending command `CANCELLED` in the gate, returns the correct `GateResult`, and that a subsequent confirmation attempt on the same `commandId` is rejected. | [ ] | | |
| 6 | **Audit trail completeness** — confirm that every `DecisionCommand` emitted across a full simulated market day has a non-blank `reasonCode` and a non-blank `explanation`. No command may be audited with empty reason fields. | [ ] | | |
| 7 | **Forced-action override correctness** — verify that when the Risk Guard fires FORCE_EXIT or FORCE_REDUCE, the resulting `DecisionCommand` has `overriddenByRiskGuard=true`, `riskGuardDecision` set to the correct non-ALLOW value, and the `explanation` contains the triggered condition codes (e.g. `[ZERO_BID]`, `[NET_DELTA_BREACH]`). | [ ] | | |
| 8 | **Mode isolation** — confirm that in SIMULATION mode the system never writes to `position_sessions`, `agentic_pending_commands`, or any live session table. All DB writes must be scoped to audit-only tables (`agentic_session_state`, `agentic_decision_audit`). | [ ] | | |
| 9 | **REST endpoint error handling** — verify that all `/api/agentic/*` endpoints return `400 Bad Request` for missing or malformed JSON bodies, `405 Method Not Allowed` for wrong HTTP methods, and `200 OK` with a machine-readable JSON response body for all success cases. | [ ] | | |

---

## Sign-off

All 9 items must be marked `[x]` before live-assist promotion.

**Final sign-off:** ________________  
**Date:** ________________  
**Note:** Any item that cannot be verified must be escalated and blocked until
resolved. Partial sign-off is not permitted.
