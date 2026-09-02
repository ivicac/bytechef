# Knowledge Base Ownership in the VectorStore Path Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the two unscoped paths by which a run can read or write a knowledge base belonging to another account or another pool.

**Architecture:** A knowledge base is owned by one connected user or is shared, and resolution is the only enforcement point. Two paths skip it: the `VECTOR_STORE` cluster element (read) and the `writeAsDocument` destination (write). The read fix threads a context down to `createVectorStore` by widening the cluster-element SPIs — following the shape `GuardrailsFunction` already uses — so the knowledge base implementation can resolve through the pool-and-owner-aware helper. The write fix scopes the source dereference.

**Tech Stack:** Java 25, Spring Boot 4, Spring AI, JUnit 5, Mockito, AssertJ, Testcontainers.

**Investigation:** `.superpowers/sdd/2026-08-31-resource-ownership-plan1-remove-row-level/n1-investigation.md` — read §1.3 (traced paths), §5.2 (the write hole) and §7 (recommendation and risks) before starting. This plan implements its recommendation with two corrections, both recorded below.

## Global Constraints

- **The resolution helper is `KnowledgeBaseOptionsUtils.resolveKnowledgeBase`.** Every path that names a knowledge base must go through it. It takes the pool and the owner and fails closed.
- **A resolve call that returns an empty owner is not a fix.** `OwnerResolution.resolve(ClusterElementContext, …)` reads `agentActionContext`, which is null on every path except tool execution, and then falls back to `resolveCurrentPrincipal()` — which is empty on a background worker thread. Empty owner means "sees everything", so a fix built that way closes the hole visually and leaves it open. **Every task that threads a context must prove, with a test, that the owner resolves to a NON-EMPTY value on the path it claims to fix** — not merely that the resolve call is present.
- Pool comes from `KnowledgeBaseOptionsUtils.poolFor(Optional<Owner>)`: owner present → `EMBEDDED` only. Never derive the pool from `PlatformType`.
- Widen an SPI directly only where it has few implementations. Where it has many, add a context-carrying **default** method that delegates to the existing abstract one, so only the implementation that needs the context overrides it. Counts verified in this repo: `VectorStoreFunction` 2, `RagFunction` 2, `DocumentRetrieverFunction` 1, `ChatMemoryFunction` **10**.
- Java style: blank line before control statements; blank line after a variable modification that precedes its use; no trailing blank line before a class's closing brace; no `_` prefix on private methods; descriptive names. No `TODO:` comments.
- EE files (`server/ee/`) take the ByteChef Enterprise header and a `@version ee` javadoc tag; CE files take Apache 2.0.
- Test naming: `Test` for unit, `IntTest` for integration; camelCase method names with no underscores.
- Component definition snapshots under `src/test/resources/definition/` change when a cluster element's shape changes. Regenerating takes TWO runs and the first crashes: delete the `.json` from BOTH `src/test/resources/definition/` and `build/resources/test/definition/`, run once (it writes to src then throws `NullPointerException: url` on the still-missing classpath copy — the expected midpoint), then run again.
- Run `./gradlew spotlessApply` before every commit.
- Never judge a Gradle run piped into `tail`/`grep` — redirect to a file, `echo $?` on its own line, then grep for `^> Task .* FAILED`. **A test task reported UP-TO-DATE has proved nothing; use `--rerun`.**
- Gradle is slow in this worktree (a full compile has taken 13 minutes). Prefer reading code over running builds, and scope test runs to affected modules — a full-tree test run has wedged repeatedly.
- Commit messages: `732 <description>`. Fresh commits only, never amend. Do not `git stash` — the stash is shared across worktrees.

---

## Two corrections to the investigation's recommendation

**1. `ChatMemoryFunction` is widened by a default method, not by changing its SAM.** The investigation estimated ~6 implementations and flagged the churn as its biggest risk. There are **10**, and only one (`VectorStoreChatMemory`) reaches a vector store — the other nine would gain a parameter they never use, plus a snapshot regeneration each. A default method reduces that to one file.

**2. The write hole is confirmed real and goes first.** The investigation could not establish whether `KnowledgeBaseSource` rows are pool/owner-partitioned. They are not: `KnowledgeBaseSource` carries `knowledgeBaseId` and no `ownerId`, `platformType` or `environment`. So an arbitrary `sourceId` genuinely reaches any knowledge base in any pool. It is a write primitive and it is independent of all the SPI work, so it is Task 1.

