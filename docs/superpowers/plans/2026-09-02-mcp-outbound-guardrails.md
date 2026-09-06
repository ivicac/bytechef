# MCP Outbound Guardrails Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redact sensitive data from workflow and component results before they leave a ByteChef automation or embedded MCP server into an external agent's model context.

**Architecture:** A `RedactingToolCallback` decorator wraps every `ToolCallback` the two MCP configurations register. It resolves an `McpOutboundRedactor` per call through a CE SPI whose EE implementation reads a new, separately-switched workspace setting. `ToolCallback#call` returns the already-serialized result as a `String`, so one redaction pass covers every nested field.

**Tech Stack:** Java 25, Spring Boot 4.0.7, Spring AI 2.0.1, Gradle 9.7, JUnit 5 + AssertJ + Mockito, GraphQL (Spring for GraphQL + client codegen), React 19 / TypeScript 6 for the settings checkbox.

**Spec:** `docs/superpowers/specs/2026-09-02-mcp-outbound-guardrails-design.md`

## Global Constraints

- Every file under `server/ee/` uses the **ByteChef Enterprise license header** and carries a `@version ee` Javadoc tag. Spotless selects the header by that tag's presence in the file **content**, not by path.
- Every file under `server/libs/` uses the **Apache 2.0** license header.
- Run `./gradlew spotlessApply` before every commit. Never judge a Gradle run piped into `tail`/`grep` — redirect to a file, check `$?` on its own line, then grep the file for `^> Task .* FAILED` (never `error:`, which matches module paths like `:server:libs:core:error:`).
- Client: run `npm run check` from `client/` with a **600000 ms** tool timeout (it is auto-backgrounded at 120s). Requires Node 22.19–24; Node 26 fake-fails on jsdom `localStorage`.
- Unit test classes end in `Test` (never `IntTest`). Test method names are camelCase with **no underscores** — Checkstyle enforces this on all methods in test sources, including private helpers.
- Java style: exactly one blank line before `if`/`for`/`while`/`try`/`switch` (except immediately after an opening `{`), one blank line between a variable modification and the next statement using it, no trailing blank line before a class's closing `}`, no `_` prefix on private methods, no short or cryptic variable names.
- **No new code comments explaining rationale** — rationale goes in the commit message and in Javadoc, not in inline comments.
- `redactMcpResults` must **not** participate in `AiGuardrails#isActive`. Adding it there would make enabling MCP redaction start attaching guardrails advisors to Copilot and the canvas AI Agent.
- The MCP path **never tokenizes**. No `PiiTokenSession` anywhere in this work.

---

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `server/libs/platform/platform-ai/platform-ai-api/src/main/java/com/bytechef/platform/ai/guardrails/McpOutboundRedactorProvider.java` | CE SPI: resolve a redactor for a workspace + surface |
| `.../platform-ai-api/.../guardrails/McpOutboundRedactor.java` | CE SPI: `String -> String` |
| `.../platform-ai-api/.../guardrails/McpOutboundRedactionException.java` | typed unchecked failure, shared by both MCP flavours |
| `.../platform-ai-api/.../guardrails/RedactingToolCallback.java` | the decorator: per-call resolution, fail closed |
| `.../platform-ai-api/src/test/.../guardrails/RedactingToolCallbackTest.java` | decorator unit tests |
| `server/ee/.../platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/mcp/McpOutboundRedactorProviderImpl.java` | EE impl |
| `.../platform-ai-guardrails-service/src/test/.../mcp/McpOutboundRedactorProviderTest.java` | EE impl unit tests |
| `server/libs/automation/.../automation-ai-mcp-server/src/test/.../config/McpOutboundGuardrailsCoverageTest.java` | automation coverage scan |
| `server/ee/libs/embedded/.../embedded-ai-mcp-server/src/test/.../config/McpOutboundGuardrailsCoverageTest.java` | embedded coverage scan |

**Modified:**

| File | Change |
|---|---|
| `.../platform-ai-guardrails-api/.../domain/AiGuardrailsWorkspaceSettings.java` | add `Boolean redactMcpResults` |
| `.../platform-ai-guardrails-service/.../service/AiGuardrailsWorkspaceSettingsServiceImpl.java` | `KEY_REDACT_MCP_RESULTS`, `toMap`, `toSettings` |
| `.../platform-ai-guardrails-graphql/src/main/resources/graphql/ai-guardrails-workspace-settings.graphqls` | field on type + input |
| `.../platform-ai-guardrails-graphql/.../web/graphql/AiGuardrailsWorkspaceSettingsGraphQlController.java` | input record + mapping |
| `.../platform-ai-guardrails-service/.../AiGuardrails.java` | add `resolveMcpOutboundPolicy` |
| `server/libs/automation/.../config/AutomationMcpServerConfiguration.java` | `guard(...)` helper + 3 stream call sites |
| `server/ee/libs/embedded/.../config/EmbeddedMcpServerConfiguration.java` | `guard(...)` helper + 2 stream call sites |
| `client/src/graphql/platform/ai-guardrails/aiGuardrailsWorkspaceSettings.graphql` | request the new field |
| `client/src/shared/middleware/graphql.ts`, `graphql-types.ts` | regenerated |
| `client/src/ee/pages/settings/automation/ai/guardrails/AiGuardrails.tsx` + `.test.tsx` | the checkbox |

**Spec gap this plan closes:** the spec's §Settings specifies storage only. Without the GraphQL and client changes in Tasks 2 and 3 there is no way for a customer to turn the switch on, making the feature unreachable. Those tasks are additions to the spec, not deviations from it.

---

## Task 1: The `redactMcpResults` setting — storage

