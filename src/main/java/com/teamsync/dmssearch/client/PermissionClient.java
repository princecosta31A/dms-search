package com.teamsync.dmssearch.client;

import com.teamsync.dmssearch.config.PermissionProperties;
import com.teamsync.dmssearch.dto.client.PermissionResponse;
import com.teamsync.dmssearch.dto.client.ValidateActionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * HTTP client for permission-service.
 *
 * <p>Uses {@link RestClient} rather than OpenFeign: document-service resolves
 * {@code @FeignClient(name = "permission-service")} through Consul, and this
 * service has neither Feign nor Consul on its classpath. Adding both for a
 * single POST would be a lot of machinery for one call, so the URL is
 * configured directly.
 *
 * <p>Timeouts are set explicitly. Without them this call inherits the JDK
 * default of "wait forever", and since it runs in front of every search, one
 * unresponsive permission-service would hang every search thread rather than
 * failing them.
 */
@Slf4j
@Component
public class PermissionClient {

    private final RestClient restClient;
    private final PermissionProperties props;

    public PermissionClient(PermissionProperties props) {
        this.props = props;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()));

        this.restClient = RestClient.builder()
                .baseUrl(props.getUrl())
                .requestFactory(factory)
                .build();

        if (props.isEnabled()) {
            log.info("[Permission] Enabled — url={}{} connectTimeoutMs={} readTimeoutMs={}",
                    props.getUrl(), props.getValidatePath(),
                    props.getConnectTimeoutMs(), props.getReadTimeoutMs());
        }
    }

    /**
     * Calls validate-action.
     *
     * <p>Exceptions are deliberately NOT caught here — a transport failure must
     * reach {@code PermissionValidationService} so it can fail closed. Swallowing
     * it and returning false would be indistinguishable from a genuine denial,
     * and returning true would hand out access whenever the service is down.
     */
    public PermissionResponse<Boolean> validateAction(ValidateActionRequest request) {
        return restClient.post()
                .uri(props.getValidatePath())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<PermissionResponse<Boolean>>() {});
    }
}
