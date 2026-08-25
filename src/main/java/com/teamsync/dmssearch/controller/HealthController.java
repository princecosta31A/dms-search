package com.teamsync.dmssearch.controller;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.teamsync.dmssearch.config.ElasticsearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Liveness/readiness for probes and humans.
 *
 * <p>Deliberately performs a REAL round-trip to Elasticsearch rather than only
 * checking that the client bean exists — a client object stays non-null long
 * after the cluster behind it has gone away, which is exactly the outage a
 * health check is supposed to catch.
 *
 * <p>It also verifies the configured <b>alias</b> resolves. A cluster that is up
 * but has no {@code dms-documents} alias cannot serve a single search, so
 * reporting "healthy" there would be a lie.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final ElasticsearchClient es;
    private final ElasticsearchProperties props;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, String> checks = new LinkedHashMap<>();

        boolean reachable = false;
        try {
            reachable = es.ping().value();
            checks.put("elasticsearch", reachable ? "ok" : "error");
        } catch (Exception e) {
            // Message only, never the stack trace at this level — an unreachable
            // dependency is an expected operational state, not a code fault, and
            // probes hit this endpoint every few seconds.
            checks.put("elasticsearch", "error: " + e.getClass().getSimpleName());
            log.warn("[Health] Elasticsearch unreachable: {}", e.getMessage());
        }

        if (reachable) {
            try {
                boolean aliasExists = es.indices().existsAlias(a -> a.name(props.getIndex())).value();
                checks.put("index_alias", aliasExists ? "ok" : "error: alias '" + props.getIndex() + "' not found");
            } catch (Exception e) {
                checks.put("index_alias", "error: " + e.getClass().getSimpleName());
                log.warn("[Health] Alias check failed for '{}': {}", props.getIndex(), e.getMessage());
            }
        } else {
            checks.put("index_alias", "skipped");
        }

        boolean ok = checks.values().stream().allMatch("ok"::equals);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ok ? "ok" : "degraded");
        body.put("checks", checks);

        return ResponseEntity
                .status(ok ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(body);
    }
}
