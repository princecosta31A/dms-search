# dms-search

Read-only search API over the Elasticsearch document index. Users never reach
Elasticsearch or Kibana directly.

- **Port** 7008 · **Java** 21 · **Spring Boot** 4.0.6
- **Reads** the alias `dms-documents` — never a concrete index name
- **No Mongo, no Kafka, no Consul.** It only queries Elasticsearch.
- Writes are owned elsewhere: es-ingestion creates documents, es-update mutates them

## Versioning

Header-based, matching document-service — the path carries no version segment:

```
X-API-Version: 1.0
```

| Version | Status | Sunset |
|---|---|---|
| **1.0** | Current | — |

Omitting the header resolves to the default (`1.0`) rather than returning 400, so
curl and browser exploration stay frictionless. Clients that want stability
should send it explicitly, so a future `1.1` cannot change their behaviour
silently.

Configured in `WebConfig#configureApiVersioning` via Spring Framework 7's native
API versioning; supported/default versions come from `api.versioning.*`.

## One endpoint, two jobs

```
GET /api/search/documents
```

| Job | Parameters | Query it builds |
|---|---|---|
| Exact identifier lookup | `attr.<key>`, `folderAttr.<key>`, `documentTypeName`, … | `term` on the keyword field |
| Free-text discovery | `q` | `match` on `searchAll`, `operator: and` |

Which query runs depends on which parameters arrive, not which URL is called.

### Never put an identifier in `q`

`q` is analysed free text. Hyphens split it, so `q=TOKEN-2345687654` matches a
document whose token is `TOKEN-20260819054206` — both contain the term `token`.
Verified against real data. For exact values use `attr.<key>` / `folderAttr.<key>`,
which are `term` queries and cannot false-positive.

## Authorisation

Three headers, injected by **gateway-service** after it validates the caller's token:

```
X-Tenant-Id      required   ─┐ the authorisation boundary
X-Workspace-Id   required   ─┘
X-User-Id        optional   audit only
```

Access is **workspace-scoped**: a user with a role in a workspace may see every
document in it. So authorisation is exactly two `term` filters on fields already
indexed — no permission-service call, no ACL field, nothing to keep in sync.

Those two filters are added in `SearchQueryBuilder` **before** anything the
caller sent, in `filter` context, on every single query. There is no flag,
parameter or "admin" path that omits them, and `SearchQueryBuilderTest`'s
`SecurityBoundary` tests assert that across every permutation.

> ⚠️ **This trusts the gateway.** The headers only mean anything if the service
> is unreachable except through it. If port 7008 is directly reachable, any
> caller can set `X-Tenant-Id` and read another tenant's documents. Enforce it
> with a NetworkPolicy or mesh mTLS — not by convention.

## Pagination

Two modes; sending both is a `400`.

| Mode | Params | Use for | Cost |
|---|---|---|---|
| Offset | `page`, `size` | page-numbered UI | O(page × size) **per shard** |
| Cursor | `cursor` | infinite scroll, export | constant at any depth |

**Offset ceiling: `page × size + size ≤ 10,000`** (the index's
`max_result_window`), rejected with `SRCH-400-001` beyond that. With 4 shards,
`from=9980` makes every shard return 10,000 hits to the coordinating node so it
can sort 40,000 and discard 39,980. That is why the cursor exists.

Every sort is tie-broken by `documentId`. Without a unique tiebreaker, equal
scores order arbitrarily and paging repeats some documents while skipping others
— verified: offset and cursor return identical ordering across 45 documents.

`total` stops counting at 10,000; `totalIsExact: false` means "at least this
many". Counting further (`track_total_hits: true`) costs a full match scan on
every query.

## Response shape

A result is a **summary**, not the whole document. `content` (full OCR text),
`path` and `bucket` are excluded at the Elasticsearch level — a 50-page PDF's
text is ~200 KB, so returning it for 20 hits would make one page several
megabytes of data nothing displays.

`attr` and `folderAttr` **are** returned, so callers can confirm a hit matched
the field they expected.

For the full document, call document-service with the `documentId`.

## Errors

