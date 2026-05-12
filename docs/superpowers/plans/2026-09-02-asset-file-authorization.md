# Asset-File Authorization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the authorization gap in `AssetFileFacadeImpl` — the common sink of three cross-workspace vulnerabilities — by splitting it into a membership-guarded facade for callers with a principal and an ownership-checked system facade for callers without one.

**Architecture:** `AssetFileSystemFacade` becomes the lower layer, holding every operation a no-principal caller needs and gating each on file ownership (`AssetFile.workspaceId` equals the server-derived workspace passed in). `AssetFileFacade` stays the upper layer, adds a membership check (is the current user in the owning workspace?) and delegates downward. Neither door is unchecked; they perform *different* checks.

**Tech Stack:** Java 25, Spring Boot 4.0.7, Gradle 9.7, JUnit 5 + AssertJ + Mockito, Spring for GraphQL.

**Spec:** `docs/superpowers/specs/2026-09-02-asset-file-authorization-design.md`

## Global Constraints

- Files under `server/libs/` use the **Apache 2.0** header. Files under `server/ee/` use the **ByteChef Enterprise** header and carry a `@version ee` Javadoc tag — Spotless selects the header by that tag's presence in file **content**, not by path.
- Run `./gradlew spotlessApply` before every commit. **Never judge a Gradle run piped into `tail`/`grep`** — redirect to a file, check `$?` on its own line, then grep the file for `^> Task .* FAILED` (never `error:`, which matches module paths like `:server:libs:core:error:`). Set `JAVA_HOME=$(ls -d "$HOME"/.sdkman/candidates/java/current)`.
- Unit test classes end in `Test`; integration tests end in `IntTest`. Test method names are camelCase with **no underscores** — Checkstyle enforces this on all methods in test sources, including private helpers.
- Java style: one blank line before `if`/`for`/`while`/`try`/`switch` except immediately after an opening `{`; one blank line between a variable modification and the next statement using it; no trailing blank line before a class's closing `}`; no `_` prefix on private methods; no short or cryptic variable names.
- **No new inline code comments explaining rationale** — rationale goes in Javadoc and the commit message.
- **Membership only.** No `ASSET_FILE_*` permission scope is created. Do not add `@PreAuthorize` to either facade: in CE `PermissionServiceImpl.hasWorkspaceScope` returns `SecurityUtils.isAuthenticated()`, so it buys authentication only.
- **The privilege-granting trio never reaches the system facade.** `enablePublicLink`, `disablePublicLink` and `createSignedDownloadToken` mint anonymous access and stay membership-only.
- Commit convention: `732 <description>`.

---

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `automation-asset-file-api/.../service/AssetFileSystemFacade.java` | The no-principal interface: 8 methods, ownership-checked |
| `automation-asset-file-service/.../service/AssetFileSystemFacadeImpl.java` | Ownership checks + the operations themselves |
| `automation-asset-file-service/.../service/AssetFileSystemFacadeTest.java` | Ownership refusal per method |
| `automation-asset-file-service/.../service/AssetFileMembershipTest.java` | Membership refusal per guarded method |
| `automation-asset-file-service/.../service/AssetFileSystemFacadeCallerScanTest.java` | Pins the system facade's caller set |

**Modified:**

| File | Change |
|---|---|
| `automation-asset-file-api/.../service/AssetFileFacade.java` | Remove `getOwningWorkspaceId`; javadoc the membership contract |
| `automation-asset-file-service/.../service/AssetFileFacadeImpl.java` | Membership check on every method; delegate the shared three |
| `automation-asset-file-service/build.gradle.kts` | Never-up-to-date `assetFileCallerScan` task wired into `check` |
| `components/asset-file/.../AssetFileComponentHandler.java` + 7 actions | Take `AssetFileSystemFacade` |
| `automation-asset-file-graphql/.../AssetFileGraphQlAccessGuard.java` | Deleted |
| `automation-asset-file-graphql/.../AssetFileGraphQlController.java` | Drop guard calls |
| `automation-asset-file-rest/.../AssetFileRestController.java` | Drop the two private guard methods |
| `ee/.../ai/hub/agent/WebhookBridgeAgent.java`, `AiHubRoutingAgent.java` | Take `AssetFileSystemFacade` |
| `automation-asset-file-rest/.../AssetFilePublicDownloadController.java` | Take `AssetFileSystemFacade` |
| `.agents/resource-visibility.md` | Document the two-door model |

---

## Task 1: The system facade (behaviour-preserving move)

