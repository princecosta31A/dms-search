package com.teamsync.dmssearch.legacy;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link LegacyDocumentSummary} (the index shape) to
 * {@link LegacyDocumentResponse} (document-service's shape).
 *
 * <p>Every awkwardness of serving an old index behind a familiar contract is
 * concentrated here, so neither the DTO nor the query builder has to carry it.
 * The mirror of document-service's {@code DocumentResponseMapper}, and like that
 * one it is pure: no Elasticsearch call, no Mongo read, one object in and one
 * object out.
 */
@Component
public class LegacyDocumentResponseMapper {

    public LegacyDocumentResponse toResponse(LegacyDocumentSummary src) {
        if (src == null) {
            return null;
        }
        return LegacyDocumentResponse.builder()
                .documentId(src.getDocumentId())
                .documentName(src.getDocumentName())
                .ownedBy(src.getUserId())
                .workspaceId(src.getWorkspaceId())
                .parentId(src.getFolderId())
                .tenantId(src.getTenantId())
                .extension(stripLeadingDot(src.getDocumentExt()))
                .documentType(buildDocumentType(src))
                .createdAt(src.getCreatedAt())
                .updatedAt(src.getUpdatedAt())
                .storageKey(src.getPath())
                .bucketName(src.getBucket())
                .build();
    }

    /**
     * Builds the {@code documentType} block from the type name plus the
     * flattened attribute map.
     *
     * <p>Returns null — so {@code NON_EMPTY} omits the whole block — when the
     * document has neither, matching document-service's
     * {@code buildDocumentType}.
     */
    private LegacyDocumentResponse.DocumentTypeValue buildDocumentType(LegacyDocumentSummary src) {
        String name = src.getDocumentType();
        Map<String, Object> attributes = attributesToMap(src.getAttributes());

        boolean noName = name == null || name.isBlank();
        boolean noAttrs = attributes.isEmpty();
        if (noName && noAttrs) {
            return null;
        }
        return LegacyDocumentResponse.DocumentTypeValue.builder()
                .documentTypeName(name)
                .attributes(attributes.isEmpty() ? null : attributes)
                .build();
    }

    /**
     * Flattens the index's {@code nested} attribute array into the map shape
     * document-service returns.
     *
     * <pre>
     *   [{"key":"pan_number","value":"ABCDE1234F"}]  ->  {"pan_number":"ABCDE1234F"}
     * </pre>
     *
     * <p><b>Duplicate keys collapse, last one wins.</b> The legacy index CAN hold
     * two array entries with the same key — a multi-valued attribute — and a map
     * cannot represent that. This is not a choice the mapper gets to make well:
     * document-service's own contract types attributes as
     * {@code Map<String, Object>}, so the target shape has no room for a second
     * value either. Collapsing matches what a client parsing
     * {@code DocumentResponse} already expects; the alternative (a list-valued
     * entry for some keys and a scalar for others) would be a shape no existing
     * client can read.
     *
     * <p>{@code value_date} and {@code value_number} are not consulted: they are
     * mapped in the legacy index but never populated by the production pipeline.
     *
     * <p>{@link LinkedHashMap} so attributes keep the order Elasticsearch
     * returned them in, which is the order they were ingested — stable output for
     * the same document rather than hash order that can shift between JVMs.
     */
    private Map<String, Object> attributesToMap(List<LegacyDocumentSummary.Attribute> attributes) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (attributes == null) {
            return out;
        }
        for (LegacyDocumentSummary.Attribute attribute : attributes) {
            if (attribute == null) {
                continue;
            }
            String key = attribute.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            out.put(key, attribute.getValue());
        }
        return out;
    }

    /**
     * {@code ".pdf"} to {@code "pdf"}.
     *
     * <p>Only the leading dot, and only one: an extension is never legitimately
     * {@code "..pdf"}, and stripping greedily would mangle a value that is
     * already wrong rather than passing it through visibly.
     */
    private String stripLeadingDot(String ext) {
        if (ext == null || ext.isBlank()) {
            return null;
        }
        String trimmed = ext.strip();
        return trimmed.startsWith(".") ? trimmed.substring(1) : trimmed;
    }
}
