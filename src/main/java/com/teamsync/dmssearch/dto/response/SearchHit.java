package com.teamsync.dmssearch.dto.response;

/**
 * Implemented by every result DTO so {@code SearchService} can stamp the
 * relevance score without knowing which mapping produced the hit.
 *
 * <p>This is the ONLY thing the execution path needs to know about a result.
 * Everything else — field names, shapes, which fields even exist — stays inside
 * the version's own DTO. That is what lets one executor serve both the legacy
 * and the optimized mapping with no knowledge of either.
 */
public interface SearchHit {

    /**
     * Sets the Elasticsearch relevance score, or {@code null} when the query had
     * no free-text term (everything then scores identically and the number is
     * noise).
     */
    void setScore(Double score);
}
