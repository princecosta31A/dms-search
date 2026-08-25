package com.teamsync.dmssearch.service;

import com.teamsync.dmssearch.dto.request.RequestIdentity;
import com.teamsync.dmssearch.dto.request.SearchCriteria;
import com.teamsync.dmssearch.dto.response.SearchPage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Logs the <b>shape</b> of every search, never its content.
 *
 * <h2>The rule</h2>
 * A search term is user data of the most sensitive kind this platform holds.
 * People search for PAN numbers, passport numbers, account numbers and names —
 * so {@code q} and every filter <i>value</i> are treated as PII and never
 * written to a log. What gets logged is which filters were used, how many hits
 * came back and how long it took: enough to debug and tune, useless to an
 * attacker who gets hold of the logs.
 *
 * <p>This mirrors the discipline already in es-update's {@code log_utils.py},
 * which logs field names and byte sizes rather than payloads.
 *
 * <p>Consequence worth knowing: you cannot reproduce a user's exact query from
 * these logs. That is the intended trade. Use the {@code traceId} to correlate,
 * and ask the user what they typed.
 */
@Slf4j
@Component
public class SearchAuditLogger {

    /** Slower than this and the query is worth investigating. */
    private static final long SLOW_QUERY_MS = 1_000;

    public void logSearch(SearchCriteria criteria, RequestIdentity identity,
                          SearchPage page, long elapsedMs) {

        List<String> filters = usedFilters(criteria);

        // esTookMs vs totalMs: the gap is everything outside Elasticsearch —
        // network, deserialisation, mapping. When search "feels slow" that
        // difference is the first number to look at.
        if (elapsedMs >= SLOW_QUERY_MS) {
            log.warn("search.slow tenantId={} userId={} filters={} freeText={} hits={} " +
                            "returned={} esTookMs={} totalMs={} paging={}",
                    identity.tenantId(), identity.userId(), filters, criteria.hasFreeText(),
                    page.getTotal(), page.getItems().size(), page.getTookMs(), elapsedMs,
                    criteria.getCursor() != null ? "cursor" : "offset");
            return;
        }

        log.info("search.completed tenantId={} userId={} filters={} freeText={} hits={} " +
                        "returned={} esTookMs={} totalMs={} paging={} page={} size={}",
                identity.tenantId(), identity.userId(), filters, criteria.hasFreeText(),
                page.getTotal(), page.getItems().size(), page.getTookMs(), elapsedMs,
                criteria.getCursor() != null ? "cursor" : "offset",
                page.getPage(), page.getSize());
    }

    public void logFailure(SearchCriteria criteria, RequestIdentity identity, Exception e) {
        // Exception type and message only. An Elasticsearch error can quote the
        // offending query back at you, so the stack trace stays at DEBUG where
        // it will not reach a shipped log by default.
        log.error("search.failed tenantId={} userId={} filters={} errorType={} error={}",
                identity.tenantId(), identity.userId(), usedFilters(criteria),
                e.getClass().getSimpleName(), e.getMessage());
        log.debug("search.failed.trace tenantId={}", identity.tenantId(), e);
    }

    /**
     * Which filters were applied — names only, never values.
     *
     * <p>Attribute filters are reported as {@code attr.<key>}: the key is schema,
     * the value is user data. Knowing a search filtered on {@code attr.pan_number}
     * is useful for tuning; knowing <i>which</i> PAN number is a leak.
     */
    private List<String> usedFilters(SearchCriteria c) {
        List<String> used = new ArrayList<>();
        if (c.getDocumentTypeName() != null) used.add("documentTypeName");
        if (c.getDocumentTypeId() != null) used.add("documentTypeId");
        if (c.getFolderId() != null) used.add("folderId");
        if (c.getFolderTypeId() != null) used.add("folderTypeId");
        if (c.getFolderTypeName() != null) used.add("folderTypeName");
        if (c.getFolderLifecycleState() != null) used.add("folderLifecycleState");
        if (c.getFileExt() != null) used.add("fileExt");
        if (c.getValidationStatus() != null) used.add("validationStatus");
        if (c.getCreatedFrom() != null || c.getCreatedTo() != null) used.add("createdAt.range");
        if (c.getUpdatedFrom() != null || c.getUpdatedTo() != null) used.add("updatedAt.range");
        if (c.isIncludeDeleted()) used.add("includeDeleted");
        if (c.isIncludeArchived()) used.add("includeArchived");
        c.getAttributes().keySet().forEach(k -> used.add("attr." + k));
        c.getFolderAttributes().keySet().forEach(k -> used.add("folderAttr." + k));
        return used;
    }
}
