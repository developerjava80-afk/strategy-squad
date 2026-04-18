package com.strategysquad.research;

import java.util.List;

/**
 * DB-backed workspace response for the research console.
 */
public record ResearchWorkspaceSnapshot(
        List<ResearchCollection> collections,
        List<ResearchScenarioSnapshot> studies
) {
}
