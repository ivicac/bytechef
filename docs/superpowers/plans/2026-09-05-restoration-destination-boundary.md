# Restoration destination boundary — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make PII restoration into a workflow's task output and into a tool call's arguments a workspace policy decision instead of an unconditional one, without changing what a live human sees.

**Architecture:** A new nullable `restoreIntoWorkflowOutput` field on `AiGuardrailsWorkspaceSettings` (null/true = today's behaviour) is resolved by `AiGuardrails` and consumed at two boundaries. The response boundary learns its destination from a new `RestorationDestination` argument threaded through `AiGuardrailsAdvisorProvider` and supplied by `AbstractAiAgentChatAction` from its own `isStreaming()` — non-streaming is a workflow output, streaming is a live conversation. The tool-argument boundary learns it from a new component on the existing `PiiTokenBoundaryPolicy`, which already travels to `PiiTokenBoundaryToolCallingManager` on the `ToolContext`.

**Tech Stack:** Java 25, Spring Boot 4, Spring AI advisors, JUnit 5 + Mockito + AssertJ, GraphQL (Spring for GraphQL + client codegen), React 19 / TypeScript.

**Spec:** `docs/superpowers/specs/2026-09-05-restoration-destination-boundary-design.md`

## Global Constraints

- **Default is ON** (D3). `restoreIntoWorkflowOutput` null or `true` means restore — today's behaviour, unchanged for every existing deployment. Only an explicit `false` gates anything.
- **Streaming responses always restore** (D8). `StreamingResponseRedactor`'s tail is never gated. A realtime voice agent must never read a token back to the person who just spoke the value.
- **Tool-call arguments are gated on all three AI Agent actions**, streaming included (D7 + D8).
- **Secrets are unaffected.** A `SECRET` span is never tokenized, so there is nothing to restore and nothing to decide.
- **Scanning is never gated** (D4). Only the restore step is in question.
- Every file under `server/ee/` carries the **ByteChef Enterprise license header** (not Apache 2.0) and a `@version ee` Javadoc tag. Files under `server/libs/` carry the Apache 2.0 header.
- Enum ordinals are persisted as INT — **append new values at the end, never reorder**.
- Run `./gradlew spotlessApply` before every commit. Judge a Gradle run by `$?` on its own line plus `grep '^> Task .* FAILED'` on a redirected log — never by a piped `tail`.
- Commit messages: `732 <description>` for server, `732 client - <description>` for client. **Never amend** — the maintainer commits in parallel on this repo. Stage **by path**, never `git add -A`.
- If a trailer is missing from a commit, fix it with a **follow-up commit**. Never move `HEAD` (`git reset --soft`, `--amend`, `git branch -f`).

---

### Task 1: The workspace setting and its resolver

**Files:**
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-api/src/main/java/com/bytechef/ee/platform/ai/guardrails/domain/AiGuardrailsWorkspaceSettings.java`
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/service/AiGuardrailsWorkspaceSettingsServiceImpl.java`
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/AiGuardrails.java`
- Test: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/AiGuardrailsRestoreIntoWorkflowOutputTest.java` (create)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `AiGuardrailsWorkspaceSettings` gains a 12th record component `Boolean restoreIntoWorkflowOutput` (last position). `AiGuardrails` gains `public boolean isRestoreIntoWorkflowOutput(@Nullable Long workspaceId)`.

- [ ] **Step 1: Write the failing test**

Create `AiGuardrailsRestoreIntoWorkflowOutputTest.java` (EE header, `@version ee` on the class Javadoc):

```java
class AiGuardrailsRestoreIntoWorkflowOutputTest {

    @Test
    void testRestoresWhenTheSettingIsUnset() {
        AiGuardrails aiGuardrails = aiGuardrailsWith(null);

        assertThat(aiGuardrails.isRestoreIntoWorkflowOutput(1L)).isTrue();
    }

    @Test
    void testRestoresWhenTheSettingIsTrue() {
        AiGuardrails aiGuardrails = aiGuardrailsWith(true);

        assertThat(aiGuardrails.isRestoreIntoWorkflowOutput(1L)).isTrue();
    }

    @Test
    void testDoesNotRestoreWhenTheSettingIsFalse() {
        AiGuardrails aiGuardrails = aiGuardrailsWith(false);

        assertThat(aiGuardrails.isRestoreIntoWorkflowOutput(1L)).isFalse();
    }

    @Test
    void testRestoresWhenNoSettingsRowExists() {
        AiGuardrails aiGuardrails = aiGuardrailsWithNoRow();

        assertThat(aiGuardrails.isRestoreIntoWorkflowOutput(1L)).isTrue();
    }
}
```

Build the `AiGuardrails` instances with a mocked `AiGuardrailsWorkspaceSettingsService` whose `fetchSettings(1L)` returns an `Optional` of a settings record carrying the flag under test (and `Optional.empty()` for the no-row case). Follow the construction already used by the neighbouring `AiGuardrails*Test` classes in the same package — copy their `@Mock` set and constructor call rather than inventing a new one.

The fourth test is the one that matters: an unavailable or absent settings row must mean *restore*, because turning restoration off by accident is the data-corruption regression D3 refused to ship.

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsRestoreIntoWorkflowOutputTest' > /tmp/t1.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/t1.log
```

Expected: FAIL — `isRestoreIntoWorkflowOutput` does not exist, and the record does not take a 12th argument.

- [ ] **Step 3: Add the record component**

Append the component **last** in `AiGuardrailsWorkspaceSettings`:

```java
    Boolean redactMcpResults, // null = not set at this level = off
    Boolean restoreIntoWorkflowOutput) { // null = not set = ON (restore), today's behaviour
```

Add a paragraph to the record's class Javadoc, after the `redactMcpResults` paragraph:

```java
 * <p>
 * {@code restoreIntoWorkflowOutput} is the one boolean here whose null and false mean different things. Null means "not
 * set", which resolves to ON -- restoration keeps happening, exactly as it did before this field existed. Only an
 * explicit {@code false} withholds restoration. That asymmetry is deliberate: this field gates whether a canvas AI
 * Agent hands real values or placeholders to whatever the workflow author wired downstream, and defaulting it off would
 * silently rewrite the output of every running workflow with no error and no failed step.
 * </p>
```

- [ ] **Step 4: Fix every construction site**

There are 33. Find them:

```bash
grep -rn "new AiGuardrailsWorkspaceSettings(" server --include='*.java' | grep -v '/build/'
```

Append `null` as the final argument at every one **except** any site whose surrounding test is specifically about this field. This is mechanical; do not change any other argument.

- [ ] **Step 5: Serialize and deserialize the field**

In `AiGuardrailsWorkspaceSettingsServiceImpl`, mirror `redactMcpResults` exactly — a `KEY_` constant beside `KEY_REDACT_MCP_RESULTS`, a null-guarded `value.put` in the write path, and a cast read appended to the record construction:

```java
    private static final String KEY_RESTORE_INTO_WORKFLOW_OUTPUT = "restoreIntoWorkflowOutput";
```

```java
        if (settings.restoreIntoWorkflowOutput() != null) {
            value.put(KEY_RESTORE_INTO_WORKFLOW_OUTPUT, settings.restoreIntoWorkflowOutput());
        }
```

```java
            (Boolean) value.get(KEY_REDACT_MCP_RESULTS),
            (Boolean) value.get(KEY_RESTORE_INTO_WORKFLOW_OUTPUT));
