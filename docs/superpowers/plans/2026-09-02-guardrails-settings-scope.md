# Guardrails Settings Scope Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Guardrails settings page actually govern Copilot and embedded MCP, by scoping Copilot's guardrails to its (server-verified) workspace and giving embedded MCP its own `Scope.EMBEDDED` settings row.

**Architecture:** Copilot's workspace id is verified against the caller's membership on the chat facade and re-injected as a server-controlled state key; `DeferredGuardrailsAdvisor` reads that workspace off the prompt's tool context per call and resolves guardrails for it through a new workspace-taking SPI entry point. Embedded MCP, which has no workspace, gets an explicit scope discriminator on the settings record and its own settings page.

**Tech Stack:** Java 25, Spring Boot 4.0.7, Spring AI 2.0.1, Gradle 9.7, JUnit 5 + AssertJ + Mockito, GraphQL (Spring for GraphQL + client codegen), React 19 / TypeScript 6.

**Spec:** `docs/superpowers/specs/2026-09-02-guardrails-settings-scope-design.md`

## Global Constraints

- Every file under `server/ee/` uses the **ByteChef Enterprise license header** and carries a `@version ee` Javadoc tag. Spotless selects the header by that tag's presence in file **content**, not by path. Files under `server/libs/` use **Apache 2.0**.
- Run `./gradlew spotlessApply` before every commit. Never judge a Gradle run piped into `tail`/`grep` — redirect to a file, check `$?` on its own line, then grep the file for `^> Task .* FAILED` (never `error:`, which matches module paths like `:server:libs:core:error:`).
- Client: run `npm run check` from `client/` with a **600000 ms** tool timeout. Node 22.19–24 (this machine: v24.15.0).
- Unit test classes end in `Test`; integration tests end in `IntTest`. Test method names are camelCase with **no underscores** — Checkstyle enforces this on all methods in test sources, including private helpers.
- Java style: one blank line before `if`/`for`/`while`/`try`/`switch` except immediately after an opening `{`; one blank line between a variable modification and the next statement using it; no trailing blank line before a class's closing `}`; no `_` prefix on private methods; no short or cryptic variable names.
- **No new inline code comments explaining rationale** — rationale goes in Javadoc and the commit message.
- **Authorization goes on the facade, never the controller.** `CopilotApiController`'s own javadoc records that its gate was deliberately moved to `CopilotChatFacade` because a check in a controller body is invisible to an audit scanning for `@PreAuthorize`. This corrects the spec's §1, which said `CopilotApiController`.
- **A workspace row must never inherit from the PLATFORM row.** `AiGuardrails#resolvePolicy` unions a workspace's settings with the global `bytechef.ai.gateway.guardrails.*` properties only. Adding inheritance would silently change what every already-configured workspace resolves to.
- Commit convention: `732 <description>`; client commits `732 client - <description>`.

---

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `.../platform-ai-guardrails-api/.../domain/AiGuardrailsSettingsScope.java` | `PLATFORM` / `WORKSPACE` / `EMBEDDED` discriminator |
| `client/src/ee/pages/settings/embedded/guardrails/EmbeddedGuardrails.tsx` + `.test.tsx` | embedded settings page |

**Modified:**

| File | Change |
|---|---|
| `.../ai-copilot-rest/.../facade/CopilotChatFacadeImpl.java` | verify + inject the workspace |
| `.../ai-copilot-api/.../constant/CopilotConstants.java` | `STATE_VERIFIED_WORKSPACE_ID` |
| `.../ai-copilot-service/.../util/CopilotToolContextUtils.java` | read the verified key |
| `.../platform-ai-api/.../guardrails/AiGuardrailsAdvisorProvider.java` | workspace-taking entry points |
| `.../platform-ai-guardrails-service/.../advisor/AiGuardrailsAdvisorProviderImpl.java` | implement them |
| `.../ai-copilot-service/.../advisor/DeferredGuardrailsAdvisor.java` | resolve the workspace per call |
| `.../ai-copilot-service/.../advisor/CopilotGuardrailsAdvisorFactory.java` | same for the tool-boundary metrics |
| `.../platform-ai-guardrails-api/.../domain/AiGuardrailsWorkspaceSettings.java` | add `scope` |
| `.../platform-ai-guardrails-service/.../service/AiGuardrailsWorkspaceSettingsServiceImpl.java` | `scopeOf` from the enum; `fetchEmbeddedSettings` |
| `.../platform-ai-guardrails-service/.../AiGuardrails.java` | embedded-scoped MCP policy |
| `.../platform-ai-guardrails-graphql/…graphqls` + controller | expose `scope` |
| `.../embedded-ai-mcp-server/.../config/EmbeddedMcpServerConfiguration.java` | request the embedded scope |
| `client/src/graphql/…/aiGuardrailsWorkspaceSettings.graphql` + generated | `scope` field |
| `.agents/ai-guardrails.md` | document the scope model |

---

## Task 1: Verify and inject Copilot's workspace

**This task is a security precondition for every task after it.** Until it lands, scoping guardrails by workspace is a bypass rather than a control.

