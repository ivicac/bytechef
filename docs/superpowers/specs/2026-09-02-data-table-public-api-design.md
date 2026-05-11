# Public Data Table API — DDL and Rows (Design)

**Date:** 2026-09-02
**Status:** Approved design, pending spec review
**Scope of this document:** A public REST/OpenAPI surface for automation data tables — table and
column definitions (DDL) and row data — under `/api/automation/v1`, plus the CLI commands that
drive it. Embedded (connected-user) data tables are a named follow-on with their own spec.

## 1. Context

Data tables have no REST surface today. They are reachable through three internal surfaces:

| Surface | Module | Shape |
|---|---|---|
| GraphQL | `automation-data-table-graphql` | `WorkspaceDataTableFacade`, 18 methods |
| Workflow component | `components/data-table` | 6 actions, 3 triggers |
| MCP / Copilot tools | `automation-ai-tool` | `queryDataTable`, `addDataTableRow`, … |

All three delegate to the same guarded facade or platform services, and the facade already
carries the three scope tokens a public surface needs: `DATA_TABLE_VIEW` and `DATA_TABLE_EDIT`
on the table, `DATA_TABLE_CREATE` on the workspace.

Three facts about the domain shape everything below:

- **The registry row is the logical table; the physical table is per environment.**
  `data_table` is keyed `(name, platform_type)` with no environment column; `createTable` in a
  second environment reuses the registry row (`DataTableServiceImpl.register`), and `dropTable`
  removes it only when the last environment's physical table is gone. Environment is selected by
  the `X-Environment` header, as on the published executions endpoint.
- **Base names are unique per pool.** `DataTableRef` normalizes to `[a-z_][a-z0-9_]*` and the
  registry is unique on `(name, platform_type)`. A name identifies a table without a workspace.
- **The row-ownership boundary is the `WHERE` clause.** In the embedded pool one physical table
  holds every account's rows, separated only by the `owner_id`/`owner_type` predicate that
  `DataTableRef` carries (`.agents/data-table-knowledge-base-ownership.md`). The automation pool
  addresses rows through `DataTableRef.unowned(...)`. Nothing in this design touches the
  predicate, and one new index deliberately makes it part of a key (§6).

The public automation API's conventions were designed in
`2026-08-24-automation-public-api-slice1-design.md` and are not yet implemented on any branch.
This spec is **self-contained**: the only slice-1 pieces it needs are the `Error` schema (which
every public-rest module declares in its own `openapi.yaml` anyway) and the one-line API-key type
check, and it lands both. Where this spec deviates from slice 1 it says so (§4.4).

## 2. Scope

**In scope:** a new spec-first module `automation-data-table-public-rest`; 8 DDL endpoints; 13 row
endpoints including filtered queries, batch insert/delete, clear, CSV import/export and upsert on a
caller-supplied external id; a reserved `external_id` column with a backfill changelog; typed
data-table errors; 18 CLI commands.

**Out of scope, each with its reason recorded here so nobody re-derives it:**

| Absent | Why |
|---|---|
| Embedded connected-user surface | Needs its own `DataTableRef` resolution through `ConnectedUserOwnerResolver`; the automation facade's `unowned` ref would read every account's rows. Follow-on spec. |
| Webhooks | `addWebhook`/`removeWebhook` are called only by the three `DataTableRecord*Trigger`s — a webhook's `url` is the trigger's own callback. The public way to react to row changes is a workflow with the data-table trigger. |
| Table rename | Changes the resource's identity: moves the URL and breaks every workflow that names the table in its `TABLE` input. Fine for a person with the list page in front of them; a footgun for an external system. Compose: create, export → import, drop. |
| Table duplicate | Same-environment schema **and data** copy (`INSERT … SELECT`). Composable from create + export/import; the copy an integration wants is cross-environment schema, which is promotion work. |
| Column type change | `ALTER COLUMN … TYPE … USING` with real data-loss edge cases; exists on no surface today. |
| Natural-key designation | "Upsert on `sku`" per table. §6's reserved column covers the need without per-table DDL. |
| Expression filters (`or`, `not`, grouping) | Needs a parser, a filter AST and a recursive SQL emitter that must provably keep the caller's `or` inside the ownership predicate. The `where` parameter is **reserved** for it (§4.3). |