---

## File Structure

**SPI (widened directly — few implementations):**
- `server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/definition/ai/agent/VectorStoreFunction.java`
- `.../ai/agent/RagFunction.java`
- `.../ai/agent/rag/DocumentRetrieverFunction.java`

**SPI (widened by default method — 10 implementations):**
- `.../ai/agent/ChatMemoryFunction.java`

**Component-facing SPI:**
- `server/libs/modules/components/ai/vectorstore/src/main/java/com/bytechef/component/ai/vectorstore/VectorStore.java`
- `.../ai/vectorstore/src/main/java/com/bytechef/component/ai/vectorstore/cluster/VectorStoreDefinition.java`

**The fix itself:**
- `server/libs/modules/components/ai/vectorstore/knowledgebase/src/main/java/com/bytechef/component/ai/vectorstore/knowledgebase/util/KnowledgeBaseVectorStore.java`
- `.../knowledgebase/src/main/java/com/bytechef/component/ai/vectorstore/knowledgebase/destination/KnowledgeBaseItemWriter.java`

**Context carriers:**
- `server/libs/modules/components/ai/agent/src/main/java/com/bytechef/component/ai/agent/action/AbstractAiAgentChatAction.java`
- `.../rag/rag-questionanswer/src/main/java/com/bytechef/component/ai/rag/questionanswer/cluster/QuestionAnswerRag.java`
- `.../rag/rag-modular/src/main/java/com/bytechef/component/ai/rag/modular/cluster/ModularRag.java`
- `.../rag/rag-modular/document-retriever/src/main/java/com/bytechef/component/ai/rag/modular/document/retriever/cluster/VectorStoreDocumentRetriever.java`
- `.../chat-memory/chat-memory-vectorstore/src/main/java/com/bytechef/component/ai/chat/memory/cluster/VectorStoreChatMemory.java`
- `.../chat-memory/chat-memory-vectorstore/src/main/java/com/bytechef/component/ai/chat/memory/util/VectorStoreChatMemoryUtils.java`

**Guard:**
- `server/libs/modules/components/ai/vectorstore/knowledgebase/src/test/java/com/bytechef/component/ai/vectorstore/knowledgebase/KnowledgeBaseComponentScopesByIdReadsTest.java`

---

## Task 1: Scope the writeAsDocument destination

**Files:**
- Modify: `server/libs/modules/components/ai/vectorstore/knowledgebase/src/main/java/com/bytechef/component/ai/vectorstore/knowledgebase/destination/KnowledgeBaseItemWriter.java`
- Test: `server/libs/modules/components/ai/vectorstore/knowledgebase/src/test/java/com/bytechef/component/ai/vectorstore/knowledgebase/destination/KnowledgeBaseItemWriterScopingTest.java`

**Interfaces:**
- Consumes: `KnowledgeBaseOptionsUtils.resolveKnowledgeBase`, `OwnerResolution.resolve`.
- Produces: nothing other tasks depend on. This task is independent and can land alone.

`open()` currently does, around lines 90-111:

```java
this.sourceId = inputParameters.getRequiredLong(SOURCE_ID);

KnowledgeBaseSource source = knowledgeBaseSourceService.fetch(sourceId)
    .orElseThrow(() -> new IllegalStateException("KnowledgeBaseSource " + sourceId + " not found"));

this.kbId = source.getKnowledgeBaseId();
```

`SOURCE_ID` is a plain required integer, expression-enabled by default, and `KnowledgeBaseSource` carries no owner, pool or environment — so this reaches any knowledge base for any account. It then writes and tombstones documents in that knowledge base.

- [ ] **Step 1: Write the failing test**

```java
class KnowledgeBaseItemWriterScopingTest {

    private static final long ACCOUNT_ID = 42L;

    // A source row carries only a knowledgeBaseId -- no owner, no pool, no environment -- so nothing about the
    // source itself constrains which knowledge base a caller reaches. The only thing that can is resolving the
    // knowledge base the source points at, for the owner the run belongs to.
    @Test
    void testASourcePointingAtAnotherAccountsKnowledgeBaseIsRefused() {
        // given a source whose knowledgeBaseId belongs to account 43, and a run belonging to account 42
        // when open(...) runs
        // then it throws, and no document is created
    }

    @Test
    void testASourcePointingAtTheRunsOwnKnowledgeBaseIsAccepted() {
        // the same shape, with the source pointing at account 42's own knowledge base
    }
}
```