**Files:**
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-api/src/main/java/com/bytechef/ee/platform/ai/guardrails/domain/AiGuardrailsWorkspaceSettings.java`
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/service/AiGuardrailsWorkspaceSettingsServiceImpl.java`
- Test: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/service/AiGuardrailsWorkspaceSettingsServiceTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `AiGuardrailsWorkspaceSettings.redactMcpResults()` returning `@Nullable Boolean`. The canonical constructor gains a **tenth** parameter, `Boolean redactMcpResults`, positioned **last** (after `Double minConfidence`).

`AiGuardrailsWorkspaceSettings` is a record, so adding a component breaks every construction site. There are 17 across 9 files; the compiler names all of them. Do not guess at them — let `compileJava compileTestJava` drive.

- [ ] **Step 1: Write the failing test**

Append to `AiGuardrailsWorkspaceSettingsServiceTest`:

```java
    @Test
    void testRedactMcpResultsRoundTripsAndIsAbsentFromOldRows() {
        AiGuardrailsWorkspaceSettings saved = new AiGuardrailsWorkspaceSettings(
            1L, null, null, null, null, null, null, null, null, true);

        aiGuardrailsWorkspaceSettingsService.saveSettings(saved);

        Optional<AiGuardrailsWorkspaceSettings> fetched =
            aiGuardrailsWorkspaceSettingsService.fetchSettings(1L);

        assertThat(fetched).isPresent();
        assertThat(fetched.get()
            .redactMcpResults()).isTrue();
    }

    @Test
    void testARowStoredBeforeThisFieldExistedReadsAsNull() {
        // A property value map written by an earlier version carries no key for this field at all.
        AiGuardrailsWorkspaceSettings settings = new AiGuardrailsWorkspaceSettings(
            1L, true, null, null, null, null, null, null, null, null);

        aiGuardrailsWorkspaceSettingsService.saveSettings(settings);

        Optional<AiGuardrailsWorkspaceSettings> fetched =
            aiGuardrailsWorkspaceSettingsService.fetchSettings(1L);

        assertThat(fetched).isPresent();
        assertThat(fetched.get()
            .redactMcpResults())
                .as("absent key must read as null, not false, so it unions as 'not set at this level'")
                .isNull();
    }
```

Match the existing test class's setup for `aiGuardrailsWorkspaceSettingsService` and its `PropertyService` stub — read the file first and reuse whatever fixture the neighbouring tests use rather than inventing one.

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsWorkspaceSettingsServiceTest*' > /tmp/t.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/t.log
```

Expected: FAIL — the constructor takes 9 arguments, not 10.

- [ ] **Step 3: Add the record component**

In `AiGuardrailsWorkspaceSettings`, add as the final component:

```java
    Double minConfidence, // null = use SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE
    Boolean redactMcpResults) { // null = not set at this level = off
```

Add to the record's class Javadoc:

```
 * <p>
 * {@code redactMcpResults} is deliberately a separate switch from {@code redactPii}/{@code redactSecrets} rather
 * than something they imply. An MCP tool is frequently how a customer hands data to their own agent on purpose;
 * enabling guardrails for chat surfaces must not silently start returning {@code [REDACTED_EMAIL_ADDRESS]} into a
 * working MCP pipeline. It selects the surface; the categories and threshold still come from the fields above.
 * </p>
```

- [ ] **Step 4: Persist and read the field**

In `AiGuardrailsWorkspaceSettingsServiceImpl`, add the constant alphabetically among the others:

```java
    private static final String KEY_REDACT_MCP_RESULTS = "redactMcpResults";
```

In `toMap`, after the `KEY_MIN_CONFIDENCE` block:

```java
        if (settings.redactMcpResults() != null) {
            value.put(KEY_REDACT_MCP_RESULTS, settings.redactMcpResults());
        }
```

In `toSettings`, add as the final constructor argument:

```java
            (Boolean) value.get(KEY_REDACT_MCP_RESULTS));
```

- [ ] **Step 5: Fix every construction site the compiler names**

```bash
./gradlew compileJava compileTestJava --continue > /tmp/c.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/c.log; grep -n "constructor AiGuardrailsWorkspaceSettings" /tmp/c.log
```

Pass `null` as the new final argument at every site except the two new tests above. The 9 files are: the GraphQL controller and its test, `AiGuardrailsTest`, `AiGuardrailsAdvisorTest`, `AiGuardrailsWorkspaceSettingsServiceTest`, `AiGuardrailsWorkspaceSettingsServiceImpl`, `AiGuardrailsWorkspaceSettingsTest`, `AiHubSpringAIAgentGuardrailsTest`, `AiGatewayGuardrailsTest`. Repeat until the compile is clean.

- [ ] **Step 6: Run the tests to verify they pass**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsWorkspaceSettingsServiceTest*' > /tmp/t.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/t.log
```

Expected: EXIT=0.

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply > /tmp/s.log 2>&1; echo "EXIT=$?"
git add -A
git commit -m "732 Add redactMcpResults to AiGuardrailsWorkspaceSettings"
```

---

## Task 2: Expose `redactMcpResults` over GraphQL

**Files:**
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-graphql/src/main/resources/graphql/ai-guardrails-workspace-settings.graphqls`
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-graphql/src/main/java/com/bytechef/ee/platform/ai/guardrails/web/graphql/AiGuardrailsWorkspaceSettingsGraphQlController.java`
- Test: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-graphql/src/test/java/com/bytechef/ee/platform/ai/guardrails/web/graphql/AiGuardrailsWorkspaceSettingsGraphQlControllerTest.java`

**Interfaces:**
- Consumes: `AiGuardrailsWorkspaceSettings.redactMcpResults()` from Task 1.
- Produces: GraphQL field `redactMcpResults: Boolean` on both `AiGuardrailsWorkspaceSettings` and `AiGuardrailsWorkspaceSettingsInput`. Task 3's client operation reads this name.

- [ ] **Step 1: Write the failing test**

Append to `AiGuardrailsWorkspaceSettingsGraphQlControllerTest`, matching the existing tests' fixture style:

```java
    @Test
    void testSaveSettingsPassesRedactMcpResultsThrough() {
        AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput input =
            new AiGuardrailsWorkspaceSettingsGraphQlController.AiGuardrailsWorkspaceSettingsInput(
                1L, null, null, null, null, null, null, null, null, true);

        aiGuardrailsWorkspaceSettingsGraphQlController.saveAiGuardrailsWorkspaceSettings(input);

        ArgumentCaptor<AiGuardrailsWorkspaceSettings> captor =
            ArgumentCaptor.forClass(AiGuardrailsWorkspaceSettings.class);

        verify(aiGuardrailsWorkspaceSettingsService).saveSettings(captor.capture());

        AiGuardrailsWorkspaceSettings saved = captor.getValue();

        assertThat(saved.redactMcpResults()).isTrue();
    }
```

Read the existing test class first: reuse its field names, mock setup, and the exact controller method name (it may differ from `saveAiGuardrailsWorkspaceSettings`).

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-graphql:test > /tmp/t.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/t.log
```

Expected: FAIL — the input record takes 9 arguments.

- [ ] **Step 3: Add the schema field**

In `ai-guardrails-workspace-settings.graphqls`, add to **both** the `type` and the `input`, positioned to match where the other booleans sit:

```graphql
    redactMcpResults: Boolean
```

- [ ] **Step 4: Add it to the controller**

Add `@Nullable Boolean redactMcpResults` as the final component of the `AiGuardrailsWorkspaceSettingsInput` record, and pass `input.redactMcpResults()` as the final argument of the `new AiGuardrailsWorkspaceSettings(...)` call in the mutation method (replacing the `null` Task 1 left there).

- [ ] **Step 5: Run the test to verify it passes**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-graphql:test > /tmp/t.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/t.log
```

Expected: EXIT=0.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply > /tmp/s.log 2>&1; echo "EXIT=$?"
git add -A
git commit -m "732 Expose redactMcpResults over GraphQL"
```

---

## Task 3: The settings checkbox (client)

**Files:**
- Modify: `client/src/graphql/platform/ai-guardrails/aiGuardrailsWorkspaceSettings.graphql`
- Modify: `client/src/shared/middleware/graphql.ts`, `client/src/shared/middleware/graphql-types.ts` (generated)
- Modify: `client/src/ee/pages/settings/automation/ai/guardrails/AiGuardrails.tsx`
- Test: `client/src/ee/pages/settings/automation/ai/guardrails/AiGuardrails.test.tsx`

**Interfaces:**
- Consumes: the GraphQL field `redactMcpResults` from Task 2.
- Produces: nothing later tasks depend on.

Read `AiGuardrails.tsx` and its test in full before editing. Add the new control by copying the shape of the existing `redactPii` control exactly — same wrapper element, same label/description structure, same state-update idiom. Do not introduce a different pattern for this one field.

- [ ] **Step 1: Add the field to the GraphQL operation**

Add `redactMcpResults` to the operation's selection set and to its input variables, next to `redactPii`.

- [ ] **Step 2: Regenerate the client types**

```bash
cd client && npx graphql-codegen
```

- [ ] **Step 3: Commit the operation and generated file separately**

```bash
git add client/src/graphql/platform/ai-guardrails/aiGuardrailsWorkspaceSettings.graphql
git commit -m "732 client - Request redactMcpResults in the guardrails settings operation"
git add client/src/shared/middleware/graphql.ts client/src/shared/middleware/graphql-types.ts
git commit -m "732 client - Regenerate GraphQL types"
```

- [ ] **Step 4: Write the failing test**

Add to `AiGuardrails.test.tsx`, mirroring the existing `redactPii` test's structure:

```tsx
    it('renders the MCP results toggle off by default and saves it when switched on', async () => {
        renderComponent();

        const toggle = await screen.findByLabelText(/redact mcp results/i);

        expect(toggle).not.toBeChecked();

        await userEvent.click(toggle);

        expect(toggle).toBeChecked();
    });
```

Adjust the label matcher to whatever copy Step 5 uses, and `renderComponent` to the existing helper's name in that file.

- [ ] **Step 5: Add the control**

In `AiGuardrails.tsx`, add a toggle bound to `redactMcpResults`, copying the `redactPii` control's markup. Label it **"Redact MCP tool results"** with the description **"Redact sensitive data from results returned to external agents through MCP servers. Off by default, and independent of the settings above."**

Remember the client conventions: object keys in natural ascending order (ESLint `sort-keys` does not autofix), named imports sorted alphabetically inside `{}`, `twMerge` rather than `cn()`, and hook ordering `useState` → `useRef` → store hooks → other hooks → derived values → `useEffect` → `return`.

- [ ] **Step 6: Run the full client check**

```bash
cd client && npm run check
```

Use a **600000 ms** tool timeout. Expected: passes. If `prettier` reformats anything, re-run before committing — it runs first in CI.

- [ ] **Step 7: Commit**

```bash
git add client/src/ee/pages/settings/automation/ai/guardrails
git commit -m "732 client - Add the Redact MCP tool results toggle"
```

---

## Task 4: `AiGuardrails#resolveMcpOutboundPolicy`

**Files:**
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/AiGuardrails.java`
- Test: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/AiGuardrailsTest.java`

**Interfaces:**
- Consumes: `AiGuardrailsWorkspaceSettings.redactMcpResults()` from Task 1.
- Produces: `public @Nullable PiiTokenBoundaryPolicy resolveMcpOutboundPolicy(@Nullable Long workspaceId)` — null when the switch is off, otherwise kinds + `minConfidence`. Task 6 calls it.

This reuses the existing `PiiTokenBoundaryPolicy` record (`Set<SensitiveKind> kinds, double minConfidence`) rather than defining a near-duplicate. Its name is wrong for a redaction-only path; renaming it to `SensitiveDataPolicy` is recorded in the spec as separate follow-up and is **not** part of this task.

