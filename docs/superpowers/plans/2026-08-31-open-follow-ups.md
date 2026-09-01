# Open follow-ups after the resource-ownership and knowledge-base work

Working checklist. Everything here came out of a review or an implementation on branch
`worktree-resource-pools-server-split`; each item names where it was found and what goes wrong if it is
left. Ordered by severity, not by effort.

## Security

- [x] **1. The pool dimension of the writeAsDocument path.** Closed. `AbstractItemStreamDelegate` now reads the
  `principalId` and `modeType` job parameters `DataStreamStreamActionDefinition` always wrote, and passes them
  to a new `ContextFactory.createClusterElementContext` overload; `getJobPrincipalId()`/`getPlatformType()` moved
  down to `JobContextAware` so both context kinds derive an owner one way. An embedded run for a connected user
  now resolves a non-empty owner and is confined to the EMBEDDED pool.
  Note for whoever reviews it: a plain swap of `requireUnassignedKnowledgeBase` for `resolveKnowledgeBase` would
  have been a LOOSENING, because `isReadableBy` admits everything for an empty owner — so the gate branches on
  whether an owner was obtained, and a run without one keeps the old refusal.
  Report: `.superpowers/sdd/2026-08-31-knowledge-base-vectorstore-ownership/item-1-report.md`.
  *Found by: Task 1 of the knowledge-base plan, which reported BLOCKED rather than resolving with an empty owner.*

- [ ] **2. `OwnerResolution` returns `Optional.empty()` for two different things.**
  "This is the vendor" and "I could not determine who this is" are the same value, and the second one means
  *sees everything*. F1 was that ambiguity's one reachable instance and is fixed; the root wants a tri-state
  — `OWNER(x)` / `VENDOR` / `UNRESOLVED` — so a caller must handle the third case deliberately.
  *Found by: my own trace of guarantee (e) after three review subagents were killed by environment errors.*

  **Now has a second confirmed instance, on the READ path.** Item 1 established that
  `KnowledgeBaseServiceImpl.isReadableBy` returns true for an empty owner, so
  `resolveKnowledgeBase(…, Optional.empty())` **admits** an account-owned knowledge base rather than refusing
  it. `KnowledgeBaseVectorStore.resolve` still resolves with a possibly-empty owner, so the read path has the
  shape the write path just had. Item 1's gate branches on whether an owner was obtained; the read path does
  not. Fixing the tri-state fixes both, which is why this is the root and not a third item.
  *Found by: item 1, while checking whether the briefed gate swap was safe.*

- [x] **3. The knowledge-base guard test matches a literal string.** Closed. The single receiver became an
  explicit list of *id-yielding calls* — matched on the METHOD rather than on the field it is called through, so
  renaming `knowledgeBaseService` does not slip one past — and the rule became "a file that makes one must also
  show an admission gate". Three gates are admissible, `resolveKnowledgeBase` (both overloads),
  `requireUnassignedKnowledgeBase` and `fetchKnowledgeBase`, which items 6/7/9 made carry the by-name rule itself.
  The routes the old string missed are now named: `.getKnowledgeBaseId()` (a source or document dereferenced into
  the knowledge base behind it — the original write hole, whatever service produced the entity),
  `knowledgeBaseSourceService.fetch(`, `.getKnowledgeBases(` (including the overload taking no owner), and the
  three by-id mutators. Proved by mutation: a probe in a package outside every scanned root, taking exactly the
  original hole's route, fails the guard; removing the gate from `KnowledgeBaseItemWriter` fails it even with
  `destination/` taken back out of the roots, so the catch is now design rather than luck.
  Report: `.superpowers/sdd/2026-08-31-knowledge-base-vectorstore-ownership/items-3-8-13-report.md`.
  *Found by: the knowledge-base final review, LOW 3.*

