package com.strategysquad.scope;

import java.util.Objects;

/**
 * Thrown when a scope activation request is structurally invalid or cannot be
 * satisfied by the current state of {@code instrument_master}.
 *
 * <p>Each instance carries a machine-readable {@link #errorCode()} suitable for
 * embedding in the HTTP 400 response body (see §6 of the design doc), a
 * human-readable {@link #details()} message, and an optional {@link #hint()}
 * that suggests a corrected request.
 *
 * <h2>Error codes</h2>
 * <ul>
 *   <li>{@code INVALID_UNDERLYING} — underlying is not NIFTY or BANKNIFTY.
 *   <li>{@code INVALID_EXPIRY} — expiry date is malformed or in the past.
 *   <li>{@code EXPIRY_NOT_IN_MASTER} — expiry is valid but absent from instrument_master;
 *       hint: call /api/admin/instruments/refresh.
 *   <li>{@code STRIKE_WINDOW_TOO_WIDE} — the window yields more than 100 strikes.
 *   <li>{@code MAX_CANDIDATES_EXCEEDED} — maxCandidates > 100.
 *   <li>{@code LEGS_INSTRUMENT_NOT_FOUND} — a LegsOnly instrument_id is not in instrument_master.
 *   <li>{@code INSTRUMENT_PAIR_INCOMPLETE} — CE/PE pair is missing for a LegsOnly scope.
 * </ul>
 */
public final class ScopeValidationException extends RuntimeException {

    private final String errorCode;
    private final String details;
    private final String hint;

    public ScopeValidationException(String errorCode, String details, String hint) {
        super("[" + Objects.requireNonNull(errorCode, "errorCode") + "] " +
              Objects.requireNonNull(details, "details"));
        this.errorCode = errorCode;
        this.details   = details;
        this.hint      = hint; // nullable
    }

    public ScopeValidationException(String errorCode, String details) {
        this(errorCode, details, null);
    }

    /** Machine-readable error code for the HTTP 400 response body. */
    public String errorCode() {
        return errorCode;
    }

    /** Human-readable description of what went wrong. */
    public String details() {
        return details;
    }

    /**
     * Optional suggestion for a corrected request. May be {@code null} when no
     * actionable hint is available.
     */
    public String hint() {
        return hint;
    }

    /**
     * Returns a JSON fragment matching the §6 error contract:
     * <pre>
     * {
     *   "error": "STRIKE_WINDOW_TOO_WIDE",
     *   "details": "...",
     *   "hint": "..."
     * }
     * </pre>
     */
    public String toJsonBody() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"error\":\"").append(errorCode).append("\"");
        sb.append(",\"details\":\"").append(escapeJson(details)).append("\"");
        if (hint != null && !hint.isBlank()) {
            sb.append(",\"hint\":\"").append(escapeJson(hint)).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }
}
