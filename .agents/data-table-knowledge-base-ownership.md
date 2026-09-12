# Data table & knowledge base ownership (embedded)

How two accounts sharing one data table or knowledge base are kept apart, and which tests prove it.

**Read this before touching `RowQuerySqlBuilder`, `DataTableRowServiceImpl`, `DataTableRef`,
`KnowledgeBaseVectorStoreWrapper` or `DataTableUtils.effectiveOwner`.** Every invariant below fails
silently: a mistake leaks one account's data to another with no exception, no failing test and no
compile error.

## One axis, and it is the only boundary

There is exactly one ownership axis: **the row**. In the EMBEDDED pool there is one physical table
per base name per environment (`edt_<envId>_<baseName>`), holding every account's rows, and one
knowledge base holding every account's chunks. Accounts are separated by an owner stamped on each
record.

An earlier design also gave a `data_table` or `knowledge_base` registry row an owner, which produced
a physical table per account (`edt_<envId>_<ownerId>_connecteduser_<baseName>`) and let two accounts
each hold an `orders`. That axis was removed —
`docs/superpowers/specs/2026-09-02-embedded-row-level-ownership-only-design.md`, which supersedes
`2026-09-01-data-table-two-ownership-axes-design.md`. Consequences accepted with it: a base name is
unique per pool again, so two accounts cannot each hold an `orders`; and a vendor cannot give one
account a private resource, because a resource private to a single account is no longer expressible.

**What this costs is defence in depth.** Under two axes, an owned physical table was a structural
barrier sitting behind the predicate. There is no second barrier now. The `WHERE` clause and the
vector-store filter are the entire separation, which is why the enforcement count has to stay at one
and why the gate below matters more than it looks.

## Ownership is connected-user only

`OwnerType` has exactly one constant, `CONNECTED_USER`; `Owner.connectedUser(long)` is its only
factory; `ConnectedUserOwnerResolver` (embedded-configuration-service) is the only `OwnerResolver`
implementation. An owner is always an account in the embedded pool, and automation never sets one.

The machinery around it looks more general than that, deliberately. `Owner` is a `(type, id)` pair,
`owner_type` is persisted beside `owner_id` as an INT ordinal, and both columns move together or
neither does. That is future-proofing, not present-day polymorphism: on the day a second `OwnerType`
is appended, two owners of different types sharing an id are two different owners, and a key or a
predicate built from the id alone would confuse them. Treat the pair as load-bearing even though
only one arm exists — and append new constants at the end, never reorder (ordinals are persisted).

A half-written pair is nobody's. `DataTableWebhook.getOwner()` throws rather than interpreting an
`owner_id` with a null `owner_type`, because reading it as "no owner" would silently turn an
account's registration into the vendor's.

## `DataTableRef` is the only way an owner reaches a row statement

```java
public record DataTableRef(
    String baseName, long environmentId, PlatformType platformType, @Nullable Owner runOwner)
```

`runOwner` is the account the run acts for. Null means a run with no owner — the vendor — which sees
unowned records only.

Every DDL and DML statement takes a ref rather than a base name, so a call site cannot supply a name
and let the callee guess an owner. This is what keeps the enforcement count at one. Two producers,
and the difference is the point:

- **`DataTableService.fetchDataTableResolution`** — resolution. This is the ONLY method on
  `DataTableService` that still takes an `Optional<Owner>`; every other one lost it with axis 1.
  Everything reachable from an EMBEDDED run comes through here.
- **`DataTableRef.unowned(baseName, environmentId, platformType)`** — an explicit claim that no run
  owner scopes this ref. Correct only where the caller knows that for certain: the AUTOMATION pool,
  and table creation. Using it on an EMBEDDED row path silently reads the vendor's records instead
  of the account's.

`DataTableServiceOwnerParameterTest` asserts the first bullet reflectively, so the parameter cannot
creep back one signature at a time.

## Reads and writes are different SQL, on purpose

In `RowQuerySqlBuilder`:

- `readableOwnerPredicate` → `(owner_id = ? OR owner_id IS NULL)`, or `owner_id IS NULL` alone for a
  run with no owner.
- `writableOwnerPredicate` → `owner_id = ?` alone.

Keep them two functions, never one with a flag. A vendor-seeded reference row is every account's to
read and nobody's to change; collapsing them either hides that row from readers or hands it to
writers. Inserts stamp both columns from the run owner, or leave both NULL.

## Knowledge bases: the same rule, in chunk metadata

`KnowledgeBaseVectorStoreWrapper` is the only route to the store, so it is the enforcement point,
exactly as `DataTableRef` is for rows. Read filter: `owner_id == <run owner> OR shared == true`; a
run with no owner gets `shared == true` alone. Writes and `delete(Filter.Expression)` take the owner
term alone.