This task adds no authorization. It relocates eight operations to a new lower layer and introduces the four `*InWorkspace` methods, so that Task 2 and Task 3 have somewhere to delegate to. Reviewers should reject it if any behaviour changes.

**Files:**
- Create: `server/libs/automation/automation-asset-file/automation-asset-file-api/src/main/java/com/bytechef/automation/assetfile/service/AssetFileSystemFacade.java`
- Create: `server/libs/automation/automation-asset-file/automation-asset-file-service/src/main/java/com/bytechef/automation/assetfile/service/AssetFileSystemFacadeImpl.java`
- Create: `server/libs/automation/automation-asset-file/automation-asset-file-service/src/test/java/com/bytechef/automation/assetfile/service/AssetFileSystemFacadeTest.java`
- Modify: `server/libs/automation/automation-asset-file/automation-asset-file-service/src/main/java/com/bytechef/automation/assetfile/service/AssetFileFacadeImpl.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `AssetFileSystemFacade` with exactly these eight methods —
  ```java
  AssetFile createFromUpload(Long workspaceId, int environment, String filename, String contentType, InputStream data);
  List<AssetFile> findAllByWorkspaceIdAndEnvironment(Long workspaceId, int environment, List<Long> tagIds);
  AssetFile findByIdInWorkspace(Long id, Long workspaceId);
  AssetFile renameInWorkspace(Long id, Long workspaceId, String newName);
  void deleteInWorkspace(Long id, Long workspaceId);
  InputStream downloadContentInWorkspace(Long id, Long workspaceId);
  AssetFile updateContentInWorkspace(Long id, Long workspaceId, String contentType, InputStream data);
  Optional<AssetFile> fetchByPublicLinkToken(String token);
  ```
  Tasks 2, 4, 5 and 7 depend on these names.

**Read first:** `AssetFileFacadeImpl:326-348` (`findByIdInWorkspace`) — it is the model every `*InWorkspace` method follows, and it already throws `IllegalArgumentException` on a null `workspaceId` and `AssetFileNotFoundException` (identical message) for both a cross-workspace id and an unknown id.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void testRenameInWorkspaceRefusesAFileOwnedByAnotherWorkspace() {
        AssetFile assetFile = new AssetFile();

        assetFile.setId(ASSET_FILE_ID);
        assetFile.setWorkspaceId(OTHER_WORKSPACE_ID);

        when(assetFileService.findById(ASSET_FILE_ID)).thenReturn(assetFile);

        assertThatExceptionOfType(AssetFileNotFoundException.class)
            .as("a server-derived workspace that does not own the file must not be able to rename it")
            .isThrownBy(() -> assetFileSystemFacade.renameInWorkspace(ASSET_FILE_ID, WORKSPACE_ID, "new-name"));

        verify(assetFileService, never()).update(any());
    }

    @Test
    void testRenameInWorkspaceRejectsANullWorkspaceRatherThanTreatingItAsUnscoped() {
        assertThatExceptionOfType(IllegalArgumentException.class)
            .as("degrading a missing workspace to unscoped is how listWorkflowExecutions returned an empty page "
                + "instead of refusing")
            .isThrownBy(() -> assetFileSystemFacade.renameInWorkspace(ASSET_FILE_ID, null, "new-name"));
    }
```

Repeat both shapes for `deleteInWorkspace`, `downloadContentInWorkspace` and `updateContentInWorkspace`, substituting the call and the `verify` for that operation's write method.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
JAVA_HOME=$(ls -d "$HOME"/.sdkman/candidates/java/current) ./gradlew :server:libs:automation:automation-asset-file:automation-asset-file-service:test --tests '*AssetFileSystemFacadeTest*' > /tmp/t.log 2>&1
echo "EXIT=$?"
```
Expected: FAIL — `AssetFileSystemFacadeImpl` does not exist yet, so this is a compile failure. That is the correct first red.

- [ ] **Step 3: Write the interface**

Apache 2.0 header. Javadoc must state the contract verbatim, because it is the sentence a future caller either honours or quietly violates:

```java
/**
 * Asset-file operations for callers that have no authenticated principal: the {@code asset-file} workflow component
 * actions, the anonymous public-link download, and the AG-UI agent-turn threads, which run on a
 * {@code ForkJoinPool.commonPool()} worker where only the tenant is bound.
 *
 * <p>
 * Every method here is ownership-checked rather than membership-checked: it verifies that the file belongs to the
 * workspace passed in, because there is no user whose membership could be tested. That makes the contract on every
 * caller of this interface load-bearing:
 * </p>
 *
 * <blockquote>The workspace must be server-derived, and the caller's access to it must have been verified upstream on
 * a thread that had a principal.</blockquote>
 *
 * <p>
 * The set of classes permitted to call this interface is pinned by {@code AssetFileSystemFacadeCallerScanTest}. A new
 * caller must be added there deliberately, in the same commit. {@code enablePublicLink}, {@code disablePublicLink} and
 * {@code createSignedDownloadToken} are deliberately absent: they mint anonymous access to a file, which no
 * server-derived workspace alone should authorize.
 * </p>
 *
 * @author Ivica Cardic
 */
