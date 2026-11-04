# dms-search

Read-only search API over the Elasticsearch document index. Users never reach
Elasticsearch or Kibana directly.

- **Port** 7008 · **Java** 21 · **Spring Boot** 4.0.9
- **Reads two indexes, one per API version** — v1.1 the alias `dms-documents`,
  v1.0 the legacy index `teamsync-dms-documents` (see [Versioning](#versioning))
- **No Mongo, no Kafka, no Consul.** It only queries Elasticsearch.
- Writes are owned elsewhere: es-ingestion creates documents, es-update mutates them

## Versioning

Header-based, matching document-service — the path carries no version segment:

```
X-API-Version: 1.0
```

| Version | Index | Mapping | Status |
|---|---|---|---|
| **1.0** (default) | `teamsync-dms-documents` | legacy — what production ingests into today | Current |
| **1.1** | `dms-documents` (alias) | optimized — pipeline not yet deployed to production | Available |

**1.0 is the default on purpose.** The es-ingestion/es-update changes that
populate the optimized index are built and dev-tested but **not deployed**, so in
production v1.1 has no data behind it. A caller that omits the header must reach
the index that actually holds documents.

The **request** contract is identical across both versions — same parameters,
same validation, same paging. What differs is the backing index, the **response
shape**, and which capabilities exist:

| | 1.0 | 1.1 |
|---|---|---|
| response DTO | document-service's `DocumentResponse` | `DocumentSummary` |
| `attributes` / `attr` in the response | nested under `documentType.attributes`, a flat **object** | top-level `attr`, a flat **object** |
| field naming | `camelCase`, `id` / `folderId` | `camelCase`, `documentId` / `folderId` |
| `includeArchived` | ignored — no such field in the mapping | honoured |
| filter by `documentTypeId` | **400** — not indexed | honoured |
| subtree search | impossible — `path` holds a bare folder id | n/a |
| date/number ranges on attributes | no — `value_date`/`value_number` never populated | n/a |
| free text | three clauses: document name, nested attribute values, folder attributes | one `searchAll` field |

Relevance scores are **not comparable between versions**: v1.1 scores a single
`searchAll` field, v1.0 combines three heterogeneous clauses. Ordering within one
result set is still meaningful.

v1.0 is not a deprecated version to be phased out over releases — it is deleted
outright, along with the whole `legacy` package, on the day production migrates
to the optimized mapping. That is why there is no sunset column.

Omitting the header resolves to the default (`1.0`) rather than returning 400, so
curl and browser exploration stay frictionless. Clients should still send it
explicitly — the response shape depends on it.

Configured in `WebConfig#configureApiVersioning` via Spring Framework 7's native
API versioning; supported/default versions come from `api.versioning.*`.

## One endpoint, two jobs

```
GET /api/search/dms/documents
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
X-Tenant-Id      required   from the token — cannot be forged
X-Workspace-Id   required   CALLER-SUPPLIED — this is the one to worry about
X-User-Id        required   subject of the permission check
```

All three are required; a missing one is `401 SRCH-401-001`, and the response
names which. `X-User-Id` was optional while it was audit-only, defaulting to the
literal `"unknown"`. That became wrong once it was the permission subject:
permission-service answers `404` for that subject, which maps to `403`, so a
**missing header** was reported to the caller as *"you do not have read
permission in this workspace"* — sending whoever investigated to audit role
assignments for a problem that did not exist. It also left `userId=unknown` in
the audit log: a compliance record of a search with no subject.

Access is **workspace-scoped**: a user with a role in a workspace may see every
document in it. Enforcing that takes **two independent mechanisms**, and it is
worth being precise about which does what.

**1. Two `term` filters — enforce the boundary.**
`tenantId` and `workspaceId` are added in `SearchQueryBuilder` **before** anything
the caller sent, in `filter` context, on every single query. There is no flag,
parameter or "admin" path that omits them, and `SearchQueryBuilderTest`'s
`SecurityBoundary` tests assert that across every permutation.

**2. A permission-service call — verifies the claim.**
The filters are not sufficient on their own. The gateway derives `tenantId` and
`userId` from the token, but **not** `workspaceId` — a user belongs to several
workspaces and picks one per request, so that header is caller-supplied.
Filtering on it means "only show this workspace"; it never asks "may you see this
workspace?". Without the second mechanism, any authenticated caller reads any
workspace in their own tenant by changing one header.

`PermissionValidationService` closes that gap, checking `file.read` against
permission-service before every search. It fails closed: a denial is `403`, and
an unreachable permission-service is `503` — never an allow.

> ⚠️ **The permission check is OFF by default.** `PERMISSION_SERVICE_ENABLED`
> defaults to `false`, because permission-service is not reachable from a
> developer laptop. **While it is false, mechanism 2 does not run** and the
> workspace boundary is unverified — exactly the hole described above. The
> service logs a WARNING on every request so this is not silent, but it must be
> `true` in any deployed environment.

> ⚠️ **All of this trusts the gateway.** The headers only mean anything if the
> service is unreachable except through it. If port 7008 is directly reachable,
> any caller can set `X-Tenant-Id` and read another tenant's documents — and no
> permission check helps, because `tenantId` would be forged too. Enforce it with
> a NetworkPolicy or mesh mTLS, not by convention.

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

### `complete` vs `totalIsExact`

Two flags answering different questions. Both are needed.

| `complete` | `totalIsExact` | What happened | Show the user |
|---|---|---|---|
| `true` | `true` | finished, counted exactly | `800 results · 40 pages` |
| `true` | `false` | **finished fine** — ES stopped counting at 10,000 | `10,000+ results` |
| `false` | `false` | ran out of time — results AND count unreliable | `incomplete — narrow your search` |
| `false` | `true` | impossible — forced `false` in `toPage()` | — |

`complete: false` means Elasticsearch hit the server-side timeout. It does **not**
abort on timeout — it returns whatever the shards had reported and raises
`timed_out`, so a truncated search is otherwise indistinguishable from a whole
one: HTTP 200, documents on screen, a total. Measured on a 10-crore index, the
same query returned `99,981,150` complete and `24,877,353` truncated.

A search that did not finish cannot have counted exactly, so `totalIsExact` is
forced `false` whenever `complete` is `false` — otherwise a truncated search
reports "800 results, exact", which is the one case a caller cannot detect.

Row 2 is a healthy broad search, not a failure. Only row 3 is a problem.

## Response shape

A result is a **summary**, not the whole document. `content` (the full OCR text)
is excluded at the Elasticsearch level — a 50-page PDF's text is ~200 KB, so
returning it for 20 hits would make one page several megabytes of data nothing
displays.

Both versions return `ApiResponse<SearchPage>`: the hits in `items`, plus
`page` / `size` / `total` / `totalIsExact` / `complete` / `hasNext` /
`nextCursor` / `tookMs`.

### v1.0 — document-service's `DocumentResponse`

v1.0 replaces document-service's `/api/documents/search/token`, `/search/pan` and
`/search/acknowledgement`, all of which return `DocumentResponse`. Each hit is
emitted in that exact shape, including the wire names `id` (not `documentId`) and
`folderId` (not `parentId`), so moving to this service is a URL change for
existing clients rather than a parser change.

```json
{
  "id": "DOC-TEST-0001",
  "documentName": "260812213869103-20260812_095825975.pdf",
  "ownedBy": "700aed14-841a-4be2-a974-aa4c889f8b39",
  "workspaceId": "6a842029e3ac57df8b8c58cf",
  "folderId": "260812213869103",
  "tenantId": "6a841cbde32f87eb8577a5cc",
  "extension": "pdf",
  "documentType": {
    "documentTypeName": "PAN_CARD",
    "attributes": { "pan_number": "ABCDE1234F", "customer_name": "Ramesh Kumar" }
  },
  "createdAt": "2026-08-20T10:17:55.113+05:30",
  "updatedAt": "2026-08-21T11:00:00.000+05:30",
  "storageKey": "260812213869103",
  "bucketName": "teamsync-docs"
}
```

The field list is a straight **intersection**: a `DocumentResponse` field appears
only if the legacy index actually holds it. These do not, and are omitted —
absent from the JSON, not `null`, exactly as document-service omits `tagIds` for
an untagged document:

| omitted | why |
|---|---|
| `displaySize` | no size field in the legacy mapping or `_source` |
| `Content-MD5` | checksum is not indexed |
| `tagIds` | tags are not indexed |
| `isRootItem` | every legacy document has a `folder_id`, so it is a constant `false` |
| `templateValidation` | the index has `validation_status` (`"VALIDATED"`), not document-service's `"1"`/`"0"`/`"-1"` code, and no `validatedAt` — same name, different value domain |
| `score` | not part of the `DocumentResponse` contract; ordering still reflects relevance |

Two values are translated rather than passed through, because the field name
matching is worthless if the value does not:

- **`extension`** — the index stores `".pdf"`, the contract returns `"pdf"`.
- **`documentType.attributes`** — the index stores a `nested` array
  `[{key,value},…]`, the contract returns a map `{key: value}`. Duplicate keys
  collapse, last one wins; `Map<String, Object>` has no room for a second value
  and neither does document-service's own contract.

`folder_type_attributes` is **not** returned: `DocumentResponse` has no field for
it. Filtering on `folderAttr.*` still works.

`LegacyDocumentSummary` is the Elasticsearch deserialisation target and stays
snake_case because it must match `_source`; `LegacyDocumentResponseMapper`
converts it. The whole translation lives in the `legacy` package and is deleted
with it.

### v1.1 — `DocumentSummary`

Its own camelCase shape, with `attr` and `folderAttr` returned at the top level
so callers can confirm a hit matched the field they expected.

For the full document, call document-service with the document id.

## Errors

Platform envelope (`status`/`message`/`data`/`error`/`details`/`requestInfo`).

**This differs from document-service**, which returns the envelope on success but
a separate `ProblemDetail` object (RFC 7807: `type`/`title`/`status`/`detail`/
`instance`/`errorCode`/`errorCategory`/`timestamp`/`traceId`/`correlationId`/
`errorContext`) on failure. An earlier version of this file claimed parity on
errors; that was wrong — only the success envelope matches. Aligning the error
shape is a known, deliberate gap, not an oversight.

| Code | HTTP | Meaning |
|---|---|---|
| `SRCH-400-001` | 400 | Offset beyond `max_result_window` — use the cursor |
| `SRCH-400-002` | 400 | Malformed cursor |
| `SRCH-400-003` | 400 | Invalid parameter |
| `SRCH-400-004` | 400 | `page` and `cursor` together |
| `SRCH-400-005` | 400 | Unsupported sort |
| `SRCH-400-006` | 400 | Unsupported `X-API-Version` |
| `SRCH-400-007` | 400 | Elasticsearch rejected the query (bad date, bad offset, mismatched cursor) |
| `SRCH-401-001` | 401 | Identity headers absent |
| `SRCH-403-001` | 403 | No `file.read` permission in that workspace |
| `SRCH-404-001` | 404 | Route does not exist |
| `SRCH-500-001` | 500 | Unhandled fault — a bug, not an operational state |
| `SRCH-503-001` | 503 | Elasticsearch unreachable (retryable, sends `Retry-After`) |
| `SRCH-503-002` | 503 | permission-service unreachable — fails closed, never allows |

This table is the contract; `SearchErrorCode.java` is the source of truth.
**Adding a code there means adding a row here** — the previous version of this
table listed 7 of 13, and the OpenAPI spec listed 2, which is why that spec no
longer exists.

**No matches is `200` with an empty array, never `404`** — an empty result set is
a successful search.

**`400` vs `503` is the distinction that matters.** Elasticsearch answering *"your
query is malformed"* is an answer, not an outage: `SRCH-400-007`, and clients
must not retry. Elasticsearch not answering at all is `SRCH-503-001`, retryable,
with `Retry-After`. Collapsing the two — as a single `catch (Exception)` does —
tells every retry policy in the fleet to re-send a request that can never succeed.

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
| `ES_INDEX` | `dms-documents` | API v1.1 — the **alias** |
| `ES_LEGACY_INDEX` | `teamsync-dms-documents` | API v1.0 — a **concrete index**, not an alias: this one has none in production, and creating one there is a separate change v1.0 must not depend on |
| `API_SUPPORTED_VERSIONS` | `1.0,1.1` | |
| `API_DEFAULT_VERSION` | `1.0` | The legacy mapping — the only version with production data |
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
  "http://localhost:7008/api/search/dms/documents?q=quarterly%20report&size=10"

# exact attribute lookup
curl -H "X-Tenant-Id: t1" -H "X-Workspace-Id: w1" \
  "http://localhost:7008/api/search/dms/documents?folderAttr.token_number=TOKEN-20260819054206"
```

## Tests

```bash
mvn test      # 80 tests
```

`SearchQueryBuilderTest.SecurityBoundary` is the one that matters: it asserts the
tenant/workspace filter survives every permutation — no filters, every filter,
free text, cursor paging, and an attempt to spoof `tenantId` through a query
parameter. A regression there is a cross-tenant data leak, not a wrong result.

## API spec

Generated from the code by `springdoc`:

```
/v3/api-docs          the spec, JSON
/swagger-ui.html      the UI
```

There was a hand-curated `search-api-v1.0.yaml` here, written spec-first like
document-service. It has been removed. Nothing generated a client from it, and
within two weeks it had drifted badly enough to be actively misleading: it
documented 2 of the 13 error codes, and omitted `complete` — the field that
tells a caller their results are partial. A client generated from it would have
silently dropped the one flag that says "this answer is incomplete".

The drift-detection step meant to prevent that (generate in CI, diff against the
curated file) was described in this README and never wired up. A stale spec is
worse than no spec, so the generated one is now the only one.

**Known gap:** the generated spec documents only `200`. Failure responses need
`@ApiResponse` annotations on the controller before it describes the error
contract — until then, `SearchErrorCode` is the source of truth for error codes.

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