**Two keys, not an absent one.** `METADATA_OWNER_ID` on an owned chunk, `METADATA_SHARED: true` on
an unowned one (`KnowledgeBaseConstants`). The obvious encoding — owned chunks carry the key,
unowned carry nothing — needs "key is absent" in a filter expression, which Spring AI's
`Filter.Expression` does not express portably across vector stores. The tag filter already hit this
wall and solved it the same way with per-tag boolean flags; mirror that rather than inventing a
second convention.

**Not tags.** Tags reach the same filter and look like the same mechanism, but a workflow step
chooses them: a step that omits them sees every chunk, and one naming another account's tag sees
that account's chunks. Tags are organization. Ownership is isolation, derived from who the run is
for and not nameable by the step. The two AND together and neither replaces the other.

## Pools are not ownership

`PlatformType` (AUTOMATION / EMBEDDED) separates two populations of tables that may share a base
name; the pool token lives in the physical name (`dt_` / `edt_`) as well as in
`data_table.platform_type`, and the two must never disagree. It survives every owner-related change
and is checked independently — `listTables` reconstructs a base name by prefix plus an explicit
`startsWith` guard, because `LIKE` treats `_` as a wildcard and `dt_1_` would otherwise match
`dt_12_`. Dropping a pool check lets an EMBEDDED ref reach an AUTOMATION table, which is as bad as a
cross-account read.

## The account selector on the component

The six data table row actions carry an optional `ACCOUNT_ID`. A vendor run may act for a named
account; a run that already belongs to one may not name a different one —
`DataTableUtils.effectiveOwner` enforces that, and with per-account tables gone it is the whole of
the isolation story for a vendor workflow.

The table dropdown and the column properties deliberately do NOT depend on `ACCOUNT_ID`: every
account sees the same tables with the same columns, so re-asking on account change costs a round
trip and can only return the same answer. The **sample-output lookup keeps the dependency** — a
sample row is still one account's row, and one built without the run owner would show an account a
row of another's.

Webhook delivery follows the same rule one level down: `listWebhooks` resolves by name and pool,
then filters on `Webhook#receivesRowsWrittenBy(runOwner)`. A registration carries the owner of the
run that made it, because keying on the table alone fires every registration on it for every row
written into it.

## The regression gate — and the tests that look like it but are not

These prove the axis. **If a change to ownership requires editing one of their assertions or fixture
values to stay green, the change is wrong, not the test.** Signature-only adaptation is fine.

| Test | Proves |
|---|---|
| `DataTableRowOwnerSourceGuardTest` | the primary gate: an owner reaches a row statement through `DataTableRef` and nothing else; no row operation takes an owner beside its ref |
| `DataTableRowOwnerScopingIntTest` | an account reads its own rows and unowned ones, never another's; writes touch its own alone |
| `DataTableWebhookRowOwnerIntTest` | one account's insert never reaches another's webhook; both owner columns stamped or neither |
| `DataTablePoolIsolationIntTest` | the pool split, at real-schema level, webhooks included |
| `OwnerTypeOrdinalStabilityTest` | `CONNECTED_USER` keeps ordinal 0 and nothing was inserted before it |
| `KnowledgeBaseDocumentOwnerScopingTest` | chunk-level separation inside a shared knowledge base |
| `KnowledgeBaseVectorStoreWrapperOwnershipTest` | the wrapper's read/write filters |
| `KnowledgeBaseVectorStoreOwnershipBackfillIntTest` | existing chunks stay visible under the filter |
| `KnowledgeBaseDocumentScopingTest` | document- and chunk-**id** guards: a run naming another account's document or chunk cannot delete, write to or load into it |

**Three near-misses, which are NOT gates.** Two rounds of this work were lost to reading their names
instead of their bodies:

- `DataTableRowPoolScopingIntTest` — despite the name, its own javadoc records that row-by-row
  scoping was moved out of it. It covers **bulk** read/write paths that walk a whole table. Real
  coverage, wrong file to treat as an ownership gate.
- `DataTableEditorAccountScopingTest` — the editor's lookups, not the row layer.
- `KnowledgeBaseComponentScopesByIdReadsTest` — a static scan with **file granularity**: a gate call
  anywhere in a file satisfies the whole file, even on a different branch than an unscoped read. It
  cannot prove the gate covers the id a second lookup uses. Documented in its own javadoc; do not
  mistake it for proof.

The general trap, which bit this area repeatedly: **a test that BUILDS its scenario from per-account
resources is not a test ABOUT per-account resources.** Before deleting one, ask whether ownership is
what it asserts or merely how it constructs the fixture. If the assertion survives with a different
fixture — one shared table with rows owned by different accounts, or two POOLS instead of two
accounts — rebuild it. Three deletions were reversed on exactly this ground.

## Development databases

`scripts/dev/cleanup-resource-owner.sql` repairs a database that ran the deleted axis-1 changesets:
it drops the leftover registry columns and indexes, drops owned physical tables and their registry
rows, restores `uk_data_table_name_platform_type` and
`uk_knowledge_base_name_platform_type_environment`, and clears the deleted changesets'
`databasechangelog` rows. It is idempotent and safe on a partially-migrated schema — which is the
normal state here, since every worktree shares one dev Postgres.
