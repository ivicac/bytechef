# AI Hub Shared Sessions and Presence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a chat owner share an AI Hub chat with the workspace or named people, let those people follow it live and (when allowed) contribute turns, and show who is present.

**Architecture:** `ai_hub_chat` gains `visibility` (the platform `ResourceVisibility` model) and `participation`; "specific people" is `PRIVATE` plus `resource_grant` rows of type `AiHubChat`. One `AiHubChatAccessPolicy` (`canView` / `canParticipate` / `canManage`) replaces every `userId != requesterUserId` check in the service and the three REST gates. Following live rides on the existing in-flight registry and attach endpoint; presence is a cache-backed registry read through a status poll that replaces the `in-flight` probe. Attribution is a small `ai_hub_chat_turn` table because the session store drops per-message metadata.

**Tech Stack:** Java 25 / Spring Boot 4, Spring Data JDBC, Liquibase, Spring GraphQL, Spring `CacheManager` (Caffeine/Redis), AG-UI, React 19 + TypeScript + assistant-ui + graphql-codegen + TanStack Query.

**Spec:** [docs/superpowers/specs/2026-09-02-ai-hub-shared-sessions-presence-design.md](../specs/2026-09-02-ai-hub-shared-sessions-presence-design.md)

## Deviations from the spec (read before starting)

1. **Attribution uses the `ai_hub_chat_turn` fallback, not message metadata.** Verified against `spring-ai-session-jdbc` 0.7.0: `JdbcSessionRepository.insertEvent` persists `SessionEvent` metadata but rebuilds every `Message` from text and tool calls only, so `Message.getMetadata()` is lost on read. Session events are appended by the memory advisor, which gives us no hook to set event metadata per turn. A turn row per `POST /ai/chat/ai_hub` is deterministic: exactly one USER event and one turn row are written per accepted post, so the k-th visible USER event is the k-th turn row.
2. **`ResourceVisibilityPicker` already exists** (`client/src/shared/components/visibility/ResourceVisibilityPicker.tsx`) with a `showSpecificPeopleOption` and a `workspaceMembers` prop. The spec's "generalise `ConnectionVisibilityPicker`" is already done; the chat dialog consumes the shared picker as-is.
3. **The sidebar today is one flat list** with no sections (`AiHubChatsSidebar.tsx`). "Shared with me" is added as a second flat list under a heading, not as a section inside an existing grouping mechanism.
4. **No `AiHubChatFacade` exists** and no `@PreAuthorize` guards the chat path beyond `isAuthenticated()`; authorization is service-level ownership. This plan keeps that shape — the access policy is called from the service and the REST controller — rather than introducing a facade layer for one feature. The sharing operations DO get their own `AiHubChatSharingFacade` with `@PreAuthorize` on the impl, mirroring `ProjectSharingFacadeImpl`, because "owner or admin" is expressible there.
5. **Workspace membership is checked through `WorkspaceUserService.fetchWorkspaceUser(userId, workspaceId)`** (EE `automation-configuration-api`), the same call `ProjectSharingFacadeImpl` makes, not through the CE-permissive `WorkspaceAccessGuard.isMember`.

## Global Constraints

- **Every new file under `server/ee/`** uses the ByteChef Enterprise license header (copy verbatim from `server/ee/libs/ai/ai-hub/ai-hub-api/src/main/java/com/bytechef/ee/ai/hub/chat/AiHubChatTool.java`) with `@version ee` and `@author Ivica Cardic`.
- **Enum ordinals are persisted as INT** — `ResourceVisibility` (`PRIVATE=0, WORKSPACE=1, ORGANIZATION=2`, pinned by `ResourceVisibilityTest`); `AiHubChatParticipation`: `VIEW=0, PARTICIPATE=1`. Append-only.
- **Chats are created `PRIVATE`** — a deliberate departure from the platform rule; it is expressed through `AiHubChatVisibilityPolicy.defaultVisibility()`, never by a force-write in a facade.
- **Every enumeration-relevant failure** (unknown chat, chat in another workspace, non-member grantee, chat the caller cannot see) throws the Hub's `NotFoundException("AiHubChat not found")` so ids cannot be probed.
- **Java blank-line rules**, no `_`-prefixed methods, descriptive names, no `TODO:` comments, no rationale comments in code.
- **Test naming:** unit tests end in `Test`; method names camelCase, no underscores.
- **Client:** `sort-keys`, sorted import destructures, `I`/`Props` interface suffixes, `Icon`-suffixed Lucide imports, `Ref`-suffixed refs, hook ordering; `vi.hoisted` for refs used in `vi.mock` factories; never flush async store updates with a fixed sleep.
- **Client GraphQL:** operations in `client/src/graphql/ai/aihub/<domain>/*.graphql`; `cd client && npm run codegen`; operations and the generated client committed separately.
- **Before every server commit:** `./gradlew spotlessApply`; judge Gradle by `$?` on a redirected run and `grep '^> Task .* FAILED'`. **Before every client commit:** `cd client && npm run check` with the tool timeout at 600000 ms.
- **`PermissionServiceVisibilityTest`** (EE `automation-configuration-service`) is the regression guard for every visibility-wired type and MUST gain an `AiHubChat` case (Task 3).

---

## File Structure

**New, `ai-hub-api`** (package `com.bytechef.ee.ai.hub.chat` unless noted):

| File | Responsibility |
|------|----------------|
| `AiHubChatParticipation.java` | `VIEW` / `PARTICIPATE` enum |
| `AiHubChatAccessPolicy.java` | `canView` / `canParticipate` / `canManage` |
| `AiHubChatSharingFacade.java` | `setVisibility`, `grantAccess`, `revokeAccess`, `getGrants` |
| `AiHubChatTurn.java` | Aggregate for `ai_hub_chat_turn` |
| `presence/AiHubPresenceRegistry.java` | `heartbeat`, `leave`, `presence(threadId)`; nested `PresenceEntry` record and `PresenceState` enum |

**New, `ai-hub-service`:**

| File | Responsibility |
|------|----------------|
| `chat/AiHubChatVisibilityPolicy.java` | `ResourceVisibilityPolicy` for `"AiHubChat"`: `{PRIVATE, WORKSPACE}`, default `PRIVATE` |
| `chat/AiHubChatVisibilityProvider.java` | `ResourceVisibilityProvider` for `"AiHubChat"` |
| `chat/AiHubChatAccessPolicyImpl.java` | The three predicates over `ResourceVisibilityResolver` + admin |
| `chat/AiHubChatSharingFacadeImpl.java` | `@PreAuthorize` owner-or-admin, membership check, grants, audit |
| `chat/repository/AiHubChatTurnRepository.java` | turn rows |
| `presence/AiHubPresenceRegistryImpl.java` | `CacheManager`-backed map per thread |
| `src/main/resources/config/liquibase/changelog/automation/aihub/20260902000010_ai_hub_chat_add_visibility.xml` | `visibility`, `participation` columns + index |
| `.../aihub/20260902000011_ai_hub_chat_turn_init.xml` | turn table |

**Modified server files:**

| File | Change |
|------|--------|
| `ai-hub-api/.../chat/AiHubChat.java` | `visibility`, `participation` columns + accessors |
| `ai-hub-api/.../chat/AiHubChatService.java` + `AiHubChatServiceImpl.java` | Access policy replaces ownership checks; `listSharedWithMe`; `recordTurn`; `loadMessages` fills `authorUserId` |
| `ai-hub-service/.../chat/AiHubAgentConversationRecorder.java` + `repository/AiHubChatRepository.java` | Channel-born rows insert `WORKSPACE`; shared list query |
| `ai-hub-service/.../audit/AiHubAuditEvent.java` | Three sharing events |
| `ai-hub-rest/.../AiHubApiController.java` | `canParticipate` / `canView` gates; turn lock; `status` + `presence` endpoints replace `in-flight` |
| `ai-hub-graphql/.../AiHubChatGraphQlController.java`, `ai-hub-chat.graphqls`, new `AiHubChatSharingGraphQlController.java` + `ai-hub-chat-sharing.graphqls` | Fields + operations |
| `server/libs/config/cache-config/.../CacheConfiguration.java` | Presence cache registration (Caffeine + Redis) |
| `server/ee/libs/automation/automation-configuration/automation-configuration-service/src/test/.../PermissionServiceVisibilityTest.java` | `AiHubChat` provider case |
| `ai-hub-service/build.gradle.kts`, `ai-hub-api/build.gradle.kts` | `platform-resource-grant-api`, EE `automation-configuration-api` |

**Client files:**

| File | Change |
|------|--------|
| `client/src/graphql/ai/aihub/chat/aiHubChats.graphql`, `aiHubChatMessages.graphql`, new `aiHubSharedChats.graphql`; new `chat-sharing/*.graphql` | Fields + operations |
| `client/src/ee/pages/automation/ai-hub/chats/AiHubChatShareDialog.tsx` (new) | Share picker + participation switch |
| `client/src/ee/pages/automation/ai-hub/AiHubPanel.tsx` | Share menu item, presence strip |
| `client/src/ee/pages/automation/ai-hub/chats/AiHubChatsSidebar.tsx` | "Shared with me" list, status poll |
| `client/src/ee/pages/automation/ai-hub/runtime-providers/inFlightRunClient.ts`, `AiHubRuntimeProvider.tsx` | Status poll, heartbeat, turn-conflict handling |
| `client/src/ee/pages/automation/ai-hub/chats/stores/useAiHubChatsStore.ts` | presence + running-user state |
| `client/src/ee/pages/automation/ai-hub/composer/AiHubChatComposer.tsx` | View-only / other's-turn states |
| `client/src/ee/pages/automation/ai-hub/messages/AiHubMessage.tsx` | Author label |

