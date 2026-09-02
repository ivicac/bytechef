# Embedded data tables and knowledge bases: row-level ownership only

Supersedes `2026-09-01-data-table-two-ownership-axes-design.md`. That design built two independent
ownership axes and argued they were orthogonal. They are, and the argument still holds; what
changed is the product decision underneath it. Embedded keeps one axis.

## What is removed

**Axis 1, the resource owner.** A `data_table` or `knowledge_base` registry row may carry an owner
today, which gives that account a physical table of its own at
`edt_<env>_<ownerId>_connecteduser_<base>` and lets two accounts each hold an `orders`. All of it
goes: the columns, the changesets, the owner segment in the physical name, `assignOwner`, and the
console controls that drove it.

**Axis 2, the row owner, is untouched.** Every physical table keeps its `owner_id` / `owner_type`
columns; reads stay `(owner_id = ? OR owner_id IS NULL)`, writes stay `owner_id = ?`, inserts stamp
the run's owner or leave both NULL. Knowledge base chunks keep `owner_id` / `shared: true` in their
metadata and the filter in `KnowledgeBaseVectorStoreWrapper`. This is the axis that isolates
accounts from one another, and after this change it is the only one.

Removing axis 1 "for embedded" is removing it outright. `ConnectedUserOwnerResolver` is its sole
producer -- automation never sets a resource owner -- so there is no second consumer to keep the
machinery alive for. Leaving the columns dormant would leave a persisted concept nothing writes and
nothing reads, which is worse than either state.

## The model after

One physical table per base name per environment per pool, holding every account's rows, separated
by the row predicate. A base name is unique per pool again.

| | before | after |
|---|---|---|
| physical name | `edt_<env>_<base>` or `edt_<env>_<ownerId>_connecteduser_<base>` | `edt_<env>_<base>` |
| two accounts, same name | two tables | one table, rows separated by owner |
| registry key | `(name, platform_type, owner_id, owner_type)` + partial index for shared | `(name, platform_type)` |
| what isolates accounts | the table for owned, the row predicate for shared | the row predicate, always |

The case the two-axes design called "resolution lands on the shared table" is now the only case.
The argument that made two axes defensible -- that the owned case needs no special handling because
its predicate is satisfied by construction -- was also the argument that the owned case earns
nothing. Whatever it bought is bought by a `WHERE` clause that runs regardless.

## `DataTableRef` carries one owner

```java
public record DataTableRef(
    String baseName, long environmentId, PlatformType platformType, @Nullable Owner runOwner)
```

`resourceOwner` and `resourceOwnerId()` are deleted. The compact constructor's invariant --
`resourceOwner == null || resourceOwner.equals(runOwner)` -- goes with them: it related two fields
and is vacuous with one.

The surviving field stays named `runOwner`, not `owner`. Rows carry owners too, and on a type whose
whole job is to reach row statements, a bare `owner()` would leave a reader guessing which of the
two they hold. The name is the last thing keeping the distinction visible now that the type no
longer holds both.

`shared(baseName, environmentId, platformType)` is renamed `unowned(...)`. Its old name asserted two
things -- an unowned table and unowned rows -- and only the second survives. Callers are the
AUTOMATION pool and the creation path.

