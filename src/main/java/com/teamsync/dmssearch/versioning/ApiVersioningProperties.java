package com.teamsync.dmssearch.versioning;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * API versioning configuration, bound from {@code api.versioning.*}.
 *
 * <p>Mirrors document-service's approach so clients speak one dialect across the
 * platform: the version travels in the {@code X-API-Version} header, not in the
 * URL path.
 *
 * <pre>
 * api.versioning.supported-versions=1.0
 * api.versioning.default-version=1.0
 * api.versioning.header-name=X-API-Version
 * </pre>
 *
 * <p>Deliberately smaller than document-service's equivalent: this service has
 * exactly one version, so there is nothing to deprecate and no sunset policy to
 * express. Add that machinery when a second version actually exists — carrying
 * it now would be dead configuration that still has to be understood.
 */
@ConfigurationProperties(prefix = "api.versioning")
public record ApiVersioningProperties(
        List<String> supportedVersions,
        String defaultVersion,
        String headerName
) {

    public String[] supportedVersionsArray() {
        return supportedVersions.toArray(new String[0]);
    }
}