Platform envelope (`status`/`message`/`data`/`error`/`details`/`requestInfo`),
matching document-service. Deliberately **not** RFC 9457 — one service emitting
`application/problem+json` while five others emit this envelope would force
clients to handle two error shapes.

| Code | HTTP | Meaning |
|---|---|---|
| `SRCH-400-001` | 400 | Offset beyond `max_result_window` — use the cursor |
| `SRCH-400-002` | 400 | Malformed cursor |
| `SRCH-400-003` | 400 | Invalid parameter |
| `SRCH-400-004` | 400 | `page` and `cursor` together |
| `SRCH-400-005` | 400 | Unsupported sort |
| `SRCH-401-001` | 401 | Identity headers absent |
| `SRCH-503-001` | 503 | Elasticsearch unreachable (retryable, sends `Retry-After`) |

**No matches is `200` with an empty array, never `404`** — an empty result set is
a successful search.

**503, not 500**, when Elasticsearch is down: the request was fine and is worth
retrying, and a 500 tells clients' retry policies the opposite.

## Logging and PII

Search terms are the most sensitive data this platform handles — people search
for PAN numbers, passport numbers and names. So **`q` and every filter value are
never logged.** `SearchAuditLogger` records the *shape* of a query: which
filters were used (names only), how many hits, how long.

`co.elastic.clients` is pinned to `WARN` because the client logs full request
bodies at `DEBUG`. Do not lower it in a deployed environment.

Consequence: you cannot reconstruct a user's exact query from logs. That is the
intended trade — correlate with `traceId` and ask the user what they typed.

## Configuration

| Env var | Default | Notes |
|---|---|---|
| `SEARCH_SERVER_PORT` | `7008` | |
| `ES_HOST_URL` | `http://localhost:9200` | |
| `ES_INDEX` | `dms-documents` | The **alias** |
| `ES_API_KEY` | — | base64 `id:key`; wins over basic auth |
| `ES_USER` / `ES_PASSWORD` | — | |
| `ES_REQUEST_TIMEOUT_MS` | `5000` | |
| `ES_MAX_RESULT_WINDOW` | `10000` | Must match the index setting |
| `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED` | `false` | Enable only where a collector exists |

Starting with no credentials logs a **warning** on purpose — correct for a local
cluster with security disabled, wrong everywhere else.

## Run

```bash
mvn spring-boot:run
curl http://localhost:7008/health
```

```bash
# free text
curl -H "X-Tenant-Id: t1" -H "X-Workspace-Id: w1" \
  "http://localhost:7008/api/search/documents?q=quarterly%20report&size=10"

# exact attribute lookup
curl -H "X-Tenant-Id: t1" -H "X-Workspace-Id: w1" \
  "http://localhost:7008/api/search/documents?folderAttr.token_number=TOKEN-20260819054206"
```

## Tests

```bash
mvn test      # 46 tests
```

`SearchQueryBuilderTest.SecurityBoundary` is the one that matters: it asserts the
tenant/workspace filter survives every permutation — no filters, every filter,
free text, cursor paging, and an attempt to spoof `tenantId` through a query
parameter. A regression there is a cross-tenant data leak, not a wrong result.

## API spec

Hand-curated at `src/main/resources/static/openapi/search-api-v1.0.yaml`
(OpenAPI 3.0.3), matching document-service's spec-first approach.

`springdoc` is on the classpath for swagger-ui and for **drift detection** —
generate the spec from the running app in CI and diff it against the curated
file. That recovers the one thing spec-first loses.

## Known limitations

These are tracked in `Elastic-Search-Architecture/FLAG-OUT.md`:

- **F-07** — the `standard` tokenizer joins on `_`, so `Prince_Passport_Scan.pdf`
  is one token and searching `passport` misses it. Affects free-text discovery.
- **F-02** — es-update drops `folderName` on a folder rename, so a renamed folder
  keeps its old name here. Because the list view renders ES data directly, that
  is **visible to users**, not just a matching quirk. Worth fixing before this
  service ships.
- **F-09** — `folderAttr` dynamic templates match snake_case suffixes; a
  camelCase tenant key gets typed differently and can leak a `*_Flag` value into
  free-text search.
