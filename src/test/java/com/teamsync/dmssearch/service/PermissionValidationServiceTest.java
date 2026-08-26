package com.teamsync.dmssearch.service;

import com.teamsync.dmssearch.client.PermissionClient;
import com.teamsync.dmssearch.config.PermissionProperties;
import com.teamsync.dmssearch.dto.client.PermissionResponse;
import com.teamsync.dmssearch.dto.client.ValidateActionRequest;
import com.teamsync.dmssearch.dto.error.SearchErrorCode;
import com.teamsync.dmssearch.dto.request.RequestIdentity;
import com.teamsync.dmssearch.exception.SearchException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The distinction these tests exist for: permission-service <i>answering "no"</i>
 * is not the same as permission-service <i>not answering</i>.
 *
 * <p>They were written after a real defect — a {@code 404 "User role not found"}
 * (a denial) was being reported as {@code 503 "Please retry"}, which told clients
 * to keep retrying a decision that would never change.
 */
class PermissionValidationServiceTest {

    private static final RequestIdentity IDENTITY =
            new RequestIdentity("tenant-1", "workspace-1", "user-42");

    private PermissionClient client;
    private PermissionProperties props;
    private PermissionValidationService service;

    @BeforeEach
    void setUp() {
        client = mock(PermissionClient.class);
        props = new PermissionProperties();
        props.setEnabled(true);
        service = new PermissionValidationService(client, props);
    }

    private PermissionResponse<Boolean> answer(Boolean data) {
        PermissionResponse<Boolean> r = new PermissionResponse<>();
        r.setData(data);
        return r;
    }

    // ------------------------------------------------------------------
    // disabled
    // ------------------------------------------------------------------

    @Test
    @DisplayName("disabled -> allows through without calling permission-service")
    void disabledSkipsTheCall() {
        props.setEnabled(false);
        assertThatCode(() -> service.requireFileRead(IDENTITY)).doesNotThrowAnyException();
        verify(client, never()).validateAction(any());
    }

    // ------------------------------------------------------------------
    // answered
    // ------------------------------------------------------------------

