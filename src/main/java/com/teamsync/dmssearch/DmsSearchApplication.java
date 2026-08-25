package com.teamsync.dmssearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * dms-search — a read-only search API over the Elasticsearch document index.
 *
 * <p>Deliberately narrow in scope:
 * <ul>
 *   <li>It <b>reads</b>. It never writes to Elasticsearch — es-ingestion owns
 *       document creation and es-update owns mutations.</li>
 *   <li>It talks to the <b>alias</b> ({@code dms-documents}), never a concrete
 *       index name, so the generation behind it can roll over without any
 *       config change here.</li>
 *   <li>It has <b>no MongoDB dependency</b>. Result lists are served from the
 *       fields already held in Elasticsearch; a client that needs the full
 *       document fetches it from document-service by id.</li>
 * </ul>
 *
 * <p><b>Authorisation:</b> tenant and workspace are read from request headers
 * injected by gateway-service after it validates the caller's token, and are
 * applied as a mandatory filter on every query. This service must therefore be
 * reachable <i>only</i> through the gateway — if its port is directly reachable,
 * a caller can set those headers themselves and read any tenant's data. Enforce
 * that at the network layer (NetworkPolicy / mesh mTLS), not by convention.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DmsSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(DmsSearchApplication.class, args);
    }
}
