# Tool-Boundary PII Restoration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore PII tokens in tool-call arguments so tools receive real values, and tokenize PII in tool results so it never reaches the model provider.

**Architecture:** A `ToolCallingManager` decorator sits below every tool-registration path on both tool-using surfaces. It reads the request's `PiiTokenSession` from `ToolContext` — the only channel that survives the worker-thread hop — restores tokens in each tool call's argument JSON before delegating, and tokenizes PII in each tool result afterwards.

**Tech Stack:** Java 25, Spring Boot 4.0.7, Spring AI 2.0.1, Gradle 9.7, JUnit 5, AssertJ, Mockito.

**Spec:** `docs/superpowers/specs/2026-08-31-tool-boundary-pii-restoration-design.md`

## Global Constraints

- Score bands are Low `0.2`, Medium `0.6`, High `0.9`; `SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE = 0.4`, compared `>=`.
- Arguments fail **open** (unresolved token left in place, `token_unresolved` recorded, run continues). Results fail **closed** (untokenizable result is redacted, never returned raw).
- Secrets are redacted irreversibly and never restored, on any path, in any direction.
- The decorator lives in **CE** (`platform-ai-sensitive-data-service`) and takes no EE dependency. A session present in `ToolContext` is itself the signal that tokenization is active.
- Files under `server/ee/` need the ByteChef Enterprise license header and a `@version ee` javadoc tag. Spotless selects the header by that tag's **content**, not the file's path.
- Run `./gradlew spotlessApply` scoped to changed modules, never bare.
- Never judge a Gradle run piped into `tail`/`grep` — the exit code becomes the filter's. Redirect to a file, check `$?` on its own line, then grep for `^> Task .* FAILED`. Never grep `error:`; it matches module paths like `:server:libs:core:error:`.
- Blank line before control statements; no trailing blank line before a class's closing brace; no `_` prefix on private methods; descriptive variable names.
- Test classes end in `Test` (unit) / `IntTest` (integration); test method names camelCase, no underscores.
- Commit format `732 <description>`. Every commit must compile on its own.

---

## File Structure

| File | Responsibility |
|---|---|
| `platform-ai-sensitive-data-service/.../tokenization/ToolBoundaryApiShapeTest.java` | Characterization test pinning the Spring AI shapes everything else assumes |
| `platform-ai-sensitive-data-service/.../tokenization/PiiTokenBoundaryToolCallingManager.java` | The decorator: restore arguments, tokenize results |
| `platform-ai-sensitive-data-service/.../tokenization/PiiTokenBoundaryToolCallingManagerTest.java` | Its unit tests |
| `platform-ai-guardrails-service/.../advisor/AiGuardrailsAdvisor.java` | Puts the session into `ToolContext` |
| `platform-ai-sensitive-data-api/.../SensitiveDataMetrics.java` | Two new default no-op events |
| `guardrails/.../AiGuardrailMetrics.java` (EE) | Overrides them |
| `components/ai/agent/.../tool/AgentToolCallingManagers.java` | Wraps its returned manager |
| `ai-hub-service/.../toolsearch/ToolSearchAdvisorConfiguration.java` | Wraps AI Hub's chain |

---

### Task 1: Pin the Spring AI tool-calling API shapes

Everything downstream reconstructs immutable Spring AI objects. Those shapes are assumed, not verified, and a wrong assumption here fails at compile time in the best case and silently drops tool calls in the worst. This task turns the assumption into a checked fact **before** any production code depends on it.