public interface AssetFileSystemFacade {
```

- [ ] **Step 4: Write the implementation**

`@Service`. Each `*InWorkspace` method is the ownership check followed by the operation:

```java
    @Override
    @Transactional
    public AssetFile renameInWorkspace(Long id, Long workspaceId, String newName) {
        findByIdInWorkspace(id, workspaceId);

        return doRename(id, newName);
    }
```

Move the bodies of `createFromUpload`, `findAllByWorkspaceIdAndEnvironment`, `findByIdInWorkspace` and `fetchByPublicLinkToken` across from `AssetFileFacadeImpl`, along with the private helpers they use (`doRename`, `doDelete`, `doDownloadContent`, `doUpdateContent` — rename the existing private bodies to these).

- [ ] **Step 5: Make `AssetFileFacadeImpl` delegate**

Inject `AssetFileSystemFacade` and replace the four moved bodies with delegating calls. No membership check yet — that is Task 2. `rename`, `delete`, `downloadContent` and `updateContent` keep their bare-id signatures and call the system facade's `*InWorkspace` variant with the workspace resolved from the file itself, preserving today's behaviour exactly.

- [ ] **Step 6: Run the full module test suite**

```bash
JAVA_HOME=$(ls -d "$HOME"/.sdkman/candidates/java/current) ./gradlew spotlessApply :server:libs:automation:automation-asset-file:automation-asset-file-service:check > /tmp/t.log 2>&1
echo "EXIT=$?"
grep -c "^> Task .* FAILED" /tmp/t.log
```
Expected: EXIT=0, zero FAILED. **`AssetFileFacadeTest` and `AssetFileFacadeIntTest` must both still pass unchanged** — if either needed editing, behaviour changed and the task is wrong.

- [ ] **Step 7: Commit**

```bash
git add server/libs/automation/automation-asset-file
git commit -m "732 Move asset-file operations behind an ownership-checked system facade"
```

---

## Task 2: Membership check on the workspace-taking methods

**Files:**
- Modify: `server/libs/automation/automation-asset-file/automation-asset-file-service/build.gradle.kts`
- Modify: `server/libs/automation/automation-asset-file/automation-asset-file-service/src/main/java/com/bytechef/automation/assetfile/service/AssetFileFacadeImpl.java`
- Create: `server/libs/automation/automation-asset-file/automation-asset-file-service/src/test/java/com/bytechef/automation/assetfile/service/AssetFileMembershipTest.java`

**Interfaces:**
- Consumes: `AssetFileSystemFacade` from Task 1.
- Produces: two private methods on `AssetFileFacadeImpl` — `boolean isMember(Long workspaceId)` and `void checkMembership(Long workspaceId)`, which throws `AccessDeniedException` when `isMember` is false. Task 3 calls `isMember` directly, because its failures take the 404 shape instead.

**Read first:** `AssetFileGraphQlAccessGuard:61-95`. Its two methods are exactly the checks being moved down, including the deliberate error-shape split: by-id failures are 404-shaped so a probe cannot confirm an id exists elsewhere; explicit-`workspaceId` failures are `AccessDeniedException` because the caller asserted membership.

- [ ] **Step 0: Add the two module dependencies**

`UserService` and `WorkspaceFacade` are not on this module's classpath — the existing guard compiles
only because it lives in `automation-asset-file-graphql`, which declares both. Add to
`automation-asset-file-service/build.gradle.kts`, in the alphabetical position the file already uses:

```kotlin
    implementation(project(":server:libs:automation:automation-configuration:automation-configuration-api"))
    implementation(project(":server:libs:platform:platform-user:platform-user-api"))
```

No cycle: `automation-configuration-api` does not reference asset-file.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void testCreateFromUploadRefusesAWorkspaceTheUserIsNotAMemberOf() {
        when(userService.fetchCurrentUser()).thenReturn(Optional.of(user(USER_ID)));
        when(workspaceFacade.getUserWorkspaces(USER_ID)).thenReturn(List.of(workspace(WORKSPACE_ID)));

        assertThatExceptionOfType(AccessDeniedException.class)
            .as("the caller is asserting membership of a workspace, so it is told plainly that the assertion failed")
            .isThrownBy(() -> assetFileFacade.createFromUpload(
                OTHER_WORKSPACE_ID, ENVIRONMENT, "f.txt", "text/plain", InputStream.nullInputStream()));

        verifyNoInteractions(assetFileSystemFacade);
    }

    @Test
    void testFindAllByWorkspaceIdAndEnvironmentRefusesAWorkspaceTheUserIsNotAMemberOf() {
        when(userService.fetchCurrentUser()).thenReturn(Optional.of(user(USER_ID)));
        when(workspaceFacade.getUserWorkspaces(USER_ID)).thenReturn(List.of(workspace(WORKSPACE_ID)));

        assertThatExceptionOfType(AccessDeniedException.class)
            .isThrownBy(() -> assetFileFacade.findAllByWorkspaceIdAndEnvironment(
                OTHER_WORKSPACE_ID, ENVIRONMENT, List.of()));

        verifyNoInteractions(assetFileSystemFacade);
    }
```

The `verifyNoInteractions` is load-bearing: without it the test passes against an implementation that checks *after* doing the work.

- [ ] **Step 2: Run to verify they fail**

```bash
JAVA_HOME=$(ls -d "$HOME"/.sdkman/candidates/java/current) ./gradlew :server:libs:automation:automation-asset-file:automation-asset-file-service:test --tests '*AssetFileMembershipTest*' > /tmp/t.log 2>&1
echo "EXIT=$?"
```
Expected: FAIL — the facade has no check, so no exception is thrown.

- [ ] **Step 3: Implement**

```java
    private boolean isMember(Long workspaceId) {
        if (workspaceId == null) {
            return false;
        }

        Long userId = userService.fetchCurrentUser()
            .map(User::getId)
            .orElse(null);

        if (userId == null) {
            return false;
        }

        List<Workspace> workspaces = workspaceFacade.getUserWorkspaces(userId);

        return workspaces.stream()
            .map(Workspace::getId)
            .anyMatch(id -> Objects.equals(id, workspaceId));
    }

    private void checkMembership(Long workspaceId) {
        if (!isMember(workspaceId)) {
            throw new AccessDeniedException("Workspace is not accessible to the current user");
        }
    }
```

Call it as the first statement of `createFromUpload`, `createFromAi`, `createBinaryFromAi`, `findAllByWorkspaceIdAndEnvironment`, `findByIdInWorkspace` and `cloneToEnvironment`.

- [ ] **Step 4: Run to verify they pass, and the module stays green**

```bash
JAVA_HOME=$(ls -d "$HOME"/.sdkman/candidates/java/current) ./gradlew spotlessApply :server:libs:automation:automation-asset-file:automation-asset-file-service:check > /tmp/t.log 2>&1
echo "EXIT=$?"
grep -c "^> Task .* FAILED" /tmp/t.log
```
Expected: EXIT=0. Pre-existing tests that call these methods will now need a member stub; add it rather than weakening the check.

- [ ] **Step 5: Commit**

```bash
git add server/libs/automation/automation-asset-file
git commit -m "732 Check workspace membership on the asset-file facade's workspace-taking methods"
```

---

## Task 3: Membership check on the bare-id methods

The larger half of the fix. Eleven methods take a bare `id` today and nothing checks who is asking.

**Files:**
- Modify: `server/libs/automation/automation-asset-file/automation-asset-file-service/src/main/java/com/bytechef/automation/assetfile/service/AssetFileFacadeImpl.java`
- Modify: `server/libs/automation/automation-asset-file/automation-asset-file-service/src/test/java/com/bytechef/automation/assetfile/service/AssetFileMembershipTest.java`

**Interfaces:**
- Consumes: `checkMembership(Long)` from Task 2.
- Produces: nothing new.

- [ ] **Step 1: Write the failing tests**

One per method. The shape, and it must assert the **404 error shape**, not `AccessDeniedException`:

```java
    @Test
    void testDeleteRefusesAFileInAWorkspaceTheUserIsNotAMemberOf() {
        AssetFile assetFile = new AssetFile();

        assetFile.setId(ASSET_FILE_ID);
        assetFile.setWorkspaceId(OTHER_WORKSPACE_ID);

        when(assetFileService.findById(ASSET_FILE_ID)).thenReturn(assetFile);
        when(userService.fetchCurrentUser()).thenReturn(Optional.of(user(USER_ID)));
        when(workspaceFacade.getUserWorkspaces(USER_ID)).thenReturn(List.of(workspace(WORKSPACE_ID)));

        assertThatExceptionOfType(AssetFileNotFoundException.class)
            .as("by-id failures stay 404-shaped so a probe cannot confirm the id exists in another workspace")
            .isThrownBy(() -> assetFileFacade.delete(ASSET_FILE_ID));
    }
```

Write this for all eleven: `delete`, `downloadContent`, `findById`, `rename`, `updateDescription`, `getVersions`, `restoreVersion`, `enablePublicLink`, `disablePublicLink`, `createSignedDownloadToken`, `updateContent`.

- [ ] **Step 2: Run to verify all eleven fail**

```bash
JAVA_HOME=$(ls -d "$HOME"/.sdkman/candidates/java/current) ./gradlew :server:libs:automation:automation-asset-file:automation-asset-file-service:test --tests '*AssetFileMembershipTest*' > /tmp/t.log 2>&1
echo "EXIT=$?"
grep "tests completed" /tmp/t.log
```
Expected: eleven failures. **Record the number and report it** — it is the evidence the gate lands on every path rather than beside it.

- [ ] **Step 3: Implement**

```java
    private void checkMembershipOfOwner(Long id) {
        Long owningWorkspaceId = resolveOwningWorkspaceId(id);

        if (!isMember(owningWorkspaceId)) {
            throw new AssetFileNotFoundException("Asset file %d not found".formatted(id));
        }
    }
```

`resolveOwningWorkspaceId` is a new private method holding the body of the existing public `getOwningWorkspaceId`, which stays on the interface until Task 6 removes it. `isMember` is the boolean half of Task 2's `checkMembership`, extracted so both call sites share one membership rule.

- [ ] **Step 4: Run to verify they pass**

```bash
JAVA_HOME=$(ls -d "$HOME"/.sdkman/candidates/java/current) ./gradlew spotlessApply :server:libs:automation:automation-asset-file:automation-asset-file-service:check > /tmp/t.log 2>&1
echo "EXIT=$?"
grep -c "^> Task .* FAILED" /tmp/t.log
```

- [ ] **Step 5: Commit**

```bash
git add server/libs/automation/automation-asset-file
git commit -m "732 Check membership on every by-id asset-file operation"
```

---

## Task 4: Rewire the component actions

**Files:**
- Modify: `server/libs/modules/components/asset-file/src/main/java/com/bytechef/component/assetfile/AssetFileComponentHandler.java:54-83`
- Modify: the seven actions in `.../component/assetfile/action/` — `AssetFileUploadAction`, `AssetFileDownloadAction`, `AssetFileGetAction`, `AssetFileFindAction`, `AssetFileUpdateContentAction`, `AssetFileRenameAction`, `AssetFileDeleteAction`

**Interfaces:**
- Consumes: `AssetFileSystemFacade` from Task 1.
- Produces: nothing.

**Read first:** `AssetFileRenameAction:83-85` — it calls `findByIdInWorkspace` then `rename`. That hand-rolled pair is exactly what `renameInWorkspace` replaces; the point of this task is that the pair can no longer be got wrong.

- [ ] **Step 1: Swap the type at the wiring point**

In `AssetFileComponentHandler`, change the constructor parameter and all seven `of(...)` calls from `AssetFileFacade` to `AssetFileSystemFacade`.

- [ ] **Step 2: Collapse each action's check-then-act pair**

For `AssetFileRenameAction`, replace:
```java
        assetFileFacade.findByIdInWorkspace(assetFileId, workspaceId);

        AssetFile assetFile = assetFileFacade.rename(assetFileId, newName);
```
with:
```java
        AssetFile assetFile = assetFileSystemFacade.renameInWorkspace(assetFileId, workspaceId, newName);
```
Do the same for `AssetFileDeleteAction`, `AssetFileDownloadAction` and `AssetFileUpdateContentAction`. `AssetFileGetAction` already calls only `findByIdInWorkspace` and needs the field type changed but no logic change; `AssetFileFindAction` and `AssetFileUploadAction` likewise.

- [ ] **Step 3: Run the component's tests**

```bash
JAVA_HOME=$(ls -d "$HOME"/.sdkman/candidates/java/current) ./gradlew spotlessApply :server:libs:modules:components:asset-file:check > /tmp/t.log 2>&1
echo "EXIT=$?"
grep -c "^> Task .* FAILED" /tmp/t.log
```
Expected: EXIT=0. Component tests regenerate `.json` definition snapshots; if one is written and the run then fails on a missing classpath copy, run again — that NPE is the expected midpoint, not a bug.

- [ ] **Step 4: Commit**

```bash
git add server/libs/modules/components/asset-file
git commit -m "732 Point the asset-file component actions at the system facade"
```

---

## Task 5: Rewire the AG-UI agents and the public download controller

**Files:**
- Modify: `server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/agent/WebhookBridgeAgent.java:1221`
- Modify: `server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/agent/AiHubRoutingAgent.java:221`
- Modify: `server/libs/automation/automation-asset-file/automation-asset-file-rest/src/main/java/com/bytechef/automation/assetfile/web/rest/AssetFilePublicDownloadController.java:80`

**Interfaces:**
- Consumes: `AssetFileSystemFacade` from Task 1.
- Produces: nothing.

Both EE files need the ByteChef Enterprise header and a `@version ee` tag — they have them already; do not disturb them.

- [ ] **Step 1: Swap the injected type in all three**

Change the constructor parameter and field from `AssetFileFacade` to `AssetFileSystemFacade`. The call sites need no other change: `WebhookBridgeAgent` and `AiHubRoutingAgent` call `createFromUpload`, and the public controller calls `fetchByPublicLinkToken` — all three are on the system facade with identical signatures.

- [ ] **Step 2: Add the javadoc that records why**

On each injected field, one sentence naming the reason, since a reader will otherwise "fix" it back:

```java
    /**
     * The system facade, not {@code AssetFileFacade}: this runs on a {@code ForkJoinPool.commonPool()} worker where
     * {@code AiHubAgentTenantBinder} has bound the tenant but no {@code Authentication}, so a membership check would
     * throw rather than deny. The workspace comes from the chat row and the caller's access to it was verified by
     * {@code AiHubApiController.enforceWorkspaceAccess} on the request thread.
     */
```

- [ ] **Step 3: Write the regression test that proves each no-principal path still works**

This is the highest-value test in the plan. These paths do not *deny* when the change is wrong — they
**throw**, because `fetchCurrentUser()` has nobody to return. A fix that looks clean and breaks
workflow-chat file upload is the failure mode being guarded against.

For each of the three, assert the call succeeds with **no `SecurityContext` established at all** — do
not stub a current user, because stubbing one hides exactly the defect:

```java
    @Test
    void testUploadSucceedsOnAThreadWithNoAuthentication() {
        SecurityContextHolder.clearContext();

        when(assetFileSystemFacade.createFromUpload(WORKSPACE_ID, ENVIRONMENT, FILENAME, CONTENT_TYPE, data))
            .thenReturn(assetFile);

        assertThatNoException()
            .as("this runs on a commonPool worker where only the tenant is bound; requiring a principal here "
                + "breaks every workflow-chat attachment upload")
            .isThrownBy(() -> subject.promoteAttachment(...));

        verifyNoInteractions(userService);
    }
```

The `verifyNoInteractions(userService)` is what makes it discriminating: it fails if the path ever
starts consulting the current user, which is the regression itself.

- [ ] **Step 4: Compile and test the affected modules**

```bash
JAVA_HOME=$(ls -d "$HOME"/.sdkman/candidates/java/current) ./gradlew spotlessApply :server:ee:libs:ai:ai-hub:ai-hub-service:check :server:libs:automation:automation-asset-file:automation-asset-file-rest:check --continue > /tmp/t.log 2>&1
echo "EXIT=$?"
grep -c "^> Task .* FAILED" /tmp/t.log
```

- [ ] **Step 5: Commit**

```bash
git add server/ee/libs/ai/ai-hub server/libs/automation/automation-asset-file
git commit -m "732 Point the no-principal asset-file callers at the system facade"
```

---

## Task 6: Delete the duplicated guards

**Files:**
- Delete: `server/libs/automation/automation-asset-file/automation-asset-file-graphql/src/main/java/com/bytechef/automation/assetfile/web/graphql/AssetFileGraphQlAccessGuard.java`
- Modify: `server/libs/automation/automation-asset-file/automation-asset-file-graphql/src/main/java/com/bytechef/automation/assetfile/web/graphql/AssetFileGraphQlController.java`
- Modify: `server/libs/automation/automation-asset-file/automation-asset-file-rest/src/main/java/com/bytechef/automation/assetfile/web/rest/AssetFileRestController.java:83,96,135,180,200`
- Modify: `server/libs/automation/automation-asset-file/automation-asset-file-api/src/main/java/com/bytechef/automation/assetfile/service/AssetFileFacade.java:93`

**Interfaces:**
- Consumes: the membership checks from Tasks 2 and 3.
- Produces: `AssetFileFacade` without `getOwningWorkspaceId`.

- [ ] **Step 1: Remove `getOwningWorkspaceId` from the interface**

It is a membership oracle — given any id it names the owning workspace. Its only callers are the two guards this task deletes. The implementation body stays as a private `resolveOwningWorkspaceId` (Task 3 already made it one).

- [ ] **Step 2: Delete the GraphQL guard and its call sites**

Remove the `accessGuard` field and every `accessGuard.verify*` call from `AssetFileGraphQlController`, then delete the guard class and its test.

- [ ] **Step 3: Delete the REST controller's private duplicate**

Remove `verifyUserCanAccessWorkspaceForUpload` and `verifyUserCanAccessFile` and their three call sites at `:83`, `:96` and `:135`. Where `:96` used the returned workspace id, take it from the `AssetFile` the facade returns instead.

- [ ] **Step 4: Verify the surfaces still refuse**

The existing controller tests that asserted a refusal must still pass — the refusal now comes from the facade. If any test mocked the guard, rewrite it to stub the facade throwing, not to delete the assertion.

```bash
JAVA_HOME=$(ls -d "$HOME"/.sdkman/candidates/java/current) ./gradlew spotlessApply :server:libs:automation:automation-asset-file:check > /tmp/t.log 2>&1
echo "EXIT=$?"
grep -c "^> Task .* FAILED" /tmp/t.log
```

- [ ] **Step 5: Commit**

```bash
git add server/libs/automation/automation-asset-file
git commit -m "732 Delete the duplicated asset-file access guards"
```

---

## Task 7: Pin the system facade's caller set

**Files:**
- Create: `server/libs/automation/automation-asset-file/automation-asset-file-service/src/test/java/com/bytechef/automation/assetfile/service/AssetFileSystemFacadeCallerScanTest.java`
- Modify: `server/libs/automation/automation-asset-file/automation-asset-file-service/build.gradle.kts`

**Interfaces:**
- Consumes: `AssetFileSystemFacade` from Task 1.
- Produces: nothing.

**Read first:** `server/libs/automation/automation-ai/automation-ai-tool/src/test/java/com/bytechef/automation/ai/tool/ToolContextWorkspaceVerificationTest.java` and its `build.gradle.kts` task. This scan is the same shape and should copy its structure — including the reasons its earlier revisions failed, which are recorded in its javadoc.

- [ ] **Step 1: Write the failing test**

```java
    private static final Set<String> EXPECTED_SYSTEM_FACADE_CALLERS = Set.of(
        "AssetFileComponentHandler",
        "AssetFileDeleteAction",
        "AssetFileDownloadAction",
        "AssetFileFacadeImpl",
        "AssetFileFindAction",
        "AssetFileGetAction",
        "AssetFilePublicDownloadController",
        "AssetFileRenameAction",
        "AssetFileUpdateContentAction",
        "AssetFileUploadAction",
        "AiHubRoutingAgent",
        "WebhookBridgeAgent");

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void testOnlyExpectedClassesReferenceTheSystemFacade() throws IOException {
        List<String> unexpected = findMainSourceFiles(repositoryRoot().resolve("server")).stream()
            .filter(AssetFileSystemFacadeCallerScanTest::referencesSystemFacade)
            .map(AssetFileSystemFacadeCallerScanTest::simpleClassName)
            .filter(className -> !EXPECTED_SYSTEM_FACADE_CALLERS.contains(className))
            .sorted()
            .toList();

        assertThat(unexpected)
            .as("AssetFileSystemFacade performs an ownership check instead of a membership check, which is only "
                + "sound for a caller with no principal and a server-derived workspace. A caller that HAS a "
                + "principal must use AssetFileFacade; reaching for this one turns the substitution into a bypass. "
                + "If this caller is genuinely principal-less, add it above in the same commit.")
            .isEmpty();
    }
```

`referencesSystemFacade` strips comments with a `DOTALL` regex and tests for the token `AssetFileSystemFacade`, but only after a cheap raw `String.contains` pre-filter — stripping every production source twice is what pushed the sibling scan over its timeout.

- [ ] **Step 2: Run to verify it fails**

Temporarily remove `"WebhookBridgeAgent"` from the set and confirm the test fails naming that file. Restore it. Report the observed failure output — a scan that has never been seen to fail is not known to work.

- [ ] **Step 3: Pin the system interface's method set**

The scan governs who may *call* the system facade. This governs what it may *offer* — without it, a
later change can add `enablePublicLink` to it and every caller silently gains the ability to mint
anonymous access from a server-derived workspace alone.

```java
    @Test
    void testTheSystemFacadeOffersExactlyTheEightPermittedOperations() {
        Set<String> methodNames = Arrays.stream(AssetFileSystemFacade.class.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());

        assertThat(methodNames)
            .as("adding a method here widens what a caller with no principal can do on nothing but a "
                + "server-derived workspace; the three link-minting operations are excluded deliberately")
            .containsExactlyInAnyOrder(
                "createFromUpload", "findAllByWorkspaceIdAndEnvironment", "findByIdInWorkspace",
                "renameInWorkspace", "deleteInWorkspace", "downloadContentInWorkspace",
                "updateContentInWorkspace", "fetchByPublicLinkToken");
    }
```

- [ ] **Step 4: Wire a never-up-to-date Gradle task**

```kotlin
val assetFileCallerScan = tasks.register<Test>("assetFileCallerScan") {
    useJUnitPlatform()

    description = "Runs the cross-module AssetFileSystemFacade caller scan. Never cached, never up to date."
    group = "verification"

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath

    dependsOn(tasks.testClasses)
    include("**/AssetFileSystemFacadeCallerScanTest*")

    outputs.upToDateWhen { false }
}

tasks.test {
    exclude("**/AssetFileSystemFacadeCallerScanTest*")
}

tasks.check {
    dependsOn(assetFileCallerScan)
}
```

Do **not** declare `server/**` as an input of `tasks.test` instead: that makes every spotless task a producer of the test task's inputs and `check` fails Gradle validation before running anything.

- [ ] **Step 5: Verify `check` passes twice running and the scan re-executes both times**

```bash
JAVA_HOME=$(ls -d "$HOME"/.sdkman/candidates/java/current) ./gradlew :server:libs:automation:automation-asset-file:automation-asset-file-service:check > /tmp/t1.log 2>&1
echo "EXIT=$?"
JAVA_HOME=$(ls -d "$HOME"/.sdkman/candidates/java/current) ./gradlew :server:libs:automation:automation-asset-file:automation-asset-file-service:check > /tmp/t2.log 2>&1
echo "EXIT=$?"
grep "assetFileCallerScan" /tmp/t2.log
```
Expected: both EXIT=0, and the scan task line in the second run carries no `UP-TO-DATE` or `FROM-CACHE` suffix.

- [ ] **Step 6: Commit**

```bash
git add server/libs/automation/automation-asset-file
git commit -m "732 Pin the asset-file system facade's caller set and method set"
```

---

## Task 8: Document the two-door model

**Files:**
- Modify: `.agents/resource-visibility.md`
- Modify: `docs/superpowers/specs/2026-09-02-asset-file-authorization-design.md` (status line only)

- [ ] **Step 1: Add a section to `.agents/resource-visibility.md`**

Cover: the two facades and which check each performs; the three no-principal caller families and why each is admissible; that the privilege-granting trio is membership-only by design; that the caller set is pinned by a scan; and the EE behaviour change — callers who today reach any asset file by id are refused unless they are members of its workspace, while CE is unchanged because `getUserWorkspaces` returns everything.

- [ ] **Step 2: Flip the spec's status line**

`**Status:** approved, not implemented` → `**Status:** implemented`.

- [ ] **Step 3: Commit**

```bash
git add .agents/resource-visibility.md docs/superpowers/specs/2026-09-02-asset-file-authorization-design.md
git commit -m "732 Document the asset-file two-door authorization model"
```

---

## Out of scope

Named here so an implementer does not widen the change:

- **`ASSET_FILE_*` permission scope** and read/write role differentiation.
- **The GraphQL `assetFileTags` query's missing guard** — it calls `AssetFileTagService`, not this facade.
- **`SlideBuilderToolCallback` / `ImageGeneratorToolCallback` not forwarding `.toolContext(...)`**, which leaves `slide_builder` and `image_generator` unable to touch asset files at all. A live functional bug, not security.
- **Whether sibling facades share this shape.** Asset files were found by following three vulnerabilities; nothing establishes that no other facade is equally unguarded.