**Files:**
- Modify: `server/ee/libs/ai/ai-copilot/ai-copilot-rest/src/main/java/com/bytechef/ee/ai/copilot/web/rest/facade/CopilotChatFacadeImpl.java`
- Modify: `server/libs/ai/ai-copilot/ai-copilot-api/src/main/java/com/bytechef/ai/copilot/constant/CopilotConstants.java`
- Test: `server/ee/libs/ai/ai-copilot/ai-copilot-rest/src/test/java/com/bytechef/ee/ai/copilot/web/rest/facade/CopilotChatFacadeImplTest.java` (create if absent; match the module's existing test style)

**Interfaces:**
- Consumes: nothing.
- Produces: `CopilotConstants.STATE_VERIFIED_WORKSPACE_ID` (`String`, value `"verifiedWorkspaceId"`). Task 2 reads this key; nothing else may.

**Read first:** `AiHubApiController.enforceWorkspaceAccess` and `injectAuthenticatedContext` — this task mirrors them, on the facade rather than the controller. Also read `CopilotChatFacadeImpl.injectAuthenticatedUserId` and `authorizeWorkflowAccess`; the new method sits beside them and follows their shape, including their fail-closed-when-unwired behaviour.

- [ ] **Step 0: Answer the spec's must-verify question, and record the answer**

The spec flags an unresolved question: the client-supplied `workspaceId` already flows into `AutomationToolInvocationContext` and into workspace-scoped Copilot tools, and it is not known whether authorization there rests on that id or on the rehydrated `Authentication` + `ResourceMembershipDecider`.

Determine it. Pick one workspace-scoped Copilot tool that reads `AutomationToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY` and follow it to the service or facade it calls; establish whether that call is authorization-checked independently of the id. Note that `authorizeWorkflowAccess` returns early when the state carries no `workflowId`, so a request without one is authorized by nothing today.

Write the answer, with the files and line numbers that settle it, at the TOP of your report file. Do not change behaviour based on it — this task's fix closes the hole either way. If you conclude it **is** currently exploitable, say so prominently in your returned summary; that is a live cross-workspace data issue and the controller needs to know immediately.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void testChatRejectsAWorkspaceTheUserIsNotAMemberOf() {
        Map<String, Object> stateMap = new HashMap<>(Map.of(CopilotConstants.STATE_WORKSPACE_ID, 99L));

        when(workspaceFacade.getUserWorkspaceIds(USER_ID)).thenReturn(List.of(1L));

        assertThatExceptionOfType(AccessDeniedException.class)
            .as("a client naming a workspace it cannot access must be refused, not silently scoped to it")
            .isThrownBy(() -> copilotChatFacade.chat("project", agUiParameters(stateMap)));
    }

    @Test
    void testChatInjectsTheVerifiedWorkspaceAndOverwritesTheClientSuppliedOne() {
        Map<String, Object> stateMap = new HashMap<>(Map.of(CopilotConstants.STATE_WORKSPACE_ID, 1L));

        when(workspaceFacade.getUserWorkspaceIds(USER_ID)).thenReturn(List.of(1L));

        copilotChatFacade.chat("project", agUiParameters(stateMap));

        assertThat(stateMap).containsEntry(CopilotConstants.STATE_VERIFIED_WORKSPACE_ID, 1L);
        assertThat(stateMap)
            .as("the unverified key is overwritten too, so a later regression reading it still gets "
                + "server-controlled data")
            .containsEntry(CopilotConstants.STATE_WORKSPACE_ID, 1L);
    }

    @Test
    void testChatFailsClosedWhenWorkspaceAuthorizationIsNotWired() {
        Map<String, Object> stateMap = new HashMap<>(Map.of(CopilotConstants.STATE_WORKSPACE_ID, 1L));

        assertThatExceptionOfType(AccessDeniedException.class)
            .as("an app variant without the workspace facade must refuse, not skip the check")
            .isThrownBy(() -> facadeWithoutWorkspaceFacade().chat("project", agUiParameters(stateMap)));
    }
```

Adapt the fixture to the module's existing style: read `CopilotChatFacadeImpl`'s constructor and build the collaborators it needs. `WorkspaceAccessGuard.isMember(WorkspaceFacade, long userId, long workspaceId)` is what the membership check goes through — stub whatever `WorkspaceFacade` method it calls rather than guessing at `getUserWorkspaceIds`.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :server:ee:libs:ai:ai-copilot:ai-copilot-rest:test > /tmp/t.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/t.log
```

Expected: FAIL — no verification exists and `STATE_VERIFIED_WORKSPACE_ID` is undefined.

- [ ] **Step 3: Add the state key**

In `CopilotConstants`, beside `STATE_WORKSPACE_ID`:

```java
    public static final String STATE_VERIFIED_WORKSPACE_ID = "verifiedWorkspaceId";
```

- [ ] **Step 4: Verify and inject on the facade**

Add `WorkspaceFacade` as a constructor collaborator (nullable, matching how `userService`/`permissionService` are treated), and this method beside `injectAuthenticatedUserId`:

```java
    /**
     * Authorizes the client-supplied workspace id carried in the run state and re-injects it under a
     * server-controlled key. Without this gate a client could submit another tenant's workspace id and have every
     * workspace-scoped tool -- and, once guardrails are workspace-scoped, the guardrail policy itself -- resolve
     * against a workspace it cannot access. Mirrors {@code AiHubApiController.enforceWorkspaceAccess}, including its
     * defensive overwrite of the unverified key so a later regression reading the old one still gets
     * server-controlled data. Fails closed when the authorization services are not wired in the running app variant.
     */
    private void authorizeAndInjectWorkspace(Map<String, Object> stateMap) {
        Long requestedWorkspaceId = NumberUtils.asLong(stateMap.get(CopilotConstants.STATE_WORKSPACE_ID));

        if (requestedWorkspaceId == null) {
            return;
        }

        if (workspaceFacade == null || userService == null) {
            throw new AccessDeniedException("Workspace authorization is not available");
        }

        long userId = SecurityUtils.fetchCurrentUserLogin()
            .flatMap(userService::fetchUserByLogin)
            .map(User::getId)
            .orElseThrow(() -> new AccessDeniedException("Workspace authorization is not available"));

        if (!WorkspaceAccessGuard.isMember(workspaceFacade, userId, requestedWorkspaceId)) {
            throw new AccessDeniedException("Access denied to workspace " + requestedWorkspaceId);
        }

        stateMap.put(CopilotConstants.STATE_VERIFIED_WORKSPACE_ID, requestedWorkspaceId);
        stateMap.put(CopilotConstants.STATE_WORKSPACE_ID, requestedWorkspaceId);
    }
```

