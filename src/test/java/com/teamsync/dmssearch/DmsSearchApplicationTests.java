package com.teamsync.dmssearch;

import com.teamsync.dmssearch.config.ElasticsearchProperties;
import com.teamsync.dmssearch.service.SearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real Spring context.
 *
 * <h2>Why this exists</h2>
 * Every other test in this service constructs its collaborators by hand or uses
 * standalone MockMvc. That is deliberate — those tests are fast and precise —
 * but it means NOTHING else here proves the application can actually start.
 * Auto-configuration, property binding, API-version wiring and the two
 * {@link SearchService} beans are all startup-time concerns that a hand-built
 * object graph never exercises.
 *
 * <p>Two upgrades made that gap matter:
 * <ul>
 *   <li>Spring Boot 4.0.6 to 4.0.9</li>
 *   <li>OpenTelemetry pinned forward from the Boot-managed 1.55.0 to 1.67.0, a
 *       twelve-minor-version jump taken for a Trivy finding</li>
 * </ul>
 *
 * <p>A broken auto-configuration from either shows up as a context that will not
 * start — a crashlooping pod — while all 108 unit tests stay green. This test is
 * the one that fails instead.
 *
 * <p>No Elasticsearch is required: the client bean is built from configuration
 * and does not connect until a search runs, and permission-service calls are off
 * by default.
 */
@SpringBootTest
class DmsSearchApplicationTests {

    @Autowired
    private ElasticsearchProperties props;

    @Autowired
    @Qualifier("legacySearchService")
    private SearchService legacySearchService;

    @Autowired
    @Qualifier("optimizedSearchService")
    private SearchService optimizedSearchService;

    @Test
    @DisplayName("the application context starts")
    void contextLoads() {
        // Reaching the assertions at all is the test: @SpringBootTest fails the
        // run if the context cannot be built.
        assertThat(props).isNotNull();
    }

    @Test
    @DisplayName("both version-specific search services are wired and distinct")
    void bothVersionsWired() {
        assertThat(legacySearchService).isNotNull();
        assertThat(optimizedSearchService).isNotNull();

        // Two beans of the same type resolved by qualifier. If this ever collapses
        // to one instance, both API versions would query the same index and one of
        // them would be silently wrong.
        assertThat(legacySearchService)
                .as("v1.0 and v1.1 must be separate instances with separate builders")
                .isNotSameAs(optimizedSearchService);
    }

    @Test
    @DisplayName("both index names are bound from configuration")
    void bothIndexesConfigured() {
        assertThat(props.getIndex())
                .as("v1.1 target")
                .isEqualTo("dms-documents");
        assertThat(props.getLegacyIndex())
                .as("v1.0 target — the index production actually ingests into")
                .isEqualTo("teamsync-dms-documents");
    }
}
