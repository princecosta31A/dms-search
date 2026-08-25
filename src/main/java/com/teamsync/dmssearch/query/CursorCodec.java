package com.teamsync.dmssearch.query;

import co.elastic.clients.elasticsearch._types.FieldValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.teamsync.dmssearch.dto.error.SearchErrorCode;
import com.teamsync.dmssearch.exception.SearchException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Encodes and decodes the opaque pagination cursor.
 *
 * <p>A cursor is just Elasticsearch's {@code sort} values for the last hit on a
 * page, handed back so the next request can resume with {@code search_after}.
 * Unlike {@code from}/{@code size}, that costs the same at result 20 or result
 * 2,000,000 — no shard has to produce and discard everything before the page.
 *
 * <h2>Why it is opaque</h2>
 * Base64 signals "do not read or construct this". The encoding is an
 * implementation detail: it changes whenever the sort chain changes, and a
 * client that parsed it would break silently.
 *
 * <h2>Why it is not signed</h2>
 * A forged cursor cannot widen access. The tenant/workspace filter is rebuilt
 * server-side on every request from gateway headers, so the worst a tampered
 * cursor achieves is an odd position inside the caller's own permitted results.
 * Signing would add key management for no security gain.
 *
 * <p>Values are stored with an explicit type tag. Sort values are heterogeneous
 * — {@code _score} is a double, {@code createdAt} a long (epoch millis),
 * {@code documentId} a string — and round-tripping a long as a double would
 * corrupt the resume position.
 */
@Slf4j
@Component
public class CursorCodec {

    /** Bumped if the payload shape changes, so old cursors fail loudly. */
    private static final String VERSION = "1";

    private static final String FIELD_VERSION = "v";
    private static final String FIELD_VALUES = "s";

    private static final String TYPE_STRING = "s";
    private static final String TYPE_LONG = "l";
    private static final String TYPE_DOUBLE = "d";
    private static final String TYPE_BOOLEAN = "b";
    private static final String TYPE_NULL = "n";

    // Jackson 2, matching the Elasticsearch client. (Spring Boot 4 serialises
    // HTTP with Jackson 3; both are on the classpath under different packages.)
    private final ObjectMapper mapper = new ObjectMapper();

    public String encode(List<FieldValue> sortValues) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put(FIELD_VERSION, VERSION);
            ArrayNode values = root.putArray(FIELD_VALUES);

            for (FieldValue value : sortValues) {
                ObjectNode node = values.addObject();
                switch (value._kind()) {
                    case String -> node.put(TYPE_STRING, value.stringValue());
                    case Long -> node.put(TYPE_LONG, value.longValue());
                    case Double -> node.put(TYPE_DOUBLE, value.doubleValue());
                    case Boolean -> node.put(TYPE_BOOLEAN, value.booleanValue());
                    // A null sort value is legitimate (a document missing the
                    // sort field) and must round-trip, or paging skips it.
                    case Null -> node.put(TYPE_NULL, true);
                    default -> throw new IllegalStateException("Unsupported sort value kind: " + value._kind());
                }
            }

            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mapper.writeValueAsBytes(root));

        } catch (Exception e) {
            // Failing to build a cursor must not fail the search the caller
            // already paid for — they simply get a page with no "next".
            log.warn("[Cursor] Encoding failed, page returned without a cursor: {}", e.toString());
            return null;
        }
    }

    public List<FieldValue> decode(String cursor) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(cursor);
            JsonNode root = mapper.readTree(new String(raw, StandardCharsets.UTF_8));

            if (!VERSION.equals(root.path(FIELD_VERSION).asText())) {
                throw new IllegalArgumentException("unsupported cursor version");
            }

            JsonNode values = root.path(FIELD_VALUES);
            if (!values.isArray() || values.isEmpty()) {
                throw new IllegalArgumentException("cursor carries no sort values");
            }

            List<FieldValue> decoded = new ArrayList<>(values.size());
            for (JsonNode node : values) {
                if (node.has(TYPE_STRING)) {
                    decoded.add(FieldValue.of(node.get(TYPE_STRING).asText()));
                } else if (node.has(TYPE_LONG)) {
                    decoded.add(FieldValue.of(node.get(TYPE_LONG).asLong()));
                } else if (node.has(TYPE_DOUBLE)) {
                    decoded.add(FieldValue.of(node.get(TYPE_DOUBLE).asDouble()));
                } else if (node.has(TYPE_BOOLEAN)) {
                    decoded.add(FieldValue.of(node.get(TYPE_BOOLEAN).asBoolean()));
                } else if (node.has(TYPE_NULL)) {
                    decoded.add(FieldValue.NULL);
                } else {
                    throw new IllegalArgumentException("unrecognised sort value entry");
                }
            }
            return decoded;

        } catch (SearchException e) {
            throw e;
        } catch (Exception e) {
            // Never echo the cursor back in the message — it is caller-supplied
            // and would land verbatim in logs and browser consoles.
            throw new SearchException(
                    SearchErrorCode.INVALID_CURSOR,
                    SearchErrorCode.INVALID_CURSOR.getDefaultMessage(),
                    List.of("cursor"));
        }
    }
}