Call it in `chat` immediately after `authorizeWorkflowAccess(stateMap, mode)`.

An absent workspace id returns early rather than throwing: not every Copilot surface carries one, and Task 4's fallback handles that case. Do **not** make an absent id an error — that would break those surfaces.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./gradlew :server:ee:libs:ai:ai-copilot:ai-copilot-rest:test > /tmp/t.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/t.log
```

Expected: EXIT=0.

- [ ] **Step 6: Check for hand-assembled contexts that now need the new collaborator**

Adding a constructor collaborator to a scanned bean breaks other modules' hand-assembled test contexts.

```bash
./gradlew compileTestJava --continue > /tmp/c.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/c.log
```

Fix whatever it names. Then check for Spring contexts that scan this bean's package and would now fail to start:

```bash
grep -rn "CopilotChatFacadeImpl\|com.bytechef.ee.ai.copilot.web.rest.facade" server --include='*.java' --exclude-dir=build | grep "/src/test/"
```

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply > /tmp/s.log 2>&1; echo "EXIT=$?"
git add -A
git commit -m "732 Verify Copilot's workspace id before anything scopes off it"
```

---

## Task 2: Read the verified workspace, not the client-supplied one

**Files:**
- Modify: `server/libs/ai/ai-copilot/ai-copilot-service/src/main/java/com/bytechef/ai/copilot/util/CopilotToolContextUtils.java:63`
- Test: `server/libs/ai/ai-copilot/ai-copilot-service/src/test/java/com/bytechef/ai/copilot/util/CopilotToolContextUtilsTest.java` (create if absent)

