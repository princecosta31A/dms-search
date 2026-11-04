package com.teamsync.dmssearch.config;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestContextMdcFilterTest {

    private final RequestContextMdcFilter filter = new RequestContextMdcFilter();

    /** MDC and outgoing baggage as seen from inside the chain, i.e. by the controller. */
    private final Map<String, String> mdcInChain = new HashMap<>();
    private Baggage baggageInChain;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("headers only: published to MDC and added to outgoing baggage")
    void headersOnly() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/search/dms/documents");
        request.addHeader("X-Tenant-Id", "t1");
        request.addHeader("X-Workspace-Id", " w1 ");
        request.addHeader("X-User-Id", "u1");

        run(request);

        assertThat(mdcInChain)
                .containsEntry("tenant.id", "t1")
                .containsEntry("workspace.id", "w1")
                .containsEntry("user.id", "u1")
                .doesNotContainKey("correlation.id");
        assertThat(baggageInChain.getEntryValue("tenant.id")).isEqualTo("t1");
        assertThat(baggageInChain.getEntryValue("workspace.id")).isEqualTo("w1");
        assertThat(baggageInChain.getEntryValue("user.id")).isEqualTo("u1");
    }

    @Test
    @DisplayName("baggage wins over headers, and correlation.id is taken from baggage only")
    void baggageWins() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/search/dms/documents");
        request.addHeader("X-Tenant-Id", "from-header");

        Baggage inbound = Baggage.builder()
                .put("correlation.id", "corr-1")
                .put("session.id", "sess-1")
                .put("tenant.id", "from-gateway")
                .build();
        try (Scope ignored = inbound.makeCurrent()) {
            run(request);
        }

        assertThat(mdcInChain)
                .containsEntry("correlation.id", "corr-1")
                .containsEntry("session.id", "sess-1")
                .containsEntry("tenant.id", "from-gateway");
    }

    @Test
    @DisplayName("nothing arrives: nothing is published, nothing is invented")
    void nothingArrives() throws Exception {
        run(new MockHttpServletRequest("GET", "/api/search/dms/documents"));

        assertThat(mdcInChain).isEmpty();
        assertThat(baggageInChain.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("MDC is cleared after the request so a pooled thread does not inherit it")
    void mdcClearedAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/search/dms/documents");
        request.addHeader("X-Tenant-Id", "t1");
        request.addHeader("X-Workspace-Id", "w1");

        run(request);

        assertThat(MDC.get("tenant.id")).isNull();
        assertThat(MDC.get("workspace.id")).isNull();
    }

    private void run(MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            Map<String, String> copy = MDC.getCopyOfContextMap();
            if (copy != null) {
                mdcInChain.putAll(copy);
            }
            baggageInChain = Baggage.current();
        });
    }
}
