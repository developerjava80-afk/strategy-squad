package com.strategysquad.research;

import java.time.Instant;

/**
 * Workspace collection for organizing research studies.
 */
public record ResearchCollection(
        String id,
        String name,
        String description,
        Instant createdAt
) {
}
