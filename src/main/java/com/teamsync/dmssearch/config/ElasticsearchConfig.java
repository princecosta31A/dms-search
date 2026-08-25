package com.teamsync.dmssearch.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.ElasticsearchTransportConfig;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the Elasticsearch client.
 *
 * <p>Uses the Rest5 transport (Apache HttpClient 5), which ships inside
 * elasticsearch-java 9.x. The older {@code rest_client} transport would need the
 * separate {@code elasticsearch-rest-client} artifact, which is deliberately not
 * on the classpath.
 */
@Slf4j
@Configuration
public class ElasticsearchConfig {

    @Bean(destroyMethod = "close")
    public ElasticsearchTransport elasticsearchTransport(ElasticsearchProperties props) {
        ElasticsearchTransportConfig.Builder builder =
                new ElasticsearchTransportConfig.Builder().host(props.getHost());

        // API key wins over basic auth: it is the credential that survives a
        // trailing newline in a mounted secret, and the one production uses.
        if (hasText(props.getApiKey())) {
            builder.apiKey(props.getApiKey().strip());
            log.info("[ES] Using API key authentication — host={}", props.getHost());
        } else if (hasText(props.getUsername()) && hasText(props.getPassword())) {
            builder.usernameAndPassword(props.getUsername(), props.getPassword());
            log.info("[ES] Using basic authentication — host={} user={}",
                    props.getHost(), props.getUsername());
        } else {
            // Loud on purpose. Unauthenticated is correct for a local docker
            // cluster with security disabled and wrong everywhere else, and a
            // silent start makes that impossible to notice until data leaks.
            log.warn("[ES] No credentials configured — connecting UNAUTHENTICATED to {}", props.getHost());
        }

        return new Rest5ClientTransport(builder.build());
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