## 3. Architecture

New EE module `server/ee/libs/automation/automation-data-table/automation-data-table-public-rest`,
a clone of `automation-configuration-public-rest`'s shape:

- `openapi.yaml` is the source of truth. `generateOpenAPISpring` (openapi-generator 7.24.0, `spring`
  generator, `interfaceOnly`, the vendored `pojo.mustache`) emits `*Api` interfaces and `*Model`
  classes into committed `generated/`. `Page` is schema-mapped to `org.springframework.data.domain.Page`.
- Hand-written `DataTableApiController` and `DataTableRowApiController` implement the interfaces.
  Each does two things: resolve `{name}` → id through `DataTableService.getIdByBaseName(name, AUTOMATION)`,
  then call `WorkspaceDataTableFacade`. **The public module introduces no `@PreAuthorize` of its own.**
- The six operations the facade lacks are added **to the facade**, guarded there (§5, §6). The
  GraphQL and MCP surfaces get them for free next time they need them.
- Monolith only (`server-app`), like every other public-rest module.
- `AutomationApiKeySecurityConfigurer`'s `^/api/automation/v[0-9]+/.+` pattern already covers the
  new paths; no security wiring is added.

Rejected: extending `automation-configuration-public-rest` (couples a separate module family to
the configuration roadmap and makes both the spec file and the CLI client "everything automation"),
and controllers over the platform services with their own guards (this repo moved authorization
onto facades; a controller wired past the facade compiles fine and silently drops the workspace
check).

## 4. Resource model and conventions

### 4.1 Credential and environment

`AUTOMATION` API key. `AutomationApiKeyAuthenticationProvider` gains the check slice 1 specified:

```java
if (apiKey.getType() != PlatformType.AUTOMATION) {
    throw new BadCredentialsException("Automation API key required");
}
```

`X-Environment` header, optional, default `PRODUCTION`. The environment selects the physical
table; the resource the API exposes is **the table in the requested environment**.
`GET /data-tables/{name}` is 404 when no physical table exists there, even if the registry row
does — columns are read from the physical table, and nothing else is coherent.

### 4.2 Identifiers

**Tables are addressed by name; rows by numeric id, optionally by a caller-supplied external id.**

Slice 1's "configuration by uuid" rule was considered and not applied. Its first justification —
lineage across environments — already holds for the numeric registry id here; its second,
non-enumerability, is real but answered in §7 by never distinguishing "not found" from "not
yours". Against that, the base name is what every other surface uses (the component's `TABLE`
input is the base name), is unique per pool, and reads as a URL. Liferay Objects, which solves the
same two problems, addresses collections by object name and records by numeric id plus an optional
caller-owned `externalReferenceCode`; this design follows the same three-handle shape.

Names are validated **strictly** on create and add-column against `^[a-z_][a-z0-9_]*$` → 400.
No silent lowercasing: a public contract that mutates its input cannot be round-tripped. The
reserved names `id`, `owner_id`, `owner_type`, `external_id` → 400.

### 4.3 Resources

**`DataTable`**

| Field | Type | Notes |
|---|---|---|
| `name` | string | identity |
| `description` | string, nullable | registry field, environment-independent |
| `columns` | `[{name, type}]` | `type` ∈ `STRING NUMBER INTEGER DATE DATE_TIME BOOLEAN`; reserved columns are never listed |
| `tags` | `[string]` | tag names |
| `createdDate`, `lastModifiedDate` | date-time | |

**`DataTableRow`**

| Field | Type | Notes |
|---|---|---|
| `id` | int64 | |
| `externalId` | string, nullable | §6 |
| `values` | object | keyed by column name |