**Interfaces:**
- Consumes: `CopilotConstants.STATE_VERIFIED_WORKSPACE_ID` from Task 1.
- Produces: the tool context's `AgentToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY` now carries the verified workspace. Task 4 reads it.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void testTheVerifiedWorkspaceWinsOverAClientSuppliedOne() {
        Map<String, Object> state = new HashMap<>();

        state.put(CopilotConstants.STATE_WORKSPACE_ID, 99L);
        state.put(CopilotConstants.STATE_VERIFIED_WORKSPACE_ID, 1L);

        Map<String, Object> toolContext = CopilotToolContextUtils.toToolContext(state);

        assertThat(toolContext)
            .as("a tool context built from the client-supplied id would scope tools and guardrails to a "
                + "workspace the caller may not be a member of")
            .containsEntry(AgentToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY, 1L)
            .containsEntry(AutomationToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY, 1L);
    }

    @Test
    void testNoWorkspaceIsCarriedWhenOnlyTheUnverifiedKeyIsPresent() {
        Map<String, Object> state = new HashMap<>();

        state.put(CopilotConstants.STATE_WORKSPACE_ID, 99L);

        Map<String, Object> toolContext = CopilotToolContextUtils.toToolContext(state);

        assertThat(toolContext)
            .as("an unverified id must never reach the tool context, even when no verified one exists")
            .doesNotContainKey(AutomationToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY);
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :server:libs:ai:ai-copilot:ai-copilot-service:test --tests '*CopilotToolContextUtilsTest*' > /tmp/t.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/t.log
```

Expected: FAIL — line 63 reads `STATE_WORKSPACE_ID`.

- [ ] **Step 3: Read the verified key**

Change line 63 from `state.get(CopilotConstants.STATE_WORKSPACE_ID)` to `state.get(CopilotConstants.STATE_VERIFIED_WORKSPACE_ID)`.

Do the same at `SliceSpringAIAgent.java:82`, which reads `STATE_WORKSPACE_ID` directly.

Then confirm no other production reader remains:

```bash
grep -rn "STATE_WORKSPACE_ID" server --include='*.java' --exclude-dir=build | grep -v "/src/test/"
```

The only remaining production references should be the constant's own declaration, Task 1's facade (which reads then overwrites it), and `ConnectedUserCopilotApiController`'s comment.

- [ ] **Step 4: Run to verify it passes**

Expected: EXIT=0.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply > /tmp/s.log 2>&1; echo "EXIT=$?"
git add -A
git commit -m "732 Carry Copilot's verified workspace into the tool context"
```

---

## Task 3: A workspace-taking SPI entry point

**Files:**
- Modify: `server/libs/platform/platform-ai/platform-ai-api/src/main/java/com/bytechef/platform/ai/guardrails/AiGuardrailsAdvisorProvider.java`
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisorProviderImpl.java`
- Test: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisorProviderTest.java` (create if absent)

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `Optional<Advisor> getAdvisorForWorkspace(@Nullable Long workspaceId, String surface)`
  - `@Nullable SensitiveDataMetrics getMetricsForWorkspace(@Nullable Long workspaceId, String surface)`

  Task 4 calls both.

**Distinct names, not overloads.** `getAdvisor(@Nullable PlatformType, @Nullable Long, String)` already exists and is called as `getAdvisor(null, null, surface)` at several sites; an overload taking `(@Nullable Long, String)` would make those ambiguous.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void testGetAdvisorForWorkspaceUsesTheGivenWorkspaceWithoutDerivingOne() {
        when(aiGuardrails.isActive(7L)).thenReturn(true);

        assertThat(provider.getAdvisorForWorkspace(7L, "copilot")).isPresent();

        verify(projectDeploymentService, never()).getProjectDeployment(anyLong());
    }

    @Test
    void testGetAdvisorForWorkspaceIsEmptyWhenNothingIsActiveForThatWorkspace() {
        when(aiGuardrails.isActive(7L)).thenReturn(false);

        assertThat(provider.getAdvisorForWorkspace(7L, "copilot")).isEmpty();
    }

    @Test
    void testGetMetricsForWorkspaceAgreesWithGetAdvisorForWorkspace() {
        when(aiGuardrails.isActive(7L)).thenReturn(true);

        assertThat(provider.getMetricsForWorkspace(7L, "copilot")).isNotNull();

        when(aiGuardrails.isActive(8L)).thenReturn(false);

        assertThat(provider.getMetricsForWorkspace(8L, "copilot")).isNull();
    }
```

- [ ] **Step 2: Run to verify it fails**

Expected: FAIL — the methods do not exist.

- [ ] **Step 3: Add them to the SPI**

```java
    /**
     * As {@link #getAdvisor}, but for a caller that has ALREADY resolved its workspace and must not have one derived
     * for it. Copilot and the AI-Hub delegation sub-agents carry a server-verified workspace on the request; deriving
     * from a {@code jobPrincipalId} they do not have is what made them resolve the tenant-default row instead.
     *
     * @param workspaceId the caller's resolved workspace, or {@code null} for the tenant default
     * @param surface     identifies the calling surface for metrics/telemetry
     * @return the guardrails advisor, or empty when none applies
     */
    Optional<Advisor> getAdvisorForWorkspace(@Nullable Long workspaceId, String surface);

    /**
     * The {@link SensitiveDataMetrics} counterpart of {@link #getAdvisorForWorkspace}, resolving through the identical
     * active/inactive gate for the identical arguments.
     *
     * @param workspaceId the caller's resolved workspace, or {@code null} for the tenant default
     * @param surface     identifies the calling surface for metrics/telemetry
     * @return the metrics instance, or {@code null} when none applies
     */
    @Nullable
    SensitiveDataMetrics getMetricsForWorkspace(@Nullable Long workspaceId, String surface);
```

- [ ] **Step 4: Implement them**

In `AiGuardrailsAdvisorProviderImpl`, reusing the existing private `buildMetricsIfActive(workspaceId, surface)` so both paths share one active/inactive gate:

```java
    @Override
    public Optional<Advisor> getAdvisorForWorkspace(@Nullable Long workspaceId, String surface) {
        AiGuardrailMetrics metrics = buildMetricsIfActive(workspaceId, surface);

        if (metrics == null) {
            return Optional.empty();
        }

        return Optional.of(new AiGuardrailsAdvisor(aiGuardrails, workspaceId, metrics));
    }

    @Override
    public @Nullable SensitiveDataMetrics getMetricsForWorkspace(@Nullable Long workspaceId, String surface) {
        return buildMetricsIfActive(workspaceId, surface);
    }
```

- [ ] **Step 5: Add stubs wherever the SPI is implemented elsewhere**

```bash
grep -rln "implements AiGuardrailsAdvisorProvider" server --include='*.java' --exclude-dir=build
```

Any other implementation (including test doubles) needs the two new methods.

- [ ] **Step 6: Run to verify it passes, then format and commit**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test > /tmp/t.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/t.log
./gradlew spotlessApply > /tmp/s.log 2>&1; echo "EXIT=$?"
git add -A
git commit -m "732 Add a workspace-taking entry point to the guardrails advisor SPI"
```

---

## Task 4: Copilot resolves its workspace per call

**Files:**
- Modify: `server/libs/ai/ai-copilot/ai-copilot-service/src/main/java/com/bytechef/ai/copilot/advisor/DeferredGuardrailsAdvisor.java`
- Modify: `server/libs/ai/ai-copilot/ai-copilot-service/src/main/java/com/bytechef/ai/copilot/advisor/CopilotGuardrailsAdvisorFactory.java`
- Test: `server/libs/ai/ai-copilot/ai-copilot-service/src/test/java/com/bytechef/ai/copilot/advisor/CopilotGuardrailsAdvisorFactoryTest.java`

**Interfaces:**
- Consumes: `getAdvisorForWorkspace` / `getMetricsForWorkspace` (Task 3); the verified workspace in the tool context (Task 2).
- Produces: nothing.

Read `DeferredGuardrailsAdvisor` first. It already resolves per call — this task changes only *what workspace* it resolves for. The workspace arrives on the prompt's `ToolCallingChatOptions.getToolContext()` under `AgentToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY`, the same map `AiGuardrailsAdvisor` reads for the PII session.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void testTheAdvisorIsResolvedForTheWorkspaceOnThePromptsToolContext() {
        AiGuardrailsAdvisorProvider aiGuardrailsAdvisorProvider = mock(AiGuardrailsAdvisorProvider.class);
        List<String> invocationLog = new ArrayList<>();

        when(aiGuardrailsAdvisorProvider.getAdvisorForWorkspace(7L, SURFACE))
            .thenReturn(Optional.of(new RecordingCallAdvisor(invocationLog)));
        when(aiGuardrailsAdvisorProvider.getAdvisorForWorkspace(null, SURFACE)).thenReturn(Optional.empty());

        List<Advisor> advisors = newFactory(aiGuardrailsAdvisorProvider).guardrailsAdvisors();

        CallAdvisor deferredAdvisor = (CallAdvisor) advisors.getFirst();

        deferredAdvisor.adviseCall(requestWithWorkspace(7L), passThroughChain());

        assertThat(invocationLog)
            .as("the guardrails policy must come from the workspace this session runs in")
            .containsExactly("guarded");
    }

    @Test
    void testAPromptWithNoWorkspaceFallsBackToTheTenantDefault() {
        AiGuardrailsAdvisorProvider aiGuardrailsAdvisorProvider = mock(AiGuardrailsAdvisorProvider.class);

        when(aiGuardrailsAdvisorProvider.getAdvisorForWorkspace(null, SURFACE)).thenReturn(Optional.empty());

        List<Advisor> advisors = newFactory(aiGuardrailsAdvisorProvider).guardrailsAdvisors();

        CallAdvisor deferredAdvisor = (CallAdvisor) advisors.getFirst();

        deferredAdvisor.adviseCall(requestWithoutWorkspace(), passThroughChain());

        verify(aiGuardrailsAdvisorProvider).getAdvisorForWorkspace(null, SURFACE);
    }