**Files:**
- Test: `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service/src/test/java/com/bytechef/platform/ai/sensitivedata/tokenization/ToolBoundaryApiShapeTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: verified knowledge of how to read tool calls off a `ChatResponse`, rebuild one with rewritten arguments, read tool results off a `ToolExecutionResult`, and rebuild one. Later tasks depend on the exact constructors/builders this test proves.

- [ ] **Step 1: Write the characterization test**

```java
@Test
void testToolCallArgumentsAreReachableAndRebuildableOnAChatResponse() {
    AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
        "call-1", "function", "sendEmail", "{\"to\":\"[PII_EMAIL_ADDRESS_1_k3n9]\"}");
    AssistantMessage assistantMessage = new AssistantMessage("", Map.of(), List.of(toolCall));
    ChatResponse chatResponse = new ChatResponse(List.of(new Generation(assistantMessage)));

    AssistantMessage output = chatResponse.getResult()
        .getOutput();

    assertThat(output.getToolCalls()).singleElement()
        .extracting(AssistantMessage.ToolCall::arguments)
        .isEqualTo("{\"to\":\"[PII_EMAIL_ADDRESS_1_k3n9]\"}");

    AssistantMessage.ToolCall rewritten = new AssistantMessage.ToolCall(
        toolCall.id(), toolCall.type(), toolCall.name(), "{\"to\":\"bob@acme.io\"}");
    ChatResponse rebuilt = new ChatResponse(
        List.of(new Generation(new AssistantMessage(output.getText(), output.getMetadata(), List.of(rewritten)))),
        chatResponse.getMetadata());

    assertThat(rebuilt.getResult()
        .getOutput()
        .getToolCalls()).singleElement()
            .extracting(AssistantMessage.ToolCall::arguments)
            .isEqualTo("{\"to\":\"bob@acme.io\"}");
}
```

- [ ] **Step 2: Run it and let it tell you the truth**

Run:
```bash
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:test --tests '*ToolBoundaryApiShapeTest*' > /tmp/t1.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t1.log || echo "no failed tasks"
```

If it does not compile, the API differs from the assumption. **Correct the test to match the real API, do not force the API to match the test** — the whole point of this task is to discover the real shapes. Record every correction in your report; later tasks are written against these shapes and must be told if they changed.

- [ ] **Step 3: Add the `ToolExecutionResult` half**

```java
@Test
void testToolResultsAreReachableAndRebuildableOnAToolExecutionResult() {
    ToolResponseMessage.ToolResponse response = new ToolResponseMessage.ToolResponse(
        "call-1", "lookupCustomer", "{\"email\":\"bob@acme.io\"}");
    ToolExecutionResult result = ToolExecutionResult.builder()
        .conversationHistory(List.of(new ToolResponseMessage(List.of(response))))
        .build();

    ToolResponseMessage toolResponseMessage = (ToolResponseMessage) result.conversationHistory()
        .getLast();

    assertThat(toolResponseMessage.getResponses()).singleElement()
        .extracting(ToolResponseMessage.ToolResponse::responseData)
        .isEqualTo("{\"email\":\"bob@acme.io\"}");
}
```

- [ ] **Step 4: Run both, confirm green**

Same command as Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service/src/test/java/com/bytechef/platform/ai/sensitivedata/tokenization/ToolBoundaryApiShapeTest.java
git commit -m "732 Pin the Spring AI tool-calling API shapes with a characterization test"
```

---

### Task 2: Carry the token session into ToolContext

**Files:**
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisor.java`
- Create: `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-api/src/main/java/com/bytechef/platform/ai/sensitivedata/tokenization/PiiTokenSessionToolContext.java`
- Test: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisorTest.java`

**Interfaces:**
- Consumes: `PiiTokenSession` (existing), `AiGuardrails#newTokenSession()` (existing).
- Produces: `PiiTokenSessionToolContext.KEY` (a `String` constant), `PiiTokenSessionToolContext.from(@Nullable ToolContext)` returning `@Nullable PiiTokenSession`, and `PiiTokenSessionToolContext.into(Map<String, Object>, PiiTokenSession)` returning a new `Map<String, Object>`.

Tool calls run on worker threads that do not inherit `EnvironmentContext`, `TenantContext` or `SecurityContext` — this is why `RehydrateContextToolCallback` exists. A ThreadLocal-held session is invisible at the tool boundary. `ToolContext` is the channel `AgentToolInvocationContext` already uses for this hop.

- [ ] **Step 1: Write the accessor and its failing test**