Output typing: STRING→string, NUMBER→number, INTEGER→integer, BOOLEAN→boolean,
DATE→`YYYY-MM-DD`, DATE_TIME→ISO-8601 UTC. Input accepts the natural JSON type **or** a string —
`DataTableRowServiceImpl.coerceValue` already does this; declaring it is additive lenience.
Unknown column → 400 `ROW_VALUE_INVALID`; `null` clears a value; on update, omitted columns are
untouched (merge semantics, which is what `updateRow` does today).

**Collections.** Rows use the existing `Page` envelope (`number`, `size`, `numberOfElements`,
`totalPages`, `totalElements`, `content`), `pageNumber` (default 0) and `pageSize` (default 50,
max 500). `sort=col:ASC|DESC`, repeatable, applied in order; default `id:ASC`.
`filter=col:OP:value`, repeatable, ANDed. **`where` is reserved** for a future expression filter
and documented as such in the spec.

Filter grammar: split on the first two colons; the value is the remainder, so DATE_TIME literals
with colons survive. `OP` ∈ `EQ NEQ IN CONTAINS STARTS_WITH GT GTE LT LTE BETWEEN`; `IN` and
`BETWEEN` take comma-separated values, as the component documents. The parser never interprets
values — type coercion is the service's. Bad operator, unknown column, hidden column (`owner_*`),
wrong arity for `BETWEEN` → 400 `FILTER_INVALID`.

### 4.4 Workspace scoping

`GET`/`POST /workspaces/{workspaceId}/data-tables`; item URLs are flat (`/data-tables/{name}…`)
because a base name is unambiguous without a workspace.

**Deviation from slice 1**, recorded deliberately: slice 1 wrote "`workspaceId` is a required
query parameter on every collection endpoint; item URLs stay flat". This spec nests the collection
under the workspace path. Slice 1 should be aligned to the nested form when it is implemented so
the API does not carry both idioms.

### 4.5 Versioning

`/v1` additive-only, per slice 1.

## 5. DDL endpoints

All guarded by the facade's existing annotations; the controller only resolves the name first.

| Method | Path | Guard | Notes |
|---|---|---|---|
| GET | `/workspaces/{workspaceId}/data-tables?tag=` | `Workspace:DATA_TABLE_VIEW` | not paged — the facade returns a list and a workspace's table count is small; `tag` filters the facade result |
| POST | `/workspaces/{workspaceId}/data-tables` | `Workspace:DATA_TABLE_CREATE` | `{name, description?, columns[≥1], tags?}` → 201 + resource; 409 on duplicate |
| GET | `/data-tables/{name}` | `DataTable:DATA_TABLE_VIEW` | |
| PATCH | `/data-tables/{name}` | `DataTable:DATA_TABLE_EDIT` | `{description?, tags?}`, omitted = untouched |
| DELETE | `/data-tables/{name}` | `DataTable:DATA_TABLE_EDIT` | drops this environment's physical table; registry row goes with the last one (existing `dropTable`). 204 |
| POST | `/data-tables/{name}/columns` | `DataTable:DATA_TABLE_EDIT` | `{name, type}` → 201 + resource; 409 on duplicate |
| DELETE | `/data-tables/{name}/columns/{column}` | `DataTable:DATA_TABLE_EDIT` | 204; irreversible, drops the column's data — stated in the description |
| POST | `/data-tables/{name}/columns/{column}/rename` | `DataTable:DATA_TABLE_EDIT` | `{newName}` → 200 + resource |

**PATCH is the only environment-independent DDL operation**: it writes the registry row, not a
physical table, and must not be "fixed" to require a physical table in the current environment.
It needs one new method, `DataTableService.updateDescription(baseName, description, platformType)`
and its facade counterpart `updateDescription(dataTableId, description)`. Tags are resolved by
name: existing workspace tags reused, unknown names created through `TagService.save`, then
`updateTags`.

Column rename stays although table rename does not: it does not change the resource's identity,
the blast radius is one field, and there is no data-preserving alternative expressible through the
API short of a full export/import.

## 6. Row endpoints and `external_id`

### 6.1 The reserved column

