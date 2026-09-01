# Resource-Level Ownership Only — Design

**Status:** draft, awaiting review
**Supersedes parts of:** `2026-08-27-per-account-dt-kb-plan1-owner-model.md`
**Builds on:** `2026-08-29-embedded-automation-resource-pools-design.md` (the pool split is unaffected)

## Goal

Delete row-level ownership from data tables. A data table or knowledge base is owned by exactly one
connected user, or by nobody and therefore shared. Nothing below the resource carries an owner.

## Why

Two ownership levels exist today, in parallel:

| Level | Where | Effect |
|---|---|---|
| Resource | `data_table.owner_id`, `knowledge_base.owner_id` | the whole resource belongs to one CU, or is shared |
| Row | `owner_id` / `owner_type` columns on every physical data table | one shared table, rows filtered per CU |

Knowledge bases only ever had the resource level. Data tables grew both. The row level is where
essentially every defect found in the pool-split review lived — the trigger sample-output leak, the
unreachable shared write, the account selector whose description was true for reads and false for
writes, the webhook fan-out. Each was a separate place the same rule had to be re-enforced by hand.

Resource-level ownership needs enforcing in one place per resource: resolution. A caller either
resolves the resource or does not. There is no second question about what they may see inside it.

The row level is **unreleased** — neither `RowOwnerFilter` nor `DataTableOwnerColumnMigrator` appears in
`v0.31.4` — so no customer data carries row owners and this is a deletion, not a migration.

## Accepted cost

Separating a CU's content is cheap for KB and expensive for DT, and this design pays the expensive one:

- KB separates by a metadata filter (`METADATA_KNOWLEDGE_BASE_ID`) inside one shared vector store. A
  per-CU KB is a row and a filter value.
- DT separates by a physical Postgres table per `data_table` row. A per-CU table is a `CREATE TABLE`.

So `CU count × logical tables` physical tables. Postgres degrades in the tens of thousands, and the
`information_schema` scans that already run twice per data-table step get worse. There is no third
option: giving DT per-CU *logical* resources over one shared physical table requires a discriminator
column, and that discriminator is row-level ownership under another name.

This trade was raised twice and accepted deliberately. It is recorded here so that a future reader
finds a decision rather than an oversight. If CU counts reach the thousands, this is the design to
revisit first.

## The model

An owner is a connected user, or absent.

- **owner set** — the resource belongs to that CU. Only that CU's runs, and vendor runs that name the
  CU, resolve it.
- **owner unset** — the resource is shared. Every run resolves it.

Identical for data tables and knowledge bases.

### Resolution: the CU's own wins, shared is the fallback

A run for CU-42 naming `orders` resolves CU-42's `orders` if one exists, otherwise the shared `orders`,
otherwise fails.

This is deliberately the opposite of the cross-pool rule, where an ambiguous name fails closed. Across
pools, two matches mean a bug. Within a pool, two matches are the feature: the vendor ships one workflow
naming `orders`, and "give this customer their own orders table" becomes a drop-in override needing no
workflow edit. Failing on ambiguity would make per-CU resources useless.

A vendor run (no owner) resolves only shared resources unless it names an account — see below.

### Reaching a CU's resource from a vendor run

The existing `accountId` property on the data table actions survives, with its meaning changed: it no
longer filters rows, it selects **whose resource to resolve**. A vendor run naming account 42 resolves
CU-42's `orders`, falling back to shared.

A run that already belongs to a CU may not name a different one — the existing rule in
`DataTableUtils.effectiveOwner`, kept verbatim.

The property's description must be rewritten. Its current wording ("Leave empty to act on the records
shared with every account") describes row filtering and becomes wrong.

### Listing

Unchanged and already correct: an unowned resource is listed for everyone, an owned one only for its
owner, and a vendor run with no owner lists everything. This is the table-level behaviour that was
already specified and is not being narrowed.

## Schema

**Physical naming gains the owner.** `edt_<envId>_<baseName>` collides when two CUs each own an
`orders`. It becomes `edt_<envId>_<ownerId>_<baseName>`, with the shared form keeping the current
`edt_<envId>_<baseName>` so existing shared tables are untouched.

**Registry keys gain the owner:**
- `data_table`: `(name, platform_type)` → `(name, platform_type, owner_id)`
- `knowledge_base`: `(name, platform_type, environment)` → `(name, platform_type, environment, owner_id)`

Both need the partial-unique-index treatment for the NULL owner case, for the reason recorded in
`CLAUDE.md` under Variables: Postgres treats every NULL as distinct, so a non-partial unique index
including `owner_id` never fires for shared resources and two concurrent creates of the same shared
name both succeed.

**Row owner columns are removed** from the DDL in `DataTableServiceImpl` (both the create and duplicate
paths) along with their index, and `DataTableOwnerColumnMigrator` is deleted.

**Decided: orphaned row-owner columns are left in place on existing dynamic tables.** Dropping them
would be cleaner, but every worktree on this machine shares one Postgres, so a sibling worktree still
running the old code would break the moment the columns disappeared. Deleting
`DataTableOwnerColumnMigrator` stops new ones appearing; the existing ones are inert. They are invisible
to workflows either way — `RowQuerySqlBuilder` already keeps reserved columns unaddressable — so the
cost of leaving them is untidiness in dev databases, and the cost of dropping them is breaking a
colleague's running worktree.

No production database is affected: the columns are unreleased, so they exist only where someone ran
this branch.

## What gets deleted

- `RowOwnerFilter` in its entirety
- `DataTableUtils.selectionFilter`, `poolFor(RowOwnerFilter)`
- `DataTableRowServiceImpl.checkWritableOwner`
- The `shared` record property added for the vendor shared-write case — writes now go to whichever
  resource resolved, so there is nothing to disambiguate
- The trigger sample-output narrowing — a trigger reads the resource it resolved, and every row in it
  belongs to that resource's owner
- The row-owner parameter threaded through `DataTableRowService` (14 methods)
- `DataTableOwnerColumnMigrator`

## What changes

**Webhooks collapse.** A webhook hangs off a table and the table has exactly one owner, so the
per-registration owner columns, the owner on `DataTableWebhookEvent`, and the listener's delivery
predicate all reduce to: the table's owner is the registration's owner, implicitly. The pool scoping on
`findByName` stays — that is a pool concern, not an ownership one.

**N1 stops being deferrable.** `KnowledgeBaseVectorStore.createVectorStore` takes `knowledgeBaseId`
straight from workflow JSON and wraps it with no pool and no owner check. Under row filtering a missed
check leaked some rows; under this design the resource check is the *only* enforcement point, so a
missed check leaks the entire knowledge base. It is part of this work.

`createVectorStore` receives `(inputParameters, connectionParameters, embeddingModel)` and has no
context to resolve an owner from, so this requires threading a context through the `VectorStore`
cluster-element SPI, which every vectorstore component implements. That is the largest single piece of
this design and the one most likely to need its own task breakdown.

## Out of scope

- The embedded console reaching parity with automation (sub-project 2 — unchanged by this)
- Auto-provisioning a per-CU copy when a CU first touches a shared resource. Per-CU resources are
  created deliberately, through the existing `assignOwner` path on the embedded GraphQL facades.
- The pool split itself. Pools and ownership are orthogonal and both survive.

## Sequencing

All of this lands in the `worktree-resource-pools-server-split` worktree on top of the verified
pool-split commits, then the whole set is regrouped, then merged ff-only to `0_732`. No intermediate
merge.