Fill those in against the real constructor and mocks — the point of the test is the refusal, so assert on the exception AND on `verify(knowledgeBaseDocumentService, never()).createSyncedDocument(...)`. A test that only asserts the throw would pass if the write happened first.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :server:libs:modules:components:ai:vectorstore:knowledgebase:test --tests '*KnowledgeBaseItemWriterScopingTest' --rerun > /tmp/t.log 2>&1
```
Then `echo $?` on its own line and grep `/tmp/t.log` for `^> Task .* FAILED`. Expected: the cross-account case does not throw.

- [ ] **Step 3: Resolve the knowledge base before writing to it**

Resolve the run's owner, then resolve `source.getKnowledgeBaseId()` through `KnowledgeBaseOptionsUtils.resolveKnowledgeBase` with `poolFor(owner)` and that owner, and use the resolved knowledge base's id as `kbId`. If it does not resolve, throw — do not fall back to the raw id.

`open()` receives a `Context`; establish what it is castable to before writing the resolution, and if no owner can be obtained there, STOP and report rather than resolving with an empty owner. An empty owner means "sees everything" and would leave the hole open while looking closed.

- [ ] **Step 4: Run tests to verify they pass**

- [ ] **Step 5: Commit**

```bash
git add server/libs/modules/components/ai/vectorstore/knowledgebase
git commit -m "732 Scope the writeAsDocument destination to the knowledge base its run may reach"
```

---

## Task 2: Widen VectorStoreFunction, RagFunction and DocumentRetrieverFunction

**Files:**
- Modify: `server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/definition/ai/agent/VectorStoreFunction.java`
- Modify: `.../ai/agent/RagFunction.java`
- Modify: `.../ai/agent/rag/DocumentRetrieverFunction.java`
- Modify: `server/libs/modules/components/ai/vectorstore/src/main/java/com/bytechef/component/ai/vectorstore/cluster/VectorStoreDefinition.java`
- Modify: `.../ai/agent/rag/rag-questionanswer/src/main/java/com/bytechef/component/ai/rag/questionanswer/cluster/QuestionAnswerRag.java`
- Modify: `.../ai/agent/rag/rag-modular/src/main/java/com/bytechef/component/ai/rag/modular/cluster/ModularRag.java`
- Modify: `.../ai/agent/rag/rag-modular/document-retriever/src/main/java/com/bytechef/component/ai/rag/modular/document/retriever/cluster/VectorStoreDocumentRetriever.java`
- Modify: `server/libs/modules/components/ai/agent/src/main/java/com/bytechef/component/ai/agent/action/AbstractAiAgentChatAction.java`

**Interfaces:**
- Produces: `VectorStoreFunction.apply(Parameters, Parameters, Parameters, Map<String, ComponentConnection>, Context)`; the same trailing `Context context` parameter added to `RagFunction.apply` and `DocumentRetrieverFunction.apply`.

Follow `GuardrailsFunction` exactly — it already carries `Context context` after `componentConnections`, and is the in-family precedent:

```java
Advisor apply(
    Parameters inputParameters, Parameters connectionParameters, Parameters extensions,
    Map<String, ComponentConnection> componentConnections, Context context,
    List<Message> conversationHistory) throws Exception;
