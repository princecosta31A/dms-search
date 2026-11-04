package com.teamsync.dmssearch.config;

import com.teamsync.dmssearch.dto.request.RequestIdentity;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Single owner of the request-scoped caller identity — a port of document-service's filter of the
 * same name, so a search request logs and traces under exactly the same keys as an upload does.
 * Resolves it once per request and publishes it to the three places that each serve a different
 * consumer:
 *
 * <ul>
 *   <li><b>MDC</b> — so every log line on this thread carries it. Log-local; never leaves the JVM.</li>
 *   <li><b>Span attributes</b> — so APM can filter/group transactions by tenant, workspace or user.
 *       Also local: an attribute belongs to one span and is not sent to the next service.</li>
 *   <li><b>Baggage</b> — the only one of the three that actually travels. The agent injects it as
 *       the {@code baggage} header on every outgoing HTTP call, so permission-service receives the
 *       same values this service saw.</li>
 * </ul>
 *
 * <p>Why this has to exist: the OTel (EDOT) agent's {@code logback-mdc} instrumentation publishes
 * {@code trace_id} / {@code span_id} on its own, but never baggage. Without this filter the
 * {@code correlationId} / {@code tenantId} / {@code workspaceId} columns of the log pattern rendered
 * {@code none} on every line, even for requests the gateway had fully populated.
 *
 * <p>The gateway is the source of truth. Each field is read from baggage first:
 * <ul>
 *   <li>{@code correlation.id} / {@code session.id} — baggage only, and never re-published. Nothing
 *       is substituted when the gateway propagated none: the key stays unset and the log pattern
 *       renders {@code none}. An id invented here would differ per service, which is worse than an
 *       honest gap.</li>
 *   <li>{@code tenant.id} / {@code workspace.id} / {@code user.id} — baggage first, falling back to
 *       the {@code X-Tenant-Id} / {@code X-Workspace-Id} / {@code X-User-Id} headers. The fallback is
 *       safe because these are caller-supplied facts, not generated ids. When a value arrives only
 *       as a header, it is added to the outgoing baggage so the rest of the call graph gets it
 *       without every client having to spell the header out itself.</li>
 * </ul>
 *
 * <p>Nothing here generates a value, and nothing here validates one — a missing identity header is
 * still rejected by {@code SearchController} with {@code SRCH-401-001}. A field that arrives nowhere
 * is simply not published anywhere.
 *
 * <p>Registered at {@link Ordered#HIGHEST_PRECEDENCE} so it wraps every other filter — the MDC is
 * populated for their log lines too, the baggage scope covers every downstream call made during the
 * request, and both are torn down only after the chain returns. MDC keys are removed in
 * {@code finally} because Tomcat threads are pooled: a leftover value would be stamped onto an
 * unrelated later request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextMdcFilter extends OncePerRequestFilter {

    static final String CORRELATION_KEY = "correlation.id";
    static final String SESSION_KEY = "session.id";
    static final String TENANT_KEY = "tenant.id";
    static final String WORKSPACE_KEY = "workspace.id";
    static final String USER_KEY = "user.id";

    /**
     * Gateway-issued ids that exist in baggage or nowhere: no {@code X-} header fallback and never
     * re-published, because whatever the gateway sent is already travelling on its own.
     */
    private static final List<String> BAGGAGE_ONLY_KEYS = List.of(CORRELATION_KEY, SESSION_KEY);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        List<String> ownedKeys = new ArrayList<>(5);
        try {
            Baggage inbound = Baggage.current();
            Span span = Span.current();

            for (String key : BAGGAGE_ONLY_KEYS) {
                String value = inbound.getEntryValue(key);
                if (put(ownedKeys, key, value) != null) {
                    span.setAttribute(key, value);
                }
            }

            BaggageBuilder outbound = inbound.toBuilder();
            boolean augmented = resolve(ownedKeys, span, outbound, inbound,
                    TENANT_KEY, request.getHeader(RequestIdentity.HEADER_TENANT));
            augmented |= resolve(ownedKeys, span, outbound, inbound,
                    WORKSPACE_KEY, request.getHeader(RequestIdentity.HEADER_WORKSPACE));
            augmented |= resolve(ownedKeys, span, outbound, inbound,
                    USER_KEY, request.getHeader(RequestIdentity.HEADER_USER));

            if (!augmented) {
                // Everything already came from baggage — it propagates as-is, no new scope needed.
                filterChain.doFilter(request, response);
                return;
            }
            try (Scope ignored = outbound.build().makeCurrent()) {
                filterChain.doFilter(request, response);
            }
        } finally {
            ownedKeys.forEach(MDC::remove);   // mandatory: pooled thread must not inherit this request's values
        }
    }

    /**
     * Resolves one identity field — baggage first, request header as fallback — and publishes it to
     * the MDC and the current span. Returns {@code true} when the value came from the header and so
     * had to be added to the outgoing baggage; {@code false} when it was already in baggage (it is
     * propagating already) or absent entirely.
     */
    private boolean resolve(List<String> ownedKeys, Span span, BaggageBuilder outbound,
                            Baggage inbound, String key, String headerValue) {
        String fromBaggage = inbound.getEntryValue(key);
        String value = isPresent(fromBaggage) ? fromBaggage : strip(headerValue);
        if (put(ownedKeys, key, value) == null) {
            return false;
        }
        span.setAttribute(key, value);
        if (isPresent(fromBaggage)) {
            return false;
        }
        outbound.put(key, value);
        return true;
    }

    /**
     * Stores {@code value} under {@code key} when it is present, recording the key so it can be
     * removed again. Returns the stored value, or {@code null} when nothing was stored.
     */
    private String put(List<String> ownedKeys, String key, String value) {
        if (!isPresent(value)) {
            return null;
        }
        MDC.put(key, value);
        ownedKeys.add(key);
        return value;
    }

    /** Matches {@code SearchController#resolveIdentity}, which strips the same headers. */
    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