Every physical table gains `external_id VARCHAR(255)` beside `owner_id`/`owner_type` in the one
`buildCreateTableSql`, and a unique index:

```sql
CREATE UNIQUE INDEX ON <physical> (owner_id, external_id) NULLS NOT DISTINCT
    WHERE external_id IS NOT NULL
```

Both halves of that index are load-bearing:

- `NULLS NOT DISTINCT` (PostgreSQL 15, already the floor) is for `owner_id`: without it every
  `NULL owner_id` — every automation-pool row — is a distinct key and the pool gets no uniqueness
  at all. This is the `property (key, scope, environment) WHERE scope_id IS NULL` trap again,
  solved here with one non-partial predicate instead of a second index.
- `WHERE external_id IS NOT NULL` is for `external_id`: with `NULLS NOT DISTINCT` and no
  predicate, every row without an external id would collapse into one key and the backfill would
  fail on the second row of every table.

In the embedded pool two accounts may each hold `external_id = 'ORD-1'` in the shared physical
table; the owner is part of the key.

`ReservedColumns` gains `EXTERNAL_ID`: reserved (never in `columns[]`, not creatable or droppable)
but **not hidden** — filterable and sortable like `id`. `buildCreateTableSql`, `listColumns`,
`duplicateTable`'s copied-column list, and CSV import/export all learn about it; `createTable` and
`addColumn` reject it by name.

On the wire `externalId` is a top-level row field, never inside `values` — platform metadata, like
`id`. Non-blank, ≤255 characters, caller-chosen, changeable or clearable through PATCH.

### 6.2 Endpoints

Ownership is untouched: the facade's `dataTableRef(id, environmentId)` builds
`DataTableRef.unowned(...)`.

| Method | Path | Guard | Notes |
|---|---|---|---|
| GET | `/data-tables/{name}/rows?filter=&sort=&pageNumber=&pageSize=` | `DATA_TABLE_VIEW` | `Page<DataTableRow>` |
| GET | `/data-tables/{name}/rows/{id}` | `DATA_TABLE_VIEW` | 404 |
| POST | `/data-tables/{name}/rows` | `DATA_TABLE_EDIT` | `{values, externalId?}` → 201 + row; 409 on duplicate external id |
| PATCH | `/data-tables/{name}/rows/{id}` | `DATA_TABLE_EDIT` | `{values?, externalId?}`, merge semantics; 404 |
| DELETE | `/data-tables/{name}/rows/{id}` | `DATA_TABLE_EDIT` | 204; 404 |
| GET | `/data-tables/{name}/rows/by-external-id/{externalId}` | `DATA_TABLE_VIEW` | 404 |
| PUT | `/data-tables/{name}/rows/by-external-id/{externalId}` | `DATA_TABLE_EDIT` | `{values}`; **upsert**: 201 + row when created, 200 + row when merged. Idempotent by construction |
| DELETE | `/data-tables/{name}/rows/by-external-id/{externalId}` | `DATA_TABLE_EDIT` | 204; 404 |
| POST | `/data-tables/{name}/rows/batch` | `DATA_TABLE_EDIT` | `{rows:[{values, externalId?}], createStrategy: INSERT\|UPSERT}` (default `INSERT`); **max 1000 rows; one transaction, all or nothing**; `UPSERT` requires `externalId` on every row (400 `ROW_EXTERNAL_ID_REQUIRED`); returns `{rows:[…]}` |
| DELETE | `/data-tables/{name}/rows?ids=1,2,3` | `DATA_TABLE_EDIT` | `ids` **required** — a bare `DELETE /rows` is 400, never "everything"; max 1000; returns `{deletedCount, deletedIds}` |
| POST | `/data-tables/{name}/rows/clear` | `DATA_TABLE_EDIT` | explicit truncate; returns `{deletedCount}` |
| POST | `/data-tables/{name}/rows/import` | `DATA_TABLE_EDIT` | body `text/csv`; header names existing columns, optional `external_id` column honoured; returns `{importedCount}` |
| GET | `/data-tables/{name}/rows/export` | `DATA_TABLE_VIEW` | `text/csv` attachment; `external_id` is always a column |

