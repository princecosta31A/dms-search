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

    @Bean(destroyMethod = "close")
    public ElasticsearchTransport elasticsearchTransport(ElasticsearchProperties props) {
        RestClientBuilder builder = RestClient.builder(HttpHost.create(props.getHost()));

        // API key wins over basic auth: it is the credential that survives a
        // trailing newline in a mounted secret, and the one production uses.
        if (hasText(props.getApiKey())) {
            builder.setDefaultHeaders(new org.apache.http.Header[]{
                    new BasicHeader("Authorization", "ApiKey " + props.getApiKey().strip())
            });
            log.info("[ES] Using API key authentication — host={}", props.getHost());
        } else if (hasText(props.getUsername()) && hasText(props.getPassword())) {
            CredentialsProvider credentials = new BasicCredentialsProvider();
            credentials.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(props.getUsername(), props.getPassword()));
            builder.setHttpClientConfigCallback(http -> http.setDefaultCredentialsProvider(credentials));
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
        builder.setRequestConfigCallback(cfg -> cfg
                .setConnectTimeout(props.getRequestTimeoutMs())
                .setSocketTimeout(props.getRequestTimeoutMs()));

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