```

- [ ] **Step 6: Add the resolver**

In `AiGuardrails`, beside `resolveToolBoundaryPolicy`:

```java
    /**
     * Returns whether restoration may return real values into a workflow task output or a tool call's arguments for
     * {@code workspaceId}. Reads the settings row through the fail-open {@code findSettings} and treats every ambiguous
     * answer -- no row, no value, a swallowed lookup failure -- as {@code true}.
     *
     * <p>
     * Fail-OPEN here, unlike {@link #resolveMcpOutboundPolicy}, and for the opposite reason. There, failing open would
     * leak raw customer records to an external client that asked for a tool result. Here, failing closed would replace
     * the values every already-running workflow hands to its downstream nodes with placeholders -- a Slack post that
     * starts saying {@code [PII_EMAIL_ADDRESS_1_k3n9]}, with no error and no failed step. A database blip must not
     * corrupt the output of workflows whose authors never opted into tokenization in the first place.
     * </p>
     *
     * @param workspaceId the workspace to resolve, or {@code null} for the tenant default
     * @return {@code true} unless the workspace has explicitly set the flag to {@code false}
     */
    public boolean isRestoreIntoWorkflowOutput(@Nullable Long workspaceId) {
        AiGuardrailsWorkspaceSettings settings = findSettings(workspaceId);

        return settings == null || !Boolean.FALSE.equals(settings.restoreIntoWorkflowOutput());
    }
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsRestoreIntoWorkflowOutputTest' > /tmp/t1.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/t1.log
```

Expected: PASS.

- [ ] **Step 8: Compile the whole tree**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew compileJava compileTestJava --continue > /tmp/c1.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/c1.log
```

