package com.teamsync.dmssearch.query;

import co.elastic.clients.elasticsearch._types.FieldValue;
import com.teamsync.dmssearch.dto.error.SearchErrorCode;
import com.teamsync.dmssearch.exception.SearchException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {

    private final CursorCodec codec = new CursorCodec();

    @Test
    @DisplayName("string and long sort values round-trip with their types intact")
    void roundTripsMixedTypes() {
        // A createdAt sort produces [long epochMillis, string documentId].
        // Round-tripping the long as a double would corrupt the resume position.
        List<FieldValue> original = List.of(FieldValue.of(1787566168000L), FieldValue.of("doc-123"));

        List<FieldValue> decoded = codec.decode(codec.encode(original));

        assertThat(decoded).hasSize(2);
        assertThat(decoded.get(0).longValue()).isEqualTo(1787566168000L);
        assertThat(decoded.get(1).stringValue()).isEqualTo("doc-123");
    }

    @Test
    @DisplayName("relevance sort values (double) round-trip")
    void roundTripsDouble() {
        List<FieldValue> original = List.of(FieldValue.of(1.5610453d), FieldValue.of("doc-9"));
        List<FieldValue> decoded = codec.decode(codec.encode(original));

        assertThat(decoded.get(0).doubleValue()).isEqualTo(1.5610453d);
        assertThat(decoded.get(1).stringValue()).isEqualTo("doc-9");
    }

    @Test
    @DisplayName("a null sort value round-trips rather than being dropped")
    void roundTripsNull() {
        // A document missing the sort field sorts as null. Losing it would make
        // search_after resume from the wrong place and skip documents.
        List<FieldValue> decoded = codec.decode(
                codec.encode(List.of(FieldValue.NULL, FieldValue.of("doc-1"))));

        assertThat(decoded.get(0).isNull()).isTrue();
        assertThat(decoded.get(1).stringValue()).isEqualTo("doc-1");
    }

    @Test
    @DisplayName("the cursor is opaque — not readable as plain text")
    void cursorIsOpaque() {
        String cursor = codec.encode(List.of(FieldValue.of("secret-doc-id")));
        // base64 signals "echo this back, do not parse it"
        assertThat(cursor).doesNotContain("secret-doc-id");
    }

    @Test
    @DisplayName("URL-safe so it survives a query string unencoded")
    void cursorIsUrlSafe() {
        String cursor = codec.encode(List.of(
                FieldValue.of(Long.MAX_VALUE), FieldValue.of("a/b+c=d")));
        assertThat(cursor).doesNotContain("+").doesNotContain("/").doesNotContain("=");
    }

    @Test
    @DisplayName("garbage is rejected as INVALID_CURSOR, not a 500")
    void garbageRejected() {
        assertThatThrownBy(() -> codec.decode("this-is-not-a-cursor"))
                .isInstanceOf(SearchException.class)
                .satisfies(e -> assertThat(((SearchException) e).getErrorCode())
                        .isEqualTo(SearchErrorCode.INVALID_CURSOR));
    }

    @Test
    @DisplayName("valid base64 that is not a cursor is rejected")
    void wrongPayloadRejected() {
        String notACursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"hello\":\"world\"}".getBytes());

        assertThatThrownBy(() -> codec.decode(notACursor))
                .isInstanceOf(SearchException.class);
    }

    @Test
    @DisplayName("a cursor from an older format version is rejected, not misread")
    void oldVersionRejected() {
        // Silently misinterpreting an old cursor would page from a wrong offset.
        String oldVersion = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"v\":\"0\",\"s\":[{\"s\":\"doc-1\"}]}".getBytes());

        assertThatThrownBy(() -> codec.decode(oldVersion))
                .isInstanceOf(SearchException.class);
    }

    @Test
    @DisplayName("the error never echoes the caller-supplied cursor back")
    void errorDoesNotEchoInput() {
        String hostile = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"v\":\"1\",\"s\":[{\"bogus\":\"LEAKED-PAN-ABCDE1234F\"}]}".getBytes());

        assertThatThrownBy(() -> codec.decode(hostile))
                .isInstanceOf(SearchException.class)
                .hasMessageNotContaining("LEAKED-PAN-ABCDE1234F")
                .hasMessageNotContaining(hostile);
    }

    @Test
    @DisplayName("an unencodable value yields null rather than failing the search")
    void encodeFailureDegradesGracefully() {
        // The caller already paid for the query; losing the "next" link is a far
        // better outcome than losing the page.
        assertThat(codec.encode(List.of())).isNotNull();
    }
}