```

Add helpers building a `ChatClientRequest` whose prompt carries `ToolCallingChatOptions` with (and without) `AgentToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY`, and a `CallAdvisorChain` mock returning `ChatClientResponse.builder().build()`. Reuse the file's existing `RecordingCallAdvisor` and `newFactory` helpers.

- [ ] **Step 2: Run to verify it fails**

Expected: FAIL — `getAdvisorForWorkspace` is never called; the advisor still calls `getAdvisor(null, null, surface)`.

- [ ] **Step 3: Resolve the workspace in the advisor**

In `DeferredGuardrailsAdvisor`, replace the `resolveAdvisor()` body's call with a workspace-aware one and add the reader:

```java
    private @Nullable Advisor resolveAdvisor(ChatClientRequest chatClientRequest) {
        AiGuardrailsAdvisorProvider aiGuardrailsAdvisorProvider = aiGuardrailsAdvisorProviderProvider.getIfAvailable();

        if (aiGuardrailsAdvisorProvider == null) {
            return null;
        }

        return aiGuardrailsAdvisorProvider.getAdvisorForWorkspace(workspaceId(chatClientRequest), surface)
            .orElse(null);
    }

    /**
     * Reads the session's server-verified workspace off the prompt's tool context -- the channel
     * {@code AgentToolInvocationContext} already travels on, and the one {@code AiGuardrailsAdvisor} reads for its
     * {@code PiiTokenSession}. Returns {@code null} when the prompt carries none, which resolves the tenant-default
     * row: not every Copilot surface carries a workspace, and failing the call would be a worse outcome than applying
     * the tenant default on a feature most tenants have switched off.
     */
    private static @Nullable Long workspaceId(ChatClientRequest chatClientRequest) {
        Prompt prompt = chatClientRequest.prompt();

        if (!(prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions)) {
            return null;
        }

        Map<String, Object> toolContext = toolCallingChatOptions.getToolContext();

        if (CollectionUtils.isEmpty(toolContext)) {
            return null;
        }

        return NumberUtils.asLong(toolContext.get(AgentToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY));
    }
```

Pass `chatClientRequest` into `resolveAdvisor` from both `adviseCall` and `adviseStream`.

- [ ] **Step 4: Do the same for the tool-boundary metrics**

`CopilotGuardrailsAdvisorFactory.getMetrics()` resolves via `getMetrics(null, null, GUARDRAILS_SURFACE)`. The tool boundary's metrics supplier has no `ChatClientRequest` in scope, so it cannot read the prompt. Leave it resolving the tenant default and record why in its Javadoc:

```java
     * <p>
     * The tool-boundary metrics still resolve the tenant default: the supplier runs inside
     * {@code executeToolCalls}, which has no {@code ChatClientRequest} to read a workspace from. This affects only
     * which workspace tag tool-boundary counters carry, never whether redaction happens -- that is decided by the
     * {@code PiiTokenSession} the workspace-resolved advisor seeds.
     * </p>
```

- [ ] **Step 5: Run to verify it passes**

```bash
./gradlew :server:libs:ai:ai-copilot:ai-copilot-service:test > /tmp/t.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/t.log
```

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply > /tmp/s.log 2>&1; echo "EXIT=$?"
git add -A
git commit -m "732 Resolve Copilot guardrails for the session's workspace"
```

---

## Task 5: An explicit scope on the settings record

**Files:**
- Create: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-api/src/main/java/com/bytechef/ee/platform/ai/guardrails/domain/AiGuardrailsSettingsScope.java`
- Modify: `.../domain/AiGuardrailsWorkspaceSettings.java`
- Modify: `.../service/AiGuardrailsWorkspaceSettingsServiceImpl.java`
- Modify: `.../platform-ai-guardrails-api/.../service/AiGuardrailsWorkspaceSettingsService.java`
- Test: `.../platform-ai-guardrails-service/src/test/.../service/AiGuardrailsWorkspaceSettingsServiceTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum AiGuardrailsSettingsScope { PLATFORM, WORKSPACE, EMBEDDED }`
  - `AiGuardrailsWorkspaceSettings` gains `AiGuardrailsSettingsScope scope` as its **first** component, before `Long workspaceId`.
  - `Optional<AiGuardrailsWorkspaceSettings> fetchEmbeddedSettings()` on the service.

  Task 6 calls `fetchEmbeddedSettings`; Task 7's GraphQL work exposes `scope`.

The record is a record, so adding a component breaks every construction site. Let the compiler name them — do not enumerate by hand.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void testEmbeddedSettingsRoundTripIndependentlyOfAnyWorkspaceRow() {
        aiGuardrailsWorkspaceSettingsService.saveSettings(new AiGuardrailsWorkspaceSettings(
            AiGuardrailsSettingsScope.EMBEDDED, null, null, null, null, null, null, null, null, null, true));

        Optional<AiGuardrailsWorkspaceSettings> fetched =
            aiGuardrailsWorkspaceSettingsService.fetchEmbeddedSettings();

        assertThat(fetched).isPresent();
        assertThat(fetched.get()
            .redactMcpResults()).isTrue();
    }

    @Test
    void testEmbeddedAndPlatformScopesAreDistinctRows() {
        aiGuardrailsWorkspaceSettingsService.saveSettings(new AiGuardrailsWorkspaceSettings(
            AiGuardrailsSettingsScope.EMBEDDED, null, null, null, null, null, null, null, null, null, true));

        assertThat(aiGuardrailsWorkspaceSettingsService.fetchSettings(null))
            .as("an embedded row must not be readable as the tenant default, or the two scopes collapse "
                + "into the ambiguity this change exists to remove")
            .isEmpty();
    }
```