---

### Task 1: Columns, enum, domain, policy registration

**Files:**
- Create: `ai-hub-api/.../chat/AiHubChatParticipation.java`; `ai-hub-service/.../chat/AiHubChatVisibilityPolicy.java`; the `20260902000010_ai_hub_chat_add_visibility.xml` changelog
- Modify: `ai-hub-api/.../chat/AiHubChat.java`; `ai-hub-api/build.gradle.kts` (add `implementation(project(":server:libs:platform:platform-api"))` is already present — nothing to add for `ResourceVisibility`)
- Test: `ai-hub-api/src/test/java/com/bytechef/ee/ai/hub/chat/AiHubChatVisibilityTest.java`; `ai-hub-service/src/test/.../chat/AiHubChatVisibilityPolicyTest.java`

**Interfaces:**
- Produces: `AiHubChat.getVisibility(): ResourceVisibility` / `setVisibility(ResourceVisibility)`; `AiHubChat.getParticipation(): AiHubChatParticipation` / `setParticipation(...)`; `AiHubChatVisibilityPolicy.RESOURCE_TYPE = "AiHubChat"`.

- [ ] **Step 1: Failing domain test**

`AiHubChatVisibilityTest`:

```java
class AiHubChatVisibilityTest {

    @Test
    void testParticipationOrdinalsAreStable() {
        assertThat(AiHubChatParticipation.VIEW.ordinal()).isEqualTo(0);
        assertThat(AiHubChatParticipation.PARTICIPATE.ordinal()).isEqualTo(1);
        assertThat(AiHubChatParticipation.values()).hasSize(2);
    }

    @Test
    void testNewChatIsPrivateAndViewOnly() {
        AiHubChat chat = new AiHubChat(3L);

        assertThat(chat.getVisibility()).isEqualTo(ResourceVisibility.PRIVATE);
        assertThat(chat.getParticipation()).isEqualTo(AiHubChatParticipation.VIEW);
    }

    @Test
    void testVisibilityRoundTripsThroughTheOrdinal() {
        AiHubChat chat = new AiHubChat(3L);

        chat.setVisibility(ResourceVisibility.WORKSPACE);
        chat.setParticipation(AiHubChatParticipation.PARTICIPATE);

        assertThat(chat.getVisibility()).isEqualTo(ResourceVisibility.WORKSPACE);
        assertThat(chat.getParticipation()).isEqualTo(AiHubChatParticipation.PARTICIPATE);
    }
}
```

Run: `./gradlew :server:ee:libs:ai:ai-hub:ai-hub-api:test --tests '*AiHubChatVisibilityTest' > /tmp/s1.log 2>&1; echo "exit=$?"` — expected `exit=1`.

- [ ] **Step 2: Enum, columns, accessors**

`AiHubChatParticipation.java`: `public enum AiHubChatParticipation { VIEW, PARTICIPATE }`.

In `AiHubChat.java` after the `ai_agent_id` field (line 116-117):

```java
    @Column
    private int visibility = ResourceVisibility.PRIVATE.ordinal();

    @Column
    private int participation = AiHubChatParticipation.VIEW.ordinal();
```

with `getVisibility()` → `ResourceVisibility.values()[visibility]`, `setVisibility(ResourceVisibility)`, `getParticipation()`, `setParticipation(...)`. Import `com.bytechef.platform.security.domain.ResourceVisibility`.

- [ ] **Step 3: Changelog**

`aihub/20260902000010_ai_hub_chat_add_visibility.xml` (prologue from `20260503000001_ai_hub_chat_add_auto_titled.xml`):

```xml
    <changeSet id="20260902000010" author="Ivica Cardic">
        <addColumn tableName="ai_hub_chat">
            <column name="visibility" type="INT" defaultValueNumeric="0">
                <constraints nullable="false"/>
            </column>
            <column name="participation" type="INT" defaultValueNumeric="0">
                <constraints nullable="false"/>
            </column>
        </addColumn>

        <createIndex tableName="ai_hub_chat" indexName="idx_ai_hub_chat_workspace_visibility">
            <column name="workspace_id"/>
            <column name="visibility"/>
            <column name="status"/>
            <column name="updated_at" descending="true"/>
        </createIndex>

        <rollback>
            <dropIndex tableName="ai_hub_chat" indexName="idx_ai_hub_chat_workspace_visibility"/>
            <dropColumn columnName="participation" tableName="ai_hub_chat"/>
            <dropColumn columnName="visibility" tableName="ai_hub_chat"/>
        </rollback>
    </changeSet>
```

Existing rows, including channel-born ones, stay `PRIVATE` (spec rollout decision ⚑2).

`AiHubChatRepository.insertAgentChatIfAbsent` (`@Query` at lines 102-114) lists its columns explicitly: add `visibility` and `participation` to the column list and two new parameters `int visibility, int participation` at the end of the method signature, bound as `:visibility`, `:participation`. Task 4 passes `WORKSPACE` / `VIEW` from the recorder.

- [ ] **Step 4: The visibility policy**

`ai-hub-service/.../chat/AiHubChatVisibilityPolicy.java` (mirror `ProjectVisibilityPolicy`):

```java
@Component
public class AiHubChatVisibilityPolicy implements ResourceVisibilityPolicy {

    public static final String RESOURCE_TYPE = "AiHubChat";

    @Override
    public String resourceType() {
        return RESOURCE_TYPE;
    }

    @Override
    public ResourceVisibility defaultVisibility() {
        return ResourceVisibility.PRIVATE;
    }

    @Override
    public Set<ResourceVisibility> supportedVisibilities() {
        return Set.of(ResourceVisibility.PRIVATE, ResourceVisibility.WORKSPACE);
    }
}
```

`AiHubChatVisibilityPolicyTest`: default is `PRIVATE`, `ORGANIZATION` unsupported, and `new ResourceVisibilityPolicyRegistry(List.of(new AiHubChatVisibilityPolicy()))` accepts it (the registry throws when the default is outside the supported set).

- [ ] **Step 5: Run and commit**

```bash
./gradlew :server:ee:libs:ai:ai-hub:ai-hub-api:test :server:ee:libs:ai:ai-hub:ai-hub-service:test --tests '*AiHubChatVisibility*' > /tmp/s1b.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/s1b.log
./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:testIntegration --tests '*AiHubChatRepositoryIntTest' > /tmp/s1i.log 2>&1; echo "exit=$?"
./gradlew spotlessApply > /tmp/sa.log 2>&1; echo "exit=$?"
git add server/ee/libs/ai/ai-hub
git commit -m "Give an AI Hub chat a visibility and a participation mode" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

### Task 2: Access policy, visibility provider, service authorization

**Files:**
- Create: `ai-hub-api/.../chat/AiHubChatAccessPolicy.java`; `ai-hub-service/.../chat/AiHubChatAccessPolicyImpl.java`, `AiHubChatVisibilityProvider.java`
- Modify: `ai-hub-service/build.gradle.kts` (add `implementation(project(":server:ee:libs:automation:automation-configuration:automation-configuration-api"))` for `WorkspaceUserService`, and `implementation(project(":server:ee:libs:platform:platform-resource-grant:platform-resource-grant-api"))`); `ai-hub-api/.../chat/AiHubChatService.java` + `AiHubChatServiceImpl.java` (`loadAndCheckOwnership` → policy; every caller)
- Modify: `server/ee/libs/automation/automation-configuration/automation-configuration-service/src/test/java/com/bytechef/ee/automation/configuration/service/PermissionServiceVisibilityTest.java`
- Test: `ai-hub-service/src/test/.../chat/AiHubChatAccessPolicyTest.java`; extend `AiHubChatServiceTest`

**Interfaces:**
- Consumes: `ResourceVisibilityResolver.filterVisibleIds(String resourceType, long workspaceId, Collection<VisibilityRecord> candidates)` (EE impl handles admin bypass, reach, ownership by `createdBy` login, and grants); `ResourceVisibilityProvider` / `VisibilityRecord(long id, ResourceVisibility visibility, String createdBy)`; `SecurityUtils.hasCurrentUserThisAuthority(AuthorityConstants.ADMIN)`.
- Produces:

```java
public interface AiHubChatAccessPolicy {

    boolean canView(AiHubChat chat, long userId);

    boolean canParticipate(AiHubChat chat, long userId);

    boolean canManage(AiHubChat chat, long userId);
}
```

  and `AiHubChatService` methods `getViewable(long chatId, long requesterWorkspaceId, long requesterUserId)`, `getParticipable(...)`, `getManageable(...)` replacing the single `loadAndCheckOwnership` role; `AiHubChatService.getByThreadIdViewable(String threadId, long requesterUserId)`.

- [ ] **Step 1: Failing policy test**

`AiHubChatAccessPolicyTest` (pure unit; `SecurityContextHolder` set/cleared per test like `PermissionServiceVisibilityTest`):

```java
class AiHubChatAccessPolicyTest {

    private static final long OWNER_ID = 3L;
    private static final long OTHER_ID = 4L;
    private static final long WORKSPACE_ID = 7L;