```java
@Test
void testSessionRoundTripsThroughToolContext() {
    PiiTokenSession session = PiiTokenSession.create();

    Map<String, Object> context = PiiTokenSessionToolContext.into(Map.of("existing", "kept"), session);

    assertThat(context).containsEntry("existing", "kept");
    assertThat(PiiTokenSessionToolContext.from(new ToolContext(context))).isSameAs(session);
}

@Test
void testAbsentSessionReadsAsNull() {
    assertThat(PiiTokenSessionToolContext.from(new ToolContext(Map.of()))).isNull();
    assertThat(PiiTokenSessionToolContext.from(null)).isNull();
}
```

- [ ] **Step 2: Run it, expect failure**

Expected: FAIL, `PiiTokenSessionToolContext` does not exist.

- [ ] **Step 3: Implement the accessor**

```java
public final class PiiTokenSessionToolContext {

    public static final String KEY = "bytechef.pii-token-session";

    private PiiTokenSessionToolContext() {
    }

    public static @Nullable PiiTokenSession from(@Nullable ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }

        Object value = toolContext.getContext()
            .get(KEY);

        return value instanceof PiiTokenSession piiTokenSession ? piiTokenSession : null;
    }

    public static Map<String, Object> into(Map<String, Object> context, PiiTokenSession session) {
        Map<String, Object> merged = new HashMap<>(context);

        merged.put(KEY, session);

        return merged;
    }
}
```

- [ ] **Step 4: Run, expect PASS**

- [ ] **Step 5: Seed it from the advisor**

In `adviseCall` and `adviseStream`, after `PiiTokenSession session = aiGuardrails.newTokenSession();`, merge the session into the forwarded request's tool context. Read the request's existing `ToolContext` from its `ChatOptions` when they are `ToolCallingChatOptions`, merge via `PiiTokenSessionToolContext.into`, and forward a request carrying the merged map. **Preserve every existing entry** — `AgentToolInvocationContext` lives in the same map and dropping it would break security-context rehydration on worker threads.

- [ ] **Step 6: Test that the advisor seeds it**

```java
@Test
void testAdviseCallPutsTheSessionIntoTheForwardedToolContext() {
    // capture the ChatClientRequest passed to the chain, then:
    assertThat(PiiTokenSessionToolContext.from(capturedToolContext())).isNotNull();
}
```

- [ ] **Step 7: Mutation-verify**

Remove the merge from `adviseCall`; the Step 6 test must fail. Restore it.

- [ ] **Step 8: Commit**

```bash
git add server/libs/platform/platform-ai/platform-ai-sensitive-data server/ee/libs/platform/platform-ai/platform-ai-guardrails
git commit -m "732 Carry the PII token session into ToolContext for the tool boundary"
```

---

### Task 3: Restore tokens in tool-call arguments (outbound, fails open)

**Files:**
- Create: `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service/src/main/java/com/bytechef/platform/ai/sensitivedata/tokenization/PiiTokenBoundaryToolCallingManager.java`
- Test: `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service/src/test/java/com/bytechef/platform/ai/sensitivedata/tokenization/PiiTokenBoundaryToolCallingManagerTest.java`

**Interfaces:**
- Consumes: `PiiTokenSessionToolContext.from(ToolContext)`, `PiiTokenSession#restoreWithUnresolvedCount(String)` returning `RestoreResult(String text, int unresolvedCount)`, and the shapes Task 1 verified.
- Produces: `PiiTokenBoundaryToolCallingManager.wrap(ToolCallingManager delegate, SensitiveDataRedactor redactor, @Nullable SensitiveDataMetrics metrics)` returning `ToolCallingManager`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void testTokensInToolArgumentsAreRestoredBeforeTheToolRuns() {
    PiiTokenSession session = PiiTokenSession.create();
    String token = session.tokenFor("EMAIL_ADDRESS", "bob@acme.io");

    RecordingToolCallingManager delegate = new RecordingToolCallingManager();
    ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(delegate, redactor(), null);

    manager.executeToolCalls(
        promptWithSession(session), chatResponseWithToolCall("sendEmail", "{\"to\":\"" + token + "\"}"));

    assertThat(delegate.lastArguments()).isEqualTo("{\"to\":\"bob@acme.io\"}");
}