- [ ] **Step 1: Write the failing test**

Append to `AiGuardrailsTest`, reusing that class's existing fixture for constructing an `AiGuardrails` with stubbed settings:

```java
    @Test
    void testResolveMcpOutboundPolicyReturnsNullWhenTheSwitchIsOff() {
        stubSettings(new AiGuardrailsWorkspaceSettings(
            1L, true, true, null, null, null, null, null, null, null));

        assertThat(aiGuardrails.resolveMcpOutboundPolicy(1L))
            .as("redactPii being on must not imply MCP outbound redaction")
            .isNull();
    }

    @Test
    void testResolveMcpOutboundPolicyCarriesTheWorkspacesKindsAndThreshold() {
        stubSettings(new AiGuardrailsWorkspaceSettings(
            1L, true, false, null, null, null, null, null, 0.7, true));

        PiiTokenBoundaryPolicy policy = aiGuardrails.resolveMcpOutboundPolicy(1L);

        assertThat(policy).isNotNull();
        assertThat(policy.kinds()).containsExactly(SensitiveKind.PII);
        assertThat(policy.minConfidence()).isEqualTo(0.7);
    }

    @Test
    void testResolveMcpOutboundPolicyDoesNotAffectIsActive() {
        stubSettings(new AiGuardrailsWorkspaceSettings(
            1L, null, null, null, null, null, null, null, null, true));

        assertThat(aiGuardrails.isActive(1L))
            .as("enabling MCP outbound redaction must not start attaching advisors to chat surfaces")
            .isFalse();
    }
```

Replace `stubSettings` with whatever the existing test class uses to feed a settings row; read it first.

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsTest*' > /tmp/t.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/t.log
```

Expected: FAIL — `resolveMcpOutboundPolicy` does not exist.

- [ ] **Step 3: Implement the method**

Add to `AiGuardrails`, directly after `resolveToolBoundaryPolicy`:

```java
    /**
     * Returns the policy for redacting an MCP server's outbound tool results, or {@code null} when
     * {@code redactMcpResults} is unset or false for {@code workspaceId}.
     *
     * <p>
     * Gated on its own setting rather than on {@link #isActive} or on {@code redactPii}/{@code redactSecrets}: an MCP
     * tool is frequently how a customer hands data to their own agent on purpose, so enabling guardrails for chat
     * surfaces must not silently start rewriting an MCP pipeline's payloads. The switch selects the surface; the kinds
     * and threshold still come from the same workspace fields every other surface reads.
     * </p>
     *
     * <p>
     * Reads the settings row directly rather than going through {@link #resolvePolicy}: {@code redactMcpResults} has no
     * global counterpart to union with, so there is nothing for the effective-policy machinery to combine.
     * </p>
     *
     * @param workspaceId the workspace to resolve, or {@code null} for the tenant default
     * @return the outbound policy, or {@code null} when outbound redaction is off
     */
    public @Nullable PiiTokenBoundaryPolicy resolveMcpOutboundPolicy(@Nullable Long workspaceId) {
        AiGuardrailsWorkspaceSettings settings = findSettings(workspaceId);

        if (settings == null || !Boolean.TRUE.equals(settings.redactMcpResults())) {
            return null;
        }

        return resolveToolBoundaryPolicy(workspaceId);
    }
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsTest*' > /tmp/t.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/t.log
```

Expected: EXIT=0.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply > /tmp/s.log 2>&1; echo "EXIT=$?"
git add -A
git commit -m "732 Resolve an MCP outbound redaction policy from its own workspace switch"
```

---

## Task 5: The CE SPI and `RedactingToolCallback`

**Files:**
- Create: `server/libs/platform/platform-ai/platform-ai-api/src/main/java/com/bytechef/platform/ai/guardrails/McpOutboundRedactor.java`
- Create: `.../McpOutboundRedactorProvider.java`
- Create: `.../McpOutboundRedactionException.java`
- Create: `.../RedactingToolCallback.java`
- Test: `server/libs/platform/platform-ai/platform-ai-api/src/test/java/com/bytechef/platform/ai/guardrails/RedactingToolCallbackTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `interface McpOutboundRedactor { String redact(String serializedResult); }`
  - `interface McpOutboundRedactorProvider { Optional<McpOutboundRedactor> fetchRedactor(@Nullable Long workspaceId, String surface); }`
  - `class McpOutboundRedactionException extends RuntimeException`
  - `RedactingToolCallback.wrap(ToolCallback delegate, ObjectProvider<McpOutboundRedactorProvider> provider, @Nullable Long workspaceId, String surface)` returning `ToolCallback`

`McpServerErrorType` is **not** usable here — it lives in `com.bytechef.automation.ai.mcp.server.exception`, the automation module, and this class is shared with embedded. Hence a typed exception beside the SPI.

- [ ] **Step 1: Write the failing test**

Create `RedactingToolCallbackTest`:

```java
class RedactingToolCallbackTest {

    private static final String SURFACE = "mcp_automation";

    @Test
    void testRedactsTheDelegatesResult() {
        ToolCallback toolCallback = wrap(
            new FixedToolCallback("{\"email\":\"bob@acme.io\"}"),
            providerReturning(result -> result.replace("bob@acme.io", "[REDACTED_EMAIL_ADDRESS]")));

        assertThat(toolCallback.call("{}")).isEqualTo("{\"email\":\"[REDACTED_EMAIL_ADDRESS]\"}");
    }

    @Test
    void testReturnsTheResultUntouchedWhenNoRedactorResolves() {
        ToolCallback toolCallback = wrap(new FixedToolCallback("{\"email\":\"bob@acme.io\"}"), emptyProvider());

        assertThat(toolCallback.call("{}")).isEqualTo("{\"email\":\"bob@acme.io\"}");
    }