    private final ResourceVisibilityResolver visibilityResolver = mock(ResourceVisibilityResolver.class);
    private final AiHubChatAccessPolicy policy = new AiHubChatAccessPolicyImpl(visibilityResolver);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testOwnerCanDoEverything() {
        AiHubChat chat = chat(ResourceVisibility.PRIVATE, AiHubChatParticipation.VIEW);

        assertThat(policy.canView(chat, OWNER_ID)).isTrue();
        assertThat(policy.canParticipate(chat, OWNER_ID)).isTrue();
        assertThat(policy.canManage(chat, OWNER_ID)).isTrue();
        verifyNoInteractions(visibilityResolver);
    }

    @Test
    void testAdminCanDoEverything() {
        authenticate("ana", AuthorityConstants.ADMIN);
        AiHubChat chat = chat(ResourceVisibility.PRIVATE, AiHubChatParticipation.VIEW);

        assertThat(policy.canManage(chat, OTHER_ID)).isTrue();
    }

    @Test
    void testStrangerCannotViewAPrivateChat() {
        authenticate("ana");
        AiHubChat chat = chat(ResourceVisibility.PRIVATE, AiHubChatParticipation.VIEW);
        when(visibilityResolver.filterVisibleIds(eq("AiHubChat"), eq(WORKSPACE_ID), any())).thenReturn(Set.of());

        assertThat(policy.canView(chat, OTHER_ID)).isFalse();
        assertThat(policy.canParticipate(chat, OTHER_ID)).isFalse();
    }

    @Test
    void testReachOrGrantAllowsViewOnly() {
        authenticate("ana");
        AiHubChat chat = chat(ResourceVisibility.WORKSPACE, AiHubChatParticipation.VIEW);
        when(visibilityResolver.filterVisibleIds(eq("AiHubChat"), eq(WORKSPACE_ID), any())).thenReturn(Set.of(11L));

        assertThat(policy.canView(chat, OTHER_ID)).isTrue();
        assertThat(policy.canParticipate(chat, OTHER_ID)).isFalse();
        assertThat(policy.canManage(chat, OTHER_ID)).isFalse();
    }

    @Test
    void testParticipateModeAllowsTurns() {
        authenticate("ana");
        AiHubChat chat = chat(ResourceVisibility.WORKSPACE, AiHubChatParticipation.PARTICIPATE);
        when(visibilityResolver.filterVisibleIds(eq("AiHubChat"), eq(WORKSPACE_ID), any())).thenReturn(Set.of(11L));

        assertThat(policy.canParticipate(chat, OTHER_ID)).isTrue();
        assertThat(policy.canManage(chat, OTHER_ID)).isFalse();
    }

    @Test
    void testChatWithoutWorkspaceIsOwnerOnly() {
        authenticate("ana");
        AiHubChat chat = chat(ResourceVisibility.WORKSPACE, AiHubChatParticipation.PARTICIPATE);

        chat.setWorkspaceId(null);

        assertThat(policy.canView(chat, OTHER_ID)).isFalse();
        verifyNoInteractions(visibilityResolver);
    }

    private static AiHubChat chat(ResourceVisibility visibility, AiHubChatParticipation participation) {
        AiHubChat chat = new AiHubChat(OWNER_ID);

        ReflectionTestUtils.setField(chat, "id", 11L);
        chat.setWorkspaceId(WORKSPACE_ID);
        chat.setVisibility(visibility);
        chat.setParticipation(participation);

        return chat;
    }

