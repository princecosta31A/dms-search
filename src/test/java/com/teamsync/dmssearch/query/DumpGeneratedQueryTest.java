package com.teamsync.dmssearch.query;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.teamsync.dmssearch.config.ElasticsearchProperties;
import com.teamsync.dmssearch.dto.request.RequestIdentity;
import com.teamsync.dmssearch.dto.request.SearchCriteria;
import jakarta.json.stream.JsonGenerator;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.Map;

/**
 * Prints the exact Elasticsearch request dms-search generates, so it can be
 * pasted into Kibana Dev Tools and timed directly.
 *
 * <p>Not an assertion — a diagnostic. When a search is slow this separates
 * "Elasticsearch is slow" from "dms-search is slow getting to Elasticsearch":
 * run the printed query in Kibana and compare its `took` against the service's
 * `esTookMs`.
 */
class DumpGeneratedQueryTest {

    private static final RequestIdentity IDENTITY = new RequestIdentity(
            "6a79a78de32f87eb8577a5bb", "6a79b6c123a50e03f3ae3a64", "prince");

    private SearchQueryBuilder builder() {
        ElasticsearchProperties props = new ElasticsearchProperties();
        props.setIndex("dms-documents");
        props.setRequestTimeoutMs(5000);
        return new SearchQueryBuilder(props);
    }

    private void dump(String label, SearchCriteria criteria) {
        SearchRequest request = builder().build(criteria, IDENTITY, 0, null);
        JsonpMapper mapper = new JacksonJsonpMapper();
        StringWriter w = new StringWriter();
        try (JsonGenerator g = mapper.jsonProvider().createGenerator(w)) {
            request.serialize(g, mapper);
        }
        System.out.println("\n===== " + label + " =====");
        System.out.println("GET dms-documents/_search");
        System.out.println(w);
    }

    @Test
    void dumpQueries() {
        dump("1. LIST ALL (no filters) — the most common request",
                SearchCriteria.builder().page(0).size(20).build());

        dump("2. FREE TEXT",
                SearchCriteria.builder().page(0).size(20).q("Test-Folder1").build());

        dump("3. EXACT ATTRIBUTE LOOKUP",
                SearchCriteria.builder().page(0).size(20)
                        .folderAttributes(Map.of("pan_number", "PAN-8085569990")).build());

        dump("4. DEEP PAGE (page 400 — the expensive case)",
                SearchCriteria.builder().page(400).size(20).build());
    }
}
