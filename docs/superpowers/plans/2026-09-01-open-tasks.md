# Open tasks

State as of `baa5cb77d1b`. Supersedes the open items in
`2026-08-31-open-follow-ups.md`; that file stays as the record of what was closed and why.

Ordered by what blocks what, not by severity. Severity is in the right-hand column.

---

## A. Finishing the two-axis feature

### A1. Component owner wiring for data tables — IN FLIGHT

Task 3 of `2026-09-01-data-table-row-level-ownership-plan.md`. A data table step in a running
workflow resolves a ref with no `runOwner`, so it reads only unowned rows and stamps nothing on
insert. Until this lands the data table row axis does nothing from a workflow.

**Blocks:** nothing else, but the DT half of the feature is not usable without it.

### A2. Knowledge base ingestion does not stamp an owner — FIXED

**Fixed** in `d5b32ab0941`. `knowledge_base_document` carries `owner_id` and `owner_type`, written
at creation by the source-sync writer (from the owner its admission gate already resolved), by the
console upload facade (from the current principal), and by the component's own `load`. The chunker
reads the pair back off the row and the chunk metadata carries it, so the account survives the gap
between the request and the asynchronous chunking. Existing documents carry no owner and keep
producing shared chunks, which needs no backfill.

The end-to-end assertion is `KnowledgeBaseChunkOwnershipE2EIntTest`: a document an account uploaded
produces chunks that account finds and another account and the vendor do not, through the real
worker, a real `PgVectorStore` and the real wrapper.

The rest of this entry is the record of the defect. Chunks written by `KnowledgeBaseItemWriter` and
by console uploads carried `shared: true` even when an account drove the upload, because the chunker
runs asynchronously with no principal left to ask. Search filtering was correct and nothing leaked,
but nothing ever *produced* an owned chunk, so the knowledge base row axis was inert end to end.

### A5. A knowledge base document's owner is not consulted where its knowledge base is — FIXED

**Fixed.** `KnowledgeBaseDocument` now answers the question the knowledge base used to
(`isReadableBy` / `isWritableBy`), and all three sites ask it. The rule is the one the rest of the
two-axis design uses, stated on those two methods: a read reaches the caller's documents plus the
unowned ones, a write or delete reaches the caller's alone, and a run with no owner is the vendor
and reaches the unowned documents only, never falling through to an account's.

- `requireKnowledgeBaseDocument` is gone, split into `requireReadableKnowledgeBaseDocument` and
  `requireWritableKnowledgeBaseDocument` so a call site has to say which of the two it is. Both
  report an owner refusal as `KnowledgeBaseDocumentNotFoundException` with the same message a
  missing row produces, which `KnowledgeBaseDocumentOwnerScopingTest` pins directly rather than
  leaving to the javadoc.
- `tombstoneUnseen` and the tombstoned-document listing are each two owner-explicit repository
  queries rather than one keyed on `source_id`. The sync writer flushes the run's owner to the step
  `ExecutionContext` beside `seenRecordIds`, which is the only thing that survives the crossing into
  the job listener — the same gap the document row solves for the chunker.
- `sweepTombstonedDocumentChunks(sourceId, owner)` takes the owner through to the listing, since it
  deletes what that listing returns by raw vector-store id and has nothing below it that could
  refuse.

A fourth site went with them, unlisted above because it was found by fixing the other three rather
than by the original sweep: the document picker listed every document in the admitted knowledge base,
which in a shared one named another account's documents before any step ran. It takes the read rule.
Leaving it would have made the chunk picker's gate beside it look arbitrary, which is how the next
instance of this becomes invisible.

Both owner columns move together throughout: the pair is written by `setOwner` alone, read back as a
pair, and a half-written pair belongs to nobody and satisfies neither predicate — asserted in SQL by
`testTombstoneUnseenReapsNeitherHalfOfAHalfWrittenOwner` and in the listener by
`testAfterJobTreatsAnOwnerIdWithoutATypeAsNoOwner`.

**One consequence, since closed by A6.** A document created before `owner_id` existed carries no owner,
so an account could read it and not rewrite it even in a knowledge base assigned to that account.
Treating an unowned document in an owned knowledge base as that account's was rejected then as a
special case the design does not have, and that judgement stands — A6 closed the gap the other way,
by making assignment restamp the documents, so no such document survives an assignment to be a special
case about. A document in a knowledge base that was never assigned is still unowned and still the
vendor's, which is A2's answer and needs no backfill.