```

- [ ] **Step 1: Widen the three SPIs**

Add `Context context` after `componentConnections` on each, with a javadoc line saying what it is for: the implementation resolves the owner from it, and a knowledge base is reachable only for the owner the run belongs to.

- [ ] **Step 2: Compile to enumerate the breakage**

```bash
./gradlew compileJava compileTestJava --continue > /tmp/c.log 2>&1
```
`echo $?` on its own line, then grep for `^> Task .* FAILED`. The compiler now lists every implementation and caller. Work through them.

- [ ] **Step 3: Thread the context from the two owner-bearing frames**

`AbstractAiAgentChatAction` already holds an `ActionContext` at both call sites — `getRagAdvisor(...)` around line 1117 and `buildChatMemoryResult(...)` around line 326 — and currently uses it only for an error wrapper. Pass it through. `ModularRag.getDocumentRetriever(extensions, componentConnections)` has no context parameter at all; add one and pass it down.

- [ ] **Step 4: Verify the whole tree compiles**

- [ ] **Step 5: Commit**

```bash
git add server/libs/platform/platform-component server/libs/modules/components/ai
git commit -m "732 Carry the invocation context down the vector store cluster element chain"
```

---

## Task 3: Widen ChatMemoryFunction by default method

**Files:**
- Modify: `server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/definition/ai/agent/ChatMemoryFunction.java`
- Modify: `server/libs/modules/components/ai/agent/chat-memory/chat-memory-vectorstore/src/main/java/com/bytechef/component/ai/chat/memory/cluster/VectorStoreChatMemory.java`
- Modify: `.../chat-memory-vectorstore/src/main/java/com/bytechef/component/ai/chat/memory/util/VectorStoreChatMemoryUtils.java`

**Interfaces:**
- Consumes: the widened `VectorStoreFunction` from Task 2.
- Produces: a default `ChatMemoryFunction.apply(..., Context context)` that delegates to the existing abstract `apply(...)`.

Ten components implement `ChatMemoryFunction` and only `VectorStoreChatMemory` reaches a vector store. Widening the SAM would give nine of them a parameter they never use plus a snapshot regeneration each.

- [ ] **Step 1: Add the default overload**

```java
/**
 * Context-carrying form. Defaults to the context-free one, because only a chat memory backed by a vector store
 * needs to know who the run is for -- the other nine implementations address a store the vendor configured, not
 * one that belongs to an account.
 */
default ChatMemory apply(
    Parameters inputParameters, Parameters connectionParameters, Parameters extensions,
    Map<String, ComponentConnection> componentConnections, Context context) throws Exception {

    return apply(inputParameters, connectionParameters, extensions, componentConnections);
}
```

Match the real return type and parameter list of the existing SAM; the shape above is illustrative of the delegation, not a literal copy.

- [ ] **Step 2: Override it in VectorStoreChatMemory and pass the context on**

Only this one implementation overrides the new form, and it passes the context into `VectorStoreFunction.apply`. `VectorStoreChatMemoryUtils.getVectorStore(...)` needs the context threaded through it as well.

- [ ] **Step 3: Confirm nothing else changed**

```bash
git status --porcelain
```
Expected: the three files above and nothing under the other nine chat-memory components. If any of them changed, the default method is not doing its job.

- [ ] **Step 4: Verify the tree compiles and the chat-memory modules pass**

- [ ] **Step 5: Commit**

```bash
git add server/libs/platform/platform-component server/libs/modules/components/ai/agent/chat-memory
git commit -m "732 Let a vector store chat memory see its invocation context, without disturbing the other nine"
```

---

## Task 4: Resolve the knowledge base in the VECTOR_STORE cluster element

**Files:**
- Modify: `server/libs/modules/components/ai/vectorstore/src/main/java/com/bytechef/component/ai/vectorstore/VectorStore.java`
- Modify: `server/libs/modules/components/ai/vectorstore/knowledgebase/src/main/java/com/bytechef/component/ai/vectorstore/knowledgebase/util/KnowledgeBaseVectorStore.java`
- Test: `server/libs/modules/components/ai/vectorstore/knowledgebase/src/test/java/com/bytechef/component/ai/vectorstore/knowledgebase/util/KnowledgeBaseVectorStoreScopingTest.java`

**Interfaces:**
- Consumes: the widened SPIs from Tasks 2 and 3.
- Produces: `VectorStore.createVectorStore(Parameters, Parameters, EmbeddingModel, Context)` as a default method delegating to the existing three-argument SAM.

This is the task that actually closes the read hole. `VectorStoreImpl.createVectorStore` currently takes `knowledgeBaseId` from input parameters and wraps it with no check.

- [ ] **Step 1: Write the failing test**

Assert that a run belonging to account 42, naming a knowledge base owned by account 43, does not get a wrapper bound to that id — it throws. And that the same run naming its own knowledge base succeeds.

**Then the assertion the investigation insists on:** a test proving the owner resolved is NON-EMPTY on the agent RAG path. An empty owner sees everything, so a test that only checks "resolve was called" would pass against a fix that resolves nothing.

- [ ] **Step 2: Run test to verify it fails**

- [ ] **Step 3: Add the default four-argument overload on `VectorStore`**

```java
/**
 * Context-carrying form. Defaults to the context-free one, so the thirteen vector store components that address a
 * store the vendor configured need no change; only a store whose identity belongs to an account overrides it.
 */
