package com.teamsync.dmssearch.versioning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Binds the record the way Spring does at startup, with the properties MISSING.
 *
 * <p>Without {@code @DefaultValue} this record binds {@code supportedVersions} to
 * {@code null}, and {@code supportedVersionsArray()} then throws NPE while the
 * application context is still being built — a crashlooping pod whose stack trace
 * names {@code toArray}, not the property nobody set.
 *
 * <p>It never happened because {@code application.yml} carries all three values.
 * It would happen the first time a deployment used a trimmed ConfigMap, or a new
 * environment copied only the {@code elasticsearch:} block.
 */
class ApiVersioningPropertiesTest {

    private ApiVersioningProperties bind(Map<String, Object> properties) {
        ConfigurationPropertySource source = new MapConfigurationPropertySource(properties);
        return new Binder(source)
                .bind("api.versioning", Bindable.of(ApiVersioningProperties.class))
                .orElseGet(() -> new Binder(new MapConfigurationPropertySource(Map.of()))
                        .bindOrCreate("api.versioning", Bindable.of(ApiVersioningProperties.class)));
    }

    @Test
    @DisplayName("no api.versioning config at all -> usable defaults, not a startup NPE")
    void missingConfigDoesNotBreakStartup() {
        ApiVersioningProperties props = bind(Map.of());

        assertThatCode(props::supportedVersionsArray)
                .as("this threw NullPointerException before @DefaultValue was added")
                .doesNotThrowAnyException();

        assertThat(props.supportedVersionsArray()).containsExactly("1.0");
        assertThat(props.defaultVersion()).isEqualTo("1.0");
        assertThat(props.headerName()).isEqualTo("X-API-Version");
    }

    @Test
    @DisplayName("a partial config still fills the gaps")
    void partialConfigIsCompleted() {
        // The realistic failure: someone sets one value and assumes the rest carry
        // over. Before the defaults, this bound the other two to null.
        ApiVersioningProperties props = bind(Map.of("api.versioning.header-name", "X-Version"));

        assertThat(props.headerName()).isEqualTo("X-Version");
        assertThat(props.supportedVersionsArray()).containsExactly("1.0");
        assertThat(props.defaultVersion()).isEqualTo("1.0");
    }

    @Test
    @DisplayName("explicit config still wins over the defaults")
    void explicitConfigOverridesDefaults() {
        ApiVersioningProperties props = bind(Map.of(
                "api.versioning.supported-versions", "1.0,2.0",
                "api.versioning.default-version", "2.0",
                "api.versioning.header-name", "X-Api-Ver"));

        assertThat(props.supportedVersionsArray()).containsExactly("1.0", "2.0");
        assertThat(props.defaultVersion()).isEqualTo("2.0");
        assertThat(props.headerName()).isEqualTo("X-Api-Ver");
    }
}