- [x] **15. A document id is dereferenced without checking it belongs to the admitted knowledge base.** Closed.
  Two guards beside the three knowledge base gates — `requireKnowledgeBaseDocument` (document to its knowledge
  base) and `requireKnowledgeBaseDocumentChunk` (chunk to its document to its knowledge base) — take the id of
  the ALREADY ADMITTED knowledge base, never one from input. Both refusals are reported as the exception a
  missing row throws, so the id space is not an existence oracle; the chunk half needed a
  `KnowledgeBaseDocumentChunkNotFoundException` to exist at all, since the service threw a plain
  `RuntimeException` a guard could only have string-matched. `updateSingle`'s two checks sit at the TOP of the
  method rather than beside the deletes they protect, because it deletes before it loads. The options twins are
  covered too: `documentOptions`, `tagOptions` and `documentChunkOptions` now admit their knowledge base through
  the same gate before listing, and the unpicked case returns empty rather than `getAllTagNames()`.
  Every refusal test was watched fail first, each on the DAMAGE and not on a missing exception — the chunk store
  in the test is live, and survival is asserted before the refusal so an unguarded build reports the row that
  went missing. A first draft asserted the victim document's STATUS, which passed against a completely
  unguarded build because `load` sets PROCESSING and then READY; it was replaced by the two things that persist.
  Report: `.superpowers/sdd/2026-08-31-knowledge-base-vectorstore-ownership/item-15-report.md`.
  *Also found in the same pass:* `KnowledgeBaseSearchAction` carried a FOURTH private inline copy of the tag
  dropdown, ungated and with the same tenant-wide `getAllTagNames()` fallback; folded back into the shared util.
  The one remaining `getAllTagNames()` caller is `KnowledgeBaseDocumentTagGraphQlController` — same whole-tenant
  shape, different module and auth model, not touched here.
  *Original finding:*
  `KnowledgeBaseVectorStore.load` admits the knowledge base through a gate, then takes
  `knowledgeBaseDocumentId` — ordinary expression-enabled workflow input — and calls
  `knowledgeBaseDocumentService.getKnowledgeBaseDocument(existingDocumentId)`, flipping that row's status and
  hanging new chunks off it, without ever comparing `document.getKnowledgeBaseId()` to the id it just admitted.
  `updateSingle` is the same shape one level down: it deletes chunks by a caller-supplied
  `knowledgeBaseDocumentChunkId` or `knowledgeBaseDocumentId` before `load` runs at all. The gate is on the
  knowledge base; the document and chunk ids beside it are unchecked, so a run admitted to its own knowledge base
  can still write to and delete out of another pool's documents.
  Item 3's guard cannot catch this — it is file-granular, and the file already shows a gate. The same unchecked
  shape is in the options path: `documentOptions` and `tagOptions` list another pool's document names and tag
  names for any `knowledgeBaseId` typed into the parameter.
  *Found by: item 3, while searching for routes to a knowledge base id that its two lists do not cover.*

## Correctness

- [x] **4. `assignOwner` accepts any pool's id**, so an admin can give an AUTOMATION row an owner and break
  the vendor's own resolution. Closed. Both impls now refuse a non-null owner outside EMBEDDED, phrased as
  "not EMBEDDED" so a pool added later has to opt in. Passing null stays allowed in every pool — it is the
  repair path for a row that acquired an owner before the guard existed. `6efbe96deaf`
  *(final review B, F2)*

- [x] **5. `owner_type` is absent from the new `data_table` unique key.** Closed. Both unreleased changesets
  edited in place; `owner_type` joins the owned-rows index and is deliberately kept out of the partial
  shared-rows one, where a stray type with no id must still collide. Two IntTests pin the key shape against
  the database using raw SQL, since the second `OwnerType` the key exists for cannot be produced through the
  domain. `92168a6e8fd`
  *(found independently by review B as F3 and review C as C-2 — two routes, one finding)*