@Test
void testAnUnresolvedTokenIsLeftInPlaceAndDoesNotAbortTheRun() {
    PiiTokenSession session = PiiTokenSession.create();

    RecordingToolCallingManager delegate = new RecordingToolCallingManager();
    ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(delegate, redactor(), null);

    manager.executeToolCalls(
        promptWithSession(session),
        chatResponseWithToolCall("sendEmail", "{\"to\":\"[PII_EMAIL_ADDRESS_9_zzzz]\"}"));

    assertThat(delegate.lastArguments()).isEqualTo("{\"to\":\"[PII_EMAIL_ADDRESS_9_zzzz]\"}");
}

@Test
void testWithNoSessionInToolContextTheDelegateSeesArgumentsUnchanged() {
    RecordingToolCallingManager delegate = new RecordingToolCallingManager();
    ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(delegate, redactor(), null);

    manager.executeToolCalls(promptWithoutSession(), chatResponseWithToolCall("sendEmail", "{\"to\":\"x\"}"));

    assertThat(delegate.lastArguments()).isEqualTo("{\"to\":\"x\"}");
}
```

- [ ] **Step 2: Run, expect failure**

Expected: FAIL, class does not exist.

- [ ] **Step 3: Implement argument restoration only**

`resolveToolDefinitions` delegates unchanged. `executeToolCalls` reads the session from the prompt's tool context; when it is null, delegates unchanged. Otherwise it rebuilds the `ChatResponse` with each tool call's `arguments` passed through `session.restoreWithUnresolvedCount(...)`, keeping `id`, `type` and `name`, then delegates. Result handling arrives in Task 4.

- [ ] **Step 4: Run, expect PASS**

- [ ] **Step 5: Mutation-verify**

Make the restore a no-op (return the input); the first test must fail while the third still passes. Revert.

- [ ] **Step 6: Commit**

```bash
git add server/libs/platform/platform-ai/platform-ai-sensitive-data
git commit -m "732 Restore PII tokens in tool-call arguments at the tool boundary"
```

---

### Task 4: Tokenize PII in tool results (inbound, fails closed)

**Files:**
- Modify: `.../tokenization/PiiTokenBoundaryToolCallingManager.java`
- Test: `.../tokenization/PiiTokenBoundaryToolCallingManagerTest.java`

**Interfaces:**
- Consumes: `SensitiveDataRedactor#tokenizeWithSpans(String, Set<SensitiveKind>, PiiTokenSession, SensitiveDataMetrics)` returning `RedactionResult(String text, List<SensitiveSpan> accepted)`, and `#redact(String, Set<SensitiveKind>, SensitiveDataMetrics)`.
- Produces: nothing new.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void testPiiInAToolResultIsTokenizedBeforeItReachesTheModel() {
    PiiTokenSession session = PiiTokenSession.create();
    ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(
        returningResult("{\"email\":\"bob@acme.io\"}"), redactor(), null);

    ToolExecutionResult result = manager.executeToolCalls(promptWithSession(session), anyToolCall());

    assertThat(lastResponseData(result)).doesNotContain("bob@acme.io")
        .contains("[PII_EMAIL_ADDRESS_");
}

@Test
void testTheSameValueKeepsOneTokenAcrossTheWholeCall() {
    PiiTokenSession session = PiiTokenSession.create();
    String promptToken = session.tokenFor("EMAIL_ADDRESS", "bob@acme.io");

    ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(
        returningResult("{\"email\":\"bob@acme.io\"}"), redactor(), null);

    ToolExecutionResult result = manager.executeToolCalls(promptWithSession(session), anyToolCall());

    assertThat(lastResponseData(result)).contains(promptToken);
}