The identifier-length check stays. Its stated rationale ("the owner the name carries is what pushes
a name over") stops being true, but a long base name can still exceed 63 bytes and Postgres still
truncates rather than refusing. The check is kept and its comment rewritten to say so.

The class javadoc is rewritten rather than edited. Its thesis is that a ref is what stops a caller
pairing a base name with an owner of its own choosing and thereby reaching another account's
*table*. No such table exists after this change. The ref remains the single place a row operation
learns whose run it is, which is a smaller claim and the one that is now true.

## `PhysicalTableNaming` loses the owner segment

`buildPhysicalName(platformType, environmentId, baseName)` and `prefix(platformType, environmentId)`.
`ownerTypeToken(OwnerType)` and `ownerType(String)` are deleted, and with them the reason the base
name grammar `[a-z_][a-z0-9_]*` had to forbid a leading digit in order to keep owned and shared
names from colliding. The grammar itself stays -- it is also the SQL identifier allowlist -- but its
justification narrows.

`DataTableServiceImpl`'s physical-name parse regex loses its optional owner group. `listTables`
reconstructs a base name from `information_schema` by stripping the pool and environment prefix and
nothing else.

## Service signatures: split the parameter by the job it was doing

`Optional<Owner>` on `DataTableService` currently answers two questions in one argument -- *which
registry row do I resolve* (axis 1) and *which rows may I touch* (axis 2). Each method keeps the
parameter only if it was asking the second question.

| method | `Optional<Owner>` |
|---|---|
| `createTable`, `dropTable`, `duplicateTable`, `renameTable`, `addColumn`, `removeColumn`, `renameColumn`, `fetchDataTable`, `listTables` | removed |
| `fetchDataTableResolution` | kept; meaning narrows to the run owner bound into the row predicate |
| `assignOwner` | deleted |

`DataTableServiceImpl`'s private `register` loses its owner argument, and `DataTable.ownerId` /
`ownerType`, `ownerOf()`, `isReadableBy()` and `findOwnedDataTable()` are deleted. The
`fetchDataTable(DataTableRef)` overload already takes no owner and is unchanged.

`KnowledgeBaseService` takes the same treatment. `getKnowledgeBase(id, platformTypes, owner)`, both
`getKnowledgeBases` overloads and `fetchKnowledgeBase(name, environment, platformType, owner)` lose
the parameter -- a name now resolves to one knowledge base. `assignOwner`,
`KnowledgeBaseOwnerAssignedEvent`, `KnowledgeBaseOwnerAssignedListener` and the document
re-stamping they drive are deleted. `KnowledgeBase.ownerId` / `ownerType` are deleted.
`KnowledgeBaseDocument.ownerId` is axis 2 and stays.

`RemoteDataTableServiceClient` follows the interface.

## The account selector on the component narrows

`ACCOUNT_ID` on the six row actions stays. A vendor run may still act for a named account; that now
chooses rows rather than a table. `effectiveOwner`'s guard -- a run already belonging to an account
may not name a different one -- stays, and is now the whole of the isolation story for a vendor
workflow.

Two lookup dependencies are dropped because their answers no longer vary by account:
`optionsLookupDependsOn(ACCOUNT_ID)` on the table dropdown, and `ACCOUNT_ID` in
`propertiesLookupDependsOn(TABLE, ACCOUNT_ID)` for the column properties. Every account sees the
same tables with the same columns.

The sample-output lookup keeps `ACCOUNT_ID`. A sample row is still one account's row, and a sample
built without the run owner would show one account a row of another's -- which was true under two
axes and remains true under one.

## Embedded GraphQL

Both schemas shed the same four things, and the two consoles are their only callers:

- the `ownerId` argument on `embeddedDataTables` / `embeddedKnowledgeBases`
- `assignEmbeddedDataTableOwner` / `assignEmbeddedKnowledgeBaseOwner` and their input types
- `ownerId` on `CreateEmbeddedDataTableInput` / `CreateEmbeddedKnowledgeBaseInput`
- `ownerId` on `EmbeddedDataTable` / `EmbeddedKnowledgeBase`

`UpdateEmbeddedKnowledgeBaseInput` is unchanged, but its schema comment explaining why the owner is
deliberately absent from it describes a mutation that no longer exists and is deleted.

The facades, their impls and both GraphQL controllers follow. Client operations are edited and
`graphql.ts` / `graphql-types.ts` regenerated with `npx graphql-codegen`, committed separately from
the operations per the repo convention.

## Console UI

Both embedded pages become plain per-environment lists, the same shape as their automation
counterparts. The page-level owner filter, the per-row assign control and the owner picker in both
create dialogs are removed; `OwnerSelect.tsx` and `useEmbeddedConnectedUsers.ts` are deleted, having
no other importer.

`DataTableScopeType` becomes `{type: 'WORKSPACE'; workspaceId: number} | {type: 'EMBEDDED'}`, and
the knowledge base scope type likewise. `useDataTables` and `useKnowledgeBases` lose the `ownerId`
plumbing; `CreateDataTableDialog`, `CreateKnowledgeBaseDialog` and their hooks lose the picker and
the `connectedUsers` prop.

The empty-list copy ("tables you create appear here, where you can assign them to an account") is
rewritten.

No row-level affordance replaces the removed controls. The embedded console has no row or document
detail route to hang one on, so an account selector that survived would filter nothing. An
account-scoped row browser is a separate feature and is not in this scope.

## Schema and migration

None of axis 1 is in `origin/master` -- `git merge-base --is-ancestor` refuses every ownership
commit from `d71447a64d6` onward -- so it has shipped to nobody and the changesets are deleted
rather than reversed. A fresh database never grows the columns; the branch's own history is the
only place they existed.

The changelog directories are wired with `<includeAll>` in
`server/libs/config/liquibase-config/src/main/resources/config/liquibase/master.xml`, so deleting
a file removes it -- there are no `<include>` lines to edit, and the extracted changeset below is
picked up by filename order. Stale copies under `build/resources/` must be deleted too, or
Liquibase sees both the old and the new set on the classpath.

Deleted:

- `20260827000001_platform_data_table_add_owner.xml`
- `20260831000002_platform_data_table_owner_unique_index.xml`
- `20260827000001_platform_knowledge_base_add_owner.xml`
- `20260831000001_platform_knowledge_base_owner_unique_index.xml`

**Deleting the knowledge base index file is not a clean revert, and this is the one trap in the
migration.** That file carries a fifth changeset, `-4`, which drops
`uk_knowledge_base_name_environment`. That constraint arrived from 0_732 independently of
ownership, keys on `(name, environment)` with no `platform_type`, and left standing it forbids the
same knowledge base name existing in both the AUTOMATION and EMBEDDED pools. It has nothing to do
with owners and must survive. It is extracted verbatim into
`20260902000001_platform_knowledge_base_drop_name_environment_unique.xml`; its
`uniqueConstraintExists` precondition makes it idempotent, so a database that already ran the
original simply `MARK_RAN`s the new one.

Deleting the two index files also means `uk_data_table_name_platform_type` and
`uk_knowledge_base_name_platform_type_environment` are never dropped, which restores the
pre-ownership registry key without a changeset saying so.

Databases that already ran the deleted changesets keep orphan columns, orphan owned physical tables
and registry rows pointing at physical names that no longer resolve. They are development
databases only. `scripts/dev/cleanup-resource-owner.sql` repairs them, following
`cleanup-synthetic-deployment-orphans.sql` as precedent: drop the owner columns and indexes where
present, re-add the two unique constraints where missing, and drop every
`edt_%_connecteduser_%` physical table together with its registry row. Owned rows are dropped, not
merged into the shared table -- an owned and a shared table of the same base name can have
divergent column sets, and no shipped data is at stake.

## What survives untouched

Listed because the value of this change depends on it: these are axis 2 and must still pass
unchanged.

`Owner`, `OwnerType`, `OwnerResolver`, `ConnectedUserOwnerResolver`, `DataTableOwnerResolverGuard`,
`KnowledgeBaseOwnerResolverGuard`, `ReservedColumns.OWNER_ID` / `OWNER_TYPE`,
`DataTableOwnerColumnMigrator` and `DataTableOwnerColumnChange`, `RowQuerySqlBuilder`'s read and
write predicates, `DataTableWebhook.ownerId`, `KnowledgeBaseDocument.ownerId`, the chunk `owner_id`
/ `shared` metadata, `KnowledgeBaseVectorStoreWrapper`'s filter and
`KnowledgeBaseVectorStoreOwnershipBackfill`.

`DataTableWebhook`'s owner is worth calling out. Its javadoc explains that it once carried none, on
the premise that the table it hangs off has exactly one owner, and that row-level ownership falsified
that premise. After this change the shared table is the only kind there is, so the webhook owner is
load-bearing in every case rather than in one. The field is unchanged; the paragraph is rewritten.

## The javadoc is part of the work

`DataTableRef`, `PhysicalTableNaming`, `DataTableService`, `KnowledgeBaseService`,
`DataTableWebhook`, `DataTableUtils` and both unique-index changesets carry multi-paragraph
arguments for why two accounts each need an `orders` table. Those arguments become false, not
stale. Rewriting them is a share of the work rather than a pass at the end, and the plan schedules
it that way. A comment that argues for a design the code no longer has is worse than no comment:
the next reader trusts it.

## Testing

Deleted, being axis 1 alone: `DataTableOwnershipTest`, `DataTableOwnerUniqueIndexIntTest`,
`DataTableOwnerResolutionTest`, `DataTableEmbeddedOwnedTableIntTest`,
`DataTableListTablesOwnedPhysicalTableIntTest`, `DataTableWebhookOwnedTableIntTest`,
`KnowledgeBaseOwnershipTest`, `KnowledgeBaseOwnerUniqueIndexIntTest`,
`KnowledgeBaseAssignOwnerIntTest`, `EmbeddedKnowledgeBaseCreateOwnerResolutionTest`.

Trimmed, being both axes: `DataTableRefTest`, `PhysicalTableNamingTest`,
`DataTableEmbeddedActionTest`, `DataTableEditorAccountScopingTest`,
`DataTableServiceImplBaseNameTest`, `EmbeddedDataTableApiFacadeCreateTest` (its
`testCreateGoesIntoTheEmbeddedPool` is about pool routing and survives; the two owner cases go),
`EmbeddedDataTableApiFacadeTest`, `EmbeddedKnowledgeBaseApiFacadeTest`,
`KnowledgeBaseComponentScopesByIdReadsTest`.

Kept and expected to pass unmodified -- the regression suite for this change:
`DataTableRowOwnerScopingIntTest`, `DataTableWebhookRowOwnerIntTest`,
`DataTableRowPoolScopingIntTest`, `OwnerTypeOrdinalStabilityTest`,
`KnowledgeBaseDocumentOwnerScopingTest`, `KnowledgeBaseVectorStoreWrapperOwnershipTest`,
`KnowledgeBaseVectorStoreOwnershipBackfillIntTest`. If any of them needs editing, the row axis has
been disturbed and that is a defect in the change rather than in the test.

Two new structural tests, in the spirit of the two-axes design's "the ref is the only source of
both owners":

- no physical name a `DataTableRef` can build contains an owner segment, for any owner
- no `DataTableService` DDL method accepts an `Owner`, asserted reflectively, so the parameter
  cannot creep back one signature at a time

Client tests follow their components: `EmbeddedDataTables.test.tsx`, `useDataTables.test.tsx`,
`useCreateDataTableDialog.test.tsx`, `CreateDataTableDialog.test.tsx`, the knowledge base
equivalents and `KnowledgeBases.test.tsx`.

## Consequences accepted

- **Two accounts cannot hold tables of the same name.** The second create fails on
  `uk_data_table_name_platform_type`. This is the feature being removed, stated as its cost.
- **A vendor cannot give one account a private table.** Everything is one shared table with per-row
  isolation. A resource genuinely private to one account is no longer expressible.
- **Row isolation becomes single-layer.** Under two axes an owned table was a second, structural
  barrier behind the predicate. The predicate is now the only thing between two accounts' data,
  which raises the stakes on `RowQuerySqlBuilder` and on the chunk filter, and is why the kept
  tests above must pass untouched.