- [x] **6. A reassigned table becomes unreachable once a second owner holds the name.** Closed by renaming
  rather than by tolerating the divergence. The recorded obstacle — `data_table` has no environment column, so
  one registry row maps to N physical tables — turned out not to need the environment enum at all: the fan-out
  reads `information_schema`, so it moves the instances that exist and a table created in DEVELOPMENT and never
  promoted is one rename rather than three attempts. Every target is checked before the first rename runs, inside
  the method's existing transaction where Postgres keeps DDL transactional, so a collision in one environment
  leaves every other and the registry row untouched. Unassigning renames back, which is also the repair path for
  a row that diverged on this branch before the change. `357a5dbcc4e`
  *(final review B, F4)*

- [x] **7. `resolveBarePhysicalOccupant`'s sole-candidate branch can resolve an unowned ref to an owned row.**
  Closed, and item 6 is what let it be closed rather than merely narrowed: the heuristic existed only to
  reconcile the divergence `assignOwner` created, so once the rename removed the divergence the whole helper had
  nothing left to answer. Gone with it: `physicalRef`'s existence probe (an `information_schema` round trip on
  every resolution) and `findAllByNameAndPlatformType`, which had no other caller. An unowned ref is now taken at
  its word. `abd25b13f10`
  *(Task 5 review, N2; review C reached the same branch as C-3)*

- [x] **8. Editor options and dynamic properties skip `effectiveOwner`**, so a vendor step naming an account
  sees the shared table's columns. Closed. Both editor paths — the table dropdown and the dynamic column
  properties — now derive their owner the way the six actions already did, and each call site declares
  `optionsLookupDependsOn(ACCOUNT_ID)` / `propertiesLookupDependsOn(TABLE, ACCOUNT_ID)` so the editor re-asks
  when the account changes; without that the threading is invisible, because the answer is cached from before
  the account was set. The trigger dropdown is threaded identically, where it is the identity — no trigger
  declares the selector — so the two options paths cannot drift.
  `DataTableComponentResolutionGuardTest` did NOT cover them: its owner rule scanned `action/` only, and both
  paths live in `DataTableUtils`. It now scans the whole component, with `DataTableUtils` trusted for minting
  an owner (it defines `effectiveOwner`) and the three triggers listed as having no account selector to
  reconcile. `DataTableEditorAccountScopingTest` is the behavioural half, with a shared `orders` and account
  42's own `orders` carrying different columns so a wrong lookup cannot be right by accident.
  *(final review B, F7)*

