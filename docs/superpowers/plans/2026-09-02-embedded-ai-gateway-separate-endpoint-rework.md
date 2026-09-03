# Embedded AI Gateway — Separate-Endpoint Rework Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move embedded gateway traffic onto its own endpoint, where the connected user comes from authentication rather than a caller-settable header — and delete the machinery the header approach needed.

**Architecture:** A hand-written `@RestController` at `/api/embedded/v1/{externalUserId}/ai-gateway/chat/completions`, living in an embedded module that may depend on `embedded-connected-user-api` directly. It resolves the connected user itself and hands the facade a `Long connectedUserId`. The automation endpoint is untouched. The `ConnectedUserResolver` SPI and its `ObjectProvider` plumbing are deleted.

**Tech Stack:** Java 25, Spring Boot 4, Spring Web / WebFlux `Flux`, JUnit 5, AssertJ, Mockito, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-08-25-embedded-ai-gateway-design.md` — §5 (revised 2026-09-02), ⚑8, ⚑9.

**Prerequisite:** the automation-header revert must have landed (it restores `AiGatewayChatCompletionApiController` to 3-arg facade calls and makes upstream's `AiGatewayChatCompletionApiControllerRoutingTest` pass). Verify before starting:

```bash
grep -c "resolveExternalUserIdHeader\|X-ByteChef-External-User-Id" server/ee/libs/automation/automation-ai/automation-ai-gateway/automation-ai-gateway-public-rest/src/main/java/com/bytechef/ee/automation/ai/gateway/public_/web/rest/AiGatewayChatCompletionApiController.java
```

Expected `0`. A non-zero result means the revert has not landed; stop.

## Global Constraints

- **Enterprise licence header + `@version ee` Javadoc tag on every new file.** Spotless selects the header from the tag in file *content*, not the path.
- **The automation endpoint must not change.** `AiGatewayChatCompletionApiController` and `AiGatewayChatCompletionApiControllerRoutingTest` are untouched by every task here. The routing test passing unmodified is a standing success criterion.
- **Never auto-create a connected user.** Use `ConnectedUserService.fetchConnectedUser`, never `getConnectedUser` (which throws) and never `createConnectedUser`. `ConnectedUserConstants.FRONTEND_RESERVED_PATH_SEGMENTS` reserves a retired `external` literal because minting a phantom connected user from a path segment already shipped once.
- **The route keeps its `{externalUserId}` segment.** That is what places it on the safe side of the reserved-segments trap, so no allowlist entry is needed. A route under `/api/embedded/v<n>/` *without* that segment would need one.
- **Nullable `Long`, never primitive**, for the connected user id. Null is a real state meaning "automation traffic".
- **`:check` is the final gate, not `:test`** — `:test` runs neither SpotBugs, Checkstyle nor PMD. Read SpotBugs findings from `build/reports/spotbugs/*.html`; the XML report is disabled in this repo and will be stale.
- **Gradle must not be judged through a pipe.** Redirect to a file, check `$?` on its own line, grep for `^> Task .* FAILED`.
- **Ignore IDE build-path, cycle, deprecation and unresolved-type diagnostics** — this repo's index lags Gradle badly.
- **Run `./gradlew spotlessApply` before every commit.** Commit prefix `embedded-gateway`.

---

## File Structure

| File | Change |
|---|---|
| `platform-ai-gateway-api/.../connecteduser/ConnectedUserResolver.java` | **delete** |
| `automation-ai-gateway-api/.../facade/AiGatewayFacade.java` | overloads take `@Nullable Long connectedUserId` |
| `automation-ai-gateway-service/.../facade/AiGatewayFacadeImpl.java` | delete `resolveConnectedUserId` + the two `ObjectProvider`s; accept the id |
| `embedded-ai/embedded-ai-gateway-connected-user/` | **renamed** to `embedded-ai-gateway-public-rest` |
| `…/EmbeddedConnectedUserResolver.java` | **delete** |
| `…/EmbeddedAiGatewayChatCompletionApiController.java` | **new** — the endpoint |
| `settings.gradle.kts`, `server-app/build.gradle.kts` | module rename |
| `automation-ai-gateway-service/src/test/.../EmbeddedAiGatewayIntTest.java` | adapted to the new path |

---

## Task 1: Reshape the facade, delete the SPI

**Files:**
- Delete: `platform-ai-gateway-api/src/main/java/com/bytechef/ee/platform/ai/gateway/connecteduser/ConnectedUserResolver.java`
- Modify: `automation-ai-gateway-api/.../facade/AiGatewayFacade.java`
- Modify: `automation-ai-gateway-service/.../facade/AiGatewayFacadeImpl.java`
- Test: `automation-ai-gateway-service/src/test/.../facade/AiGatewayFacadeTest.java`

**Interfaces produced:**
- `chatCompletion(request, tracingHeaders, promptHeaders, @Nullable Long connectedUserId)`
- `chatCompletionStream(request, tracingHeaders, promptHeaders, @Nullable Long connectedUserId, …)`
- `AiGatewayFacadeImpl.resolveEmbeddedDefaultRoutingPolicyId(@Nullable Long connectedUserId, long environmentId)` — unchanged signature, now fed from the parameter rather than resolved internally.

**What is being removed and why.** The SPI existed solely because identity arrived at an *automation*-module controller, which must not depend on embedded. With a controller in an embedded module, that constraint is gone. Deleting it also removes the fail-open hazard the spec had to document: an `ObjectProvider` that silently resolves nothing when the implementation is absent.

- [ ] **Step 1: Change the tests first**

In `AiGatewayFacadeTest`, the existing embedded tests stub `connectedUserResolver.resolve(...)`. Replace that with passing a `Long` directly. The four properties pinned in the previous round must survive in adapted form:

```java
@Test
void testEmbeddedDefaultPolicyIsResolvedForAKnownConnectedUser() {
    when(embeddedSettingsService.find(ENVIRONMENT_ID))
        .thenReturn(Optional.of(
            new AiGatewayEmbeddedSettings(ENVIRONMENT_ID, null, null, null, null, null, 42L, null)));

    assertThat(aiGatewayFacade.resolveEmbeddedDefaultRoutingPolicyId(5L, ENVIRONMENT_ID)).isEqualTo(42L);
}

@Test
void testNullConnectedUserIdSkipsEmbeddedResolutionEntirely() {
    assertThat(aiGatewayFacade.resolveEmbeddedDefaultRoutingPolicyId(null, ENVIRONMENT_ID)).isNull();

    verifyNoInteractions(embeddedSettingsService);
}
```

Delete the two tests that only made sense with the SPI — the unknown-external-id rejection and the missing-resolver fall-through. **Unknown-id rejection does not disappear; it moves to the controller** (Task 3), where the identity is. Do not silently drop that coverage: Task 3 adds it back.

`testChatCompletionAttributesSpendWhenRequestCarriesExplicitRoutingPolicy` and the model-default equivalent must keep passing with a `Long` supplied — they are what proved attribution is not coupled to routing.

- [ ] **Step 2: Run to verify they fail, then implement**

Remove both `ObjectProvider` constructor collaborators and `resolveConnectedUserId`. Thread the `connectedUserId` parameter from `chatCompletion` / `chatCompletionStreamInternal` to where `resolveConnectedUserId`'s result was used — routing, spend attribution and span attributes.

~~Keep the sync/streaming split (`applyRoutingPolicyPrecedence` vs `applyEmbeddedDefaultRoutingPolicyForStreaming`) exactly as it is. Both methods carry Javadoc explaining why they are separate: merging them reactivates model-default resolution for streaming, a behaviour change to automation traffic that a previous round specifically removed.~~

**Superseded 2026-09-02.** The split was closed deliberately after the rework landed: `applyEmbeddedDefaultRoutingPolicyForStreaming` is deleted and `chatCompletionStream` now runs the same `applyRoutingPolicyPrecedence` chain as `chatCompletion`. The behaviour change this paragraph warned about was accepted with the asymmetry stated in full — a model's `defaultRoutingPolicyId` applying to a sync request and being ignored on the streaming request beside it was the larger problem. Do not restore the split.

- [ ] **Step 3: Verify and commit**

```bash
./gradlew :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:check \
          :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-public-rest:check \
          :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-api:check \
          --continue > /tmp/rw-t1.log 2>&1
echo "exit=$?"; grep "^> Task .* FAILED" /tmp/rw-t1.log
```

The public-rest module is in that list deliberately: upstream's routing test must still pass.

```bash
./gradlew spotlessApply > /tmp/rw-spotless.log 2>&1; echo "exit=$?"
git add server/ee/libs
git commit -m "embedded-gateway Take a resolved connected user id and retire the resolver SPI"
```

---

## Task 2: Repurpose the module

**Files:**
- Rename: `server/ee/libs/embedded/embedded-ai/embedded-ai-gateway-connected-user` → `embedded-ai-gateway-public-rest`
- Delete: `…/EmbeddedConnectedUserResolver.java`
- Modify: that module's `build.gradle.kts`, `settings.gradle.kts`, `server/apps/server-app/build.gradle.kts`

**Why reuse rather than create.** The module's SPI implementation is being deleted and its purpose replaced; renaming keeps the module count flat and matches the `embedded-*-public-rest` convention the other three embedded surfaces follow.

- [ ] **Step 1: Rename with history preserved**

```bash
git mv server/ee/libs/embedded/embedded-ai/embedded-ai-gateway-connected-user \
       server/ee/libs/embedded/embedded-ai/embedded-ai-gateway-public-rest
git rm server/ee/libs/embedded/embedded-ai/embedded-ai-gateway-public-rest/src/main/java/com/bytechef/ee/embedded/ai/gateway/connecteduser/EmbeddedConnectedUserResolver.java
```

Update the include in `settings.gradle.kts` and the `implementation(project(...))` line in `server-app/build.gradle.kts` — locate both by content, not line number.

- [ ] **Step 2: Give the module its REST dependencies**

It currently declares only `spring-context`, `platform-api`, `embedded-connected-user-api` and `platform-ai-gateway-api`. It now needs the gateway facade and Spring Web. **Model the dependency set on `embedded-webhook-public-rest`**, which is the closest sibling — a hand-written embedded public REST module — rather than inventing one. Read its `build.gradle.kts` first.

It will need at minimum `spring-web`, the `automation-ai-gateway-api` project for `AiGatewayFacade`, and whatever that sibling declares for Jackson and validation.

- [ ] **Step 3: Verify the rename did not orphan anything**

```bash
grep -rn "embedded-ai-gateway-connected-user" --include='*.kts' . ; echo "(empty = no stale references)"
./gradlew :server:ee:libs:embedded:embedded-ai:embedded-ai-gateway-public-rest:check > /tmp/rw-t2.log 2>&1
echo "exit=$?"; grep "^> Task .* FAILED" /tmp/rw-t2.log
```

- [ ] **Step 4: Commit**

```bash
./gradlew spotlessApply > /tmp/rw-spotless.log 2>&1; echo "exit=$?"
git add -A server settings.gradle.kts
git commit -m "embedded-gateway Repurpose the connected user module as the embedded gateway REST surface"
```

---

## Task 3: The embedded endpoint

**Files:**
- Create: `…/embedded-ai-gateway-public-rest/src/main/java/com/bytechef/ee/embedded/ai/gateway/public_/web/rest/EmbeddedAiGatewayChatCompletionApiController.java`
- Test: `…/src/test/java/…/EmbeddedAiGatewayChatCompletionApiControllerTest.java`

**Read these two first — they are the pattern, do not invent one:**
- `embedded-webhook-public-rest`'s `RequestTriggerApiController` — a hand-written embedded controller mapped at `@RequestMapping("${openapi.openAPIDefinition.base-path.embedded:}/v1")`. This is why no OpenAPI generation is needed here.
- `embedded-execution-public-rest`'s `ActionApiController` — takes `externalUserId` as a path variable and calls `SecurityUtils.checkCurrentUserLogin(externalUserId)` before doing anything. That call is what stops a caller addressing another customer's identity.

**The route:**

```
POST ${openapi.openAPIDefinition.base-path.embedded:}/v1/{externalUserId}/ai-gateway/chat/completions
```

with a second mapping producing `MediaType.TEXT_EVENT_STREAM_VALUE` for the streaming form.

**Read `AiGatewayChatCompletionApiController` for the streaming shape**, but note one thing it learned the hard way: the JSON mapping must declare `consumes = MediaType.APPLICATION_JSON_VALUE` and `produces = MediaType.APPLICATION_JSON_VALUE`, and the SSE mapping `produces = TEXT_EVENT_STREAM_VALUE`. Spring compares `consumes` before `produces`, and a non-empty `consumes` unconditionally outranks an empty one — a JSON mapping that also advertises `text/event-stream` makes the SSE mapping unreachable. That bug shipped once on the automation endpoint; do not reproduce it here.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void testResolvesTheConnectedUserAndPassesItsIdToTheFacade() throws Exception {
    when(connectedUserService.fetchConnectedUser("customer-1", ENVIRONMENT_ID))
        .thenReturn(Optional.of(connectedUser));

    mockMvc.perform(post("/v1/customer-1/ai-gateway/chat/completions")
        .contentType(MediaType.APPLICATION_JSON)
        .content(REQUEST_BODY))
        .andExpect(status().isOk());

    verify(aiGatewayFacade).chatCompletion(any(), any(), any(), eq(5L));
}

@Test
void testUnknownExternalUserIdIsRejected() throws Exception {
    when(connectedUserService.fetchConnectedUser("ghost", ENVIRONMENT_ID)).thenReturn(Optional.empty());

    mockMvc.perform(post("/v1/ghost/ai-gateway/chat/completions")
        .contentType(MediaType.APPLICATION_JSON)
        .content(REQUEST_BODY))
        .andExpect(status().isForbidden());

    verifyNoInteractions(aiGatewayFacade);
}

@Test
void testDisabledConnectedUserIsRejected() throws Exception {
    when(connectedUser.isEnabled()).thenReturn(false);
    when(connectedUserService.fetchConnectedUser("customer-1", ENVIRONMENT_ID))
        .thenReturn(Optional.of(connectedUser));

    mockMvc.perform(post("/v1/customer-1/ai-gateway/chat/completions")
        .contentType(MediaType.APPLICATION_JSON)
        .content(REQUEST_BODY))
        .andExpect(status().isForbidden());
}

@Test
void testNoConnectedUserIsEverCreated() throws Exception {
    when(connectedUserService.fetchConnectedUser("ghost", ENVIRONMENT_ID)).thenReturn(Optional.empty());

    mockMvc.perform(post("/v1/ghost/ai-gateway/chat/completions")
        .contentType(MediaType.APPLICATION_JSON)
        .content(REQUEST_BODY));

    verify(connectedUserService, never()).createConnectedUser(anyString(), anyLong());
    verify(connectedUserService, never()).createConnectedUser(anyString(), any(Environment.class));
}
```

The last test is not redundant with the third. It is a regression guard for a bug this codebase has already shipped — a path segment being read as an identity and minting a phantom user. Assert against **both** `createConnectedUser` overloads.

`testUnknownExternalUserIdIsRejected`'s `verifyNoInteractions(aiGatewayFacade)` is the one that proves rejection happens *before* any gateway work, not after.

- [ ] **Step 2: Run to verify they fail, then implement**

The controller: read `externalUserId` as a `@PathVariable`, call `SecurityUtils.checkCurrentUserLogin(externalUserId)`, resolve via `fetchConnectedUser(...).filter(ConnectedUser::isEnabled)`, reject with 403 when empty, and pass `connectedUser.getId()` to the facade.

Derive the environment from the authenticated principal the way sibling embedded controllers do — read one rather than assuming a mechanism.

- [ ] **Step 3: Verify and commit**

```bash
./gradlew :server:ee:libs:embedded:embedded-ai:embedded-ai-gateway-public-rest:check --continue > /tmp/rw-t3.log 2>&1
echo "exit=$?"; grep "^> Task .* FAILED" /tmp/rw-t3.log
./gradlew spotlessApply > /tmp/rw-spotless.log 2>&1; echo "exit=$?"
git add server/ee/libs
git commit -m "embedded-gateway Add the embedded chat completion endpoint"
```

---

## Task 4: Integration test and end-to-end verification

**Files:**
- Modify: `automation-ai-gateway-service/src/test/.../facade/EmbeddedAiGatewayIntTest.java`
- Verify: every module touched across the rework

- [ ] **Step 1: Adapt the integration test**

It currently exercises resolution through the SPI. Rework it to the new shape: a connected user exists in one environment; the embedded settings row names a default policy; resolving that user's id yields that policy. Keep its two most valuable assertions unchanged, because neither depends on how identity arrives:

- a null-`workspaceId` policy is returned by `getDefaultRoutingPolicies()` and **not** by `getRoutingPoliciesByWorkspaceId(...)`, against real Postgres;
- an unknown external id creates **no** `ConnectedUser` row — assert the row count before and after.

- [ ] **Step 2: Full verification across everything the rework touched**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-api:check \
          :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-service:check \
          :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:check \
          :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-public-rest:check \
          :server:ee:libs:embedded:embedded-ai:embedded-ai-gateway-public-rest:check \
          --continue > /tmp/rw-final.log 2>&1
echo "exit=$?"; grep "^> Task .* FAILED" /tmp/rw-final.log; echo "(empty = green)"

./gradlew :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:testIntegration > /tmp/rw-int.log 2>&1
echo "exit=$?"; grep "^> Task .* FAILED" /tmp/rw-int.log
```

Docker must be running for the integration test; on this machine Testcontainers needs the OrbStack socket, since `/var/run/docker.sock` is a dangling symlink.

- [ ] **Step 3: Confirm the automation surface is genuinely untouched**

```bash
git diff <rework-base>..HEAD -- server/ee/libs/automation/automation-ai/automation-ai-gateway/automation-ai-gateway-public-rest/src/main/java/com/bytechef/ee/automation/ai/gateway/public_/web/rest/AiGatewayChatCompletionApiController.java
```

Expected: empty. The automation controller must not appear in this rework's diff at all.

- [ ] **Step 4: Commit**

```bash
./gradlew spotlessApply > /tmp/rw-spotless.log 2>&1; echo "exit=$?"
git add server
git commit -m "embedded-gateway Adapt the integration test to the embedded endpoint"
```

---

## Self-Review

**Spec coverage**

| Spec item | Task |
|---|---|
| §5 separate endpoint at `/api/embedded/v1/{externalUserId}/ai-gateway/…` | Task 3 |
| §5 identity from authentication, `checkCurrentUserLogin` guard | Task 3 |
| §5 header retired | prerequisite revert (already landed) |
| §5 `ConnectedUserResolver` SPI and its module retired | Tasks 1, 2 |
| §5 facade takes a resolved `Long connectedUserId` | Task 1 |
| §11 unknown/disabled connected user → 403 at the controller | Task 3 |
| §11 fail-open resolver gap removed | Task 1 (deleting the `ObjectProvider`) |
| §3.6 reserved-segment trap avoided structurally | Task 3 — the route keeps its `{externalUserId}` segment |
| ⚑9 header removed outright, not deprecated | prerequisite revert |

**Not covered, deliberately:** Phase 2's ⚑3 header enforcement becomes meaningless under this design and needs deleting from the Phase 2 spec — that is a spec edit, not an implementation task, and belongs with whoever picks Phase 2 up.

**Placeholder scan:** no TBD or TODO. Four steps say "read file X and follow its pattern" rather than reproducing code — the webhook controller's mapping style, the execution controller's `checkCurrentUserLogin` usage, the webhook module's dependency set, and how siblings derive the environment. Each names the file exactly and states the property to copy. Reproducing them would duplicate code the repo owns and rot against it.

**Type consistency:** `connectedUserId` is `@Nullable Long` in the facade signatures (Task 1), produced by `connectedUser.getId()` in Task 3, and asserted as `eq(5L)` in Task 3's tests. `resolveEmbeddedDefaultRoutingPolicyId(@Nullable Long, long)` keeps its signature across Tasks 1 and 4.

**One risk this plan cannot close:** Task 3 assumes the embedded controller can derive an environment id from the authenticated principal the way sibling embedded controllers do. If that mechanism turns out to be specific to the unified/execution surfaces and unavailable here, the controller needs another source and Task 3 should stop and re-scope rather than inventing one.