`KnowledgeBaseDocumentScopingTest` seeded documents with no owner while the run had one, a state the
write rule now refuses, so its fixtures are stamped rather than the rule weakened. Its own javadoc now
says which half of the gate it pins.

The rest of this entry is the record of the defect. Three places narrowed to a knowledge base or a
source and stopped there, which was sufficient while a knowledge base had one owner and is not now
that a shared one holds documents belonging to many accounts.

- `KnowledgeBaseOptionsUtils.requireKnowledgeBaseDocument` checks only that the document sits in the
  admitted knowledge base. In a shared knowledge base an account can therefore name another
  account's document id to `load`/`update`, whose chunks are then rewritten stamped with the
  *caller's* owner — a document's content moving between accounts.
- `KnowledgeBaseDocumentRepository.tombstoneUnseen(sourceId, seenIds, …)` keys on `source_id` alone,
  so a `FULL_REPLACE` sync run for one account tombstones documents another account's run created
  from the same source.
- `KnowledgeBaseDocumentFacade.sweepTombstonedDocumentChunks(sourceId)` then deletes those documents'
  chunks from the vector store by raw id, across accounts.

All three are reachable only where two accounts share one knowledge base and, for the latter two,
one source. Severity is real but the exposure is narrower than A4's, which fired on every insert.

### A6. Assigning a knowledge base to an account does not move its documents — FIXED

**Fixed.** `KnowledgeBaseServiceImpl.assignOwner` is now three writes in one transaction, symmetric with
`DataTableServiceImpl.assignOwner`: the registry row, every document in the knowledge base, and every one
of those documents' chunks in the vector store. The chunks go with the documents because a chunk carries
the account independently of its document row — that is why the pair was written into chunk metadata at
all — so moving the documents alone would leave a document whose owner disagrees with its own chunks: the
account could edit the document and find not a word of it in a search. That is the half-owner shape in a
new costume.

The guard from `79e88314a34` came across whole. Assignment is **refused** when the knowledge base holds
documents belonging to a different account, with the same reasoning: re-stamping only the unowned ones
would leave the rest sitting in a knowledge base their account can no longer resolve — not disclosed, but
silently unreachable, which is worse to discover late. The vendor unassigns first, which says "share this
with everyone" in as many words, and then assigns. Unassignment stays unchecked, and is both the way out
of the refusal and the repair path for a document that acquired an owner before these rules existed.

Both columns move together throughout, in one statement each way (`restampOwnedBy` / `restampUnowned`), and
the guard's count requires the pair explicitly so a document carrying an `owner_id` beside a null
`owner_type` counts as nobody's — it does not block an assignment, and the assignment completes it.

The stale sentence that made this invisible was on `KnowledgeBaseService.assignOwner` itself: "Documents
inherit through `knowledge_base_id` and carry no owner of their own, so this one write moves the whole
knowledge base." True when written, false the moment documents got an owner column, and still sitting there
justifying the single write. It now says what the three writes are and why.

The assertions are on what the account may DO afterwards rather than on the columns: after an assignment the
account can write documents it could not write before (`KnowledgeBaseAssignOwnerIntTest`), and a chunk that
carried `shared: true` carries the pair and no longer carries `shared`
(`KnowledgeBaseVectorStoreMetadataServiceIntTest`, against a real vector table, because every risk in that
statement is a SQL one). The refusal test asserts the other account's documents are untouched and the registry
row unchanged, not merely that a throwable arrived — a guard that threw after re-stamping would satisfy the
weaker assertion and still have moved the data — and its sibling pins that a knowledge base whose documents are
unowned or already the target's stays assignable, so the guard cannot be satisfied by refusing everything.

The rest of this entry is the record of the defect. A document written before `owner_id` existed is unowned.
The read predicate admits unowned records and the write predicate does not, so such a document was
**read-only to the account that owns the knowledge base holding it** — the account owned the whole knowledge
base and could not edit what was in it. Data tables had already solved this and knowledge bases had not.

### A7. `findSyncedDocument` is unscoped — the same defect class, fifth instance — FIXED

**Fixed.** `findSyncedDocument(sourceId, sourceRecordId, owner)` takes the owner and applies the WRITE rule,
because what the caller does with the answer is rewrite it: a run reaches its own document alone, and a run
with no owner is the vendor and reaches the unowned ones, never falling through to an account's. Two
owner-explicit queries rather than one keyed on the sync key, matching the tombstone pair beside it.

