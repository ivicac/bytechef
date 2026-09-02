# Two ownership axes for data tables and knowledge bases — implementation plan

> **For agentic workers:** implement task by task. Each task ends with a green build and a commit.

**Goal:** add row-level ownership to data tables AND knowledge bases **beside** the resource-level
ownership already built, so a connected user can own a whole table or knowledge base, *or* own the
rows and chunks inside a shared one.

**Spec:** `docs/superpowers/specs/2026-09-01-data-table-two-ownership-axes-design.md`. Read it
first — especially "How the axes interact", which is why nothing branches.

**This plan is purely additive.** Nothing built for resource-level ownership is removed. If you
find yourself deleting a registry owner column, a physical naming form, an `assignOwner`, or a
console owner control, you have misread the plan — stop and re-read the spec.

**Reference for the SQL only:** `d16f95a87cc^` holds a previous row-level implementation, deleted
earlier on this branch. Its `RowQuerySqlBuilder` and `DataTableRowServiceImpl` are the authority on
the predicate SQL. Its *shape* is the anti-pattern — see Global Constraints.

## Global Constraints

- **Both owners reach SQL only from `DataTableRef`.** No row operation gains an owner parameter,
  and `RowOwnerFilter` is not reintroduced. It produced sixteen methods and six pieces of shared
  plumbing, each independently omittable. If an operation needs an owner it does not have, its
  caller resolved the wrong ref.
- **Knowledge bases get the same two axes**, in Task 4, through their own chokepoint — the vector
  store wrapper. Tasks 1-3 touch no knowledge base file and Task 4 touches no data table file; the
  two are independent and may run at the same time.
- **The pool split is not touched.** `platform_type` and `poolFor(owner)` stay as they are.
- Java style: blank line before control statements; blank line after a variable modification then
  used; no trailing blank line before a class's closing brace; descriptive names.
- EE files carry the Enterprise header and `@version ee`.
- Commits: `732 <description>` server, `732 client - <description>` client.
- Stage only files you changed. Never `git stash` — shared across worktrees here.
- Never amend an existing commit.
- Gradle: redirect to a file, check `$?` on its own line, grep `^> Task .* FAILED`. A task reported
  `UP-TO-DATE` proves nothing; use `--rerun` when you need evidence a test ran.
- A `--` inside an XML comment in a Liquibase changelog is a parse error only `testIntegration`
  catches. It has bitten three times on this branch.

---

## Task 1 — `DataTableRef` gains the second owner

Smallest first, because everything else consumes it.

**Files:**
- `platform-data-table-api/.../domain/DataTableRef.java`
- every construction site (`DataTableServiceImpl.fetchDataTableResolution`, `shared(...)` callers)
- `platform-data-table-service/src/test/.../domain/DataTableRefTest.java`

Rename the existing `owner` component to `resourceOwner` and add `runOwner`, both
`@Nullable Owner`. `resourceOwner` keeps every current behaviour — it is what
`PhysicalTableNaming.buildPhysicalName` receives and what `physicalName()` uses. `runOwner` is new
and is not yet read by anything; Task 2 consumes it.

Add to the compact constructor:

```java
Assert.isTrue(
    resourceOwner == null || resourceOwner.equals(runOwner),
    "A ref addressing an owned table must be held by a run acting for that owner");
```

`shared(...)` passes null for both. `fetchDataTableResolution` passes the registry row's owner as
`resourceOwner` and the run's owner as `runOwner` — it already has both in hand.

`ownerId()` currently returns the resource owner's id. Split it into `resourceOwnerId()` and
`runOwnerId()` rather than leaving one method whose meaning a caller must guess; update callers.

**Tests:** the invariant refuses a mismatched pair (watch it fail with the assert removed); a
shared ref carries neither owner; an account's own table carries both and they agree; an account on
the shared table carries a run owner and no resource owner. Keep the identifier-length test.

**Commit.**

---

## Task 2 — physical columns and the row predicates

**Files:**
- the DDL paths in `platform-data-table-service` (`DataTableServiceImpl` create; the column
  migrator/alter path)
- `platform-data-table-service/.../execution/service/DataTableRowServiceImpl.java`
- `.../execution/service/RowQuerySqlBuilder.java`
- `platform-data-table-api/.../domain/ReservedColumns.java` (javadoc only — see below)

**2a. Columns.** Every physical data table gains `owner_id BIGINT NULL` and `owner_type INT NULL`,
plus an index on `owner_id`. Uniform across owned and shared tables — the spec explains why there
is no special case. Existing physical tables need an `ALTER TABLE ... ADD COLUMN` sweep over
`information_schema` for both pool prefixes.