- [x] **9. `resolveKnowledgeBase(name, …)` does a full environment-and-pool list then filters in Java.** Closed.
  Two targeted finders on `KnowledgeBaseRepository`, both keyed off the new unique index; the owned one carries
  `owner_type` as well as `owner_id`, because that is what the `isReadableBy` filter it replaces compared. The
  rule is byte-for-byte the same rule, but it moved down to `KnowledgeBaseService.fetchKnowledgeBase`, beside its
  data-table twin — its second half (a run with no owner never falls through to an account's) is a security
  boundary and was being composed in a component util. `993393ff2dc`
  *(Task 6 review)*

- [x] **10. One `verify(never())` at `DataTableWebhookEventListenerTest:92` still cannot fail** for any
  implementation that compiles. Closed — kept, not deleted, but converted to a captor over every lookup
  asserting the whole set is the account's own table. Proved by injecting a cross-pool second lookup: the
  old form passed, the new one fails. `653f0cfa630`
  *(Task 3 of the ownership plan; confirmed still open by the final review)*

- [x] **11. `EmbeddedDataTableApiFacadeCreateTest` never exercises the account-carrying branch** — only
  `createDataTable(..., null)`. Closed. Two facade tests cover the owner-carrying call and the physical name
  it produces; the registry half, which a mock cannot prove, is asserted in
  `DataTableEmbeddedOwnedTableIntTest` beside the physical name. `75d12651a27`
  *(final review D)*

- [ ] **15. `KnowledgeBaseVectorStore` dereferences a document and chunk id it never checks. LIVE, and destructive.**
  The knowledge base itself is admitted properly. Then `knowledgeBaseDocumentId` and
  `knowledgeBaseDocumentChunkId` are read straight from workflow input (`:352-353`) and used without ever
  confirming they belong to the knowledge base that was admitted:
  `deleteKnowledgeBaseDocumentChunk(knowledgeBaseDocumentChunkId)` at `:391` deletes by caller-supplied id,
  `load` flips another pool's document to `STATUS_PROCESSING`, and `updateSingle` deletes chunks *before*
  `load` runs. `documentOptions`/`tagOptions` are the read-only twin.
  This is worse than the holes items 1-14 addressed: those reached the wrong knowledge base, this destroys
  another account's data by id. The admission gate on the knowledge base does not help, because the ids are
  independent inputs.
  *Found by: item 3's search for other routes to a knowledge base — the search was the point of that item.*
  *Deliberately NOT papered over: the guard scan is file-granular and this file already shows a gate, so
  adding `.getKnowledgeBaseDocument(` to the id-yielding list would have greened it while enforcing nothing.*

- [x] **16. `getAllTagNames()`'s remaining caller.** Closed by **scoping**, and the check turned up a live hole
  rather than a justified listing. The premise recorded here — that the controller runs under its own
  `@PreAuthorize` — was false. The *mutation* beside the two queries does go through
  `KnowledgeBaseDocumentApiFacade` (document → knowledge base → workspace role), which is presumably where the
  impression came from; the two queries called `KnowledgeBaseDocumentTagService` directly and carried no
  annotation at all. Below the missing gate both readers are `findAll()` over `knowledge_base_document`, which
  sits beneath all three separations the product has — the `workspace_knowledge_base` relation, the
  `platform_type` pool split, and the `owner_id`/`owner_type` pair an embedded knowledge base carries — so one
  call returned every tag name in the tenant across all of them, and the by-document twin returned the document
  ids too, enumerating the whole id space for a caller who can reach none of it.
  **Not console-only.** `SecurityConfiguration` requires only `.authenticated()` on `/graphql`, and
  `EmbeddedApiKeySecurityConfigurer` explicitly matches `^/graphql$` whenever an `Authorization` header is
  present, routing a connected user's JWT to a principal holding `List.of()` authorities — the exact caller item
  15 spent its effort keeping away from other accounts' tags.
  Both queries now take a `knowledgeBaseId` and pass through the API facade, where a new `checkKnowledgeBaseRole`
  enters the rule `checkDocumentRole` already applied one level down, refusing identically for a missing and for
  a foreign knowledge base. The console caller always had the id. The three unscoped service readers
  (`getAllTagNames`, the no-arg `getTagNamesByKnowledgeBaseDocumentId`, and `getTagNamesByKnowledgeBaseDocumentName`,
  which had no caller at all) are deleted rather than left beside the scoped ones, and the interface javadoc now
  says a knowledge base id is the only way in, and why. Both refusals watched failing against a build with the
  gate calls removed. `6bdbc6d161a`
  *Found by: item 15, after removing the component callers left it with one.*

  *Noticed while there, NOT fixed:* `KnowledgeBaseTagGraphQlController.knowledgeBaseTagsByKnowledgeBase()` is the
  same shape one entity up — no argument, no `@PreAuthorize`, straight to `knowledgeBaseTagFacade.getTagsByKnowledgeBaseId()`
  — and is reachable by the same principals. Its sibling `knowledgeBaseTags(workspaceId)` is properly gated, so
  this is one unguarded query beside a guarded one, not a whole controller.

## Product

- [ ] **12. Sub-project 2 — embedded console parity.** Never designed. The server supports per-account data
  tables and knowledge bases; the console cannot drive them, because `ownerId` on `createEmbeddedDataTable`
  has **no client caller at all**. The headline feature — a shared `orders` and an account's own `orders` —
  is reachable only through raw GraphQL.
  Smaller than first estimated: 74 files are already shared, and of the 96 in
  `client/src/pages/automation/datatable`, only **11** touch anything automation-specific — and every
  production one is a hook (`useDataTable`, `useDataTableLeftSidebar`, `useDeleteDataTableAlertDialog`,
  `useDeleteDataTableRowsDialog`, `useRenameDataTableDialog`). Components, cells, cell-renderers and stores
  are already generic. So this is a hooks-layer scope change plus a move, following the pattern the list
  components already went through — not a 96-file extraction.

- [x] **14. A physical data table name carries `ownerId` but not `ownerType`.** Closed. The name is now
  `<pool>_<envId>_<ownerId>_<ownerTypeToken>_<baseName>`, `DataTableRef` carries an `Owner` rather than an
  `ownerId`, and `findByNameAndPlatformTypeAndOwnerIdAndOwnerType` replaces the id-only finder everywhere — the
  registry lookups had the same shape as the name, so an id alone could match two rows the unique index
  deliberately keeps apart.
  There was a second, sharper instance beside the collision: `listTables` parses the owner back out of the
  physical name, and with no type there to read it hardcoded `Owner.connectedUser(id)`. That is
  mis-attribution, not collision — an owned table of a second type would have been handed to a connected user
  of the same id.
  **The token is the enum constant lowercased with its underscores removed (`connecteduser`), not the ordinal.**
  The ordinal is what `owner_type` persists and would have looked consistent, but the two fail differently: a
  column holding a stale ordinal can be UPDATEd, while a physical table carrying one must be found and renamed,
  and until it is every name silently addresses another owner's table. A renamed constant fails loudly instead
  — `PhysicalTableNaming.ownerType` returns empty and the name does not parse. The underscores go because the
  token sits between two underscore-delimited fields: `5_connected_user_orders` parses two ways, and resolving
  that would mean consulting the enum from inside a regex over `information_schema`. The id stays ahead of the
  type, because the leading digit is what tells the owned form from the shared one.
  **Shared tables keep `<pool>_<envId>_<baseName>` byte for byte** — released customer data, not renamed.
  **No migration, and "recreate them" is the honest answer.** Owned physical tables came into existence on this
  unmerged branch, so production has zero and only local dev databases hold any. There is no in-product repair
  either: item 6's rename fan-out matches the new form, so unassign-then-reassign finds nothing to move. A
  `DataTableListTablesOwnedPhysicalTableIntTest` case pins what happens meanwhile — the name does not parse, the
  registry lookup misses, and `listTables` skips it with a warning, which is that module's existing read-only
  invariant. It is never handed to an owner it does not belong to.
  Lengthening the owned form brought Postgres's 63-byte identifier limit within reach, and Postgres truncates
  rather than refusing, so `DataTableRef` now refuses such a name at construction. That can only newly reject
  something already broken: shared names did not change length. Proved by mutation on both halves — dropping the
  type from the prefix fails the naming and ref assertions, and restoring the old two-part parse fails both
  `listTables` integration assertions. `4008bc02c86`
  *Found by: items 6/7/9, while renaming physical tables on assign.*

## Documentation

- [x] **13. A migration note for bridged workflows.** Written, as
  `docs/superpowers/specs/2026-09-01-embedded-automation-resource-pools-migration-note.md`: what breaks, who is
  affected, the two SQL queries that tell you whether you are, and the remedy — the resource must exist in the
  embedded pool, created shared to reproduce the old behaviour or per-account to use the new one.
  There is no convention to follow: no changelog for the application, and
  `docs/content/docs/platform/use-bytechef/self-hosted/management/upgrades.mdx` is a generic how-to-upgrade
  guide that records no individual changes, so a version-notes page there would have been invented rather than
  followed. The note therefore sits beside its design, and the design's own Migration section — which said "no
  data step", true of the schema and misleading about the behaviour — now points at it.

## Closed today

- [x] `AutomationSearchFacadeImpl` admitted a null `workspaceId`, and `SearchResult.workspaceId()` defaulted
  to null — a provider that forgot to answer had its results returned to every workspace. `workspaceId()` is
  now abstract and the facade fails closed. `238dc1b3f54`
- [x] Up to three `information_schema` scans per row operation collapsed to one. `columnTypeMap` took a table
  name and re-fetched what its caller had just discarded; it now takes the specs.
- [x] The merged #5591 adds `uk_knowledge_base_name_environment` on `(name, environment)`, which on a merged
  tree survives this branch's index creations and then forbids per-account knowledge bases. Dropped, with a
  precondition. `9686bcc6768`

## Not a defect — recorded so it is not re-raised

#5591's own premise was correct. A review of Task 6 reported `environment` as "already in the key", but that
was true only on this branch, where `ea0fb02271b` had added it. On `0_732` it genuinely was missing, and
#5591 added it correctly. No correction is owed to that issue.

---

## Item 17 — closed, and it opened something larger

**Part 1 (the item itself): closed.** `57588fb7596`, `5ff5c048816`.
`knowledgeBaseTagsByKnowledgeBase()` now names a workspace and goes through
`WorkspaceKnowledgeBaseFacade` under the same `hasPermission(#workspaceId, 'Workspace',
'KNOWLEDGE_BASE_VIEW')` its already-guarded sibling carried. Three unscoped
`KnowledgeBaseTagFacade` readers deleted; two of the three had no caller at all.
The neighbouring `updateKnowledgeBaseTags` mutation, which took a knowledge base id and
never checked it, was gated in the same pass.

**Part 2 (the sweep): 32 holes remain, unfixed, reported only.**

603 `@QueryMapping`/`@MutationMapping` methods. Not one carries a method-level
`@PreAuthorize` as its own gate -- authorization lives in the facades here, which is
the convention. **33 of them reach a facade that checks nothing**: 19 reads and 14
writes returning or mutating tenant data, all reachable by a connected user's
zero-authority JWT. One was fixed in pass (the mutation above); 32 stand.

Worst three, all verified at the source:

1. `ApprovalTaskGraphQlController` create/update/delete -- ungated writes over every
   approval task in the tenant. The *reads* beside them are `isTenantAdmin()`, with a
   comment at line 65 explaining that the rows expose `jobResumeId`, the token that
   approves the run. Reads guarded because they leak the token; writes not guarded at all.
2. `KnowledgeBaseSourceGraphQlController`, all six mappings -- read any source by id or
   workspace, create/edit/delete/enable any of them. The mutations resolve the owning
   workspace from the id and never check it against the caller.
3. `DataTableTagGraphQlController` -- item 17's exact twin one domain over.
   `dataTableTagsByTable()` takes no argument and `findAll`s the tenant under
   `@PreAuthorize("isAuthenticated()")`, which grants precisely what `/graphql` already
   required and reads as a gate while being none. Its sibling `dataTableTags(Long
   workspaceId)` takes a workspace id and is ungated outright.

**The generalisable finding.** Only 5 of the 33 take no scoping argument, so hunting for
"item 17's shape" -- a mapping with no argument to scope by -- finds five and stops.
The reliable smell is **asymmetry within one controller**: every one of the 33 sits beside
a gated sibling. That is a mechanical check, and it is the one worth automating.

Full report: `.superpowers/sdd/2026-08-31-knowledge-base-vectorstore-ownership/item-17-report.md`.

**Status: not this branch's work.** 32 holes across approval tasks, knowledge base
sources, data table tags, and more is its own spec, its own plan, and its own branch.
Recorded here as the finding; NOT scoped into the pool split.

---

## Merge decision — one merge, not cherry-picks

Asked whether items 15/16/17's fixes should be extracted onto `0_732` independently, since
those holes are live in the product there. **Answer: no — the branch merges as one.**

Consequence to hold onto: the live holes on `0_732` stay live until this branch lands, so
landing it is now what closes them. That puts the branch's remaining work on the critical
path for three security fixes, and argues against widening the branch any further. Item 17's
32-hole sweep stays OUT for exactly that reason.

Remaining before the merge: item 12 (in flight), item 2 (below), then the 28-commit
`git rebase --onto 0_732` and a ff-only landing.

---

## Item 2 — the `OwnerResolution` tri-state, scoped properly

I previously described this as "30 call sites, and a live read hole". Having traced it, both
halves of that were wrong, and it is worth writing down what it actually is.

### It is ONE branch, not thirty

`OwnerResolution` returns `Optional.empty()` from three places per form. Two of the three are
correct and must stay:

- `ownerResolver == null` -- Community Edition ships no resolver, no principal below the tenant
  exists, and "see everything" is the right answer. `OwnerResolver`'s own javadoc says so.
- Anything delegated to `resolveJobPrincipal` / `resolveCurrentPrincipal` -- those contracts
  define empty as "the principal belongs to no connected user", i.e. the vendor. The resolver
  looked and answered. That is a VENDOR, not an UNRESOLVED.

Exactly one branch conflates the two: the action form's

```java
if (jobPrincipalId == null || platformType == null) {
    return Optional.empty();
}
```

This is "I could not determine the run at all" wearing the same clothes as "the vendor". The
cluster-element and trigger forms do not have it -- both fall through to
`resolveCurrentPrincipal()` instead of returning empty, so they always reach the resolver. The
cluster form inherits it only via the agent-action-context recursion.

### It IS reachable in a non-editor context -- via exactly one caller

`jobPrincipalId == null` was assumed to imply "editor test run", which the earlier branch already
handles. `ContextFactory.createClusterElementContext`'s javadoc states that assumption outright.
It is false for one caller:

`ActionDefinitionServiceImpl.executeProcessErrorResponse` builds an action context with
`jobPrincipalId = null`, `type = null`, and `editorEnvironment = **false**`. Every other
`createActionContext` caller that passes a null principal passes `true`. So this one context
reaches the gap branch, resolves to an empty owner, and downstream
`KnowledgeBaseServiceImpl.isReadableBy` admits everything on an empty owner
(`if (ownerId == null || owner.isEmpty()) return true;`) across both pools, since `poolFor(empty)`
opens both.

### But there is no path from it to a resource today

`executeProcessErrorResponse` invokes only `actionDefinition.getProcessErrorResponse()`, an
HTTP-error mapper. The knowledge base component defines none -- its generated definition snapshot
records `"processErrorResponse": null` -- and neither does the data table component. So nothing
reachable from that context resolves a data table or knowledge base.

### Ruling: does not block the merge; files as its own ticket

What this is: a type-level defect whose safety currently rests on a coincidence -- that no
`processErrorResponse` function happens to touch a scoped resource. That is a real thing to fix,
because the coincidence is invisible and nothing enforces it. It is NOT a live exploit, and
calling it one would be wrong.

Against fixing it here: it changes a shared platform-api return type with ~30 call sites, in a
branch that is already the critical path for three security fixes that ARE live on `0_732`
(items 15, 16, 17). Widening it delays those for a hardening with no exploit path.

So: item 2 does not block the merge. It files alongside item 17's 32-hole sweep as follow-up
work, and the two belong in the same security-hardening branch.

**Cheap guard worth taking now if anything:** a test pinning that no `processErrorResponse`
function reaches a scoped resource would make the coincidence explicit and fail the day it stops
holding. Noted, not done -- it is the same widening argument at smaller scale.

---

## Merge: dropped

The merge is off the table for now. No rebase, no landing; the branch stays where it is.

Two consequences worth keeping written down rather than rediscovering later:

1. **Items 15, 16 and 17's fixes stay unlanded**, so those holes remain live on `0_732` and in
   the product. That was previously a temporary state justified by an imminent merge; it is now
   an accepted one. If that is not the intent, the cherry-pick option is still available and was
   declined only on the assumption the branch was about to land.

2. **Item 2's ruling loses one of its two supporting arguments.** It rested on (a) not an
   exploit, no reachable path from `executeProcessErrorResponse` to any scoped resource, and
   (b) don't widen a branch that is the critical path for three live fixes. (b) is now moot.
   (a) still holds on its own, and the API change is still ~30 call sites, so the conclusion
   stands -- item 2 remains follow-up work, not branch work. Recording this so the ruling is not
   later read as resting on a premise that no longer applies.

Item 12 continues; it is worth having regardless of when the branch lands.
