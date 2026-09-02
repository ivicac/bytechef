# Data tables: two ownership axes

> **Superseded by `2026-09-02-embedded-row-level-ownership-only-design.md`.** Axis 1, the
> resource owner, was removed: embedded keeps row-level ownership alone. Axis 2 below is
> still what is built. Kept for the same reason this file already keeps the record of its
> own abandoned revision -- a reader tracing the branch deserves to see that the shape
> changed deliberately rather than by drift.

Supersedes nothing. **Extends** `2026-08-31-resource-level-ownership-only-design.md` rather than
replacing it: resource-level ownership stays exactly as built, and row-level ownership is added
beside it.

An earlier revision of this file proposed row-level *only*, removing resource ownership. That was
wrong and is not what is being built. It is recorded here because the branch briefly moved that
way and a reader finding the abandoned work in the reflog deserves to know it was deliberate and
then reversed.

## The model

Two independent axes, both live.

**Axis 1 — the resource.** A `data_table` registry row may carry an owner.

| registry `owner_id` | physical table | who sees it |
|---|---|---|
| `42` | `edt_<env>_42_connecteduser_orders` | account 42 alone |
| `NULL` | `edt_<env>_orders` | every account in the pool |

Resolution is owned-wins-shared-fallback: an account with its own `orders` gets it, an account
without falls back to the shared one. A run with no owner resolves only shared tables and never
falls through to an account's. Unchanged from what is already built and tested.

**Axis 2 — the row.** Every physical table carries `owner_id` / `owner_type` columns.

- **Reads** see the run's rows plus unowned ones: `(owner_id = ? OR owner_id IS NULL)`.
- **Writes** touch the run's rows only: `owner_id = ?`.
- **Inserts** stamp the run's owner, or leave both columns NULL when it has none.
- A run with no owner reads and writes `owner_id IS NULL`.

Reads and writes are genuinely different SQL. Keep them two functions, not one with a flag: a
vendor-seeded reference row is every account's to read and nobody's to change.

## How the axes interact

The two are orthogonal, and deliberately so — there is no special case anywhere.

- Resolution lands on the **shared** table: the row predicate is what separates accounts. This is
  the case row-level ownership exists for.
- Resolution lands on an **owned** table: every row in it already belongs to that account, so the
  predicate is satisfied by construction and costs an index lookup. Inserts stamp the same owner
  the table name carries. Nothing branches.

That the owned case needs no special handling is the argument for this shape. A design where the
predicate applied only to shared tables would need every row operation to know which kind it had.

## `DataTableRef` carries two owners

This is the load-bearing decision.

```java
public record DataTableRef(
    String baseName, long environmentId, PlatformType platformType,
    @Nullable Owner resourceOwner,   // picks the physical table
    @Nullable Owner runOwner)        // picks the rows
```

`resourceOwner` is today's `owner` field, renamed. It comes off the registry row resolution
settled on and goes into the physical name.

`runOwner` is the account the run acts for and binds into the row predicate.

**One field cannot express this.** An account reading the shared table has no resource owner and a
run owner; an account reading its own table has both; a vendor has neither. Collapsing them means
the shared-table case cannot say who is asking, which is precisely the cross-account read this is
meant to prevent.

**The invariant, asserted in the compact constructor:**

```
resourceOwner == null || resourceOwner.equals(runOwner)
```

A ref addressing an account's own physical table must be held by a run acting for that account.
Resolution already guarantees it — a vendor run resolves only shared tables — so this is a
statement that resolution cannot be bypassed, and it makes a whole class of cross-account bug
impossible to construct rather than merely absent.

`shared(baseName, environmentId, platformType)` stays, meaning both owners null: the vendor.

## Knowledge bases

Both axes, same as data tables. A knowledge base belongs to one account, or is shared with per-chunk
ownership inside it.

**Axis 1** is already built: a `knowledge_base` registry row may carry an owner.

**Axis 2** puts the owner in the chunk metadata, filtered in
`KnowledgeBaseVectorStoreWrapper` -- the same place that already binds `knowledge_base_id` and the
tag filter. That wrapper is the only route to the store, so the enforcement count is one, exactly
as `DataTableRef` is for data tables.

### Not tags

Tags reach the same filter and look like the same mechanism, but they are chosen by the workflow
step:

```java
List<String> tagNames = inputParameters.getList(TAG_NAMES, String.class);
```

A step that omits them sees every chunk; a step that names another account's tag sees that
account's chunks. Tags are organization. This is isolation, derived from who the run is for and not
nameable by the step. The two AND together and neither replaces the other.

### A boolean, not an absent key

Two metadata keys: `owner_id` on an owned chunk, `shared: true` on an unowned one.

The obvious encoding -- owned chunks carry `owner_id`, unowned carry nothing -- needs "key is
absent" in a filter expression, which Spring AI's `Filter.Expression` does not express portably
across vector stores. This codebase already hit the same wall for tags and solved it the same way,
with per-tag boolean flags (`tag_names_NAME: true`). Mirror that rather than inventing a second
convention.

Read filter: `owner_id == <run owner> OR shared == true`. A run with no owner gets `shared == true`
alone.

### Write asymmetry, and the id-list delete

`delete(Filter.Expression)` takes the **write** predicate -- `owner_id == <run owner>` alone. A
vendor's shared chunk is every account's to read and nobody's to delete, matching the data table
rule.

`delete(List<String> idList)` currently passes ids straight to the store with no filter at all, and
a filter expression cannot be applied to an id list. It must resolve each id and refuse any chunk
outside the caller's scope, or be routed through the expression form. Left as it is, it is a
cross-account delete by id -- the same shape as the hole found and closed earlier on this branch.

### The backfill is not optional

Existing chunks carry neither key, so under the read filter above they match nothing and every
document already in the store becomes invisible. A backfill setting `shared: true` on every
existing chunk has to land with the filter, not after it.

## Migration

Physical data tables are created by runtime DDL, not Liquibase, so no changeset adds the row
columns. The DDL that creates a table gains them, and existing physical tables need an
`ALTER TABLE ... ADD COLUMN` sweep — every table in `information_schema` matching the pool prefix,
both owned and shared, since the columns are uniform.

The registry changesets already on this branch (`20260827000001_platform_data_table_add_owner`,
`20260831000002_platform_data_table_owner_unique_index`) stay. They are axis 1 and remain correct.

`ReservedColumns` already declares `OWNER_ID` and `OWNER_TYPE` and already hides them from
callers — machinery left in place when row-level ownership was removed. Its javadoc says the
columns are "no longer created"; that sentence becomes false and must be rewritten.

## Testing

Negative assertions throughout: an account must be shown *not* to see another's rows.

Row axis, in the shared table:
- an account reads its own rows and the unowned ones, and not another account's
- an account's update and delete do not touch an unowned row
- a vendor run reads and writes unowned rows only, never falling through to an account's
- an insert with no owner produces an unowned row; one with an owner stamps **both** columns

Interaction:
- in an owned table, the predicate is satisfied and rows are stamped with the table's own owner
- the ref invariant refuses a `resourceOwner` that disagrees with the `runOwner`

Structural:
- **the ref is the only source of both owners** — no row operation signature takes an owner, and
  no `RowOwnerFilter`-style parameter exists. This is what keeps the enforcement count at one, and
  it is the test most worth having
- the pool split still holds: an EMBEDDED ref never reaches an AUTOMATION table