`clear` stays although rename and duplicate do not: paging every id and deleting in 1000-chunks is
O(rows) calls and never empties a table under concurrent writes; drop + recreate loses tags and
description with the registry row. Its purpose is the primary CSV use of this API — reload a lookup
table as `clear` → `import` — and the explicit `POST …/clear` is the safe shape for it.

Batch atomicity holds per request. A client retrying a timed-out `INSERT` batch double-inserts;
that is what `UPSERT` with `externalId` is for, and the documentation says to use it for
retry-safe loads.

### 6.3 New facade and service methods

| Facade (`WorkspaceDataTableFacade`, guarded) | Platform service | Notes |
|---|---|---|
| `Page<DataTableRow> listRows(id, filters, sorts, pageNumber, pageSize, env)` | existing filtered `listRows` + **new** `countRows(ref, filters)` | `countRows` is one `COUNT(*)` built by `RowQuerySqlBuilder` from the same fragment; the `Page` envelope needs `totalElements` and today's callers fake it with `limit + 1` |
| `DataTableRow getRow(id, rowId, env)` | existing | |
| `Optional<DataTableRow> fetchRowByExternalId(id, externalId, env)` | **new** | |
| `List<DataTableRow> insertRows(id, rows, strategy, env)` | **new** `insertRows` / per-row `upsertRow` in one transaction | |
| `UpsertResult upsertRow(id, externalId, values, env)` | **new** `upsertRow(ref, externalId, values)` | `INSERT … ON CONFLICT (owner_id, external_id) WHERE external_id IS NOT NULL DO UPDATE SET …`; the conflict target is the ownership index, so an account cannot upsert onto another account's row and there is no read-then-write race. Result says created-vs-updated |
| `DeleteResult deleteRows(id, rowIds, env)` | **new** | `{deletedCount, deletedIds}` |
| `long clearRows(id, env)` | **new** | |
| `int importCsv(...)` | existing, gains the count as a return value | |
| `updateDescription(id, description)` | **new** | §5 |

All new facade methods carry `DATA_TABLE_EDIT` except `listRows`/`getRow`/`fetchRowByExternalId`
(`DATA_TABLE_VIEW`). The storage limit is enforced inside the row service
(`dataTableStorageService.checkWithinLimit`) and therefore applies to every new write path with
no work in the public module.

## 7. Errors

### 7.1 Shape

The platform's `ProblemDetail`, declared as the `Error` schema on every 4xx/5xx response:
`type` (URI `<problem base>/<code>`), `title`, `status`, `detail`, and the two properties
`AbstractResponseEntityExceptionHandler` already emits — `errorKey` (**integer**) and
`entityClass`. Slice 1 left the key's type open; the platform handler fixes it as an int, and a
string key here would give the product two error shapes. Consumers branch on `errorKey`; the table
below is the contract.

### 7.2 Typed errors

New `DataTableErrorType extends AbstractErrorType` in `platform-data-table-api` (beside
`DataTableStorageLimitExceededException`; the `-api` placement follows the rule that
cross-deployment contracts live in `-api`) and `DataTableException extends AbstractException`.
The services throw these at the seams the API depends on, replacing bare
`IllegalArgumentException` there; programmer-error asserts stay as they are.

