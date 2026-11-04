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
 * api.versioning.supported-versions=1.0,1.1
 * api.versioning.default-version=1.0
 * api.versioning.header-name=X-API-Version
 * </pre>
 *
 * <p>Two versions exist: <b>1.0</b> queries the legacy mapping that production
 * runs today, <b>1.1</b> the optimized mapping. 1.0 is the default because the
 * pipeline populating the optimized index is not yet deployed there, so a caller
 * that omits the header must reach the index that actually holds documents.
 *
 * <p>Still deliberately smaller than document-service's equivalent: no
 * deprecation or sunset policy. v1.0 is not a deprecated version to be phased
 * out over releases — it is deleted outright, along with the whole {@code legacy}
 * package, on the day production migrates. Sunset machinery would be dead
 * configuration that still has to be understood.
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
        @DefaultValue({"1.0", "1.1"}) List<String> supportedVersions,
        @DefaultValue("1.0") String defaultVersion,
        @DefaultValue("X-API-Version") String headerName
) {

    public String[] supportedVersionsArray() {
        return supportedVersions.toArray(new String[0]);
    }
}