**The duplication is the intended answer, and is written into the code so the next reader does not undo it.**
Two accounts syncing one shared source now produce two documents for the same source record, one each. Under
per-account ownership each account's copy IS its own record: it carries that account's owner, its chunks carry
that account, and only that account's run may rewrite or tombstone it. The single-document alternative is one
row whose content is whichever account synced last and whose owner is whichever account synced first — a
document belonging to one account and describing another's run.

The schema had to move with it. `uk_kb_doc_source_record` made `(source_id, source_record_id)` unique on the
same premise the lookup did, so the second account's run would have found nothing and then failed to insert.
`20260901000002_platform_knowledge_base_document_source_record_owner.xml` replaces it with two partial unique
indexes, owned and unowned. Two rather than one over four columns: Postgres treats every NULL as distinct, so
a single index would enforce nothing at all for the vendor's rows. The predicates are complementary and cover
every row with a `source_id`, and the unowned index is deliberately the one that claims a half-written owner —
the same answer the read and write predicates give that shape.

The rest of this entry is the record of the defect. `findSyncedDocument(sourceId, sourceRecordId)` consulted
no owner, so account 43's sync run found account 42's document for the same source record and
`replaceSyncedDocument` rewrote its content. Ownership did not move — the document stayed 42's — but its
content became whatever 43's run wrote, which is the same content-crossing-accounts effect A5 fixed elsewhere.

### A4. Data table trigger webhooks fan out across accounts on a shared table — FIXED

**Fixed.** `data_table_webhook` carries the owner of the run that registered it again, and
`listWebhooks(DataTableRef)` returns only the registrations entitled to a row written by that ref's
run owner. The rule settled on is the read predicate's, stated below; the delivery-side assertion
lives in `DataTableWebhookRowOwnerIntTest`. The rest of this entry is the record of the defect.

`data_table_webhook` carries no owner. `DataTableWebhook`'s own javadoc says why: "the table it
hangs off has exactly one, so an owner here could only agree with the table's or contradict it."

That premise was true while ownership was resource-level only. Row-level made it false. A shared
table has no owner and holds rows belonging to many accounts, so `listWebhooks(DataTableRef)` --
which keys on registry id and environment, and whose listener filters on table identity alone --
fires **every** webhook registered on that table for **every** row inserted into it. Account 42's
row payload is POSTed to account 99's registered URL.

This is the "trigger webhooks fan out row payloads across accounts" defect from earlier in the
branch, fixed then for resource-level ownership and reintroduced in a new shape by the row axis.
Introduced by this branch, so it is ours to fix rather than inherited.

The orphaned `owner_id`/`owner_type` columns are still on `data_table_webhook` on databases built
before they were dropped, which makes the schema half cheaper than it looks.

**Semantics settled.** The read predicate's rule: a webhook owned by an account fires for the rows
that account may read -- its own plus the unowned ones -- and a vendor webhook fires for unowned
rows only. The alternative, "fires for its own rows", differs exactly on the vendor's shared rows
and was rejected because a trigger is the push form of a read: an account polling a shared table
sees the vendor's rows, so a trigger that skipped them would make push and pull disagree. The
reasoning is written into `DataTableWebhookService.Webhook#receivesRowsWrittenBy`.

### A3. Embedded knowledge bases are pinned to default chunking, permanently — FIXED

`KnowledgeBase` carries three chunking settings -- `maxChunkSize` (default 1024),
`minChunkSizeChars` and `overlap` -- which decide how a document is split before embedding and
therefore what a search can return. The automation create dialog exposes all three.

`CreateEmbeddedKnowledgeBaseInput` exposes none of them, and `embedded-knowledge-base.graphqls`
has **no update mutation at all** -- only the listing, `assignEmbeddedKnowledgeBaseOwner` and
`createEmbeddedKnowledgeBase`. So a knowledge base created from the embedded console is stuck at
1024-character chunks for its whole life, and the only way out is to delete it and recreate it,
which means re-uploading and re-embedding every document in it.

An earlier revision of this entry called that "no create-time override" and "a product gap, not a
defect". Both were wrong: it is not adjustable later, because there is no later.

Needs the three fields on the create input and an update mutation beside it. The automation dialog
is the shape to follow; the shared dialog component already renders these controls and hides them
in embedded scope only because the mutation cannot carry them.

---

### A8. Changing a knowledge base's chunking does not re-chunk what is already embedded

Raised by A3's implementer. New documents pick up the new settings; documents already embedded keep
the chunks they were split into, and there is no re-chunk action anywhere.

So a vendor who discovers their chunk size is wrong can fix it for future uploads only. That is
better than A3's "never", and it is not what changing the setting implies -- the corpus the setting
was wrong for is exactly the corpus that stays wrong.