Expected: exit 0, no FAILED lines. If a construction site was missed, it appears here.

- [ ] **Step 9: Commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/platform/platform-ai/platform-ai-guardrails
git commit -m "732 Add the restoreIntoWorkflowOutput workspace setting and its fail-open resolver"
```

---

### Task 2: RestorationDestination, threaded to the advisor

**Files:**
- Create: `server/libs/platform/platform-ai/platform-ai-api/src/main/java/com/bytechef/platform/ai/guardrails/RestorationDestination.java`
- Modify: `server/libs/platform/platform-ai/platform-ai-api/src/main/java/com/bytechef/platform/ai/guardrails/AiGuardrailsAdvisorProvider.java`
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisorProviderImpl.java`
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisor.java`
- Modify: `server/libs/modules/components/ai/agent/src/main/java/com/bytechef/component/ai/agent/action/AbstractAiAgentChatAction.java`
- Test: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisorProviderDestinationTest.java` (create)

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `RestorationDestination` enum with values `CONVERSATION` and `WORKFLOW_OUTPUT`. `AiGuardrailsAdvisorProvider#getAdvisor(PlatformType, Long, String, RestorationDestination)` — the existing 3-arg method becomes a `default` delegating with `CONVERSATION`. `AiGuardrailsAdvisor`'s constructors take a trailing `RestorationDestination` and expose it to Task 3 via a private field.

- [ ] **Step 1: Create the enum**

`RestorationDestination.java` (Apache 2.0 header — this is CE):

```java
package com.bytechef.platform.ai.guardrails;

/**
 * Where a guarded response's restored text is going, which decides whether restoration is a policy question at all.
 *
 * <p>
 * Not derivable from {@link GuardrailSurface} alone. {@code AI_AGENT} covers three actions sharing one advisor, and two
 * of them stream to a live human: a rule keyed on the surface string would make a realtime voice agent read
 * {@code [PII_EMAIL_ADDRESS_1_k3n9]} back to the caller who had just spoken the address. The distinction is consent and
 * visibility, not the identity of the recipient, so the call site that knows its own route out supplies it.
 * </p>
 *
 * @author Ivica Cardic
 */
public enum RestorationDestination {

    /**
     * The restored text goes back to the party who supplied the input -- a chat thread, the Copilot panel, or a canvas
     * agent's streamed tokens. Restoration is unconditional; the consolidation spec's rule holds as written.
     */
    CONVERSATION,

    /**
     * The restored text becomes a workflow task output that downstream nodes read. The receiving party is whatever the
     * workflow author wired next, which is not the party that supplied the value, so restoration is a workspace policy
     * decision.
     */
    WORKFLOW_OUTPUT
}
```

- [ ] **Step 2: Write the failing test**

Create `AiGuardrailsAdvisorProviderDestinationTest.java`:

```java
    @Test
    void testTheThreeArgOverloadResolvesAConversation() {
        assertThat(recordedDestination).isEqualTo(RestorationDestination.CONVERSATION);
    }

    @Test
    void testAWorkflowOutputDestinationReachesTheAdvisor() {
        assertThat(recordedDestination).isEqualTo(RestorationDestination.WORKFLOW_OUTPUT);
    }
```

Implement both against an anonymous `AiGuardrailsAdvisorProvider` that captures the 4-arg method's `destination` parameter into a field, calling the 3-arg default in the first test and the 4-arg method in the second. This pins the default's delegation, which is the part a later refactor would silently break.