    @Test
    void testFailsClosedWhenRedactionThrows() {
        ToolCallback toolCallback = wrap(
            new FixedToolCallback("{\"email\":\"bob@acme.io\"}"), providerReturning(result -> {
                throw new IllegalStateException("detector exploded");
            }));

        assertThatThrownBy(() -> toolCallback.call("{}"))
            .as("the raw payload must never be returned when redaction fails")
            .isInstanceOf(McpOutboundRedactionException.class);
    }

    @Test
    void testResolvesTheRedactorPerCallNotAtConstruction() {
        AtomicReference<McpOutboundRedactor> current = new AtomicReference<>();

        McpOutboundRedactorProvider provider =
            (workspaceId, surface) -> Optional.ofNullable(current.get());

        ToolCallback toolCallback = wrap(new FixedToolCallback("bob@acme.io"), staticProvider(provider));

        assertThat(toolCallback.call("{}")).isEqualTo("bob@acme.io");

        current.set(result -> "[REDACTED_EMAIL_ADDRESS]");

        assertThat(toolCallback.call("{}"))
            .as("a client's cached tools/list must not pin the redactor resolved when the list was built")
            .isEqualTo("[REDACTED_EMAIL_ADDRESS]");
    }

    private static ToolCallback wrap(
        ToolCallback delegate, ObjectProvider<McpOutboundRedactorProvider> providerProvider) {

        return RedactingToolCallback.wrap(delegate, providerProvider, 1L, SURFACE);
    }