    @Test
    @DisplayName("granted -> proceeds")
    void grantedProceeds() {
        when(client.validateAction(any())).thenReturn(answer(true));
        assertThatCode(() -> service.requireFileRead(IDENTITY)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("data:false -> 403, not 503")
    void deniedIsForbidden() {
        when(client.validateAction(any())).thenReturn(answer(false));
        assertThatThrownBy(() -> service.requireFileRead(IDENTITY))
                .isInstanceOf(SearchException.class)
                .satisfies(e -> assertThat(((SearchException) e).getErrorCode())
                        .isEqualTo(SearchErrorCode.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("404 'User role not found' -> 403, NOT 503")
    void notFoundIsDenialNotOutage() {
        // The exact defect this class was written for. permission-service
        // ANSWERED — the user has no role in this workspace. Retrying will never
        // change that, so 503 "please retry" is the wrong thing to tell a client.
        when(client.validateAction(any())).thenThrow(
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                        org.springframework.http.HttpHeaders.EMPTY,
                        "{\"errorCode\":\"20011\",\"errorMessage\":\"User role not found\"}".getBytes(),
                        null));

        assertThatThrownBy(() -> service.requireFileRead(IDENTITY))
                .isInstanceOf(SearchException.class)
                .satisfies(e -> assertThat(((SearchException) e).getErrorCode())
                        .isEqualTo(SearchErrorCode.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("any 4xx is treated as a denial")
    void otherClientErrorsAreDenials() {
        for (HttpStatus status : new HttpStatus[]{
                HttpStatus.FORBIDDEN, HttpStatus.UNAUTHORIZED, HttpStatus.BAD_REQUEST}) {

            // doThrow(), not when(...).thenThrow(): when() CALLS the mock, so on
            // the second iteration the previous stub fires during stubbing and
            // the raw HttpClientErrorException escapes the test.
            doThrow(HttpClientErrorException.create(
                    status, status.getReasonPhrase(),
                    org.springframework.http.HttpHeaders.EMPTY, new byte[0], null))
                    .when(client).validateAction(any());

            assertThatThrownBy(() -> service.requireFileRead(IDENTITY))
                    .as("status %s", status)
                    .satisfies(e -> assertThat(((SearchException) e).getErrorCode())
                            .isEqualTo(SearchErrorCode.PERMISSION_DENIED));
        }
    }

    @Test
    @DisplayName("a null payload is a denial, not an approval")
    void nullPayloadIsDenied() {
        // "No value" must never be read as "yes".
        when(client.validateAction(any())).thenReturn(answer(null));
        assertThatThrownBy(() -> service.requireFileRead(IDENTITY))
                .satisfies(e -> assertThat(((SearchException) e).getErrorCode())
                        .isEqualTo(SearchErrorCode.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("a null response is a denial, not an approval")
    void nullResponseIsDenied() {
        when(client.validateAction(any())).thenReturn(null);
        assertThatThrownBy(() -> service.requireFileRead(IDENTITY))
                .satisfies(e -> assertThat(((SearchException) e).getErrorCode())
                        .isEqualTo(SearchErrorCode.PERMISSION_DENIED));
    }

    // ------------------------------------------------------------------
    // not answered
    // ------------------------------------------------------------------

    @Test
    @DisplayName("connection failure -> 503, not 403")
    void unreachableIsUnavailable() {
        // The caller may well be authorised — we could not find out. Saying
        // "forbidden" would send them debugging permissions that are fine.
        when(client.validateAction(any()))
                .thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> service.requireFileRead(IDENTITY))
                .satisfies(e -> assertThat(((SearchException) e).getErrorCode())
                        .isEqualTo(SearchErrorCode.PERMISSION_BACKEND_UNAVAILABLE));
    }

    @Test
    @DisplayName("timeout -> 503")
    void timeoutIsUnavailable() {
        when(client.validateAction(any()))
                .thenThrow(new ResourceAccessException("timeout", new SocketTimeoutException()));

        assertThatThrownBy(() -> service.requireFileRead(IDENTITY))
                .satisfies(e -> assertThat(((SearchException) e).getErrorCode())
                        .isEqualTo(SearchErrorCode.PERMISSION_BACKEND_UNAVAILABLE));
    }

    // ------------------------------------------------------------------
    // payload
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sends FILE_READ with the caller's identity")
    void sendsFileReadWithIdentity() {
        when(client.validateAction(any())).thenReturn(answer(true));
        service.requireFileRead(IDENTITY);

        ArgumentCaptor<ValidateActionRequest> captor =
                ArgumentCaptor.forClass(ValidateActionRequest.class);
        verify(client).validateAction(captor.capture());

        ValidateActionRequest sent = captor.getValue();
        assertThat(sent.getUserId()).isEqualTo("user-42");
        assertThat(sent.getTenantId()).isEqualTo("tenant-1");
        assertThat(sent.getWorkspaceId()).isEqualTo("workspace-1");
        // "file.read", NOT "FILE_READ". permission-service matches the dotted
        // form; sending the enum name produced 404 "User role not found", which
        // reads like a missing role rather than an unrecognised action. Same
        // wire value document-service sends.
        assertThat(sent.getAction()).isEqualTo("file.read");
    }

    // ------------------------------------------------------------------
    // envelope shape
    // ------------------------------------------------------------------

    @Test
    @DisplayName("parses permission-service's real envelope — which has no 'success' field")
    void parsesRealEnvelope() {
        // Verbatim output of permission-service's ApiResponseBuilder. This DTO once
        // declared `boolean success` and `String code` — neither field exists. Under
        // Jackson 3 that was fatal, not merely unused: the all-args constructor is
        // auto-detected as a creator, and a primitive argument with nothing to bind
        // to aborts the parse, so every ALLOWED search came back 503.
        String body = """
                {"httpStatus":200,"message":"Access Allowed","data":true,\
                "path":"/validate-action","timestamp":"2026-08-26T16:30:00+05:30"}""";

        PermissionResponse<Boolean> parsed = JsonMapper.builder().build()
                .readValue(body, new TypeReference<PermissionResponse<Boolean>>() {});

        assertThat(parsed.getData()).isTrue();
        assertThat(parsed.getHttpStatus()).isEqualTo(200);
        assertThat(parsed.getMessage()).isEqualTo("Access Allowed");
    }
}