@Test
void testASecretInAToolResultIsRedactedAndNotTokenized() {
    PiiTokenSession session = PiiTokenSession.create();
    ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(
        returningResult("key=AKIAIOSFODNN7EXAMPLE"), redactor(), null);

    ToolExecutionResult result = manager.executeToolCalls(promptWithSession(session), anyToolCall());

    assertThat(lastResponseData(result)).doesNotContain("AKIAIOSFODNN7EXAMPLE")
        .contains("[REDACTED_")
        .doesNotContain("[PII_");
}

@Test
void testAResultThatCannotBeTokenizedIsRedactedRatherThanReturnedRaw() {
    PiiTokenSession closedSession = PiiTokenSession.create();

    closedSession.close();

    ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(
        returningResult("{\"email\":\"bob@acme.io\"}"), redactor(), null);

    ToolExecutionResult result = manager.executeToolCalls(promptWithSession(closedSession), anyToolCall());

    assertThat(lastResponseData(result)).doesNotContain("bob@acme.io");
}
```

- [ ] **Step 2: Run, expect failure**

- [ ] **Step 3: Implement result tokenization**

After delegating, walk the returned `ToolExecutionResult`'s conversation history, and for each `ToolResponseMessage` rebuild its responses with `responseData` passed through `tokenizeWithSpans(text, Set.of(SensitiveKind.PII, SensitiveKind.SECRET), session, metrics)`. Wrap that call in a try/catch: **on any failure, substitute `redact(text, Set.of(PII, SECRET), metrics)` instead of the original text.** If redaction also fails, substitute the empty string. Never return the raw text on a failure path — this is the fail-closed half of the asymmetry, and it is the opposite of the engine's usual fail-open posture.

- [ ] **Step 4: Run, expect PASS**

- [ ] **Step 5: Mutation-verify the fail-closed path**

Change the catch block to return the original text; the fourth test must fail. Revert. This is the most important mutation in the plan: it is the difference between a degraded answer and a silent PII leak.

- [ ] **Step 6: Commit**

```bash
git add server/libs/platform/platform-ai/platform-ai-sensitive-data
git commit -m "732 Tokenize PII in tool results, failing closed on error"
```

---

### Task 5: Wire both surfaces, and prove dynamic tools are covered

**Files:**
- Modify: `server/libs/modules/components/ai/agent/src/main/java/com/bytechef/component/ai/agent/tool/AgentToolCallingManagers.java`
- Modify: `server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/toolsearch/ToolSearchAdvisorConfiguration.java`
- Test: `server/ee/libs/ai/ai-hub/ai-hub-service/src/test/java/com/bytechef/ee/ai/hub/toolsearch/ToolSearchPiiBoundaryTest.java`

**Interfaces:**
- Consumes: `PiiTokenBoundaryToolCallingManager.wrap(...)`.
- Produces: nothing new.

`AgentToolCallingManagers.getToolCallingManager(@Nullable Integer maxToolCalls)` is the sole factory for all three AI Agent actions, realtime included. AI Hub composes `LazyToolCallingManager` and `UnknownToolRecoveringToolCallingManager`; this becomes a third layer.

- [ ] **Step 1: Write the test that justified the whole approach**

Spec decision D2 rejected a per-`ToolCallback` decorator because AI Hub resolves tools dynamically at call time. If that is not tested, D2 is an unverified assertion.

```java
@Test
void testADynamicallyResolvedToolStillGetsItsArgumentsRestored() {
    PiiTokenSession session = PiiTokenSession.create();
    String token = session.tokenFor("EMAIL_ADDRESS", "bob@acme.io");

    // Register the callback ONLY through the dynamic resolver, never at construction.
    RecordingToolCallback dynamic = new RecordingToolCallback("sendEmail");
    ToolCallingManager manager = toolSearchManagerResolving(dynamic);

    manager.executeToolCalls(
        promptWithSession(session), chatResponseWithToolCall("sendEmail", "{\"to\":\"" + token + "\"}"));

    assertThat(dynamic.lastInput()).isEqualTo("{\"to\":\"bob@acme.io\"}");
}
```

- [ ] **Step 2: Run, expect failure**

Expected: FAIL — the dynamic tool receives the token, because nothing wraps it yet.

- [ ] **Step 3: Wrap both surfaces**

In `AgentToolCallingManagers`, wrap the manager returned by `getToolCallingManager` before returning it. In `ToolSearchAdvisorConfiguration`, wrap the composed chain. Both take `SensitiveDataRedactor` and the surface's existing metrics instance.

- [ ] **Step 4: Run, expect PASS**

- [ ] **Step 5: Verify the agent surface too**

```bash
./gradlew :server:libs:modules:components:ai:agent:check :server:ee:libs:ai:ai-hub:ai-hub-service:check --continue > /tmp/t5.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t5.log || echo "no failed tasks"
```

- [ ] **Step 6: Commit**

```bash
git add server/libs/modules/components/ai/agent server/ee/libs/ai/ai-hub
git commit -m "732 Wire the PII tool boundary into the agent component and AI Hub"
```

---

### Task 6: Observability

**Files:**
- Modify: `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-api/src/main/java/com/bytechef/platform/ai/sensitivedata/SensitiveDataMetrics.java`
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/AiGuardrailMetrics.java`
- Test: `.../tokenization/PiiTokenBoundaryToolCallingManagerTest.java`

