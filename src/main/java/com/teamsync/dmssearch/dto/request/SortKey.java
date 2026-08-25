package com.teamsync.dmssearch.dto.request;

import co.elastic.clients.elasticsearch._types.SortOrder;

import java.util.Arrays;
import java.util.Optional;

/**
 * The sort orders a caller may request.
 *
 * <p>A closed enum rather than a free-text field name: accepting an arbitrary
 * field would let a caller sort by something unmapped (an error), by an
 * analysed text field (nonsense ordering), or probe which fields exist.
 */
public enum SortKey {

    /** Best-match first. Meaningless without a {@code q}, so the service falls
     *  back to {@link #CREATED_AT} when no free-text term was supplied. */
    RELEVANCE("relevance", null, SortOrder.Desc),

    /** Newest first. */
    CREATED_AT("createdAt", "createdAt", SortOrder.Desc),

    /** Most recently touched first. */
    UPDATED_AT("updatedAt", "updatedAt", SortOrder.Desc),

    /** A→Z. Sorts on the keyword field, which carries the lowercase normalizer,
     *  so ordering is case-insensitive. */
    FILE_NAME("fileName", "fileName", SortOrder.Asc);

    private final String apiName;
    private final String field;
    private final SortOrder order;

    SortKey(String apiName, String field, SortOrder order) {
        this.apiName = apiName;
        this.field = field;
        this.order = order;
    }

    public String apiName() {
        return apiName;
    }

    /** ES field to sort on; {@code null} for relevance (uses {@code _score}). */
    public String field() {
        return field;
    }

    public SortOrder order() {
        return order;
    }

    public static Optional<SortKey> fromApiName(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(k -> k.apiName.equalsIgnoreCase(value.strip()))
                .findFirst();
    }

    public static String supported() {
        return String.join(", ", Arrays.stream(values()).map(SortKey::apiName).toList());
    }
}