`ReservedColumns` already declares both constants and already hides them from callers; that
machinery is correct and needs no code change. Its javadoc says the columns are "no longer
created", which becomes false — rewrite that sentence.

**2b. Predicates**, from the spec and `d16f95a87cc^`:

- reads (`getRow`, `listRows` ×2, `exportCsv`): `(owner_id = ? OR owner_id IS NULL)`; with no run
  owner, `owner_id IS NULL`
- writes (`updateRow`, `deleteRow`): `owner_id = ?`; with no run owner, `owner_id IS NULL`
- inserts (`insertRow`, `importCsv`): stamp `owner_id` **and** `owner_type` from the run owner, or
  leave both NULL. Both columns move together — an `owner_id` with a null `owner_type` matches no
  predicate and belongs to nobody.

All eight public operations keep their signatures and read `runOwner` off the ref they already
take.

**Tests** — every assertion watched failing first:

- an account reads its own rows and unowned ones, not another account's
- an account's update and delete do not touch an unowned row
- a vendor run reads and writes unowned rows only, never falling through to an account's
- an insert with no owner produces an unowned row; one with an owner stamps both columns
- in an *owned* table the predicate is satisfied and rows are stamped with that owner — this pins
  the no-special-case claim
- **the ref is the only source guard**: no row operation signature takes an owner, and no
  `RowOwnerFilter` exists. The most important test here
- the pool split still holds

Drafts of the last two exist at
`/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/09b12164-0c9a-4c6b-9def-e6cd4afcaf1f/scratchpad/rowlevel-draft/`
— written against a single-owner ref, so they need adapting, but the structure is sound.

**Commit.**

---

## Task 3 — surface the row owner where a run gets one

**Files:** `server/libs/modules/components/data-table/.../util/DataTableUtils.java` and the action
and cluster-element classes around it.

The component resolves a ref through `fetchDataTableResolution`, which now needs the run's owner
for `runOwner`. It already derives an owner via `OwnerResolution` for the pool; confirm the same
value reaches `runOwner` and that an editor test run scopes to the connected user driving it.

No new component parameter. A step never names an owner — that is the whole point.

**Tests:** an embedded run's rows are its own; a vendor run's are the unowned ones. Extend the
existing component scoping tests rather than adding a parallel set.

**Commit.**

---

## Task 4 — knowledge base chunk ownership

Independent of Tasks 1-3: different modules, no shared files. May run alongside them.

**Files:**
- `platform-knowledge-base-api/.../constant/KnowledgeBaseConstants.java`
- `knowledgebase` component: `util/KnowledgeBaseVectorStoreWrapper.java`,
  `util/KnowledgeBaseVectorStore.java`, `destination/KnowledgeBaseItemWriter.java`,
  `action/KnowledgeBaseSearchAction.java`, `cluster/KnowledgeBaseSearchTool.java`
- the document-processing path that writes chunks

Read the spec's "Knowledge bases" section first. It settles the encoding, the write asymmetry and
the backfill, and each of those is a decision you should not re-make.

**4a.** Add `METADATA_OWNER_ID = "owner_id"` and `METADATA_SHARED = "shared"` beside the existing
metadata constants.

**4b.** `KnowledgeBaseVectorStoreWrapper` takes the run's `Owner` (nullable) alongside the
`knowledgeBaseId` and `tagNames` it already takes. It is the single chokepoint; nothing outside it
applies an owner filter.

- `add`: stamp `owner_id` when the run has an owner, `shared: true` when it does not
- `similaritySearch`: AND `(owner_id == <run owner> OR shared == true)` onto the existing combined
  filter; with no run owner, `shared == true` alone
- `delete(Filter.Expression)`: AND `owner_id == <run owner>` — the write predicate, deliberately
  narrower than the read one
- `delete(List<String>)`: currently unscoped. Resolve each id and refuse chunks outside scope, or
  route through the expression form. Do not leave it as it is

**4c.** The owner reaches the wrapper the way the pool already does — from `OwnerResolution`, at
the step that constructs it. No new component parameter; a step never names an owner.

**4d. Backfill.** Every existing chunk gets `shared: true`. Without it the read filter matches
nothing and every existing document disappears. This lands with the filter, in the same commit.

**Tests**, every assertion watched failing first:

- an account's search returns its own chunks and shared ones, not another account's
- a vendor run's search returns shared chunks only, never falling through to an account's
- an account cannot delete a shared chunk, by expression or by id
- an account cannot delete another account's chunk by id — pin this one specifically; it is the
  hole the id-list overload leaves open today
- a chunk written by a run with no owner is `shared`, and one written with an owner is not
- tags and ownership AND together: a step naming another account's tag still sees nothing of theirs
- backfilled chunks remain visible to everyone

**Commit.**