**Interfaces:**
- Produces: `SensitiveDataMetrics#recordToolArgsRestored()` and `#recordToolResultTokenized()`, both `default` no-ops so `@FunctionalInterface` compatibility and existing lambda usage survive.

- [ ] **Step 1: Write the failing tests**

Both events are **incidence per tool invocation**, not per span — matching the existing `containsKind` idiom. Assert: fires once when at least one token was restored; fires exactly once when several were restored in one invocation; does not fire when none were.

```java
@Test
void testRecordsToolArgsRestoredAtMostOncePerInvocationWithSeveralTokens() {
    PiiTokenSession session = PiiTokenSession.create();
    String first = session.tokenFor("EMAIL_ADDRESS", "a@x.io");
    String second = session.tokenFor("EMAIL_ADDRESS", "b@x.io");
    RecordingMetrics metrics = new RecordingMetrics();

    ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(
        new RecordingToolCallingManager(), redactor(), metrics);

    manager.executeToolCalls(
        promptWithSession(session),
        chatResponseWithToolCall("sendEmail", "{\"a\":\"" + first + "\",\"b\":\"" + second + "\"}"));

    assertThat(metrics.toolArgsRestoredCount).isEqualTo(1);
}

@Test
void testDoesNotRecordToolArgsRestoredWhenNothingWasRestored() {
    RecordingMetrics metrics = new RecordingMetrics();
    ToolCallingManager manager = PiiTokenBoundaryToolCallingManager.wrap(
        new RecordingToolCallingManager(), redactor(), metrics);

    manager.executeToolCalls(
        promptWithSession(PiiTokenSession.create()), chatResponseWithToolCall("sendEmail", "{\"to\":\"x\"}"));

    assertThat(metrics.toolArgsRestoredCount).isZero();
}
```

- [ ] **Step 2: Run, expect failure**

- [ ] **Step 3: Add the events and record them**

Guard each with a boolean set outside the per-tool-call loop so the count is incidence, not per-span. Reuse the existing `token_unresolved` event for unresolved argument tokens rather than adding a third name.

- [ ] **Step 4: Run, expect PASS**

- [ ] **Step 5: Mutation-verify**

Move the record call inside the per-call loop; the several-tokens test must fail with `expected: 1 but was: 2`. Revert.

- [ ] **Step 6: Commit**

```bash
git add server/libs/platform/platform-ai/platform-ai-sensitive-data server/ee/libs/platform/platform-ai/platform-ai-guardrails
git commit -m "732 Record tool-boundary restore and tokenize events"
```

---

### Task 7: Documentation

**Files:**
- Modify: `.agents/ai-guardrails.md`
- Modify: `docs/content/docs/platform/automation/build/workflows/ai/agent/guardrails/pii.md`

- [ ] **Step 1: Update the internal doc**