Not a leak and not a regression. Needs either a re-chunk action or, at minimum, the dialog saying
plainly that the change applies to new documents.

### A9. The create dialog's `minChunkSizeChars` default is 1; the entity's is 100

`KnowledgeBase` declares `private int minChunkSizeChars = 100`. The shared create dialog pre-fills
`useState('1')`. So a knowledge base created through the UI with the field untouched gets a minimum
chunk size of 1, a hundredth of the intended floor, while one created through the API gets 100.

Pre-existing on the automation surface; A3 made it visible on embedded too by unhiding the control.
A3's implementer left it rather than changing automation's behaviour as a side effect of an
embedded task, which was right.

Invisible from either side alone: the entity is correct, the dialog is internally consistent, and
nothing connects a Java field default to a `useState` string literal. Same structural blindness as
the stale javadocs -- correctness held locally everywhere and the contradiction lived in the gap.

### A10. A knowledge base assignment is not atomic across its two stores

Raised by A6/A7's implementer, and recorded because it was nowhere else. `assignOwner` writes the
registry row and the documents in one transaction, then the chunks' metadata in the vector store,
which is a separate datasource with no spanning transaction. A crash between the two leaves a
document whose owner disagrees with its chunks -- the exact state the change exists to prevent --
in a narrow window. Re-running repairs it; both statements are idempotent.

Every other vector-store write in the module already has this property, so this is the existing
posture rather than something the two-axis work introduced. Recorded so it is a known posture
rather than an unexamined one.

## B. Live on `0_732`, fixed here but unlanded

**These land with the merge.** The merge is deferred, not cancelled, and cherry-picking was
considered and declined: extracting three security fixes onto `0_732` ahead of the branch buys
nothing the merge will not deliver, and splitting them makes two histories to reconcile.

So they stay live in the product until the branch lands, deliberately and with that understood.
Do not re-raise this as an open decision -- it is a made one. What it does mean is that the
branch's remaining work sits on the critical path for three security fixes, which is an argument
against widening its scope further.

### B1. Cross-knowledge-base read through the tag filter — HIGH

`PgVectorFilterExpressionConverter` renders the filter tree as flat JSONPath with no parentheses,
and `&&` binds tighter than `||`. `0_732` has no `Filter.Group` anywhere in
`KnowledgeBaseVectorStoreWrapper` and ANDs the tag filter, which is an OR chain, straight in. A
search naming **two or more tags** therefore renders as
`knowledge_base_id == 7 && tag_a == true || tag_b == true`, matching any chunk carrying `tag_b` in
**any** knowledge base.

Fixed on this branch in `b5fb3b3920e` by wrapping both operands in `Filter.Group`.

### B2. 32 ungated GraphQL mappings — HIGH

From item 17's sweep. 19 reads and 14 writes returning or mutating tenant data with no
authorization, reachable by a connected user's zero-authority JWT. Worst:
`ApprovalTaskGraphQlController`'s create/update/delete, sitting beside reads that ARE
`isTenantAdmin()` precisely because the rows expose `jobResumeId`, the token that approves a run.

The reliable way to find them is asymmetry within one controller — every one of the 33 sat beside
a gated sibling. Only 5 take no scoping argument, so looking for argument-less mappings finds five
and stops.

Full list: `.superpowers/sdd/2026-08-31-knowledge-base-vectorstore-ownership/item-17-report.md`.

### B3. Items 15 and 16 — HIGH

A destructive cross-account chunk delete, and a cross-tenant read through ungated GraphQL queries.
Both fixed on this branch, neither landed.

---

## C. Hardening, no known exploit path

### C1. `OwnerResolution` cannot tell "the vendor" from "I could not tell"

One branch — the action form's `jobPrincipalId == null || platformType == null` — returns an empty
owner meaning "unresolved", which every consumer reads as "the vendor, sees everything". Reachable
outside the editor by exactly one caller, `ActionDefinitionServiceImpl.executeProcessErrorResponse`,
which passes a null principal with `editorEnvironment = false`.

Nothing reachable from there touches a scoped resource today: it invokes only a component's HTTP
error mapper, and neither the data table nor the knowledge base component defines one. So this is
safe by coincidence rather than by construction, which is the reason to fix it rather than a reason
to hurry.

A cheap intermediate: a guard test pinning that no `processErrorResponse` reaches a scoped
resource, which fails the day the coincidence stops holding.

