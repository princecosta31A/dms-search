package com.teamsync.dmssearch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * permission-service client configuration, bound from {@code permission.service.*}.
 */
@Data
@ConfigurationProperties(prefix = "permission.service")
public class PermissionProperties {

    /**
     * When false, every check returns true and no call is made.
     *
     * <p>Defaults to <b>false</b> — this is new, and turning it on before
     * permission-service is confirmed reachable would take search down entirely
     * (the check fails closed). Flip to true once verified.
     *
     * <p>While false, a user can read any workspace inside their own tenant by
     * changing the {@code X-Workspace-Id} header, because nothing else verifies
     * workspace membership. That is the whole reason this exists.
     */
    private boolean enabled = false;

    /**
     * Base URL, e.g. {@code http://permission-service.teamsync2.svc.cluster.local:8080}.
     *
     * <p>A plain URL rather than a Feign/Consul service name: this service has
     * neither on its classpath, and one HTTP call does not justify adding them.
     */
    private String url = "http://localhost:8080";

    /** Path appended to {@link #url}. */
    private String validatePath = "/api/permission/validate-action";

    /**
     * Connect timeout. Short on purpose — an unreachable permission-service
     * should fail fast, not hold a search thread through a TCP timeout.
     */
    private int connectTimeoutMs = 2_000;

    /**
     * Read timeout. This sits in front of EVERY search, so its worst case is
     * added to every request's latency. Kept tight for that reason.
     */
    private int readTimeoutMs = 3_000;
}
