package com.teamsync.dmssearch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Elasticsearch connection + query tuning, bound from {@code elasticsearch.*}.
 */
@Data
@ConfigurationProperties(prefix = "elasticsearch")
public class ElasticsearchProperties {

    /** Full URL, e.g. {@code http://localhost:9200}. */
    private String host = "http://localhost:9200";

    /**
     * The ALIAS to query — never a concrete index name. The physical index behind
     * it is {@code <alias>-000001}; keeping every query on the alias is what lets
     * rollover swap the generation underneath without redeploying this service.
     */
    private String index = "dms-documents";

    /** Optional basic auth. Ignored when {@link #apiKey} is set. */
    private String username;
    private String password;

    /** Optional API key (base64 "id:key"). Takes precedence over basic auth. */
    private String apiKey;

    /**
     * Per-request timeout. A search that has not returned by now is worse than
     * useless — the caller has given up, and holding the connection open only
     * feeds a queue that makes the outage worse.
     */
    private int requestTimeoutMs = 5_000;

    /**
     * Hard ceiling on offset pagination, mirroring the index's
     * {@code max_result_window}. Requests beyond it are rejected with a clear 400
     * rather than being passed through to surface a raw Elasticsearch
     * {@code search_phase_execution_exception}.
     *
     * <p>The cost is real: with 4 shards, {@code from=9980} makes EVERY shard
     * return 10,000 hits to the coordinating node so it can sort them and discard
     * 39,980. Deep offset paging is why the cursor exists.
     */
    private int maxResultWindow = 10_000;

    /** Default page size when the caller does not specify one. */
    private int defaultPageSize = 20;

    /** Largest page a caller may request. */
    private int maxPageSize = 100;

    // ---- connection pool -------------------------------------------------
    // The REST client's own defaults are 10 per route and 30 total. Left alone,
    // those are the real concurrency ceiling of this service, whatever the web
    // server is willing to accept.

    /**
     * Simultaneous connections to a single Elasticsearch node.
     *
     * <p>This, not the servlet thread pool, decides how many searches can be in
     * flight at once. Raising it past what the cluster can serve does not help —
     * a 2-vCPU node runs roughly 4 concurrent searches and queues the rest — but
     * the client default of 10 is low enough to become the bottleneck first on
     * anything larger.
     */
    private int maxConnPerRoute = 50;

    /** Simultaneous connections across all nodes. */
    private int maxConnTotal = 100;

    /**
     * How long a request may wait for a free connection from the pool.
     *
     * <p><b>The default is -1: wait forever.</b> That is the bug this setting
     * closes. {@link #requestTimeoutMs} does not cover this wait — its clock only
     * starts once a connection has been leased — so without a limit here a burst
     * queues indefinitely, invisible to every other timeout configured.
     *
     * <p>Deliberately short. If no connection is free within two seconds the
     * cluster is already saturated, and failing fast is kinder than adding to a
     * queue nobody is draining.
     */
    private int connectionRequestTimeoutMs = 2_000;
}