In its existing voice: the boundary is a `ToolCallingManager` decorator and why (dynamic tool resolution makes registration-time wrapping unsafe); the session travels via `ToolContext` because tool calls lose ThreadLocal context; the fail-open/fail-closed asymmetry **and its reason**, since a future reader applying the engine's usual fail-open rule uniformly would reintroduce the leak; that secrets are never restored at the boundary.

**Verify every number and name against the code with a command before writing it.**

- [ ] **Step 2: Update the customer-facing page**

Operator register, not engineer. What changed for them: tools now receive real values rather than placeholders, and data a tool returns is protected before the model sees it. Note the capability cost plainly — an agent can no longer reason about the content of PII a tool returned, only pass it onward.

- [ ] **Step 3: Commit**

```bash
git add .agents docs/content
git commit -m "732 docs - Document the PII tool boundary"
```

---

### Task 8: Full verification

- [ ] **Step 1: Every touched module, forced**

```bash
export DOCKER_HOST=unix:///Volumes/Data/Users/ivicac2/.orbstack/run/docker.sock
./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-api:check \
          :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:check \
          :server:libs:modules:components:ai:agent:check \
          :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:check \
          :server:ee:libs:ai:ai-hub:ai-hub-service:check \
          --rerun-tasks --continue > /tmp/t8.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t8.log || echo "no failed tasks"
```

`--rerun-tasks` matters: a cached `UP-TO-DATE` is not evidence for a verification task. SpotBugs findings are in the HTML report; the XML is disabled in this repo and never rewritten.

- [ ] **Step 2: Whole-server compile**

```bash
./gradlew compileJava compileTestJava --continue > /tmp/t8c.log 2>&1
echo "exit=$?"
grep -E '^> Task .* FAILED' /tmp/t8c.log || echo "no failed tasks"
```

- [ ] **Step 3: Confirm each constraint with a real command**

1. A tool receives the real value when the model passes a token.
2. A tool result containing PII reaches the model tokenized, never raw.
3. A secret in a tool result is redacted, never tokenized, never restored.
4. A dynamically resolved tool is covered (spec D2's justification).
5. An unresolved argument token does not abort the run.
6. Result tokenization failure redacts rather than returning raw text.
7. One value keeps one token across prompt, argument and result.
8. No `server:ee:` dependency appears in either CE sensitive-data module's build file.

**Do not fix anything you find here.** Report it. A verification task that repairs what it discovers cannot be trusted to have looked honestly.

---

## Self-Review

**Spec coverage.** §1 both directions → Tasks 3, 4. §2 manager decorator + two wrap points → Task 5, with D2's justification tested in Task 5 Step 1. §3 CE placement → Task 3 file paths, checked in Task 8 constraint 8. §4 `ToolContext` → Task 2. §5 data flow and one-value-one-token → Task 4 Step 1. §6 asymmetry → Tasks 3 and 4, each mutation-verified. §7 secrets → Task 4. §8 observability → Task 6. §9 testing → Tasks 3–6 and Task 8 Step 3. §10 blast radius → the File Structure table. §11 non-goals → nothing implements them.

**Placeholder scan.** No "TBD", no "add error handling", no "similar to Task N". Task 1 deliberately instructs the implementer to correct the test against the real API — that is discovery, not a placeholder, and it is the one place where the plan admits it is working from an unverified assumption.

**Type consistency.** `PiiTokenBoundaryToolCallingManager.wrap(ToolCallingManager, SensitiveDataRedactor, SensitiveDataMetrics)` is used identically in Tasks 3, 4, 5, 6. `PiiTokenSessionToolContext.from`/`.into` match between Tasks 2 and 3. `restoreWithUnresolvedCount` returns `RestoreResult(text, unresolvedCount)`, matching the existing record. `tokenizeWithSpans` returns `RedactionResult(text, accepted)`, matching the existing record.

**Known risk.** Task 1 exists because the Spring AI reconstruction shapes in Tasks 3 and 4 are assumed. If Task 1 finds them different, Tasks 3 and 4's code blocks need adjusting before implementation — the executor must carry Task 1's findings forward.