- [ ] **Step 3: Run the test to verify it fails**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsAdvisorProviderDestinationTest' > /tmp/t2.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/t2.log
```

Expected: FAIL — no 4-arg `getAdvisor`.

- [ ] **Step 4: Widen the SPI**

In `AiGuardrailsAdvisorProvider`, keep the existing 3-arg signature but turn it into a `default`, and add the 4-arg abstract method:

```java
    /**
     * As {@link #getAdvisor(PlatformType, Long, String, RestorationDestination)}, for the callers whose responses go
     * back to the party that produced the input. Every surface but the canvas AI Agent is one of these.
     */
    default Optional<Advisor> getAdvisor(
        @Nullable PlatformType platformType, @Nullable Long jobPrincipalId, String surface) {

        return getAdvisor(platformType, jobPrincipalId, surface, RestorationDestination.CONVERSATION);
    }

    /**
     * @param destination where this call's restored response text goes; see {@link RestorationDestination} for why the
     *                    surface alone cannot answer this
     */
    Optional<Advisor> getAdvisor(
        @Nullable PlatformType platformType, @Nullable Long jobPrincipalId, String surface,
        RestorationDestination destination);
```

Do the same for `getAdvisorForWorkspace`.

- [ ] **Step 5: Thread it through the EE implementation**

In `AiGuardrailsAdvisorProviderImpl`, implement the 4-arg forms and pass `destination` into every `new AiGuardrailsAdvisor(...)`. Add the parameter as the **last** constructor argument on both `AiGuardrailsAdvisor` constructors, storing it in a `private final RestorationDestination destination;` field. Task 3 reads that field; nothing consumes it yet, so behaviour is unchanged.

- [ ] **Step 6: Supply it from the AI Agent component**

In `AbstractAiAgentChatAction`, at the `getAdvisor` call around line 316:

```java
                aiGuardrailsAdvisorProvider
                    .getAdvisor(actionContextAware.getPlatformType(), actionContextAware.getJobPrincipalId(),
                        GuardrailSurface.AI_AGENT,
                        isStreaming() ? RestorationDestination.CONVERSATION
                            : RestorationDestination.WORKFLOW_OUTPUT)
                    .ifPresent(workspaceAdvisors::add);
```

Leave the `getMetrics` and `WorkspaceSystemPromptAdvisorProvider` calls untouched — neither restores anything.

Add a comment above it recording the reason, since the ternary reads backwards on first encounter:

```java
                // A streaming agent's tokens go to whoever is listening right now -- the realtime action emits them
                // straight back through a WebSocketEmitter to the person speaking -- so that is a conversation and
                // restores unconditionally. Only the non-streaming action's return value becomes the task output a
                // downstream node reads, and only that is a policy question. Tool-call arguments are gated on all
                // three regardless; they travel on the tool context, not through this advisor.
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsAdvisorProviderDestinationTest' > /tmp/t2.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/t2.log
```

Expected: PASS.

- [ ] **Step 8: Compile the whole tree**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew compileJava compileTestJava --continue > /tmp/c2.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/c2.log
```

- [ ] **Step 9: Commit**

```bash
./gradlew spotlessApply
git add server/libs/platform/platform-ai/platform-ai-api server/ee/libs/platform/platform-ai/platform-ai-guardrails server/libs/modules/components/ai/agent
git commit -m "732 Let a call site declare where its restored response text is going"
```

---

### Task 3: Gate the response restore, and record when it is withheld

**Files:**
- Modify: `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-api/src/main/java/com/bytechef/platform/ai/sensitivedata/SensitiveDataMetrics.java`
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisor.java`
- Test: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisorRestorationDestinationTest.java` (create)

**Interfaces:**
- Consumes: `AiGuardrails#isRestoreIntoWorkflowOutput(Long)` (Task 1); the `destination` field on `AiGuardrailsAdvisor` (Task 2).
- Produces: `SensitiveDataMetrics#recordRestoreSuppressed()` default method, event name `restore_suppressed`.

- [ ] **Step 1: Write the failing tests**