### C5. The embedded public action API crossed the pool boundary, not the account boundary — SETTLED

An earlier revision of this entry said naming `dataTable` on the embedded public action endpoint
"would have read across every account and both pools". **The account half was wrong.** Written down
rather than deleted, because the reasoning that produced it is a trap worth seeing.

What is true: `ActionFacadeImpl.executeAction` holds `externalUserId` and
`integrationInstanceId`, uses both, then calls `executePerform` with a null job principal, a null
platform type and `editorEnvironment = false`. `componentName` is caller-supplied with no allowlist,
and the component filters are listing-only. So an owner-scoped component IS reachable there, and
before C1 it resolved to an empty owner.

What an empty owner actually reaches, which is the part the earlier reading got wrong:

- `poolFor(Optional.empty())` returns **both** pools. This part crosses a boundary: an embedded
  call can resolve an AUTOMATION-pool table.
- `fetchDataTableResolution(..., Optional.empty())` resolves **shared registry rows only**. The
  resolution rule refuses to fall through to an account's table -- that is a property of the method,
  asserted in its own tests.
- the row predicate with a null `runOwner` matches `owner_id IS NULL` only, so it reaches **unowned
  rows only**.

An empty owner therefore means the vendor and sees the vendor's shared data. It cannot reach an
account's table or an account's rows in a shared table. The mistake was reading "empty owner" as
"unscoped" when the whole design makes it "the vendor" -- the same conflation C1 exists to fix,
made while reasoning about C1.

**What remains is real but small:** an embedded surface reaching AUTOMATION-pool tables, which the
pool split exists to prevent. The endpoint is the vendor's own API-key surface with
`externalUserId` as a parameter, so the caller is the vendor reading the vendor's own automation
data. Low severity, and C1's throw closes it either way.

**C1 stays in section C.** It was hardening, as originally recorded.

### C2. `owner_type` is not encoded in knowledge base chunk metadata — FIXED

**Fixed** in `d5b32ab0941`, with A2, because writing an id without a type would have been creating
the half-owner shape A2 exists to forbid. A chunk's metadata now carries `owner_id` and `owner_type`
together or `shared: true` alone, and the read and write predicates ask for the pair. The startup
sweep completes the type on chunks written before it, guarded on `OwnerType` having exactly one
constant so it stops rather than guesses the day a second is appended.

The spec named two keys, `owner_id` and `shared`. That was safe only while `OwnerType` had one
constant; a second value makes principals of different types sharing an id indistinguishable, with
nothing left in an already-written chunk to tell them apart. Data tables already carry both columns.

### C3. Knowledge base backfill scans every chunk on each boot — FIXED

**Fixed** in `afff2cf08ab`. Both sweeps still run on every startup — a "done" flag would have to live
somewhere that goes out of step with a restored dump or a fresh replica — but each now has a partial
index carrying its own predicate, so after the first pass the index is empty and the planner answers
from it instead of reading every chunk. A chunk arriving later carrying neither key is still found
and still swept.

### C4. `delete(List<String>)` throws `UnsupportedOperationException`

A deliberate deviation: `Filter.Key` addresses metadata only, the vector id is in no metadata
field, and Spring AI's `VectorStore` exposes no read-by-id, so neither scoping option in the spec
was achievable. No caller regresses, but the surface was reachable from agents and now fails loudly
instead of deleting across accounts.

---

## D. Decided, recorded so they are not rediscovered

- **The merge is deferred, not cancelled**, and section B lands with it rather than by
  cherry-pick. Until then those fixes exist only on this branch.
- **Row-level for knowledge bases is via chunk ownership, not tags.** Tags reach the same filter
  but are named by the workflow step, so they are organization, not isolation. Both apply, ANDed.
- **Connected users do not see `dataTable` or `knowledgeBase` in their palette** (`aea5f399585`).
  Listing-only: a node already in a workflow still renders and still executes. Blocking execution
  would belong in the visibility provider path.
- **Assigning a shared table to an account is refused when it holds another account's rows**
  (`79e88314a34`), rather than re-stamping them or silently stranding them. A6 carried the same rule
  to knowledge bases; the two resources answer this identically and a third should follow them.
- **An assignment moves everything the resource holds, in one transaction** — a data table's rows, a
  knowledge base's documents AND their chunks. A resource whose contents keep their old owner is
  readable by the new one and writable by nobody.
- **Two accounts syncing one shared source produce two documents for the same source record**, one
  each (A7). That reads as duplication and is the design: each account's copy is its own record, and
  the alternative is one document holding whichever account synced last.