Match the existing test class's `PropertyService` stubbing; those two tests need the stub to key on scope, not only on the property key.

- [ ] **Step 2: Run to verify it fails**

Expected: FAIL — the enum and `fetchEmbeddedSettings` do not exist.

- [ ] **Step 3: Create the enum**

```java
/**
 * Which scope a guardrails settings row belongs to. Explicit rather than inferred from a nullable {@code workspaceId},
 * because that inference could express only two of the three scopes and made {@code null} mean two different things:
 * the tenant default, and (once embedded MCP needed its own row) embedded.
 *
 * <p>
 * Deliberately not {@code Property.Scope}: that type is persistence-layer and carries values ({@code AUTOMATION},
 * {@code PROJECT}, {@code INTEGRATION}) meaningless here, and a settings record in an {@code -api} module should not
 * depend on the property store's shape. Stored by {@link #name()}, as {@code blockingMode} already is in the same
 * value map, so it is not ordinal-sensitive.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public enum AiGuardrailsSettingsScope {

    PLATFORM,
    WORKSPACE,
    EMBEDDED
}
```

- [ ] **Step 4: Add the component and map it**

`AiGuardrailsWorkspaceSettings` gains `AiGuardrailsSettingsScope scope` as its first component. Add a compact-constructor check that `workspaceId` is non-null exactly when `scope == WORKSPACE`, so the two can never disagree.

In `AiGuardrailsWorkspaceSettingsServiceImpl`, replace `scopeOf(Long)` with a mapping from the enum:

```java
    private static Property.Scope scopeOf(AiGuardrailsSettingsScope scope) {
        return switch (scope) {
            case PLATFORM -> Property.Scope.PLATFORM;
            case WORKSPACE -> Property.Scope.WORKSPACE;
            case EMBEDDED -> Property.Scope.EMBEDDED;
        };
    }
```

Add `fetchEmbeddedSettings()` reading `Property.Scope.EMBEDDED` with a null `scopeId`, mirroring `VariableServiceImpl`'s embedded rows. Keep `fetchSettings(@Nullable Long workspaceId)` as-is — `null` there still means PLATFORM — so no existing caller changes.

- [ ] **Step 5: Fix every construction site the compiler names**

```bash
./gradlew compileJava compileTestJava --continue > /tmp/c.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/c.log; grep -n "constructor AiGuardrailsWorkspaceSettings" /tmp/c.log
```

Pass `AiGuardrailsSettingsScope.WORKSPACE` where a non-null `workspaceId` is passed and `PLATFORM` where null. Repeat until clean.

- [ ] **Step 6: Run to verify it passes, then format and commit**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test > /tmp/t.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/t.log
./gradlew spotlessApply > /tmp/s.log 2>&1; echo "EXIT=$?"
git add -A
git commit -m "732 Give guardrails settings an explicit scope discriminator"
```

---

## Task 6: Embedded MCP reads its own row

**Files:**
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/AiGuardrails.java`
- Modify: `.../guardrails/mcp/McpOutboundRedactorProviderImpl.java`
- Modify: `server/ee/libs/embedded/embedded-ai/embedded-ai-mcp-server/src/main/java/com/bytechef/ee/embedded/ai/mcp/server/config/EmbeddedMcpServerConfiguration.java`
- Test: `.../platform-ai-guardrails-service/src/test/.../AiGuardrailsTest.java`

**Interfaces:**
- Consumes: `fetchEmbeddedSettings()` (Task 5).
- Produces: `@Nullable PiiTokenBoundaryPolicy resolveEmbeddedMcpOutboundPolicy()`.

`resolveMcpOutboundPolicy(@Nullable Long workspaceId)` already reads the settings row **once** and reuses it — added when the fail-closed gap was closed. The embedded variant must keep that property: one read, propagating on failure.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void testResolveEmbeddedMcpOutboundPolicyReadsTheEmbeddedRowNotTheTenantDefault() {
        AiGuardrails guardrails = guardrails(null, false, false, "", false, false);

        when(settingsService.fetchEmbeddedSettings()).thenReturn(Optional.of(new AiGuardrailsWorkspaceSettings(
            AiGuardrailsSettingsScope.EMBEDDED, null, true, false, null, null, null, null, null, 0.7, true)));

        PiiTokenBoundaryPolicy policy = guardrails.resolveEmbeddedMcpOutboundPolicy();

        assertThat(policy).isNotNull();
        assertThat(policy.kinds()).containsExactly(SensitiveKind.PII);
        assertThat(policy.minConfidence()).isEqualTo(0.7);

        verify(settingsService, never()).fetchSettings(null);
    }

    @Test
    void testResolveEmbeddedMcpOutboundPolicyPropagatesALookupFailure() {
        AiGuardrails guardrails = guardrails(null, false, false, "", false, false);

        when(settingsService.fetchEmbeddedSettings()).thenThrow(new IllegalStateException("connection reset"));

        assertThatExceptionOfType(IllegalStateException.class)
            .as("swallowing this is indistinguishable from redaction being off, which returns the payload raw")
            .isThrownBy(guardrails::resolveEmbeddedMcpOutboundPolicy);
    }
