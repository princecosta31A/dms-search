package com.teamsync.dmssearch.config;

import com.teamsync.dmssearch.versioning.ApiVersioningProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web configuration — API versioning and static OpenAPI hosting.
 *
 * <h2>Versioning</h2>
 * Header-based, using Spring Framework 7's native API versioning, matching
 * document-service. The version travels in {@code X-API-Version}; the path
 * carries no version segment.
 *
 * <p>Header rather than path because a URL is an identifier for a <i>resource</i>,
 * and {@code /api/v1/search/documents} and {@code /api/v2/search/documents} are
 * the same resource represented differently — which is what a header is for. It
 * also means a client moving to 1.1 changes one header rather than every URL it
 * has hardcoded.
 *
 * <p>A default version is configured, so a caller that omits the header gets 1.0
 * rather than a 400. That keeps curl and browser exploration frictionless, and
 * pinning is still available to anyone who wants it.
 *
 * <h2>CORS</h2>
 * Deliberately NOT configured. This service is reachable only through
 * gateway-service, never from a browser, so a CORS policy here would exist only
 * to enable something that should not happen. (The search console proxies
 * server-side for exactly this reason.)
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final ApiVersioningProperties versioning;

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer
                .useRequestHeader(versioning.headerName())
                .addSupportedVersions(versioning.supportedVersionsArray())
                .setDefaultVersion(versioning.defaultVersion());
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serves the hand-curated spec at /openapi/search-api-v1.0.yaml
        registry.addResourceHandler("/openapi/**")
                .addResourceLocations("classpath:/static/openapi/")
                .setCachePeriod(3600);
    }
}