default org.springframework.ai.vectorstore.VectorStore createVectorStore(
    Parameters inputParameters, Parameters connectionParameters, EmbeddingModel embeddingModel, Context context) {

    return createVectorStore(inputParameters, connectionParameters, embeddingModel);
}
```

Keep `createVectorStore(Parameters, Parameters, EmbeddingModel)` as the single abstract method so `@FunctionalInterface` still holds and every existing lambda still compiles. Route the default methods (`load`, `delete`, `search`, `update`) through the context-carrying form.

- [ ] **Step 4: Override it in `VectorStoreImpl` and resolve**

Resolve the owner from the context, get the pool from `poolFor(owner)`, resolve the id through `KnowledgeBaseOptionsUtils.resolveKnowledgeBase`, and construct the wrapper from the resolved knowledge base. Update the inline lambda in the same file (around line 106) to pass the context.

`VectorStoreImpl` also calls the unscoped `getKnowledgeBase(Long)` in `load` (around line 190). Now that a context is available, route that through the scoped path too and remove the file's allowlist exemption in Task 5.

- [ ] **Step 5: Run tests to verify they pass**

- [ ] **Step 6: Commit**

```bash
git add server/libs/modules/components/ai/vectorstore
git commit -m "732 Resolve a knowledge base before the vector store element addresses it"
```

---

## Task 5: Close both gaps in the guard test

**Files:**
- Modify: `server/libs/modules/components/ai/vectorstore/knowledgebase/src/test/java/com/bytechef/component/ai/vectorstore/knowledgebase/KnowledgeBaseComponentScopesByIdReadsTest.java`

The hole survived because it sat in the intersection of two gaps, and the guard has both:

1. `TRUSTED_SERVICE_CALLERS` allowlists `KnowledgeBaseVectorStore.java` wholesale, justified by a comment about its `load` — which does not consider the cluster element defined in the same file.
2. `STEP_ROOTS` is `action/` + `cluster/`, and the VECTOR_STORE element lives in `util/` while the destination lives in `destination/` — so neither is required to resolve anything.

- [ ] **Step 1: Write the failing assertions**

Add `util/` and `destination/` to the roots required to resolve a knowledge base, and remove `KnowledgeBaseVectorStore.java` from the allowlist now that Task 4 routes its `load` through the scoped helper.

- [ ] **Step 2: Run to verify it fails before the earlier tasks' fixes**

If Tasks 1 and 4 are already committed this will pass immediately. In that case verify failability by mutation: revert Task 4's resolve call, watch this test go red, restore. Say so in the report — a guard that has never been seen red is indistinguishable from one that cannot fail.

- [ ] **Step 3: Commit**

```bash
git add server/libs/modules/components/ai/vectorstore/knowledgebase
git commit -m "732 Make the knowledge base guard cover the packages the hole was hiding in"
```

---

## Self-Review

**Coverage.** Read hole — Tasks 2, 3, 4. Write hole — Task 1. Guard gaps — Task 5. The non-empty-owner requirement — Task 4 Step 1, and named in Global Constraints so every task inherits it.

**Placeholders.** Task 1's test is a shape rather than literal code, deliberately: its constructor and collaborators must be read from the file first, and inventing them here would produce exactly the wrong-signature errors that cost five corrections in the previous plan. Every other code step is concrete.

**Type consistency.** `Context context` is the trailing parameter on `VectorStoreFunction`, `RagFunction` and `DocumentRetrieverFunction` (Task 2), the default overload on `ChatMemoryFunction` (Task 3) and `VectorStore` (Task 4). All follow `GuardrailsFunction`.

**Paths.** Every path in this plan was verified against the tree before writing, not recalled.

**Known risk, stated rather than designed around.** If a task cannot obtain a non-empty owner at its frame, the correct response is to STOP and report — not to resolve with an empty owner. That produces a fix that passes its own test and leaves the hole open, and it is the single most likely way this plan goes wrong.