| Key | Status | When |
|---|---|---|
| 100 `DATA_TABLE_NOT_FOUND` (existing) | 404 | unknown name, no physical table in this environment, or not visible to the caller (§7.4) |
| 101 `DATA_TABLE_NOT_CREATED` (existing) | — | pre-existing key; not thrown on any path this API reaches |
| 102 `DATA_TABLE_NOT_DUPLICATED` (existing) | — | pre-existing key; not thrown on any path this API reaches |
| 103 `DATA_TABLE_NAME_INVALID` | 400 | pattern or reserved |
| 104 `DATA_TABLE_ALREADY_EXISTS` | 409 | |
| 105 `COLUMN_NOT_FOUND` | 404 | |
| 106 `COLUMN_ALREADY_EXISTS` | 409 | |
| 107 `COLUMN_NAME_INVALID` | 400 | pattern or reserved |
| 108 `ROW_NOT_FOUND` | 404 | by id or external id |
| 109 `ROW_VALUE_INVALID` | 400 | unknown column or uncoercible value; `detail` names the column |
| 110 `ROW_EXTERNAL_ID_CONFLICT` | 409 | duplicate on insert |
| 111 `ROW_EXTERNAL_ID_REQUIRED` | 400 | `UPSERT` batch row without a key |
| 112 `FILTER_INVALID` | 400 | operator, arity, hidden column |
| 113 `SORT_INVALID` | 400 | |
| 114 `BATCH_TOO_LARGE` | 400 | more than 1000 rows or ids |
| 115 `CSV_INVALID` | 400 | header names a non-existent column |
| 116 `STORAGE_LIMIT_EXCEEDED` | 507 | `DataTableStorageLimitExceededException` |

Bean-validation failures (missing `columns`, blank `name`) return Spring's default 400
`ProblemDetail` without `errorKey`; the spec says so. An empty `ids` query parameter on the
delete-rows endpoint is `ROW_VALUE_INVALID`; a missing one is Spring's own 400 with no `errorKey`.

### 7.3 Status mapping

`GlobalResponseEntityExceptionHandler` is `@Order(HIGHEST_PRECEDENCE)` and maps every
`AbstractException` to 400, so a package-scoped advice cannot reliably outrank it. Spring resolves
`@ExceptionHandler` methods declared **on the controller class itself** before any advice, so the
public module's two controllers extend `AbstractDataTableApiController`, whose handlers map a
`DataTableErrorType`-bearing exception to 400/404/409, `DataTableStorageLimitExceededException` to
507, and (item URLs only) `AccessDeniedException` to 404. That handler is declared for
`DataTableException` and `ExecutionException` specifically and never rethrows: an exception whose
`entityClass` is not `DataTableErrorType` returns the same 400 body the global advice would have
produced, because a handler that throws is logged and yields null rather than falling through to the
advice. The body carries the same fields `AbstractResponseEntityExceptionHandler` emits. Nothing
global changes.

507 Insufficient Storage is the one registered status that says exactly what the storage limit
means, and it cannot be confused with a scope denial (403) or a rate limit (429).

### 7.4 Existence leak

Slice 1 argued 404-versus-403 is harmless because uuids are not guessable. Names are: with a plain
guard, `GET /data-tables/orders` would answer 403 for a table in another workspace and 404 for
none, telling a caller which names exist across the tenant. So on **item URLs a guard denial is
returned as 404 `DATA_TABLE_NOT_FOUND`** ("not found or not yours"). Collection and create
endpoints keep 403 — there the caller already knows the workspace exists.

## 8. CLI

**Client.** New module `cli/clients/automation-data-table`; `generateClient` pointed at the server
module's `openapi.yaml` via `${rootDir}` (the embedded clients' pattern). Committed generated
sources, not wired to `compileJava`. `AutomationClientFactory` gains `dataTableApi(config)` and
`dataTableRowApi(config)` — same base path, `AuthInterceptor` and `toCliException`.

**Commands** (`cli/commands/automation`: `AutomationDataTableCommand`,
`AutomationDataTableRowCommand`), all taking the standard `--profile/--host/--token/--environment`
and `--output json|table`:

```
automation data-table list      --workspace-id  [--tag]
automation data-table get       --name
automation data-table create    --workspace-id --name [--description] --column name:TYPE … [--tag …]
automation data-table update    --name [--description] [--tag …]
automation data-table delete    --name
automation data-table column add     --name --column name:TYPE
automation data-table column remove  --name --column
automation data-table column rename  --name --column --new-name

automation data-table row list    --name [--filter col:OP:value …] [--sort col:DIR …] [--page] [--page-size]
automation data-table row get     --name (--id | --external-id)
automation data-table row insert  --name --values '<json>' [--external-id]
automation data-table row update  --name --id --values '<json>'
automation data-table row upsert  --name --external-id --values '<json>'
automation data-table row delete  --name (--id | --ids 1,2,3 | --external-id)
automation data-table row batch   --name --file rows.json [--strategy INSERT|UPSERT]
automation data-table row clear   --name --yes
automation data-table row import  --name --file data.csv
automation data-table row export  --name [--file out.csv]
```