Create `AiGuardrailsAdvisorRestorationDestinationTest.java`. Model the fixture on the existing advisor tests in the same package — `@ExtendWith({MockitoExtension.class, ObjectMapperSetupExtension.class})`; **the ObjectMapper extension is not optional**, and a missing one has previously made an advisor test pass for the wrong reason by throwing and being converted into a block.

Five tests, all over a tokenizing workspace whose session has minted a token for `ada@example.com`, with a model response echoing that token:

```java
    @Test
    void testAWorkflowOutputRestoresWhenTheSettingIsOn() {
        // destination WORKFLOW_OUTPUT, isRestoreIntoWorkflowOutput -> true
        assertThat(responseText()).contains("ada@example.com");
    }

    @Test
    void testAWorkflowOutputKeepsTokensWhenTheSettingIsOff() {
        // destination WORKFLOW_OUTPUT, isRestoreIntoWorkflowOutput -> false
        assertThat(responseText()).doesNotContain("ada@example.com");
        assertThat(responseText()).contains("[PII_EMAIL_ADDRESS_1_");
    }

    @Test
    void testAConversationRestoresEvenWhenTheSettingIsOff() {
        // destination CONVERSATION, isRestoreIntoWorkflowOutput -> false
        assertThat(responseText()).contains("ada@example.com");
    }

    @Test
    void testWithholdingRestorationIsRecorded() {
        // destination WORKFLOW_OUTPUT, isRestoreIntoWorkflowOutput -> false
        verify(metrics).recordRestoreSuppressed();
    }

    @Test
    void testASuppressedRestoreIsNotRecordedWhenThereWasNothingToRestore() {
        // destination WORKFLOW_OUTPUT, setting off, a response carrying NO token
        verify(metrics, never()).recordRestoreSuppressed();
    }
```

The second test is the one that proves the defect: run it before the fix and it must fail on the `doesNotContain` assertion, showing the real address reaching the task output today. The last is what makes the metric worth having — "nothing to restore" and "restoration withheld" must stay distinguishable (D5).

- [ ] **Step 2: Run the tests to verify they fail**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsAdvisorRestorationDestinationTest' > /tmp/t3.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/t3.log
```

Expected: tests 2, 4 and 5 FAIL; tests 1 and 3 pass (they assert today's behaviour). **Read the failure text.** Test 2 must fail on its assertion, not on a missing method — if it errors instead, the fixture is wrong and the test proves nothing.

- [ ] **Step 3: Add the metric**

In `SensitiveDataMetrics`, beside `recordTokenUnresolved()`:

```java
    /**
     * Records that a restoration this call was entitled to perform was withheld by workspace policy -- the response
     * carried resolvable tokens and they were left in place because the destination was a workflow output and
     * {@code restoreIntoWorkflowOutput} is off.
     *
     * <p>
     * Recorded only when there was something to restore. Without that condition this counter would tick on every call
     * of every workflow under the setting, and an admin could not tell a workspace that is actually withholding data
     * from one that simply has no PII in flight -- which is the single question the setting is adopted or abandoned on.
     * </p>
     */
    default void recordRestoreSuppressed() {
    }
```

The default body is empty by design: this interface's implementations opt in, exactly as they do for the other `record*` defaults.

- [ ] **Step 4: Gate the restore**

In `AiGuardrailsAdvisor#applyResponseGuardrails`, replace the unconditional restore line:

```java
            String scanned = aiGuardrails.scanResponseText(text, workspaceId, metrics);
            String restored;

            if (restoring) {
                restored = aiGuardrails.restoreResponseText(scanned, session, metrics);
            } else {
                restored = scanned;

                PiiTokenSession.RestoreResult wouldHaveRestored = session.restoreWithUnresolvedCount(scanned);

                if (!Objects.equals(wouldHaveRestored.text(), scanned)) {
                    metrics.recordRestoreSuppressed();
                }
            }
```

Resolve `restoring` once, before the generation loop, so a multi-generation response cannot read the setting twice and disagree with itself:

```java
        boolean restoring = destination == RestorationDestination.CONVERSATION
            || aiGuardrails.isRestoreIntoWorkflowOutput(workspaceId);
```

Note the short-circuit order: a conversation never reads the setting at all, which keeps the common path free of a settings lookup and makes the streaming guarantee structural rather than incidental.

