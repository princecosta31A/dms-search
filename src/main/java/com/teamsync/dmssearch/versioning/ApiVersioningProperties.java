package com.teamsync.dmssearch.versioning;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

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
 *
 * <p><b>Every value has a {@code @DefaultValue}.</b> A record binds by constructor,
 * so without one a missing {@code api.versioning.*} block binds
 * {@code supportedVersions} to {@code null} and {@link #supportedVersionsArray()}
 * throws NPE while the context is still starting — a crashlooping pod whose stack
 * trace names {@code toArray}, not the property nobody set. The defaults match
 * what {@code application.yml} already carries, so this changes nothing in normal
 * operation; it only turns "trimmed ConfigMap" from a startup crash into a
 * service running on the values it would have used anyway.
 */
@ConfigurationProperties(prefix = "api.versioning")
public record ApiVersioningProperties(
        @DefaultValue("1.0") List<String> supportedVersions,
        @DefaultValue("1.0") String defaultVersion,
        @DefaultValue("X-API-Version") String headerName
) {

    public String[] supportedVersionsArray() {
        return supportedVersions.toArray(new String[0]);
    }
}