    private static ObjectProvider<McpOutboundRedactorProvider> providerReturning(McpOutboundRedactor redactor) {
        return staticProvider((workspaceId, surface) -> Optional.of(redactor));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<McpOutboundRedactorProvider> emptyProvider() {
        ObjectProvider<McpOutboundRedactorProvider> providerProvider = mock(ObjectProvider.class);

        when(providerProvider.getIfAvailable()).thenReturn(null);

        return providerProvider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<McpOutboundRedactorProvider> staticProvider(
        McpOutboundRedactorProvider provider) {

        ObjectProvider<McpOutboundRedactorProvider> providerProvider = mock(ObjectProvider.class);

        when(providerProvider.getIfAvailable()).thenReturn(provider);

        return providerProvider;
    }

    private record FixedToolCallback(String result) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("sendEmail")
                .description("sends an email")
                .inputSchema("{}")
                .build();
        }

        @Override
        public String call(String toolInput) {
            return result;
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :server:libs:platform:platform-ai:platform-ai-api:test > /tmp/t.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/t.log
```

Expected: FAIL — the classes do not exist. No build-file change is needed: `platform-ai-api` already has a `src/test` source set and declares `test-support`, which brings JUnit 5, AssertJ and Mockito. Note that declaring `test-support` also inherits a 30-second per-test timeout; these are pure unit tests and will not approach it.

- [ ] **Step 3: Create the two SPI interfaces**

`McpOutboundRedactor.java`:

```java
/**
 * Redacts an MCP tool's already-serialized result before it is returned to the calling agent. Resolved for one
 * workspace and one surface by {@link McpOutboundRedactorProvider}, so implementations carry their policy rather than
 * taking it per call.
 *
 * @author Ivica Cardic
 */
@FunctionalInterface
public interface McpOutboundRedactor {

    /**
     * @param serializedResult the tool result as {@code ToolCallback#call} produced it
     * @return the redacted result
     */
    String redact(String serializedResult);
}
```

`McpOutboundRedactorProvider.java`:

```java
/**
 * CE seam so the MCP server modules can redact outbound tool results without depending on the EE guardrails module,
 * the same CE-SPI/EE-impl idiom as {@code AiGuardrailsAdvisorProvider} and {@code ToolExecutionRecorder}.
 *
 * <p>
 * Hands back a fully resolved {@link McpOutboundRedactor} rather than a policy: returning a policy would leak
 * {@code SensitiveKind}, confidence thresholds and metrics tagging into two MCP modules that have no business knowing
 * them.
 * </p>
 *
 * @author Ivica Cardic
 */
public interface McpOutboundRedactorProvider {

    /**
     * @param workspaceId the MCP server's workspace, or {@code null} for the tenant default (embedded MCP servers are
     *                    {@code Scope.EMBEDDED}, not workspace-scoped, and always pass {@code null})
     * @param surface     {@code "mcp_automation"} or {@code "mcp_embedded"}, for metrics tagging
     * @return a resolved redactor, or empty when outbound redaction is off for this workspace
     */
    Optional<McpOutboundRedactor> fetchRedactor(@Nullable Long workspaceId, String surface);
}
```

`McpOutboundRedactionException.java`:

```java
/**
 * Thrown when redacting an MCP tool's outbound result fails. {@link RedactingToolCallback} fails closed on it rather
 * than returning the unredacted payload; the MCP layer converts the throw into a tool error the calling agent sees,
 * the same channel the MCP facades already use to refuse a disabled server or tool.
 *
 * @author Ivica Cardic
 */
public class McpOutboundRedactionException extends RuntimeException {

    public McpOutboundRedactionException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 4: Create the decorator**

`RedactingToolCallback.java`:

```java
/**
 * Decorates a {@link ToolCallback} registered on an automation or embedded MCP server so its result is redacted before
 * it leaves the process.
 *
 * <p>
 * MCP is the only ByteChef surface reaching a model the tenant did not choose: an external agent calls a tool, a
 * workflow runs, and the result lands in that agent's context permanently. There is no return path, so this surface
 * redacts irreversibly rather than tokenizing -- a token nothing ever restores is a broken value in someone else's
 * context, not a protected one.
 * </p>
 *
 * <p>
 * {@link ToolCallback#call} returns the result already serialized, so a single redaction pass covers every nested
 * field. Walking the result object instead would have to handle {@code Map}, {@code List}, arrays, records and nulls,
 * and would leak silently on any node type it missed.
 * </p>
 *
 * <p>
 * The redactor is resolved per call, never at registration: MCP clients cache {@code tools/list} for a long time, so
 * binding it when the tool list was assembled would mean flipping the workspace setting did nothing until the server
 * restarted.
 * </p>
 *
 * @author Ivica Cardic
 */
public class RedactingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(RedactingToolCallback.class);

    private final ToolCallback delegate;
    private final ObjectProvider<McpOutboundRedactorProvider> mcpOutboundRedactorProviderProvider;
    private final @Nullable Long workspaceId;
    private final String surface;

    private RedactingToolCallback(
        ToolCallback delegate, ObjectProvider<McpOutboundRedactorProvider> mcpOutboundRedactorProviderProvider,
        @Nullable Long workspaceId, String surface) {

        this.delegate = delegate;
        this.mcpOutboundRedactorProviderProvider = mcpOutboundRedactorProviderProvider;
        this.workspaceId = workspaceId;
        this.surface = surface;
    }

    /**
     * @param delegate                            the callback to decorate
     * @param mcpOutboundRedactorProviderProvider resolves the EE provider, absent in CE builds
     * @param workspaceId                         the MCP server's workspace, or {@code null} for the tenant default
     * @param surface                             {@code "mcp_automation"} or {@code "mcp_embedded"}
     * @return a {@link ToolCallback} that redacts {@code delegate}'s result
     */
    public static ToolCallback wrap(
        ToolCallback delegate, ObjectProvider<McpOutboundRedactorProvider> mcpOutboundRedactorProviderProvider,
        @Nullable Long workspaceId, String surface) {

        return new RedactingToolCallback(delegate, mcpOutboundRedactorProviderProvider, workspaceId, surface);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return redact(delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        return redact(delegate.call(toolInput, toolContext));
    }

    private String redact(String result) {
        McpOutboundRedactor redactor = fetchRedactor();

        if (redactor == null) {
            return result;
        }

        try {
            return redactor.redact(result);
        } catch (RuntimeException redactionException) {
            log.warn(
                "Failed to redact an outbound MCP tool result on surface {}; refusing the call rather than returning "
                    + "it unredacted",
                surface, redactionException);

            throw new McpOutboundRedactionException(
                "Failed to redact the tool result", redactionException);
        }
    }

    private @Nullable McpOutboundRedactor fetchRedactor() {
        McpOutboundRedactorProvider mcpOutboundRedactorProvider =
            mcpOutboundRedactorProviderProvider.getIfAvailable();

        if (mcpOutboundRedactorProvider == null) {
            return null;
        }

        return mcpOutboundRedactorProvider.fetchRedactor(workspaceId, surface)
            .orElse(null);
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./gradlew :server:libs:platform:platform-ai:platform-ai-api:test > /tmp/t.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/t.log
```

Expected: EXIT=0.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply > /tmp/s.log 2>&1; echo "EXIT=$?"
git add -A
git commit -m "732 Add the MCP outbound redaction SPI and its ToolCallback decorator"
```

---

## Task 6: The EE provider implementation

**Files:**
- Create: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/mcp/McpOutboundRedactorProviderImpl.java`
- Test: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/mcp/McpOutboundRedactorProviderTest.java`

**Interfaces:**
- Consumes: `AiGuardrails#resolveMcpOutboundPolicy` (Task 4), `McpOutboundRedactorProvider` / `McpOutboundRedactor` (Task 5).
- Produces: a `@Component` `McpOutboundRedactorProvider` bean. Task 7 injects it as `ObjectProvider<McpOutboundRedactorProvider>`.

- [ ] **Step 1: Write the failing test**

```java
class McpOutboundRedactorProviderTest {

    private final AiGuardrails aiGuardrails = mock(AiGuardrails.class);
    private final SensitiveDataRedactor sensitiveDataRedactor = mock(SensitiveDataRedactor.class);

    @Test
    void testEmptyWhenTheWorkspaceHasOutboundRedactionOff() {
        when(aiGuardrails.resolveMcpOutboundPolicy(1L)).thenReturn(null);

        assertThat(newProvider().fetchRedactor(1L, "mcp_automation")).isEmpty();
    }

    @Test
    void testRedactsWithTheResolvedPolicysKindsAndThreshold() {
        when(aiGuardrails.resolveMcpOutboundPolicy(1L))
            .thenReturn(new PiiTokenBoundaryPolicy(Set.of(SensitiveKind.PII), 0.7));
        when(
            sensitiveDataRedactor.redact(
                eq("bob@acme.io"), eq(Set.of(SensitiveKind.PII)), eq(0.7), any()))
                    .thenReturn("[REDACTED_EMAIL_ADDRESS]");

        Optional<McpOutboundRedactor> redactor = newProvider().fetchRedactor(1L, "mcp_automation");

        assertThat(redactor).isPresent();
        assertThat(redactor.get()
            .redact("bob@acme.io")).isEqualTo("[REDACTED_EMAIL_ADDRESS]");
    }

    @Test
    void testResolvesTheTenantDefaultForANullWorkspace() {
        when(aiGuardrails.resolveMcpOutboundPolicy(null))
            .thenReturn(new PiiTokenBoundaryPolicy(Set.of(SensitiveKind.PII), 0.4));

        assertThat(newProvider().fetchRedactor(null, "mcp_embedded")).isPresent();
    }

    @SuppressWarnings("unchecked")
    private McpOutboundRedactorProviderImpl newProvider() {
        return new McpOutboundRedactorProviderImpl(
            aiGuardrails, sensitiveDataRedactor, mock(ObjectProvider.class));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*McpOutboundRedactorProviderTest*' > /tmp/t.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/t.log
```

Expected: FAIL — the class does not exist.

- [ ] **Step 3: Implement it**

```java
/**
 * EE implementation of the MCP outbound redaction seam. Resolves the workspace's outbound policy once per
 * {@link #fetchRedactor} call and closes over it, so the returned redactor carries its kinds, threshold and
 * surface-tagged metrics rather than re-resolving them for every value.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class McpOutboundRedactorProviderImpl implements McpOutboundRedactorProvider {

    private final AiGuardrails aiGuardrails;
    private final SensitiveDataRedactor sensitiveDataRedactor;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public McpOutboundRedactorProviderImpl(
        AiGuardrails aiGuardrails, SensitiveDataRedactor sensitiveDataRedactor,
        ObjectProvider<MeterRegistry> meterRegistryProvider) {

        this.aiGuardrails = aiGuardrails;
        this.sensitiveDataRedactor = sensitiveDataRedactor;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    @Override
    public Optional<McpOutboundRedactor> fetchRedactor(@Nullable Long workspaceId, String surface) {
        PiiTokenBoundaryPolicy policy = aiGuardrails.resolveMcpOutboundPolicy(workspaceId);

        if (policy == null) {
            return Optional.empty();
        }

        AiGuardrailMetrics metrics = new AiGuardrailMetrics(meterRegistryProvider.getIfAvailable(), surface);

        return Optional.of(
            serializedResult -> sensitiveDataRedactor.redact(
                serializedResult, policy.kinds(), policy.minConfidence(), metrics));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*McpOutboundRedactorProviderTest*' > /tmp/t.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/t.log
```

Expected: EXIT=0. If the module cannot see `McpOutboundRedactorProvider`, add `implementation(project(":server:libs:platform:platform-ai:platform-ai-api"))` to its `build.gradle.kts`.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply > /tmp/s.log 2>&1; echo "EXIT=$?"
git add -A
git commit -m "732 Implement the MCP outbound redaction provider"
```

---

## Task 7: Wire both MCP configurations, with coverage scans

**Files:**
- Modify: `server/libs/automation/automation-ai/automation-ai-mcp-server/src/main/java/com/bytechef/automation/ai/mcp/server/config/AutomationMcpServerConfiguration.java`
- Modify: `server/ee/libs/embedded/embedded-ai/embedded-ai-mcp-server/src/main/java/com/bytechef/ee/embedded/ai/mcp/server/config/EmbeddedMcpServerConfiguration.java`
- Test: `server/libs/automation/automation-ai/automation-ai-mcp-server/src/test/java/com/bytechef/automation/ai/mcp/server/config/McpOutboundGuardrailsCoverageTest.java`
- Test: `server/ee/libs/embedded/embedded-ai/embedded-ai-mcp-server/src/test/java/com/bytechef/ee/embedded/ai/mcp/server/config/McpOutboundGuardrailsCoverageTest.java`

**Interfaces:**
- Consumes: `RedactingToolCallback.wrap(...)` (Task 5) and the `McpOutboundRedactorProvider` bean (Task 6).
- Produces: nothing.

Five wrap points. In `AutomationMcpServerConfiguration` the three streams are around lines 195, 203 and 212; in `EmbeddedMcpServerConfiguration` the two are around lines 186–190 and 201–203. Line numbers drift — locate by the `toAsyncToolSpecification` calls, not by number.

- [ ] **Step 1: Write the failing coverage scan (automation)**

```java
/**
 * Every {@code ToolCallback} this configuration registers must be wrapped by {@code RedactingToolCallback} before it
 * becomes an MCP tool specification, or its results reach the calling agent unredacted.
 *
 * <p>
 * A source scan rather than a context test: the configuration assembles tools from three separate streams, and the
 * regression this guards against is a fourth stream added later that forgets to call {@code guard(...)}. Five wrap
 * points across two configurations is the same shape that let a guardrails gap survive five rounds of fixes on the
 * Copilot surfaces, each round measuring only the file it touched.
 * </p>
 *
 * @author Ivica Cardic
 */
class McpOutboundGuardrailsCoverageTest {

    private static final Path CONFIGURATION_SOURCE = Path.of(
        "src/main/java/com/bytechef/automation/ai/mcp/server/config/AutomationMcpServerConfiguration.java");

    private static final Pattern COMMENT_PATTERN = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);

    private static final String SPECIFICATION_CALL = "toAsyncToolSpecification";

    private static final String GUARD_CALL = "guard(";

    @Test
    void testEveryToolSpecificationIsBuiltFromAGuardedCallback() throws IOException {
        assertTrue(
            Files.isRegularFile(CONFIGURATION_SOURCE),
            "Configuration source not found, working directory is wrong: " + CONFIGURATION_SOURCE);

        String source = COMMENT_PATTERN.matcher(Files.readString(CONFIGURATION_SOURCE))
            .replaceAll("");

        List<String> violations = new ArrayList<>();

        int siteCount = 0;

        for (String statement : source.split(";")) {
            if (!statement.contains(SPECIFICATION_CALL)) {
                continue;
            }

            siteCount++;

            if (!statement.contains(GUARD_CALL)) {
                violations.add(statement.strip());
            }
        }

        assertEquals(
            3, siteCount,
            "Expected 3 tool-specification sites in this configuration; the scan or the file has changed. Verify the "
                + "new site is guarded, then update this count deliberately.");

        if (!violations.isEmpty()) {
            fail(
                "Found " + violations.size() + " of " + siteCount + " tool-specification site(s) built from an "
                    + "unguarded ToolCallback - wrap the callback in guard(...) so its results are redacted before "
                    + "they reach the calling agent:\n" + String.join("\n---\n", violations));
        }
    }
}
```

Write the embedded counterpart identically, with `CONFIGURATION_SOURCE` pointing at
`src/main/java/com/bytechef/ee/embedded/ai/mcp/server/config/EmbeddedMcpServerConfiguration.java`, an expected
`siteCount` of **2**, the ByteChef Enterprise header and a `@version ee` tag.

- [ ] **Step 2: Run both scans to verify they fail**

```bash
./gradlew :server:libs:automation:automation-ai:automation-ai-mcp-server:test :server:ee:libs:embedded:embedded-ai:embedded-ai-mcp-server:test --tests '*McpOutboundGuardrailsCoverageTest*' --continue > /tmp/t.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/t.log
```

Expected: FAIL — no site carries `guard(`.

- [ ] **Step 3: Add the helper and wire the automation streams**

Add `ObjectProvider<McpOutboundRedactorProvider> mcpOutboundRedactorProviderProvider` as a parameter of the bean method that assembles the tools, and this private helper to the class:

```java
    private ToolCallback guard(ToolCallback toolCallback, @Nullable Long workspaceId) {
        return RedactingToolCallback.wrap(
            toolCallback, mcpOutboundRedactorProviderProvider, workspaceId, "mcp_automation");
    }
```

Insert `guard(...)` into all three streams, before `toAsyncToolSpecification`:

```java
                .map(mcpTool -> McpToolUtils.toAsyncToolSpecification(
                    guard(mcpToolFacade.getFunctionToolCallback(mcpTool), workspaceId)))
```

```java
            .map(toolCallback -> guard(toolCallback, workspaceId))
            .map(McpToolUtils::toAsyncToolSpecification)
```

for the workflow stream (before the existing `ApprovalElicitingToolSpecifications.decorate` map, which operates on the specification), and the same `.map(toolCallback -> guard(toolCallback, workspaceId))` in the workspace-contributor stream.

The workspace-contributor stream already resolves `workspaceId` inside `fetchWorkspaceIdByMcpServerId(...).ifPresent(workspaceId -> ...)`. Hoist that resolution above the first stream so all three share it:

```java
        Long workspaceId = workspaceMcpServerService.fetchWorkspaceIdByMcpServerId(mcpServer.getId())
            .orElse(null);
```

and change the contributor stream to guard on `workspaceId != null` rather than re-fetching.

- [ ] **Step 4: Wire the embedded streams**

Same helper with surface `"mcp_embedded"` and `workspaceId` **always `null`** — embedded MCP servers are `Scope.EMBEDDED`, not workspace-scoped, so they resolve the tenant-default settings row:

```java
    private ToolCallback guard(ToolCallback toolCallback) {
        return RedactingToolCallback.wrap(toolCallback, mcpOutboundRedactorProviderProvider, null, "mcp_embedded");
    }
```

Apply it at both `toAsyncToolSpecification` sites.

Add `implementation(project(":server:libs:platform:platform-ai:platform-ai-api"))` to either module's `build.gradle.kts` only if the compile says it is missing — both already declare it.

- [ ] **Step 5: Run both scans and both modules' full test suites**

```bash
./gradlew :server:libs:automation:automation-ai:automation-ai-mcp-server:test :server:ee:libs:embedded:embedded-ai:embedded-ai-mcp-server:test --continue > /tmp/t.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/t.log
```

Expected: EXIT=0.

- [ ] **Step 6: Negative-control both scans**

Remove one `guard(` call from the automation configuration, re-run its scan, confirm it FAILS naming that site, then restore it. Repeat for embedded. A coverage scan that has never been seen to fail is not evidence.

```bash
./gradlew :server:libs:automation:automation-ai:automation-ai-mcp-server:test --tests '*McpOutboundGuardrailsCoverageTest*' > /tmp/n.log 2>&1; echo "EXIT=$? (expect non-zero)"; grep -n "unguarded ToolCallback" /tmp/n.log
```

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply > /tmp/s.log 2>&1; echo "EXIT=$?"
git add -A
git commit -m "732 Redact outbound results on the automation and embedded MCP servers"
```

---

## Task 8: Document the surface

**Files:**
- Modify: `.agents/ai-guardrails.md`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Add an MCP section**

Add a `## MCP outbound` section after `## Tool boundary`, recording: the four surfaces and that MCP is now the fourth; that it redacts rather than tokenizes and why; the `redactMcpResults` switch and why it is separate from `redactPii`; that it does not participate in `isActive`; that embedded resolves the tenant default and therefore cannot vary per workspace; the fail-closed policy; and the two coverage scans with their expected site counts.

State plainly that management MCP remains uncovered, so the doc does not read as a claim that all MCP surfaces are guarded.

- [ ] **Step 2: Commit**

```bash
git add .agents/ai-guardrails.md
git commit -m "732 Document MCP outbound guardrails"
```

---

## Self-review notes

**Spec coverage.** Every spec section maps to a task: Scope → Tasks 5–7; Why redaction not tokenization → Task 5 Javadoc; Attachment point + five wrap points → Task 7; SPI seam → Task 5; Settings → Tasks 1, 2, 4; embedded tenant-default → Tasks 6, 7; fail closed → Task 5; coverage enforcement → Task 7; testing → each task's own steps.

**Spec gap found and closed.** The spec specified settings *storage* but no way to set the value, which would have shipped an unreachable feature. Tasks 2 and 3 add the GraphQL and client surface. Fold this back into the spec when the work lands.

**Type consistency.** `McpOutboundRedactor#redact`, `McpOutboundRedactorProvider#fetchRedactor`, `RedactingToolCallback#wrap`, `AiGuardrails#resolveMcpOutboundPolicy` and `AiGuardrailsWorkspaceSettings#redactMcpResults` are spelled identically in every task that references them. The new record component is last in the canonical constructor in Tasks 1, 2 and 4 alike.

**Deliberately not done here:** renaming `PiiTokenBoundaryPolicy` to `SensitiveDataPolicy` (three referencing sites, recorded in the spec as follow-up); management MCP; inbound arguments; per-server granularity; making redaction visible in `ToolExecutionRecorder` history.