Leave `StreamingResponseRedactor` **untouched** — per D8 its output is a live consumer's stream.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsAdvisorRestorationDestinationTest' > /tmp/t3.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/t3.log
```

Expected: all five PASS.

- [ ] **Step 6: Run the module's whole suite**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --continue > /tmp/t3b.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/t3b.log
```

Expected: green. Any existing advisor test that goes red here is asserting the old unconditional behaviour on a workflow destination — read it before changing it.

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add server/libs/platform/platform-ai/platform-ai-sensitive-data server/ee/libs/platform/platform-ai/platform-ai-guardrails
git commit -m "732 Withhold restoration into a workflow task output when the workspace says so"
```

---

### Task 4: Gate the tool-call arguments

**Files:**
- Modify: `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-api/src/main/java/com/bytechef/platform/ai/sensitivedata/tokenization/PiiTokenBoundaryPolicy.java`
- Modify: `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service/src/main/java/com/bytechef/platform/ai/sensitivedata/tokenization/PiiTokenBoundaryToolCallingManager.java`
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/AiGuardrails.java`
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisor.java`
- Test: `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service/src/test/java/com/bytechef/platform/ai/sensitivedata/tokenization/PiiTokenBoundaryOutboundGatingTest.java` (create)

**Interfaces:**
- Consumes: `AiGuardrails#isRestoreIntoWorkflowOutput(Long)` (Task 1).
- Produces: `PiiTokenBoundaryPolicy` gains a third component `boolean restoreOutboundArguments`; `DEFAULT` sets it `true`. `AiGuardrails` gains `public PiiTokenBoundaryPolicy resolveToolBoundaryPolicy(@Nullable Long workspaceId, boolean workflowSurface)`; the existing 1-arg form delegates with `false`.

- [ ] **Step 1: Write the failing tests**

Create `PiiTokenBoundaryOutboundGatingTest.java` (Apache header — CE module):

```java
    @Test
    void testArgumentsAreRestoredUnderTheDefaultPolicy() {
        // policy DEFAULT, a tool call whose arguments carry a resolvable token
        assertThat(delegateSawArguments()).contains("ada@example.com");
    }

    @Test
    void testArgumentsKeepTheirTokensWhenOutboundRestorationIsOff() {
        assertThat(delegateSawArguments()).doesNotContain("ada@example.com");
        assertThat(delegateSawArguments()).contains("[PII_EMAIL_ADDRESS_1_");
    }

    @Test
    void testInboundToolResultTokenizationIsUnaffected() {
        // outbound restoration off; a tool RESULT carrying a fresh e-mail
        assertThat(modelSawResult()).doesNotContain("grace@example.com");
    }

    @Test
    void testASecretIsUnaffectedEitherWay() {
        // a SECRET span was never tokenized, so there is nothing to restore under either policy
    }
```

Model the fixture on the existing `PiiTokenBoundary*Test` classes in the same package — reuse their delegate `ToolCallingManager` stub and their `Prompt`/`ChatResponse` builders rather than writing new ones.