    private static void authenticate(String login, String... authorities) {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(
                login, "n/a", Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }
}
```

Run: `./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:test --tests '*AiHubChatAccessPolicyTest' > /tmp/s2.log 2>&1; echo "exit=$?"` — expected `exit=1`.

- [ ] **Step 2: Implement the policy and the provider**

`AiHubChatAccessPolicyImpl` (`@Component`, `@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")`):

```java
    @Override
    public boolean canView(AiHubChat chat, long userId) {
        if (isOwnerOrAdmin(chat, userId)) {
            return true;
        }

        Long workspaceId = chat.getWorkspaceId();

        if (workspaceId == null) {
            return false;
        }

        Set<Long> visibleIds = visibilityResolver.filterVisibleIds(
            AiHubChatVisibilityPolicy.RESOURCE_TYPE, workspaceId,
            List.of(new VisibilityRecord(chat.getId(), chat.getVisibility(), chat.getCreatedBy())));

        return visibleIds.contains(chat.getId());
    }

    @Override
    public boolean canParticipate(AiHubChat chat, long userId) {
        if (isOwnerOrAdmin(chat, userId)) {
            return true;
        }

        return chat.getParticipation() == AiHubChatParticipation.PARTICIPATE && canView(chat, userId);
    }

    @Override
    public boolean canManage(AiHubChat chat, long userId) {
        return isOwnerOrAdmin(chat, userId);
    }

    private static boolean isOwnerOrAdmin(AiHubChat chat, long userId) {
        return chat.getUserId() == userId || SecurityUtils.hasCurrentUserThisAuthority(AuthorityConstants.ADMIN);
    }
```

`VisibilityRecord.createdBy` is what the EE resolver compares to the current login for the ownership rung. `AiHubChat` has no `created_by` column — the owner is `user_id`. Pass `null` for `createdBy` and rely on the explicit `chat.getUserId() == userId` check above for ownership; the resolver's ownership rung is then never the deciding one for chats. Confirm `ResourceVisibilityResolverImpl` (EE `automation-configuration-service`, lines 54-92) null-guards `createdBy` before comparing; if it does not, add the guard there (`Objects.equals` is null-safe, `String.equals` on a null receiver is not).

`AiHubChatVisibilityProvider` (`@Component`, same conditional; mirror `ProjectVisibilityProvider`): `resourceType()` returns `AiHubChatVisibilityPolicy.RESOURCE_TYPE`; `fetchVisibility(long id)` → `chatRepository.findById(id).map(chat -> new VisibilityRecord(chat.getId(), chat.getVisibility(), null))`. Registering the provider makes `PermissionServiceImpl.isResourceVisible` take the visibility branch for `"AiHubChat"`, so any future `hasResourceScope(chatId, "AiHubChat", …)` gets the precondition. The resolver + provider interfaces live in CE `automation-configuration-api`, which `ai-hub-service` already depends on.

- [ ] **Step 3: Route the service through the policy**

In `AiHubChatServiceImpl`:

- Inject `AiHubChatAccessPolicy accessPolicy` (constructor; update the four hand-built constructor call sites in tests with a policy stub that treats the owner as allowed — a lambda-based fake is fine since the interface has three methods: write a tiny `OwnerOnlyAccessPolicy` test helper in `src/test/.../chat/`).
- Replace `loadAndCheckOwnership(chatId, requesterWorkspaceId, requesterUserId)` with three private loaders that share one body and differ in the predicate:

```java
    private AiHubChat loadViewable(long chatId, long requesterWorkspaceId, long requesterUserId) {
        return load(chatId, requesterWorkspaceId, requesterUserId, accessPolicy::canView);
    }

    private AiHubChat loadParticipable(long chatId, long requesterWorkspaceId, long requesterUserId) {
        return load(chatId, requesterWorkspaceId, requesterUserId, accessPolicy::canParticipate);
    }

    private AiHubChat loadManageable(long chatId, long requesterWorkspaceId, long requesterUserId) {
        return load(chatId, requesterWorkspaceId, requesterUserId, accessPolicy::canManage);
    }

    private AiHubChat load(
        long chatId, long requesterWorkspaceId, long requesterUserId, BiPredicate<AiHubChat, Long> allowed) {

        AiHubChat chat = chatRepository.findById(chatId)
            .orElseThrow(() -> new NotFoundException("AiHubChat not found"));

        if (!workspaceOwnsChat(requesterWorkspaceId, chat) || !allowed.test(chat, requesterUserId)) {
            throw new NotFoundException("AiHubChat not found");
        }

        return chat;
    }
```

- Assign each public method: `loadMessages`, `summarizeTranscript`, `getById` → `loadViewable`; `appendAssistantMessage`, `truncateMessagesFrom`, `cancelAiHubRun`, `cancelWorkflowChatTurn` → `loadParticipable`; `patch`, `delete` → `loadManageable`. `getByThreadId` (line 806-815, inline predicate) → load by thread id then apply `canView`. `bulkArchiveWorkflowChatAiHubChats` keeps its owner-only loop (archiving someone else's chats in bulk is not a sharing feature).
- Add `getViewable/getParticipable/getManageable` to the interface as public aliases of the loaders (the REST controller needs them in Task 5) and `Optional<AiHubChat> findByThreadIdViewable(String threadId, long requesterUserId)`.

Extend `AiHubChatServiceTest` with: a granted non-owner can `loadMessages` but not `patch`; a `PARTICIPATE` non-owner can `appendAssistantMessage`; a `VIEW` non-owner cannot; all three failures are `NotFoundException`.

- [ ] **Step 4: Guard the regression test**

In `PermissionServiceVisibilityTest` add constants `AI_HUB_CHAT = "AiHubChat"`, `PRIVATE_CHAT_ID = 30L`, a `chatVisibilityProvider()` anonymous provider returning a `PRIVATE` record for that id (copy `connectionVisibilityProvider()` at lines 290-311), include it in the provider list passed to `PermissionServiceImpl` at line 254-259, and add:

```java
    @Test
    void testPrivateAiHubChatDeniedToNonOwnerHoldingTheScope() {
        authenticate("ana");

        assertThat(permissionService(Set.of()).hasResourceScope(PRIVATE_CHAT_ID, AI_HUB_CHAT, "WORKSPACE_VIEW"))
            .isFalse();
    }
```

- [ ] **Step 5: Run and commit**

```bash
./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:test :server:ee:libs:automation:automation-configuration:automation-configuration-service:test --tests '*PermissionServiceVisibilityTest' --tests '*AiHubChat*' > /tmp/s2b.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/s2b.log
./gradlew spotlessApply > /tmp/sa.log 2>&1; echo "exit=$?"
git add server/ee/libs/ai/ai-hub server/ee/libs/automation/automation-configuration/automation-configuration-service/src/test
git commit -m "Authorize AI Hub chat reads and writes through a view, participate and manage policy" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Sharing facade, grants, GraphQL, audit

**Files:**
- Create: `ai-hub-api/.../chat/AiHubChatSharingFacade.java`; `ai-hub-service/.../chat/AiHubChatSharingFacadeImpl.java`; `ai-hub-graphql/.../AiHubChatSharingGraphQlController.java`; `ai-hub-graphql/src/main/resources/graphql/ai-hub-chat-sharing.graphqls`
- Modify: `ai-hub-service/.../audit/AiHubAuditEvent.java`; `ai-hub-service/.../chat/AiHubChatServiceImpl.java` (`delete` removes grants); `ai-hub-graphql/src/main/resources/graphql/ai-hub-chat.graphqls` (`visibility`, `participation`, `ownerUserId`, `ownerName`, `isOwner` on `AiHubChat`)
- Test: `ai-hub-service/src/test/.../chat/AiHubChatSharingFacadeTest.java`; `ai-hub-graphql/src/test/.../AiHubChatSharingGraphQlControllerTest.java`

**Interfaces:**
- Consumes: `ResourceGrantService.grant/revoke/getGrantedUserIds/deleteGrants(String resourceType, long resourceId, …)`; `ResourceVisibilityPolicyRegistry.supports(String, ResourceVisibility)`; `WorkspaceUserService.fetchWorkspaceUser(long userId, long workspaceId)`; `UserService.fetchUser(long id)` for `ownerName`.
- Produces:

```java
public interface AiHubChatSharingFacade {

    AiHubChat setVisibility(long workspaceId, long chatId, ResourceVisibility visibility, AiHubChatParticipation participation);

    AiHubChat grantAccess(long workspaceId, long chatId, long userId);

    AiHubChat revokeAccess(long workspaceId, long chatId, long userId);

    List<Long> getGrants(long workspaceId, long chatId);
}
```

- [ ] **Step 1: Audit events**

Append to `AiHubAuditEvent`: `AI_HUB_CHAT_VISIBILITY_CHANGED(true)`, `AI_HUB_CHAT_ACCESS_GRANTED(false)`, `AI_HUB_CHAT_ACCESS_REVOKED(true)` (payloads: `chatId`, `workspaceId`, `visibility`, `participation`; `granteeUserId` for the two grant events).

- [ ] **Step 2: Failing facade test**

`AiHubChatSharingFacadeTest` (mocks `AiHubChatService`, `ResourceGrantService`, `WorkspaceUserService`, `UserService`; real `ResourceVisibilityPolicyRegistry(List.of(new AiHubChatVisibilityPolicy()))`; `AiHubAuditPublisher` null):

- `testSetVisibilityRejectsOrganization` → `IllegalArgumentException` whose message contains `ORGANIZATION`, nothing saved.
- `testSetVisibilityPersistsBothFields` → `chatService.patchSharing(chatId, visibility, participation)` called (new service method, below) and returned.
- `testGrantRequiresWorkspaceMember` → `fetchWorkspaceUser` empty → `NotFoundException`, `grant` never called.
- `testGrantIsIdempotentThroughTheService` → `resourceGrantService.grant("AiHubChat", chatId, userId)` called once.
- `testRevokeDoesNotCheckMembership` → `revoke` called even when `fetchWorkspaceUser` is empty.
- `testGetGrantsReturnsUserIds`.

Every test first stubs `chatService.getManageable(chatId, workspaceId, currentUserId)` to return a chat; `userService.getCurrentUser()` returns id 3.

- [ ] **Step 3: Implement**

Add to `AiHubChatService`: `AiHubChat patchSharing(long chatId, ResourceVisibility visibility, AiHubChatParticipation participation)` (loads by id, sets both, saves; no access check — the facade did it).

`AiHubChatSharingFacadeImpl` (`@Service`, `@Transactional`, `@ConditionalOnEEVersion`, `@ConditionalOnProperty` as before). Because chats have no `PermissionService` resource type wired for `isResourceOwner`, the owner-or-admin check is programmatic (`chatService.getManageable`) rather than a `@PreAuthorize` expression; annotate the class with `@PreAuthorize("isAuthenticated()")` so the facade never runs anonymous, and keep the programmatic check on every method:

```java
    @Override
    public AiHubChat setVisibility(
        long workspaceId, long chatId, ResourceVisibility visibility, AiHubChatParticipation participation) {

        if (!policyRegistry.supports(AiHubChatVisibilityPolicy.RESOURCE_TYPE, visibility)) {
            throw new IllegalArgumentException("Unsupported visibility for a chat: " + visibility);
        }

        AiHubChat chat = chatService.getManageable(chatId, workspaceId, currentUserId());
        AiHubChat updated = chatService.patchSharing(chat.getId(), visibility, participation);

        publish(AiHubAuditEvent.AI_HUB_CHAT_VISIBILITY_CHANGED, updated, Map.of(
            "visibility", visibility.name(), "participation", participation.name()));

        return updated;
    }

    @Override
    public AiHubChat grantAccess(long workspaceId, long chatId, long userId) {
        AiHubChat chat = chatService.getManageable(chatId, workspaceId, currentUserId());

        if (workspaceUserService.fetchWorkspaceUser(userId, workspaceId)
            .isEmpty()) {

            throw new NotFoundException("AiHubChat not found");
        }

        resourceGrantService.grant(AiHubChatVisibilityPolicy.RESOURCE_TYPE, chat.getId(), userId);
        publish(AiHubAuditEvent.AI_HUB_CHAT_ACCESS_GRANTED, chat, Map.of("granteeUserId", userId));

        return chat;
    }
```

`revokeAccess` mirrors without the membership check; `getGrants` → `resourceGrantService.getGrantedUserIds(RESOURCE_TYPE, chat.getId())`. In `AiHubChatServiceImpl.delete`, after `chatRepository.delete(chat)`, call `resourceGrantServiceProvider.getIfAvailable()` → `deleteGrants(RESOURCE_TYPE, chatId)` (inject as `ObjectProvider<ResourceGrantService>`; CE has no bean).

- [ ] **Step 4: Schema and controller**

`ai-hub-chat-sharing.graphqls`:

```graphql
extend type Query {
    aiHubChatGrants(workspaceId: ID!, chatId: ID!): [Long!]!
}

extend type Mutation {
    setAiHubChatVisibility(workspaceId: ID!, chatId: ID!, visibility: ResourceVisibility!, participation: AiHubChatParticipation!): AiHubChat!
    grantAiHubChatAccess(workspaceId: ID!, chatId: ID!, userId: ID!): AiHubChat!
    revokeAiHubChatAccess(workspaceId: ID!, chatId: ID!, userId: ID!): AiHubChat!
}

enum AiHubChatParticipation {
    VIEW
    PARTICIPATE
}
```

`ResourceVisibility` is already a GraphQL enum in the platform schema (`project-sharing.graphqls` uses it); confirm the ai-hub schema set can see it (`grep -rn "enum ResourceVisibility" server --include='*.graphqls' | grep -v build`) — if it is declared in a module the ai-hub GraphQL app does not load, declare `enum ResourceVisibility { PRIVATE WORKSPACE ORGANIZATION }` here as well; Spring GraphQL merges identical enum declarations across files only if they match exactly.

In `ai-hub-chat.graphqls` add to `type AiHubChat`: `visibility: ResourceVisibility!`, `participation: AiHubChatParticipation!`, `ownerUserId: Long!`, `ownerName: String`, `isOwner: Boolean!`. In `AiHubChatGraphQlController` add `@SchemaMapping` resolvers: `visibility`/`participation` return the enums; `ownerUserId` → `chat.getUserId()`; `ownerName` → `userService.fetchUser(chat.getUserId()).map(User::getLogin).orElse(null)` (batch later if the sidebar list makes this N+1 visible — 100 rows max today); `isOwner` → `chat.getUserId() == currentUserId`.

`AiHubChatSharingGraphQlController`: `@Controller @ConditionalOnEEVersion @ConditionalOnProperty(...)`, no class-level `@PreAuthorize` (facade owns it), four mappings delegating to the facade. Hand-built unit test asserting delegation.

- [ ] **Step 5: Run and commit**

```bash
./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:test :server:ee:libs:ai:ai-hub:ai-hub-graphql:test > /tmp/s3.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/s3.log
./gradlew spotlessApply > /tmp/sa.log 2>&1; echo "exit=$?"
git add server/ee/libs/ai/ai-hub
git commit -m "Share an AI Hub chat with the workspace or with named members" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

### Task 4: Shared-with-me listing and workspace-visible channel chats

**Files:**
- Modify: `ai-hub-service/.../chat/repository/AiHubChatRepository.java`; `AiHubChatService.java` + `AiHubChatServiceImpl.java` (`listSharedWithMe`); `AiHubAgentConversationRecorder.java:201-226`; `AiHubChatGraphQlController.java` + `ai-hub-chat.graphqls`
- Test: extend `AiHubChatServiceTest`, `AiHubAgentConversationRecorderTest`, `AiHubChatRepositoryIntTest`

**Interfaces:**
- Consumes: `ResourceGrantService.filterGrantedResourceIds(String resourceType, long userId, Collection<Long> resourceIds)`.
- Produces: `AiHubChatService.listSharedWithMe(long workspaceId, long userId, int environment)`; GraphQL `aiHubSharedChats(workspaceId: ID!, environment: Int!): [AiHubChat!]!`.

- [ ] **Step 1: Repository queries**

Add to `AiHubChatRepository` (copy the `@Query` style of the existing list queries at lines 43-58, table alias `cct`):

```java
    @Query("""
        SELECT cct.* FROM ai_hub_chat cct
        WHERE cct.workspace_id = :workspaceId AND cct.user_id <> :userId AND cct.environment = :environment
          AND cct.status = :status AND cct.visibility = :visibility
        ORDER BY cct.updated_at DESC LIMIT :limit
        """)
    List<AiHubChat> findSharedByReach(
        long workspaceId, long userId, int environment, int status, int visibility, int limit);

    @Query("""
        SELECT cct.* FROM ai_hub_chat cct
        WHERE cct.workspace_id = :workspaceId AND cct.user_id <> :userId AND cct.environment = :environment
          AND cct.status = :status AND cct.visibility = :privateVisibility
        ORDER BY cct.updated_at DESC LIMIT :limit
        """)
    List<AiHubChat> findPrivateCandidates(
        long workspaceId, long userId, int environment, int status, int privateVisibility, int limit);
```

- [ ] **Step 2: Failing service test**

In `AiHubChatServiceTest`: `testListSharedWithMeUnionsReachAndGrants` — reach query returns chats 11 and 12; private candidates returns 13 and 14; `resourceGrantService.filterGrantedResourceIds("AiHubChat", USER_ID, [13, 14])` returns `{14}`; result ids are `{11, 12, 14}` ordered by `updatedAt` desc; `testListSharedWithMeWithoutGrantServiceReturnsReachOnly` (CE: provider yields null).

- [ ] **Step 3: Implement**

```java
    @Override
    @Transactional(readOnly = true)
    public List<AiHubChat> listSharedWithMe(long workspaceId, long userId, int environment) {
        int active = AiHubChatStatus.ACTIVE.ordinal();
        List<AiHubChat> shared = new ArrayList<>(chatRepository.findSharedByReach(
            workspaceId, userId, environment, active, ResourceVisibility.WORKSPACE.ordinal(), LIST_LIMIT));
        ResourceGrantService resourceGrantService = resourceGrantServiceProvider.getIfAvailable();

        if (resourceGrantService != null) {
            List<AiHubChat> candidates = chatRepository.findPrivateCandidates(
                workspaceId, userId, environment, active, ResourceVisibility.PRIVATE.ordinal(), LIST_LIMIT * 5);
            Set<Long> grantedIds = resourceGrantService.filterGrantedResourceIds(
                AiHubChatVisibilityPolicy.RESOURCE_TYPE, userId,
                candidates.stream()
                    .map(AiHubChat::getId)
                    .toList());

            for (AiHubChat candidate : candidates) {
                if (grantedIds.contains(candidate.getId())) {
                    shared.add(candidate);
                }
            }
        }

        shared.sort(Comparator.comparing(AiHubChat::getUpdatedAt)
            .reversed());

        return shared.size() > LIST_LIMIT ? shared.subList(0, LIST_LIMIT) : shared;
    }
```

The `LIST_LIMIT * 5` candidate window is a bound, not a promise: a grant on the 501st most-recent private chat in a workspace is not listed. Note it in the deep-dive (Task 10).

GraphQL: `aiHubSharedChats(workspaceId, environment)` on `AiHubChatGraphQlController`, same guard sequence as `aiHubChats` (current user → `WorkspaceAccessGuard.verifyUserCanAccessWorkspace` → service).

- [ ] **Step 4: Channel-born rows are workspace-visible**

In `AiHubAgentConversationRecorder.findOrCreateChat` (line 217-220) pass `ResourceVisibility.WORKSPACE.ordinal(), AiHubChatParticipation.VIEW.ordinal()` as the two new trailing arguments of `insertAgentChatIfAbsent`. `AiHubChatServiceImpl.create` / `createWebhookBridgedChat` construct `new AiHubChat(userId)` and therefore stay `PRIVATE` by the field default — add an assertion to `AiHubChatServiceTest.testCreate…` that `getVisibility()` is `PRIVATE`, and to `AiHubAgentConversationRecorderTest` that the insert is called with `WORKSPACE`. In `AiHubChatRepositoryIntTest` add a round-trip: insert via `insertAgentChatIfAbsent` with `WORKSPACE`, read back, assert `getVisibility()`.

- [ ] **Step 5: Run and commit**

```bash
./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:test :server:ee:libs:ai:ai-hub:ai-hub-graphql:test > /tmp/s4.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/s4.log
./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:testIntegration --tests '*AiHubChatRepositoryIntTest' > /tmp/s4i.log 2>&1; echo "exit=$?"
./gradlew spotlessApply > /tmp/sa.log 2>&1; echo "exit=$?"
git add server/ee/libs/ai/ai-hub
git commit -m "List the chats shared with a user and make channel-born agent chats workspace-visible" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Participant turns — REST gates, turn lock, attribution

**Files:**
- Create: `ai-hub-api/.../chat/AiHubChatTurn.java`; `ai-hub-service/.../chat/repository/AiHubChatTurnRepository.java`; `.../aihub/20260902000011_ai_hub_chat_turn_init.xml`
- Modify: `ai-hub-rest/.../AiHubApiController.java` (`enforceThreadOwnership` 253-281, `enforceThreadOwnershipForAttach` 206-225, `isOwnedInFlightThread` 195-204, `chat` 107-134); `AiHubChatService`/`Impl` (`recordTurn`, `loadMessages` author fill, `truncateMessagesFrom` turn truncation); `ai-hub-chat.graphqls` (`AiHubChatMessage.authorUserId`, `authorName`); `AiHubChatGraphQlController` (`AiHubChatMessage.authorName` resolver)
- Test: `ai-hub-rest/src/test/.../AiHubApiControllerTest.java` (create following `AiHubChatGraphQlControllerTest`'s hand-built style if absent; check `ls server/ee/libs/ai/ai-hub/ai-hub-rest/src/test/java`); extend `AiHubChatServiceTest`

**Interfaces:**
- Consumes: `InFlightAiHubRunRegistry.isInFlight(String threadId)`, `getInFlightThreadIds()`, `InFlightRun.getRunId()`; `AiHubChatService.AiHubChatMessage` record.
- Produces: `AiHubChatTurn(chatId, userId, runId, createdDate)`; `AiHubChatService.recordTurn(long chatId, long userId, String runId)`; `AiHubChatMessage` gains `@Nullable Long authorUserId` as a fifth component (keep the two existing constructors delegating with `null`); a new typed REST error `TURN_IN_FLIGHT` (HTTP 409, body `{"error":"TURN_IN_FLIGHT","runningUserId":…,"runningUserName":…}`).

- [ ] **Step 1: Turn table**

`AiHubChatTurn`: `@Table("ai_hub_chat_turn")`, `@Id Long id`, `chat_id` long, `user_id` long, `run_id` VARCHAR(255), `@CreatedDate created_date` Instant. Changelog `20260902000011_ai_hub_chat_turn_init.xml`: those columns (`id` autoIncrement PK, `chat_id`/`user_id`/`created_date` NOT NULL, `run_id` nullable), index `idx_ai_hub_chat_turn_chat` on `(chat_id, created_date)`. Repository: `List<AiHubChatTurn> findAllByChatIdOrderByCreatedDateAsc(long chatId)`, `void deleteAllByChatId(long chatId)`, and a `@Modifying @Query("DELETE FROM ai_hub_chat_turn WHERE chat_id = :chatId AND id IN (SELECT id FROM ai_hub_chat_turn WHERE chat_id = :chatId ORDER BY created_date ASC OFFSET :keep)") int deleteFromOrdinal(long chatId, int keep)`.

- [ ] **Step 2: Failing service tests**

In `AiHubChatServiceTest`:

- `testLoadMessagesAssignsAuthorsByUserEventOrder`: session events USER("a"), ASSISTANT("b"), USER("c"); turn rows for users 3 then 4 → messages[0].authorUserId 3, messages[2].authorUserId 4, messages[1] null.
- `testLoadMessagesWithoutTurnRowsLeavesAuthorsNull` (channel-born chats have no POSTs).
- `testTruncateMessagesFromAlsoTruncatesTurns`: truncating from visible index 2 keeps the turn rows for the USER events before it — count the USER events among the retained visible rows and call `deleteFromOrdinal(chatId, keptUserEvents)`.
- `testRecordTurnInsertsARow`.

- [ ] **Step 3: Implement attribution**

In `loadMessages` (lines 354-420), before building messages load `List<AiHubChatTurn> turns = turnRepository.findAllByChatIdOrderByCreatedDateAsc(chat.getId())` and keep an `int userEventIndex = 0`; when a visible event is `MessageType.USER`, set `authorUserId = userEventIndex < turns.size() ? turns.get(userEventIndex).getUserId() : null` and increment. In `delete`, `turnRepository.deleteAllByChatId(chatId)`. Add the fifth record component and the `AiHubChatMessage.authorName` `@SchemaMapping` (`userService.fetchUser(authorUserId).map(User::getLogin)`), plus `authorUserId: Long` / `authorName: String` on the GraphQL type.

- [ ] **Step 4: REST gates and the turn lock**

In `AiHubApiController`:

- `enforceThreadOwnership` → rename `enforceThreadAccess`; replace `row.getUserId() != userId || …` with `!accessPolicy.canParticipate(row, userId) || chatService.getWorkspaceId(row.getId()) != workspaceId` (inject `AiHubChatAccessPolicy`).
- `enforceThreadOwnershipForAttach` → `enforceThreadViewable`, predicate `accessPolicy.canView(row, userId)`.
- `isOwnedInFlightThread` → `isViewableInFlightThread`, predicate `canView`.
- In `chat(...)`, after the thread check and before `runAgent`, when `verifiedThreadId != null`:

```java
        if (inFlightRunRegistry.isInFlight(verifiedThreadId)) {
            throw new TurnInFlightException(resolveRunningUser(verifiedThreadId));
        }

        chatService.recordTurn(chat.getId(), userId, agUiParameters.getRunId());
```

  where `resolveRunningUser` reads the most recent `AiHubChatTurn` for the chat (new `AiHubChatService.findLatestTurn(chatId)` → `Optional<AiHubChatTurn>`) and maps to `(userId, login)`. `TurnInFlightException` is a new `@ResponseStatus(HttpStatus.CONFLICT)` exception in `ai-hub-rest` with a `@ExceptionHandler` in the controller returning the JSON body above. The registry already prevents two runs on one thread (`startRun` returns the live run); this check turns the silent attach-instead-of-run into an explicit conflict the client can label.

- [ ] **Step 5: Controller tests**

`AiHubApiControllerTest` (hand-built, `MockMvc` standalone is unnecessary — call the methods directly): participant with `PARTICIPATE` passes `enforceThreadAccess`; `VIEW` non-owner gets `ResponseStatusException` 403 from `chat` but 200-equivalent from `attach`; in-flight thread → `TurnInFlightException`; `inFlightStatus` reports `true` only for viewable in-flight ids.

- [ ] **Step 6: Run and commit**

```bash
./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:test :server:ee:libs:ai:ai-hub:ai-hub-rest:test :server:ee:libs:ai:ai-hub:ai-hub-graphql:test > /tmp/s5.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/s5.log
./gradlew spotlessApply > /tmp/sa.log 2>&1; echo "exit=$?"
git add server/ee/libs/ai/ai-hub
git commit -m "Let a participant send turns on a shared AI Hub chat, one turn at a time, with attribution" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

### Task 6: Presence registry and the status poll

**Files:**
- Create: `ai-hub-api/.../presence/AiHubPresenceRegistry.java`; `ai-hub-service/.../presence/AiHubPresenceRegistryImpl.java`
- Modify: `server/libs/config/cache-config/src/main/java/com/bytechef/cache/config/CacheConfiguration.java` (constant + Redis line after 96 + Caffeine block after 138-143); `ai-hub-rest/.../AiHubApiController.java` (`status` + `presence` endpoints; keep `in-flight` one release for old clients)
- Test: `ai-hub-service/src/test/.../presence/AiHubPresenceRegistryTest.java`; extend `AiHubApiControllerTest`

**Interfaces:**
- Produces:

```java
public interface AiHubPresenceRegistry {

    enum PresenceState {
        VIEWING, TYPING
    }

    record PresenceEntry(long userId, String userName, PresenceState state, Instant lastSeen) {
    }

    void heartbeat(String threadId, long userId, String userName, PresenceState state);

    void leave(String threadId, long userId);

    List<PresenceEntry> presence(String threadId);
}
```

  REST: `GET /ai/chat/ai_hub/status?threadIds=…` → `Map<String, ThreadStatus>` with `record ThreadStatus(boolean inFlight, @Nullable Long runningUserId, @Nullable String runningUserName, int messageCount, long updatedAt, List<PresenceEntry> presence)`; `POST /ai/chat/ai_hub/{threadId}/presence` body `{"state":"VIEWING"|"TYPING"|"LEFT"}` → 204.

- [ ] **Step 1: Cache registration**

In `CacheConfiguration`: `private static final String AI_HUB_PRESENCE_CACHE = "com.bytechef.ee.ai.hub.presence.AiHubPresenceRegistry.presence";`; Redis: `.withCacheConfiguration(AI_HUB_PRESENCE_CACHE, getCacheConfiguration(1, classLoader))` (the helper's unit is minutes — 1 minute TTL); Caffeine: `registerCustomCache(AI_HUB_PRESENCE_CACHE, Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).recordStats().build())`. The registry duplicates the same literal as `public static final String CACHE_NAME` (the pattern `WorkflowChatGuard.IN_FLIGHT_CACHE_NAME` follows: `cache-config` cannot depend on `ai-hub`).

- [ ] **Step 2: Failing registry test**

`AiHubPresenceRegistryTest` uses a real `ConcurrentMapCacheManager` (`org.springframework.cache.concurrent`) so the map semantics are exercised without Caffeine:

- `testHeartbeatAddsAndUpdates` (two heartbeats from one user → one entry with the latest state);
- `testLeaveRemoves`;
- `testStaleEntriesAreDroppedOnRead` (registry built with a `Clock` set 46 s after the heartbeat → empty; TTL per entry is 45 s, enforced on read because the cache's own TTL is per thread map, not per user);
- `testThreadsAreIsolated`.

- [ ] **Step 3: Implement**

`AiHubPresenceRegistryImpl` (`@Component`, `@ConditionalOnProperty` as before): the cache value is a `HashMap<Long, PresenceEntry>` per `threadId` (serialisable for Redis — `PresenceEntry` must be `Serializable`; make the record implement it). `heartbeat` reads the map (or a new one), puts the entry with `Instant.now(clock)`, writes it back; `leave` removes and writes back; `presence` reads, filters `lastSeen.isAfter(now.minusSeconds(45))`, returns sorted by `userId`. Cache access through `cacheManager.getCache(CACHE_NAME)` with the same `IllegalStateException` guard `WorkflowChatGuard.getCache` uses. Read-modify-write on a shared cache races between two heartbeats of different users on the same thread; acceptable — the loser's entry reappears on its next 20-second heartbeat. Note it in Task 10.

- [ ] **Step 4: Endpoints**

In `AiHubApiController`:

```java
    @GetMapping(value = "/ai/chat/ai_hub/status")
    public Map<String, ThreadStatus> status(@RequestParam("threadIds") List<String> threadIds) {
        long userId = userService.getCurrentUser()
            .getId();
        Set<String> inFlight = new HashSet<>(inFlightRunRegistry.getInFlightThreadIds());
        Map<String, ThreadStatus> statusByThreadId = new HashMap<>();

        for (String threadId : threadIds) {
            Optional<AiHubChat> chatOptional = chatService.findByThreadIdViewable(threadId, userId);

            if (chatOptional.isEmpty()) {
                continue;
            }

            AiHubChat chat = chatOptional.get();
            boolean running = inFlight.contains(threadId);
            Optional<AiHubChatTurn> latestTurn = running ? chatService.findLatestTurn(chat.getId()) : Optional.empty();

            statusByThreadId.put(threadId, new ThreadStatus(
                running, latestTurn.map(AiHubChatTurn::getUserId).orElse(null),
                latestTurn.flatMap(turn -> userService.fetchUser(turn.getUserId())).map(User::getLogin).orElse(null),
                chat.getMessageCount(), toEpochMilli(chat.getUpdatedAt()), presenceRegistry.presence(threadId)));
        }

        return statusByThreadId;
    }

    @PostMapping(value = "/ai/chat/ai_hub/{threadId}/presence")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void presence(@PathVariable("threadId") String threadId, @RequestBody PresenceRequest request) {
        User user = userService.getCurrentUser();

        enforceThreadViewable(threadId, user.getId());

        if ("LEFT".equals(request.state())) {
            presenceRegistry.leave(threadId, user.getId());

            return;
        }

        presenceRegistry.heartbeat(
            threadId, user.getId(), user.getLogin(), AiHubPresenceRegistry.PresenceState.valueOf(request.state()));
    }

    public record PresenceRequest(String state) {
    }
```

Threads the caller cannot view are omitted from the map rather than reported as anything — same shape as the old probe's "unknown → false". Keep the old `in-flight` endpoint delegating to `status` (`inFlight` only) for one release so an already-open client keeps working through a deploy; remove it in the follow-up noted in Task 10.

- [ ] **Step 5: Run and commit**

```bash
./gradlew :server:libs:config:cache-config:test :server:ee:libs:ai:ai-hub:ai-hub-service:test :server:ee:libs:ai:ai-hub:ai-hub-rest:test > /tmp/s6.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/s6.log
./gradlew spotlessApply > /tmp/sa.log 2>&1; echo "exit=$?"
git add server/libs/config/cache-config server/ee/libs/ai/ai-hub
git commit -m "Track who is viewing or typing in an AI Hub chat and report it with the in-flight status" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: Client — share dialog, shared list, chat fields

**Files:**
- Create: `client/src/graphql/ai/aihub/chat/aiHubSharedChats.graphql`; `client/src/graphql/ai/aihub/chat-sharing/aiHubChatGrants.graphql`, `setAiHubChatVisibility.graphql`, `grantAiHubChatAccess.graphql`, `revokeAiHubChatAccess.graphql`
- Modify: `client/src/graphql/ai/aihub/chat/aiHubChats.graphql` (add `visibility participation ownerUserId ownerName isOwner`)
- Create: `client/src/ee/pages/automation/ai-hub/chats/AiHubChatShareDialog.tsx` + `tests/AiHubChatShareDialog.test.tsx`
- Modify: `client/src/ee/pages/automation/ai-hub/chats/api/chats.api.ts` + `hooks/useChats.ts` (`useAiHubSharedChatsQuery`); `AiHubChatsSidebar.tsx` (second list); `AiHubPanel.tsx:182-233` (Share item); `client/src/ee/pages/automation/ai-hub/chats/hooks/useAiHubChatActions.ts` (`requestShare`, `shareTarget`, `cancelShare`)

**Interfaces:**
- Consumes: `ResourceVisibilityPicker` props (`grantedUserIds`, `onGrantedUserIdsChange`, `onVisibilityChange`, `showSpecificPeopleOption`, `visibility`, `workspaceMembers`); `useIsVisibilityEditionEnabled`; whatever hook `ProjectVisibilityDialog.tsx` uses to list workspace members (copy its import).
- Produces: `AiHubChatI` gains `isOwner`, `ownerName`, `ownerUserId`, `participation`, `visibility`.

- [ ] **Step 1: Operations and codegen**

`aiHubSharedChats.graphql` selects the same field set as `aiHubChats.graphql` plus the five new fields (add the five to `aiHubChats.graphql` too). The four sharing documents mirror the schema of Task 3 exactly (`setAiHubChatVisibility` selects `id visibility participation`; grant/revoke select `id`; grants returns the list). `npm run codegen`; two commits.

- [ ] **Step 2: Failing dialog test**

`AiHubChatShareDialog.test.tsx` (mock `@/shared/middleware/graphql` hooks with `vi.hoisted` refs; mock the members hook to return two members): opening with a `PRIVATE` chat shows the picker on "Private"; choosing "Shared with workspace" and toggling "People with access can send messages" then Save calls `setAiHubChatVisibility` with `{chatId, participation: 'PARTICIPATE', visibility: 'WORKSPACE', workspaceId}`; choosing "Specific people" and ticking one member calls `grantAiHubChatAccess` once with that user id on Save and `setAiHubChatVisibility` with `PRIVATE`; an existing grant unticked calls `revokeAiHubChatAccess`.

- [ ] **Step 3: Implement the dialog and actions**

`AiHubChatShareDialog` props `{chat: AiHubChatI; onClose: () => void; open: boolean; workspaceId: number}`. State: `visibility`, `participation` (boolean switch), `grantedUserIds` seeded from `useAiHubChatGrantsQuery`. Body: `<ResourceVisibilityPicker showSpecificPeopleOption visibility={visibility} grantedUserIds={grantedUserIds} onVisibilityChange={setVisibility} onGrantedUserIdsChange={setGrantedUserIds} workspaceMembers={members} />` then a `Switch` "People with access can send messages" (disabled when `visibility === 'PRIVATE' && grantedUserIds.length === 0`). Save: run `setAiHubChatVisibility`, then diff seeded vs current grant ids into grant/revoke mutations, then `invalidateQueries({queryKey: ['aiHubChats']})` and `['aiHubSharedChats']`, close.

`useAiHubChatActions`: add `shareTarget: AiHubChatI | null`, `requestShare(chat)`, `cancelShare()`; `AiHubChatActionDialogs` renders the share dialog when `shareTarget` is set. `AiHubPanel`'s `⋮` menu gets a `Share…` item (icon `Share2Icon`) rendered only when `currentChat.isOwner` (or the user is admin — reuse the admin check the panel already has for anything, otherwise `useIsAdmin`-style hook from `@/shared/hooks`) AND `useIsVisibilityEditionEnabled()`.

- [ ] **Step 4: Sidebar "Shared with me"**

`chats.api.ts`: add `listSharedChats(workspaceId, environment)` using `AiHubSharedChatsDocument` through the same `fetcher`; `useChats.ts`: `useAiHubSharedChatsQuery(workspaceId, environmentId)` with query key `['aiHubSharedChats', workspaceId, environmentId]`. In `AiHubChatsSidebar.tsx`, below the paginated own list render a heading row "Shared with me" and a second `getChatsPage`-paginated list of `sharedChats` filtered by the same `searchTerm`, reusing `ChatItem` with `onArchive`/`onDelete`/`onRename` no-ops and a `readOnly` prop that hides the row's `⋮` menu; each shared row shows `ownerName` in the secondary line. Include shared thread ids in the probe/status poll (Task 8).

- [ ] **Step 5: Run, check, commit**

```bash
cd client && npx vitest run src/ee/pages/automation/ai-hub/chats
cd client && npm run check
```

Commit `client - Share an AI Hub chat and list the chats shared with me`.

### Task 8: Client — status poll, presence strip, composer states, author labels

**Files:**
- Modify: `client/src/ee/pages/automation/ai-hub/runtime-providers/inFlightRunClient.ts:14-22, 274-312` (`probeInFlightStatus` → `probeThreadStatus`, new `sendPresence`); `AiHubRuntimeProvider.tsx` (heartbeat effect, attach-on-other's-run, `TURN_IN_FLIGHT` handling); `chats/stores/useAiHubChatsStore.ts` (`threadStatus` map); `AiHubChatsSidebar.tsx:442-493` (poll); `AiHubPanel.tsx` (presence strip); `composer/AiHubChatComposer.tsx` (disabled states); `messages/AiHubMessage.tsx:93-116` (author label); `client/src/graphql/ai/aihub/chat/aiHubChatMessages.graphql` (`authorUserId authorName`)
- Test: `runtime-providers/tests/inFlightRunClient.test.ts` (create if absent), `chats/stores/tests/useAiHubChatsStore.test.ts`, `composer/tests/AiHubChatComposer.test.tsx` (create if absent), `messages/tests/AiHubMessage.test.tsx`

**Interfaces:**
- Produces:

```ts
export interface PresenceEntryI {
    lastSeen: number;
    state: 'TYPING' | 'VIEWING';
    userId: number;
    userName: string;
}

export interface ThreadStatusI {
    inFlight: boolean;
    messageCount: number;
    presence: PresenceEntryI[];
    runningUserId: number | null;
    runningUserName: string | null;
    updatedAt: number;
}

export async function probeThreadStatus(threadIds: ReadonlyArray<string>): Promise<Record<string, ThreadStatusI>>;
export async function sendPresence(threadId: string, state: 'LEFT' | 'TYPING' | 'VIEWING'): Promise<void>;
```

  Store: `threadStatus: Record<string, ThreadStatusI>`, `setThreadStatus(statusByThreadId)`.

- [ ] **Step 1: Failing client tests**

- `inFlightRunClient.test.ts`: `probeThreadStatus` hits `/api/platform/internal/ai/chat/ai_hub/status?threadIds=…` in batches of 40 and merges; a non-OK response yields `{}`; `sendPresence` POSTs `{state}` with `credentials: 'include'` and the XSRF header and swallows errors.
- `useAiHubChatsStore.test.ts`: `setThreadStatus` merges by thread id and leaves `chatActivity` untouched.
- `AiHubChatComposer.test.tsx` (mock the stores and `@assistant-ui/react` the way `AiHubRuntimeProvider.test.tsx` does): with `activeChat.participation === 'VIEW'` and `isOwner false` the input is replaced by a notice `data-testid="view-only-notice"`; with `threadStatus[threadId].runningUserId` set to another user the input is disabled and the notice reads `Ana's turn is running`.
- `AiHubMessage.test.tsx`: when the thread has messages by two `authorUserId`s, user bubbles render `data-testid="message-author"`; with one author they do not.

- [ ] **Step 2: Poll and heartbeat**

`inFlightRunClient.ts`: replace `IN_FLIGHT_ENDPOINT` by `STATUS_ENDPOINT = '/api/platform/internal/ai/chat/ai_hub/status'` and `PRESENCE_ENDPOINT = (threadId) => …/presence`; `probeThreadStatus` keeps the 40-id batching; keep `probeInFlightStatus` as a thin wrapper returning `Object.fromEntries(Object.entries(status).map(([id, value]) => [id, value.inFlight]))` so `reconcileProbedChatActivity` and its tests keep working. `sendPresence` uses `fetch(PRESENCE_ENDPOINT(threadId), {body: JSON.stringify({state}), credentials: 'include', headers: {'Content-Type': 'application/json', 'X-XSRF-TOKEN': getCookie('XSRF-TOKEN') || ''}, method: 'POST'})` inside try/catch.

Sidebar poll (`AiHubChatsSidebar.tsx:442-493`): poll `probeThreadStatus` over own + shared thread ids every 20 s, call `setThreadStatus` and the existing `reconcileProbedChatActivity` with the derived in-flight booleans. Runtime provider: a `useEffect` on `[chatId]` that calls `sendPresence(chatId, 'VIEWING')` immediately and every 20 s, `sendPresence(chatId, 'LEFT')` on cleanup; a focused-chat poll every 5 s that calls `probeThreadStatus([chatId])` and, when `inFlight` flips to true while `useAiHubRunStateStore` says this client is not running it, invokes the shared `attachToContinuation(chatId)` (the extracted attach callback from the approval-gate plan, Task 8 there; if that plan has not landed, extract it here the same way) and, when `messageCount` grows past the loaded count while not in flight, refetches `aiHubChatMessages`. Typing: in the composer's `onChange`, debounce 1 s → `sendPresence(chatId, 'TYPING')`, and on 3 s idle → `'VIEWING'`.

`TURN_IN_FLIGHT`: in the provider's run-error path (`onRunErrorEvent`, line 544, and the `HttpAgent` request failure branch), when the response status is 409 and the body's `error` is `TURN_IN_FLIGHT`, do not append an error bubble; instead `setThreadStatus` with `runningUserId`/`runningUserName` from the body and leave the user's typed text in the composer.

- [ ] **Step 3: Presence strip, composer states, author labels**

`AiHubPanel.tsx`: next to the title render `AiHubPresenceStrip` (new component in `ai-hub/chats/`): avatars (initials from `userName`) for each `presence` entry, typing dots when `state === 'TYPING'`, and the line `{runningUserName}'s turn is running` while `inFlight && runningUserId !== currentUserId`. Composer (`AiHubChatComposer.tsx`): extend the existing channel-born branch (line 117 / 490-508) into a small state machine — `viewOnly` (participation `VIEW` and not owner/admin) → replace the input with the notice; `othersTurn` → keep the input, `disabled`, placeholder `${runningUserName}'s turn is running…`; otherwise unchanged. `AiHubMessage.tsx`: read `authorName` off the message's metadata (`useMessage((message) => message.metadata?.custom?.authorName)`) — the provider sets `metadata: {custom: {authorName, authorUserId}}` on each loaded user message from the query's new fields — and render `<div data-testid="message-author" className="mb-1 text-right text-xs text-muted-foreground">{authorName}</div>` above the bubble only when `useAiHubStore.getState().messages` contains more than one distinct `authorUserId`.

- [ ] **Step 4: Run, check, commit**

```bash
cd client && npx vitest run src/ee/pages/automation/ai-hub
cd client && npm run check
```

Commit `client - Show who is present in an AI Hub chat and whose turn is running`.

---

### Task 9: Client — feature flag and edition gating

**Files:**
- Modify: `AiHubPanel.tsx` (Share item), `AiHubChatsSidebar.tsx` (shared list, and `enabled: sharingEnabled` on its `useAiHubSharedChatsQuery` call so a flagged-off caller issues no request for the shared list at all), `AiHubChatComposer.tsx` (states), `AiHubPresenceStrip.tsx`, `AiHubMessage.tsx` (the user-message author label, which is derived purely from message history and reads no other gate — a chat that already received a second participant's turn before the flag was later disabled would otherwise keep showing author labels forever), `AiHubRuntimeProvider.tsx` (the presence heartbeat and focused-chat status poll effects actually live here, not in any of the four components above — a fix-round finding on the first pass at this task), `useChats.ts` (`enabled` parameter on `useAiHubSharedChatsQuery`, matching `useAiHubChatMessagesQuery`'s existing shape)

- [ ] **Step 1: Gate**

Every surface from Tasks 7-8 renders only when `useIsVisibilityEditionEnabled()` is true AND `useFeatureFlagsStore()('ff-ai-hub-shared-chats')` is true; put the pair in one hook `useAiHubSharingEnabled()` in `client/src/ee/pages/automation/ai-hub/chats/hooks/` and call it from every surface — not just the four originally-named components, but also the user-message author label (`AiHubMessage.tsx`) and the two Task 8 effects in `AiHubRuntimeProvider.tsx` (see Files above). The presence heartbeat and status poll also short-circuit on the hook (the endpoints exist regardless, but a flagged-off client should not create presence entries); extract the shared `chatId`-and-gate condition those two effects both evaluate into one named, exported, unit-tested pure function (alongside this file's existing `nextFocusedThreadMissCount`/`abandonFocusedChat`/`cleanupForChatChange` helpers) rather than repeating an inline `if (!chatId || !sharingEnabled)` in both — nothing in this codebase renders the real provider, so a silently-reverted inline condition would pass every existing test. Also pass `enabled: sharingEnabled` into `useAiHubSharedChatsQuery` so the "Shared with me" query itself does not fire when the gate is off, not merely its render.

- [ ] **Step 2: Test, check, commit**

Add one test per gated component asserting nothing sharing-related renders when the hook returns false (mock the hook module with `vi.mock`). `npm run check`. Commit `client - Gate AI Hub chat sharing behind the edition and the ff-ai-hub-shared-chats flag`.

---

### Task 10: Docs

**Files:**
- Modify: `.agents/ai-hub.md` (new `### Shared chats and presence (EE)` section after "AI Hub Chats"), `.agents/resource-visibility.md` (add `AiHubChat` to the "wired so far" list with its `PRIVATE` default and the reason), `CLAUDE.md` (one line in the resource-visibility cross-cutting rule noting the chat exception)

- [ ] **Step 1: Write the sections**

Record: the `PRIVATE` default and why it departs from the platform rule; `canView/canParticipate/canManage` and which service methods use which; `user_id` still means owner; channel-born rows are `WORKSPACE`/`VIEW` and composer rows `PRIVATE`; participants get chat-scoped tools only; attribution through `ai_hub_chat_turn` by USER-event order and why (session store drops message metadata) — including that truncation must keep the two in step; the 500-row grant candidate window; the turn lock and `TURN_IN_FLIGHT`; presence read-modify-write race and 45 s TTL; the deprecated `in-flight` endpoint to delete next release; the multi-instance attach limitation and its UI copy.

- [ ] **Step 2: Commit**

```bash
git add .agents/ai-hub.md .agents/resource-visibility.md CLAUDE.md
git commit -m "Document AI Hub shared chats and presence" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## Self-review against the spec

| Spec section | Task |
|---|---|
| Model: `visibility` + `participation`, `PRIVATE` default via policy, channel-born `WORKSPACE` | 1, 4 |
| Authorization: provider registered, access policy, operation table, enumeration-safe failures, artifacts fail closed | 2 (artifact checks are unchanged — they already run the artifact's own visibility) |
| Whose tools / identity: participant turn runs as sender; chat-scoped tools only, no user-global connectors | **Gap closed here:** Task 5 must also pass the participant's `userId` into `AiHubToolInvocationContext` (it does — the controller injects the sender's id) and `AiHubChatBindingToolCallbackResolver.resolve` must skip `listUserTools(userId, workspaceId)` when the sender is not the owner. Add to Task 5 step 4: after the turn lock, set state key `AiHubStateKeys.THREAD_OWNER_ID` (new constant) = `chat.getUserId()`, and in `AiHubChatBindingToolCallbackResolver.resolve` read it from the invocation context (add `ownerUserId` to `AiHubToolInvocationContext` and `buildInvocationContext`) and union user-global tools only when `ownerUserId == userId`. Test in `AiHubChatBindingToolCallbackResolverTest`. |
| Attribution | 5 (turn table; deviation 1) |
| One turn at a time, `TURN_IN_FLIGHT` | 5, 8 |
| Presence registry, heartbeat, status poll replacing `in-flight` | 6, 8 |
| Listing: `aiHubSharedChats`, index, chat fields | 4, 1, 3, 7 |
| Sharing surface: four operations, membership check, grant cleanup on delete | 3 |
| Client: share picker, presence strip, composer states, author labels, shared sidebar section, edition gating | 7, 8, 9 |
| With the approval gate | resolve requires `canManage` — the approval-gate plan's facade already checks owner-or-admin, which equals `canManage`; when both plans land, replace its `canResolve` with `accessPolicy.canManage` (one-line follow-up noted in Task 10) |
| Audit + metrics | 3 (three events); metrics `bytechef_ai_hub_chat_share{visibility}` in the sharing facade and `bytechef_ai_hub_chat_turn_conflict` in the controller — add both as `Counter.builder` calls following `WorkflowChatMetrics`; the presence gauge is dropped (a cache-backed map has no cheap global count) |
| Distributed EE | no code; documented in Task 10 |
| Error handling table | 5 (409), 6 (heartbeat swallowed), 3 (non-member → not-found, grants deleted with chat), 8 (narrowed-while-viewing → next poll omits the id → client closes the tab: add to Task 8 step 2 that a thread missing from the poll result while open triggers `closeChat` with the toast "This chat is no longer shared with you") |
| Phases 1-3 | Tasks 1-4 = phase 1, Task 5 = phase 2, Tasks 6 and 8 = phase 3; phase 4 (push) deliberately absent |
| Rollout flag `ff-ai-hub-shared-chats`, no backfill | 9, 1 |

Type consistency: `AiHubChatAccessPolicy` predicates are named identically in Tasks 2, 3, 5, 6; `AiHubChatVisibilityPolicy.RESOURCE_TYPE` is the single `"AiHubChat"` literal used by the provider, the grants, the resolver and the regression test; `ThreadStatusI` fields match the server `ThreadStatus` record; `AiHubChatMessage`'s fifth component `authorUserId` matches the GraphQL field and the client selection.

