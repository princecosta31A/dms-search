package com.teamsync.dmssearch.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.Node;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.List;

/**
 * Builds the Elasticsearch client.
 *
 * <p>Uses the 8.x {@code RestClientTransport} (Apache HttpClient 4 via
 * {@code elasticsearch-rest-client}). The 9.x client's {@code Rest5ClientTransport}
 * and {@code ElasticsearchTransportConfig} do not exist in 8.x — and 8.x is what
 * the deployed cluster requires. See the version comment in pom.xml.
 */
@Slf4j
@Configuration
public class ElasticsearchConfig {

    /**
     * How much longer the socket may wait than Elasticsearch itself.
     *
     * <p>Elasticsearch must be the one to give up first, so a slow query always
     * ends as flagged partial results rather than sometimes a dead socket.
     */
    private static final int SOCKET_TIMEOUT_FACTOR = 2;

    @Bean(destroyMethod = "close")
    public ElasticsearchTransport elasticsearchTransport(ElasticsearchProperties props) {
        RestClientBuilder builder = RestClient.builder(HttpHost.create(props.getHost()));

        // API key wins over basic auth: it is the credential that survives a
        // trailing newline in a mounted secret, and the one production uses.
        //
        // Credentials are captured here but applied further down, in the SAME
        // httpClientConfigCallback that configures the pool. There is only one such
        // callback per builder — setting it twice replaces rather than combines —
        // so configuring the pool separately would silently drop basic auth and
        // leave the service connecting unauthenticated.
        CredentialsProvider credentials = null;

        if (hasText(props.getApiKey())) {
            builder.setDefaultHeaders(new org.apache.http.Header[]{
                    new BasicHeader("Authorization", "ApiKey " + props.getApiKey().strip())
            });
            log.info("[ES] Using API key authentication — host={}", props.getHost());
        } else if (hasText(props.getUsername()) && hasText(props.getPassword())) {
            credentials = new BasicCredentialsProvider();
            credentials.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(props.getUsername(), props.getPassword()));
            log.info("[ES] Using basic authentication — host={} user={}",
                    props.getHost(), props.getUsername());
        } else {
            // Loud on purpose. Unauthenticated is correct for a local docker
            // cluster with security disabled and wrong everywhere else, and a
            // silent start makes that impossible to notice until data leaks.
            log.warn("[ES] No credentials configured — connecting UNAUTHENTICATED to {}", props.getHost());
        }

        // Without these the client waits indefinitely on an unresponsive cluster,
        // holding a request thread rather than failing the search.
        //
        // The socket timeout is deliberately LONGER than the Elasticsearch query
        // timeout (which SearchQueryBuilder sets to requestTimeoutMs). Set equal,
        // the two race: on a slow query, sometimes the socket dies first and the
        // caller gets 503, sometimes Elasticsearch answers first with partial
        // results and complete=false. Same condition, two different outcomes,
        // decided by whichever clock happens to win.
        //
        // Giving the socket headroom makes Elasticsearch always win, so a slow
        // query has ONE predictable behaviour: partial results, clearly flagged.
        // The socket timeout then only fires for what it is actually for — a
        // connection that has stopped responding altogether.
        int socketTimeoutMs = props.getRequestTimeoutMs() * SOCKET_TIMEOUT_FACTOR;
        builder.setRequestConfigCallback(cfg -> cfg
                .setConnectTimeout(props.getRequestTimeoutMs())
                .setSocketTimeout(socketTimeoutMs)
                // Caps the wait for a free connection from the pool. Defaults to -1
                // — wait forever — and that wait is invisible to every other
                // timeout here, because setSocketTimeout only starts counting once
                // a connection has actually been leased.
                .setConnectionRequestTimeout(props.getConnectionRequestTimeoutMs()));

        // ONE httpClientConfigCallback, doing both jobs. Splitting them across two
        // calls would mean the second silently replaced the first.
        final CredentialsProvider resolvedCredentials = credentials;
        builder.setHttpClientConfigCallback(http -> {
            http.setMaxConnPerRoute(props.getMaxConnPerRoute());
            http.setMaxConnTotal(props.getMaxConnTotal());
            if (resolvedCredentials != null) {
                http.setDefaultCredentialsProvider(resolvedCredentials);
            }
            return http;
        });

        log.info("[ES] Timeouts — connect={}ms esQuery={}ms socket={}ms poolWait={}ms",
                props.getRequestTimeoutMs(), props.getRequestTimeoutMs(),
                socketTimeoutMs, props.getConnectionRequestTimeoutMs());
        log.info("[ES] Connection pool — perRoute={} total={}",
                props.getMaxConnPerRoute(), props.getMaxConnTotal());

        return new RestClientTransport(builder.build(), new JacksonJsonpMapper());
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport,
                                                   ElasticsearchProperties props) {
        log.info("[ES] Client ready — alias={} requestTimeoutMs={} maxResultWindow={}",
                props.getIndex(), props.getRequestTimeoutMs(), props.getMaxResultWindow());
        return new ElasticsearchClient(transport);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
