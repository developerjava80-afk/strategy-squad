package com.strategysquad.scope;

import java.util.List;
import java.util.Objects;

/**
 * The bounded set of instruments that satisfies a {@link Scope}.
 *
 * <p>Produced by {@link com.strategysquad.scope.UniverseResolver} (Phase 2).
 * All downstream operations — subscription, scanning, UI payload — operate
 * exclusively on the instruments in this record; the broader NFO universe is
 * never retained.
 *
 * <p>When {@link #truncated} is {@code true}, the resolver hit a soft or hard
 * cap and returned a slice of the matching rows. The caller should surface
 * {@link #narrowingHint} to the user and refuse to start a live subscription
 * for hard-capped results.
 */
public record ResolvedUniverse(
        Scope scope,
        List<InstrumentRef> instruments,
        boolean truncated,
        String narrowingHint
) {

    public ResolvedUniverse {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(instruments, "instruments must not be null");
        // Defensive copy — callers must not mutate the list after passing it in.
        instruments = List.copyOf(instruments);
        // narrowingHint may be null when truncated=false.
        if (truncated && (narrowingHint == null || narrowingHint.isBlank())) {
            throw new IllegalArgumentException(
                    "narrowingHint must be provided when truncated=true");
        }
    }

    /**
     * Convenience factory for a non-truncated universe.
     */
    public static ResolvedUniverse of(Scope scope, List<InstrumentRef> instruments) {
        return new ResolvedUniverse(scope, instruments, false, null);
    }

    /**
     * Convenience factory for a truncated universe with a narrowing hint.
     */
    public static ResolvedUniverse truncated(
            Scope scope,
            List<InstrumentRef> instruments,
            String hint
    ) {
        return new ResolvedUniverse(scope, instruments, true,
                Objects.requireNonNull(hint, "hint must not be null"));
    }

    /** Number of instruments in this universe. */
    public int size() {
        return instruments.size();
    }

    /** True when the universe contains no instruments. */
    public boolean isEmpty() {
        return instruments.isEmpty();
    }
}