```

Adjust the record literal's arity to whatever Task 5 produced.

- [ ] **Step 2: Run to verify it fails**

Expected: FAIL — the method does not exist.

- [ ] **Step 3: Implement it**

Mirror `resolveMcpOutboundPolicy` exactly, including its read-once property — fetch the embedded row, gate on `redactMcpResults`, and build the policy from that same row via `effectivePolicyOf`/`toolBoundaryPolicyOf`. Do not call `resolveToolBoundaryPolicy`, which would read again through the fail-open path.

- [ ] **Step 4: Route embedded MCP to it**

`McpOutboundRedactorProviderImpl.fetchRedactor(workspaceId, surface)` currently calls `resolveMcpOutboundPolicy(workspaceId)`. Add an embedded path: when `surface` is `"mcp_embedded"`, call `resolveEmbeddedMcpOutboundPolicy()` instead.

Branch on `surface` inside the **EE implementation**, and do **not** widen the SPI with a scope parameter. `McpOutboundRedactorProvider` is CE and must not learn EE scope types — the same reasoning that made that SPI return a resolved redactor rather than a policy. `surface` is already the discriminator the two MCP configurations pass, and mapping surface to settings scope is the EE side's business.

Do not write the surface strings as literals in two places. Promote `"mcp_automation"` and `"mcp_embedded"` to constants on `McpOutboundRedactorProvider` — they are part of its contract, and its Javadoc already names both — and have both MCP configurations and this implementation reference them.

- [ ] **Step 5: Run to verify it passes, then format and commit**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test :server:ee:libs:embedded:embedded-ai:embedded-ai-mcp-server:test --continue > /tmp/t.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/t.log
./gradlew spotlessApply > /tmp/s.log 2>&1; echo "EXIT=$?"
git add -A
git commit -m "732 Read embedded MCP guardrails from an embedded-scoped settings row"
```

---

## Task 7: Expose the scope, and an embedded settings page

**Files:**
- Modify: `.../platform-ai-guardrails-graphql/src/main/resources/graphql/ai-guardrails-workspace-settings.graphqls`
- Modify: `.../platform-ai-guardrails-graphql/.../web/graphql/AiGuardrailsWorkspaceSettingsGraphQlController.java`
- Modify: `client/src/graphql/platform/ai-guardrails/aiGuardrailsWorkspaceSettings.graphql`
- Modify: `client/src/shared/middleware/graphql.ts`, `graphql-types.ts` (generated)
- Create: `client/src/ee/pages/settings/embedded/guardrails/EmbeddedGuardrails.tsx` + `.test.tsx`

**Interfaces:**
- Consumes: `AiGuardrailsSettingsScope` (Task 5), `fetchEmbeddedSettings` (Task 5).
- Produces: nothing.

GraphQL enum values use SCREAMING_SNAKE_CASE — `PLATFORM`, `WORKSPACE`, `EMBEDDED` already are.

- [ ] **Step 1: Add `scope` to the schema and controller**

Add `scope: AiGuardrailsSettingsScope` to both the type and the input, plus the enum declaration. The query needs to accept a scope so the embedded page can read the embedded row; add a `scope` argument alongside the existing `workspaceId`.

- [ ] **Step 2: Add the controller test and make it pass**

```java
    @Test
    void testUpdateAiGuardrailsWorkspaceSettingsPassesTheEmbeddedScopeThrough() {
        aiGuardrailsWorkspaceSettingsGraphQlController.updateAiGuardrailsWorkspaceSettings(
            embeddedInput());

        ArgumentCaptor<AiGuardrailsWorkspaceSettings> captor =
            ArgumentCaptor.forClass(AiGuardrailsWorkspaceSettings.class);

        verify(aiGuardrailsWorkspaceSettingsService).saveSettings(captor.capture());

        AiGuardrailsWorkspaceSettings saved = captor.getValue();

        assertThat(saved.scope()).isEqualTo(AiGuardrailsSettingsScope.EMBEDDED);
        assertThat(saved.workspaceId()).isNull();
    }
```

Build `embeddedInput()` from the controller's input record with the arity Task 5 and Step 1 produced.

- [ ] **Step 3: Regenerate the client types and commit them separately**

```bash
cd client && npx graphql-codegen
```

```bash
git add client/src/graphql/platform/ai-guardrails/aiGuardrailsWorkspaceSettings.graphql
git commit -m "732 client - Request the guardrails settings scope"
git add client/src/shared/middleware/graphql.ts client/src/shared/middleware/graphql-types.ts
git commit -m "732 client - Regenerate GraphQL types"
```

- [ ] **Step 4: Write the failing page test**

```tsx
    it('loads and saves the embedded-scoped guardrails settings', async () => {
        renderComponent();

        const toggle = await screen.findByLabelText(/redact mcp tool results/i);

        expect(toggle).not.toBeChecked();

        await userEvent.click(toggle);

        expect(toggle).toBeChecked();
    });
```

Mirror `client/src/ee/pages/settings/automation/ai/guardrails/AiGuardrails.test.tsx`'s helper and assertion style rather than this snippet's; read it first.

- [ ] **Step 5: Build the page**

Copy the structure of `AiGuardrails.tsx`, with two differences: it sends `scope: 'EMBEDDED'` and no `workspaceId`, and it omits the workspace guard that page has (`if (currentWorkspaceId == null) return`). Register it in the embedded settings navigation beside `api-keys` / `signing-keys` / `variables`.

Client conventions: `sort-keys` needs alphabetical object keys and does not autofix; named imports sorted alphabetically inside `{}`; `twMerge` not `cn()`; hooks ordered `useState` → `useRef` → store hooks → other hooks → derived values → `useEffect` → `return`.

- [ ] **Step 6: Run the full client check**

```bash
cd client && npm run check
```