`--filter`/`--sort` pass through as the wire grammar — no second grammar to document.
`--column name:TYPE` reuses the colon convention. `clear` requires `--yes`: the CLI is where a
destructive command gets typed by hand.

## 9. Testing

- **Spec-level:** `openapi.yaml` validated by the generator at build; a test asserts every path
  declares the `Error` schema on its 4xx/5xx responses.
- **Controllers** (mocked facade, as `WorkflowExecutionApiControllerTest`): request mapping;
  filter/sort parsing — every operator, arity errors, colon-in-value; status per
  `DataTableErrorType`; the 403→404 translation; batch limits; CSV content types.
- **Facade/service `IntTest`** (Testcontainers Postgres): filtered `listRows` and `countRows`
  agree; `insertRows` atomicity (one bad row rolls back all); `upsertRow` created-vs-updated;
  uniqueness with `NULL` owner; batch `UPSERT`; `clearRows`; import with `external_id`; export
  includes it. **The ownership suite in `.agents/data-table-knowledge-base-ownership.md` gains two
  cases:** an upsert cannot land on another account's row; the index permits the same
  `external_id` across two accounts.
- **Backfill `IntTest`:** create a physical table with the pre-change `CREATE TABLE` shape, run
  the migrator, assert column and index exist and the partial unique constraint holds — including
  that two rows with `NULL external_id` coexist.
- **CLI:** the existing `StubApi` pattern per command class.
- **Security:** an `EMBEDDED`-typed key is rejected on `/api/automation/v1/data-tables`.

## 10. Migration, rollout, risks

**Schema.** One changelog in `platform-data-table-service`, ordered after
`20260901000002_platform_data_table_webhook_add_owner`, a `CustomTaskChange`
(`DataTableExternalIdColumnChange` → `DataTableExternalIdColumnMigrator`) mirroring
`DataTableOwnerColumnChange`: for every physical table of either pool (prefixes `dt_` and `edt_`
from `PhysicalTableNaming`), `ALTER TABLE … ADD COLUMN IF NOT EXISTS external_id VARCHAR(255)` and
the partial unique index of §6.1. The index is deliberately unnamed, so it cannot carry
`IF NOT EXISTS` (PostgreSQL requires a name for that) — idempotency comes from the migrator's
own `hasExternalIdColumn` guard. New tables get both from
`createPhysicalTable`, so there is still exactly one `CREATE TABLE` shape.

**Wiring.** `settings.gradle.kts` gains the server module and the CLI client module; `server-app`
depends on the new `-public-rest` module; the docs build's public-spec list gains the module — the
reference pages are generated from `*-public-rest/openapi.yaml` at build time, and a module left
off that list is silently absent.

**Behaviour a user can see**, each named here:

1. UI CSV export gains an `external_id` column.
2. `AutomationApiKeyAuthenticationProvider` rejects non-`AUTOMATION` keys; an admin key used
   against `/api/automation/v1` today stops working. Slice 1's decision, landing here first.
3. GraphQL data-table errors carry `errorKey` where they were bare messages. Additive.

**Risks.**

- Name-addressed URLs are stable only while nobody renames in the UI. The spec says so; a CLI
  `get` failing with 404 is the symptom.
- The backfill touches every physical data table in one changeset. `ADD COLUMN` with no default
  and a partial index over an all-NULL column are both metadata-only in PostgreSQL, so the cost is
  a brief `ACCESS EXCLUSIVE` lock per table, not a rewrite.

**Follow-ons named, not designed:** embedded connected-user surface; `where=` expression filter;
column type change; natural-key designation; table rename and duplicate if a consumer asks;
slice-1 alignment to the nested workspace path (§4.4).