The third test is the one that stops this task from over-reaching: the policy's existing inbound job must keep working while its new outbound component is off, and those two directions have been confused once in this design already.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:test --tests '*PiiTokenBoundaryOutboundGatingTest' > /tmp/t4.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/t4.log
```

Expected: FAIL — the policy takes two components.

- [ ] **Step 3: Widen the policy record**

```java
public record PiiTokenBoundaryPolicy(
    Set<SensitiveKind> kinds, double minConfidence, boolean restoreOutboundArguments) {
```

Add the parameter Javadoc and a paragraph explaining the two directions, because the record now governs both and the earlier confusion is worth pre-empting in the file itself:

```java
 * @param restoreOutboundArguments whether a tool call's arguments may be restored to real values before the delegate
 *                                 runs -- the OUTBOUND direction, distinct from {@code kinds}/{@code minConfidence},
 *                                 which govern only what is tokenized in a tool RESULT on the way back to the model
```

Set it `true` in `DEFAULT`, and extend that constant's Javadoc: an un-updated caller must keep restoring, since a caller that silently stopped would hand tokens to a live integration.

- [ ] **Step 4: Honour it in the manager**

In `PiiTokenBoundaryToolCallingManager`, the outbound restore method (around line 300) takes the resolved policy and returns `chatResponse` unchanged when `!policy.restoreOutboundArguments()`. Place the check beside the existing `anyGenerationHasToolCalls` early return so both cheap exits sit together. Record `recordRestoreSuppressed()` on the metrics supplier only when a tool call's arguments actually carried a resolvable token — same condition as Task 3, same reason.

Update the class Javadoc: the sentence saying arguments are "passed through `PiiTokenSession#restoreWithUnresolvedCount` before the delegate runs" is now conditional, and that sentence is what a reader trusts.

- [ ] **Step 5: Resolve it in EE**

In `AiGuardrails`, add the 2-arg `resolveToolBoundaryPolicy` and have `toolBoundaryPolicyOf` take the outbound flag:

```java
    public PiiTokenBoundaryPolicy resolveToolBoundaryPolicy(@Nullable Long workspaceId, boolean workflowSurface) {
        return toolBoundaryPolicyOf(
            resolvePolicy(workspaceId), !workflowSurface || isRestoreIntoWorkflowOutput(workspaceId));
    }
```

Keep the 1-arg form delegating with `false` so `resolveMcpOutboundPolicy` and every existing caller are untouched — an MCP outbound policy has no tool-argument direction to gate.

In `AiGuardrailsAdvisor#withSessionInToolContext`, pass whether this is the canvas agent surface:

```java
        PiiTokenBoundaryPolicy policy = aiGuardrails.resolveToolBoundaryPolicy(
            workspaceId, GuardrailSurface.AI_AGENT.equals(metrics.getSurface()));
```

This is keyed on the **surface**, not on `destination` — deliberately. Per D7 and D8 a tool call leaves the agent for a system the author chose no matter how the reply reaches the caller, so all three agent actions gate their arguments, streaming included.

- [ ] **Step 6: Run the tests to verify they pass**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:test :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --continue > /tmp/t4.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/t4.log
```

Expected: green in both modules.

- [ ] **Step 7: Add the streaming-agent case**

Append to `AiGuardrailsAdvisorRestorationDestinationTest` (Task 3's file):

```java
    @Test
    void testAStreamingAgentStillGatesItsToolArguments() {
        // destination CONVERSATION, surface AI_AGENT, isRestoreIntoWorkflowOutput -> false
        assertThat(toolContextPolicy().restoreOutboundArguments()).isFalse();
    }
```

This is the pairing D8 turns on: the same call restores its response (a live listener) and withholds its tool arguments (an integration the author chose). Nothing else in the suite pins that combination, and it is the one a future simplification would collapse.

- [ ] **Step 8: Run and commit**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsAdvisorRestorationDestinationTest' > /tmp/t4b.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/t4b.log
```

```bash
./gradlew spotlessApply
git add server/libs/platform/platform-ai/platform-ai-sensitive-data server/ee/libs/platform/platform-ai/platform-ai-guardrails
git commit -m "732 Gate outbound tool-argument restoration on the same workspace setting"
```

---

### Task 5: Expose the setting

**Files:**
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-graphql/src/main/resources/graphql/ai-guardrails-workspace-settings.graphqls`
- Modify: the GraphQL controller/mapper in `platform-ai-guardrails-graphql` that builds the settings record from the input type (find it with `grep -rln redactMcpResults server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-graphql/src/main/java`)
- Modify: `client/src/graphql/platform/ai-guardrails/aiGuardrailsWorkspaceSettings.graphql`
- Regenerate: `client/src/shared/middleware/graphql.ts`, `client/src/shared/middleware/graphql-types.ts`
- Modify: `client/src/ee/pages/settings/automation/ai/guardrails/AiGuardrails.tsx`
- Modify: `client/src/ee/pages/settings/embedded/guardrails/EmbeddedGuardrails.tsx`
- Test: `client/src/ee/pages/settings/automation/ai/guardrails/AiGuardrails.test.tsx`

**Interfaces:**
- Consumes: the `restoreIntoWorkflowOutput` field from Task 1.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Add the field to the schema**

`restoreIntoWorkflowOutput: Boolean` in **both** the `AiGuardrailsWorkspaceSettings` type and the `AiGuardrailsWorkspaceSettingsInput` input, positioned after `redactMcpResults` in each.

- [ ] **Step 2: Thread it through the GraphQL mapping**

Mirror `redactMcpResults` in the controller/mapper — read it off the input, pass it as the record's last argument, and expose it on the query result.

- [ ] **Step 3: Add it to the client operation and regenerate**

Add the field to the query and mutation in `aiGuardrailsWorkspaceSettings.graphql`, then:

```bash
cd client && npx graphql-codegen
```

Commit the operation change and the regenerated file **separately**, per the repo's GraphQL convention.

- [ ] **Step 4: Add the toggle**

On the automation guardrails page, a switch after the MCP one. **Copy names the consequence, not the mechanism** (D3's second consequence — the setting ships doing nothing, so neutral copy would leave an admin unable to tell what it is for):

- Label: `Restore PII in workflow output`
- Description: `On (default), an AI Agent node hands downstream workflow nodes and the tools it calls the caller's real values. Turn this off to hand them placeholders instead — the agent still sees real values, but a Slack post or HTTP call wired after it will receive [PII_EMAIL_ADDRESS_1_…] rather than an address. Streamed replies to a live caller are never affected.`

Follow the page's existing switch markup exactly, including the `sort-keys` ordering rule and `twMerge` for any conditional class. Add the same toggle to the embedded page if that page renders the MCP switch; skip it if it does not.

- [ ] **Step 5: Test the toggle**

Add a test to `AiGuardrails.test.tsx` alongside the existing MCP-switch test asserting the toggle renders, reflects the fetched value, and sends `restoreIntoWorkflowOutput: false` on the mutation when switched off. Reset any Zustand state in `beforeEach`, and wait on the condition with `waitFor` — never on elapsed time.

- [ ] **Step 6: Run the client checks**

```bash
cd client && npm run check
```

Use a 600000 ms timeout — this run is slow. If `node_modules` is stale after a rebase, `npm install` first.

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-graphql
git commit -m "732 Expose restoreIntoWorkflowOutput over GraphQL"
git add client/src/graphql client/src/ee/pages/settings
git commit -m "732 client - Add the workflow-output restoration toggle"
git add client/src/shared/middleware
git commit -m "732 client - Regenerate the GraphQL types for restoreIntoWorkflowOutput"
```

---

### Task 6: Write down what the rule now says

**Files:**
- Modify: `.agents/ai-guardrails.md`
- Modify: `docs/superpowers/specs/2026-09-05-restoration-destination-boundary-design.md` (status line only)

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Qualify the ordering rule**

In `.agents/ai-guardrails.md`, find the sentence stating that restoration only ever returns a value to the party that supplied it, and qualify it:

> Restoration returns a value to the party that supplied it **on a conversation surface** — a chat thread, the Copilot panel, and a canvas agent's streamed tokens, which go to whoever is listening right now. On a workflow surface the destination is whatever the author wired next, and the `restoreIntoWorkflowOutput` workspace setting decides. It is ON by default, so a canvas AI Agent hands real values to its downstream nodes and to the tools it calls unless an admin turns it off. Watch `pii_restored` tagged `surface=ai_agent` to see whether that is happening in a given workspace, and `restore_suppressed` to confirm the setting is doing something once it is off.

Add a short subsection recording the split D8 makes — which of the three agent actions gates its response, and that all three gate their tool arguments — since that asymmetry is invisible from either file alone.

- [ ] **Step 2: Update the spec status**

Change the status line to record that the design is implemented, keeping the D3 and D8 attributions.

- [ ] **Step 3: Commit**

```bash
git add .agents/ai-guardrails.md docs/superpowers/specs/2026-09-05-restoration-destination-boundary-design.md
git commit -m "732 docs - Record that restoration into a workflow output is now a policy decision"
```

---

## Final verification

- [ ] Whole-tree compile and the two affected modules' suites:

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew compileJava compileTestJava --continue > /tmp/final.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/final.log
```

- [ ] `cd client && npm run check` (timeout 600000).
- [ ] `./gradlew spotlessApply` re-run after any late edit — formatters run first in CI, and a late fix that skips them fails the build on formatting rather than on substance.