600000 ms timeout. Re-run if prettier reformats anything.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "732 client - Add the embedded guardrails settings page"
```

---

## Task 8: Document the scope model

**Files:**
- Modify: `.agents/ai-guardrails.md`
- Modify: `docs/superpowers/specs/2026-09-02-guardrails-settings-scope-design.md`

- [ ] **Step 1: Replace the surface table**

`.agents/ai-guardrails.md` must now state, per surface, which settings row governs it: canvas AI Agent and automation MCP → the run's workspace; Copilot and AI-Hub delegation sub-agents → the session's **server-verified** workspace; AI Hub main agent → its verified workspace; embedded MCP → the `EMBEDDED` row; anything unresolved → `PLATFORM`.

Record that the PLATFORM row has no UI **by design** once this lands, since no surface depends on it in normal operation.

Record the behaviour change: enabling PII redaction on the Guardrails page now changes Copilot's behaviour for that workspace, where before it did nothing. Tenants using `bytechef.ai.gateway.guardrails.*` are unaffected — those still union in.

- [ ] **Step 2: Fold Task 1's finding back into the spec**

Update the spec's §1 sub-section with the answer Task 1's Step 0 produced, replacing "has not been verified end to end". Also correct §1's claim that the check goes in `CopilotApiController` — it goes on `CopilotChatFacadeImpl`, per this plan's Global Constraints.

- [ ] **Step 3: Commit**

```bash
git add .agents/ai-guardrails.md docs/superpowers/specs/2026-09-02-guardrails-settings-scope-design.md
git commit -m "732 Document the guardrails settings scope model"
```

---

## Task 9: Pin that every tool-context workspace id is verified

Added after Task 1's Step 0 confirmed a live cross-workspace hole whose root cause was a writer of the
agent tool context taking its workspace id from an unverified client-supplied state key. Task 1 closed
that writer. This task makes a *future* writer doing the same thing loud instead of silent.

**Files:**
- Test: `server/libs/automation/automation-ai/automation-ai-tool/src/test/java/com/bytechef/automation/ai/tool/ToolContextWorkspaceVerificationTest.java`

**Interfaces:**
- Consumes: nothing at compile time — it is a source scan.
- Produces: nothing.

**The invariant.** Any production source that writes
`AgentToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY` or
`AutomationToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY` must not, in the same file, read a
known-unverified state key. Today the unverified keys are `CopilotConstants.STATE_WORKSPACE_ID` and
`AiHubStateKeys.WORKSPACE_ID`; their verified counterparts are `STATE_VERIFIED_WORKSPACE_ID` and
`AiHubStateKeys.VERIFIED_WORKSPACE_ID`.

The three writers today and their sources, all verified: `CopilotToolContextUtils` (verified key, after
Task 2), `AiHubSpringAIAgent#toolContext` (`VERIFIED_WORKSPACE_ID`), and the MCP
`WorkspaceScoped*ToolCallback` classes (workspace resolved server-side from `mcpServerId`, never from
request state).

- [ ] **Step 1: Write the failing test**

Locate the repo root by walking up from the module directory until a directory containing
`settings.gradle.kts` is found — the scan must see every module, not just this one, and a
module-relative `src/main/java` cannot. Then walk `server/` for `*.java` under `src/main/java`,
strip comments, and for each file that contains a write of either workspace-id key, fail if it also
contains an unverified key name.

Write it so it fails today only if the invariant is broken; run it and confirm it passes, then break
it deliberately in Step 3 to prove it can fail.

Assert a non-zero writer count, so a scan that silently matches nothing cannot pass. State the expected
count in the failure message rather than pinning it, since a new legitimate writer should not fail the
build — only an unverified one should.

- [ ] **Step 2: Run it and confirm it passes**

```bash
./gradlew :server:libs:automation:automation-ai:automation-ai-tool:test --tests '*ToolContextWorkspaceVerificationTest*' > /tmp/t.log 2>&1; echo "EXIT=$?"; grep -n "^> Task .* FAILED" /tmp/t.log
```

Expected: EXIT=0, and the run reports it found at least one writer.

- [ ] **Step 3: Negative-control it**

Temporarily change `CopilotToolContextUtils` to read `CopilotConstants.STATE_WORKSPACE_ID` again, re-run
the scan, and confirm it FAILS naming that file. Restore, re-run, confirm it passes. Record both outputs
in your report. A scan never observed failing is not evidence.

- [ ] **Step 4: Format and commit**

```bash
./gradlew spotlessApply > /tmp/s.log 2>&1; echo "EXIT=$?"
git add -A
git commit -m "732 Pin that every agent tool context takes a verified workspace id"
```

---

## Self-review notes

**Spec coverage.** §1 verify → Task 1; §1's must-verify → Task 1 Step 0 and Task 8 Step 2; §2 resolve → Tasks 2–4; §3 embedded scope → Tasks 5–7; §4 fallback → Task 4 Step 3 and its second test; "what becomes of the PLATFORM row" → Task 8.

**Spec corrections this plan makes.** The spec put the membership check in `CopilotApiController`; it belongs on `CopilotChatFacadeImpl`, because this codebase deliberately moved Copilot's authorization off the controller so it is not invisible to a `@PreAuthorize` audit. The spec also did not know that `authorizeWorkflowAccess` returns early when no `workflowId` is present, so a Copilot request without one is authorized by nothing today — which raises the priority of Task 1 Step 0.

**Type consistency.** `AiGuardrailsSettingsScope`, `getAdvisorForWorkspace`, `getMetricsForWorkspace`, `fetchEmbeddedSettings`, `resolveEmbeddedMcpOutboundPolicy` and `STATE_VERIFIED_WORKSPACE_ID` are spelled identically everywhere they appear. The settings record's new component is first in every task that constructs it.

**Ordering is load-bearing.** Task 1 must land before Task 4. Scoping guardrails to a workspace the client names, without the membership check, would be a bypass dressed as a control — strictly worse than today's tenant-default behaviour.
