# AI Hub Tool Approval Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A flagged AI Hub tool never executes from the model's call; it executes only from a server-verified approval mutation, with the exact arguments the model chose.

**Architecture:** A new `AiHubApprovalGateToolCallback` sits inside the existing `AiHubToolCallbackWrappers.wrap` chain (rehydrate → gate → non-empty → delegate). On a gated call it persists an `ai_hub_tool_approval` row and returns a `tool-approval-request` envelope instead of executing. A GraphQL mutation resolves the row, executes the stored callback with the stored arguments, and starts a continuation turn on the same thread through `AiHubChatStreamer`. What is gated is decided per turn by `AiHubToolApprovalPolicy` from a built-in default list, workspace rules in `ai_hub_tool_approval_rule`, and a per-tool `requires_approval` flag on `ai_hub_chat_tool`.

**Tech Stack:** Java 25 / Spring Boot 4, Spring Data JDBC, Liquibase, Spring GraphQL, Spring AI `ToolCallback` / `ToolContext`, AG-UI (`AgUiParameters`, `State`, `UserMessage`), React 19 + TypeScript + assistant-ui data parts + graphql-codegen + TanStack Query.

**Spec:** [docs/superpowers/specs/2026-09-02-ai-hub-tool-approval-gate-design.md](../specs/2026-09-02-ai-hub-tool-approval-gate-design.md)

## Deviations from the spec (read before starting)

The spec was written before the code was read closely. Five statements are adjusted here; each is a deliberate call, not an oversight.

1. **One pending approval per CHAT, not per turn.** The wrapper only sees a `ToolContext`, which carries the thread id but not the AG-UI run id. Since a new user turn supersedes a pending row anyway, "one pending per chat" and "one pending per turn" are observationally the same rule. The second gated call in a turn still receives the `deferred` envelope.
2. **The approved tool executes under the RESOLVER's security context, not the requester's.** In this plan the resolver is the chat owner or a workspace admin, and the requester is always the owner (participants arrive with the shared-sessions plan). Impersonating the requester would need `SecurityUtils.runAs(login, authorities, …)` with the requester's authorities rebuilt from `UserService.getUser(id)`; the admin case is the only one where the two differ, and an admin resolving as themselves is at least as privileged. Both ids are recorded on the row.
3. **No GraphQL IntTest.** `ai-hub-graphql` has no `GraphQlTest` infrastructure at all — its three tests construct controllers by hand (`AiHubChatGraphQlControllerTest`). The new controllers get the same hand-built unit tests; the spec's `AiHubToolApprovalGraphQlControllerIntTest` is dropped.
4. **The continuation turn carries a small state, not the original turn's state.** The wrapper cannot see the AG-UI `State` (tabs, referenced resources, active file). The row records `mode`, `environment`, `llm_provider` and `llm_model` from the tool context, and the continuation state is rebuilt from those. Open tabs and referenced resources are not re-sent; the model already has the conversation in session memory.
5. **The synthetic continuation message is a plain user message with a reserved prefix, not one with metadata.** The session store drops `Message.getMetadata()` (verified against `spring-ai-session-jdbc` 0.7.0: `insertEvent` persists only text and tool calls). The client recognises a user message whose text starts with `[tool-approval #` and renders it as a status line; the system prompt tells the model the same.

## Global Constraints

- **Every new file under `server/ee/`** uses the ByteChef Enterprise license header (copy verbatim from `server/ee/libs/ai/ai-hub/ai-hub-api/src/main/java/com/bytechef/ee/ai/hub/chat/AiHubChatTool.java`) and carries `@version ee` plus `@author Ivica Cardic` in the type Javadoc.
- **Enum ordinals are persisted as INT** — `AiHubToolApprovalStatus`: `PENDING=0, APPROVED=1, REJECTED=2, EXPIRED=3, SUPERSEDED=4, FAILED=5`; `AiHubToolApprovalRule.ToolKind`: `CATALOG=0, COMPONENT=1`; `AiHubToolApprovalRule.Mode`: `REQUIRE=0, EXEMPT=1`. Append-only, never reorder.
- **Java blank-line rules:** one blank line before `if`/`for`/`while`/`try`/`switch`/`return`-after-assignment (except immediately after an opening `{`); one blank line between a variable modification and the statement that uses it; no trailing blank line before a closing `}` of a class.
- **No `_`-prefixed private methods. No short/cryptic variable names**, including lambda parameters (`approval`, not `a`).
- **No `TODO:` comments** — Checkstyle's `TodoComment` rule fails the build. No inline rationale comments in committed code; rationale goes in the commit message.
- **Test naming:** unit tests end in `Test` (never `IntTest` unless they boot a Spring context); method names are camelCase without underscores, including private helpers.
- **Client:** object keys in ascending alphabetical order (`sort-keys`, not auto-fixable); named imports sorted alphabetically inside `{}`; interface names end in `I` or `Props`; Lucide icons imported with the `Icon` suffix; `useRef` variables end in `Ref`; hooks ordered `useState → useRef → store hooks → other hooks → derived → useEffect → return`.
- **Client tests:** module-scope refs used inside a `vi.mock` factory must be declared with `vi.hoisted(...)`.
- **Client GraphQL:** operations live in `client/src/graphql/ai/aihub/<domain>/*.graphql`; regenerate with `cd client && npm run codegen` (writes `src/shared/middleware/graphql.ts` and `graphql-types.ts`); commit the operations and the generated file separately.
- **Before every server commit:** `./gradlew spotlessApply`. Never judge a Gradle run through a pipe — redirect to a file, check `$?` on its own line, then grep the file for `^> Task .* FAILED`.
- **Before every client commit:** run from `client/`, `npm run check`, with the tool timeout set to 600000 ms.
- **Commit messages:** server `5673 <description>`-style is NOT used here; this work has no ticket yet. Use `Add …` / `Gate …` imperative subjects without a number, client commits prefixed `client - `.

---

## File Structure

**New, `server/ee/libs/ai/ai-hub/ai-hub-api`** (package `com.bytechef.ee.ai.hub.approval`):

| File | Responsibility |
|------|----------------|
| `AiHubToolApproval.java` | Spring Data JDBC aggregate for `ai_hub_tool_approval` + nested `Status` enum |
| `AiHubToolApprovalRule.java` | Aggregate for `ai_hub_tool_approval_rule` + nested `ToolKind` / `Mode` enums |
| `AiHubToolApprovalService.java` | Persistence contract: create pending, find pending by chat, list by chat, decide, supersede, expire |
| `AiHubToolApprovalRuleService.java` | Rules contract: list by workspace, save, delete |
| `AiHubToolApprovalPolicy.java` | `Set<String> gatedToolNames(long workspaceId, long userId, @Nullable Long chatId)` |
| `AiHubToolApprovalFacade.java` | `list(workspaceId, chatId)` + `resolve(workspaceId, approvalId, approved, comment)` → `Resolution` |
| `AiHubToolApprovalRuleFacade.java` | Admin-gated rules CRUD |

**New, `server/ee/libs/ai/ai-hub/ai-hub-service`** (package `com.bytechef.ee.ai.hub.approval` unless noted):

| File | Responsibility |
|------|----------------|
| `repository/AiHubToolApprovalRepository.java`, `repository/AiHubToolApprovalRuleRepository.java` | `CrudRepository`s + derived finders |
| `AiHubToolApprovalServiceImpl.java`, `AiHubToolApprovalRuleServiceImpl.java` | Persistence |
| `AiHubToolApprovalDefaults.java` | The built-in gated list + prefix rule |
| `AiHubToolApprovalPolicyImpl.java` | Merges defaults, workspace rules and per-tool flags |
| `AiHubApprovalGateToolCallback.java` | The wrapper (`DelegatingToolCallback`) |
| `AiHubApprovalGate.java` | Spring component that decides per call and wraps |
| `AiHubToolApprovalFacadeImpl.java` | Resolve: authorize, execute, continue |
| `AiHubToolApprovalRuleFacadeImpl.java` | `@PreAuthorize`-guarded rules CRUD |
| `agent/AiHubRunState.java` | Static helper that injects the authenticated state keys (extracted from `AiHubApiController`) |
| `metric/AiHubToolApprovalMetrics.java` | `bytechef_ai_hub_tool_approval{outcome}` counter |
| `src/main/resources/config/liquibase/changelog/automation/aihub/20260902000001_ai_hub_tool_approval_rule_init.xml` | Rules table |
| `.../aihub/20260902000002_ai_hub_tool_approval_init.xml` | Approvals table |
| `.../aihub_execution/20260902000003_ai_hub_chat_tool_add_requires_approval.xml` | Per-tool flag |

**Modified server files:**

| File | Change |
|------|--------|
| `ai-hub-service/.../config/AiHubJdbcRepositoryConfiguration.java` | Add `com.bytechef.ee.ai.hub.approval.repository` to `basePackages` |
| `ai-hub-service/.../agent/AiHubToolCallbackWrappers.java` | Third parameter `@Nullable AiHubApprovalGate`; gate inserted between rehydrator and non-empty guard |
| `ai-hub-service/.../agent/AiHubSpringAIAgent.java` | Builder field `approvalGate`; `wrapToolCallback` passes it; `toolContext` adds the mode key |
| `ai-hub-service/.../config/AiHubConfiguration.java` | Pass the gate bean into both agent builders |
| `ai-hub-service/.../toolsearch/ToolSearchAdvisorConfiguration.java` | Pass the gate into the catalog wrap |
| `ai-hub-service/.../audit/AiHubAuditEvent.java` | Four new events |
| `ai-hub-service/src/main/resources/prompt_ai_hub_ask.txt`, `prompt_ai_hub_build.txt` | Approval paragraph |
| `ai-hub-api/.../chat/AiHubChatTool.java`, `AiHubChatToolBinding.java` | `requiresApproval` |
| `ai-hub-api/.../chat/AiHubChatToolFacade.java` + impl | `setToolRequiresApproval(chatToolId, boolean)` |
| `ai-hub-rest/.../AiHubApiController.java` | `injectAuthenticatedContext` delegates to `AiHubRunState` |
| `ai-hub-graphql/.../AiHubToolApprovalGraphQlController.java` (new), `ai-hub-tool-approval.graphqls` (new), `ai-hub-chat-tool.graphqls` | Operations |

**Client files:**

| File | Change |
|------|--------|
| `client/src/shared/components/ai-chat/messages/ToolApprovalRequestMessage.tsx` (new) | The card |
| `.../ai-chat/messages/aiChatDataComponents.tsx`, `toToolResultDataPart.ts` | Register the `tool-approval-request` kind |
| `.../ai-chat/approvalResolutionContext.ts` | `resolveToolApproval` |
| `client/src/ee/pages/automation/ai-hub/runtime-providers/AiHubRuntimeProvider.tsx` | Implement `resolveToolApproval`, status-line rendering for the synthetic message, paused state |
| `client/src/ee/pages/automation/ai-hub/messages/AiHubMessage.tsx` | Render `[tool-approval #…` user messages as a status line |
| `client/src/ee/pages/automation/ai-hub/tools/ChatToolChips.tsx` | Shield toggle on chips |
| `client/src/ee/pages/automation/ai-hub/context/AiHubConnectors.tsx` | "Require approval" switch per tool row |
| `client/src/ee/pages/automation/ai-hub/settings/ToolApprovals.tsx` (new), `client/src/routes.tsx` | Workspace settings page |
| `client/src/graphql/ai/aihub/tool-approval/*.graphql` (new), `chat-tool/setAiHubChatToolRequiresApproval.graphql` (new) | Operations |

---

### Task 1: Domain, migrations, repositories

**Files:**
- Create: `server/ee/libs/ai/ai-hub/ai-hub-api/src/main/java/com/bytechef/ee/ai/hub/approval/AiHubToolApproval.java`
- Create: `server/ee/libs/ai/ai-hub/ai-hub-api/src/main/java/com/bytechef/ee/ai/hub/approval/AiHubToolApprovalRule.java`
- Create: `server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/approval/repository/AiHubToolApprovalRepository.java`
- Create: `server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/approval/repository/AiHubToolApprovalRuleRepository.java`
- Create: the two `aihub/` changelogs listed above
- Modify: `server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/config/AiHubJdbcRepositoryConfiguration.java:44-50`
- Test: `server/ee/libs/ai/ai-hub/ai-hub-api/src/test/java/com/bytechef/ee/ai/hub/approval/AiHubToolApprovalEnumOrdinalTest.java`

**Interfaces:**
- Produces: `AiHubToolApproval` with getters/setters for every column below and `Status` enum; `AiHubToolApprovalRule` with `ToolKind`, `Mode`; `AiHubToolApprovalRepository` with `List<AiHubToolApproval> findAllByChatIdOrderByCreatedDateDesc(long chatId)`, `Optional<AiHubToolApproval> findFirstByChatIdAndStatus(long chatId, int status)`, `List<AiHubToolApproval> findAllByChatIdAndStatus(long chatId, int status)`; `AiHubToolApprovalRuleRepository` with `List<AiHubToolApprovalRule> findAllByWorkspaceId(long workspaceId)`.

- [ ] **Step 1: Write the failing ordinal test**

`ai-hub-api/src/test/java/com/bytechef/ee/ai/hub/approval/AiHubToolApprovalEnumOrdinalTest.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.approval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubToolApprovalEnumOrdinalTest {

    @Test
    void testStatusOrdinalsAreStable() {
        assertThat(AiHubToolApproval.Status.PENDING.ordinal()).isEqualTo(0);
        assertThat(AiHubToolApproval.Status.APPROVED.ordinal()).isEqualTo(1);
        assertThat(AiHubToolApproval.Status.REJECTED.ordinal()).isEqualTo(2);
        assertThat(AiHubToolApproval.Status.EXPIRED.ordinal()).isEqualTo(3);
        assertThat(AiHubToolApproval.Status.SUPERSEDED.ordinal()).isEqualTo(4);
        assertThat(AiHubToolApproval.Status.FAILED.ordinal()).isEqualTo(5);
        assertThat(AiHubToolApproval.Status.values()).hasSize(6);
    }

    @Test
    void testRuleEnumOrdinalsAreStable() {
        assertThat(AiHubToolApprovalRule.ToolKind.CATALOG.ordinal()).isEqualTo(0);
        assertThat(AiHubToolApprovalRule.ToolKind.COMPONENT.ordinal()).isEqualTo(1);
        assertThat(AiHubToolApprovalRule.Mode.REQUIRE.ordinal()).isEqualTo(0);
        assertThat(AiHubToolApprovalRule.Mode.EXEMPT.ordinal()).isEqualTo(1);
    }

    @Test
    void testStatusRoundTripsThroughTheOrdinalColumn() {
        AiHubToolApproval approval = new AiHubToolApproval();

        approval.setStatus(AiHubToolApproval.Status.SUPERSEDED);

        assertThat(approval.getStatus()).isEqualTo(AiHubToolApproval.Status.SUPERSEDED);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
./gradlew :server:ee:libs:ai:ai-hub:ai-hub-api:test --tests '*AiHubToolApprovalEnumOrdinalTest' > /tmp/t1.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED|cannot find symbol" /tmp/t1.log | head
```

Expected: `exit=1`, `cannot find symbol: class AiHubToolApproval`.

- [ ] **Step 3: Write `AiHubToolApproval`**

`ai-hub-api/src/main/java/com/bytechef/ee/ai/hub/approval/AiHubToolApproval.java` (mirror `AiHubChatTool` for the annotation style — `@Table`, `@Id`, `@Column`, `@CreatedBy`/`@CreatedDate`/`@LastModifiedBy`/`@LastModifiedDate`/`@Version` from `org.springframework.data.annotation`):

```java
@Table("ai_hub_tool_approval")
public final class AiHubToolApproval {

    public enum Status {
        PENDING, APPROVED, REJECTED, EXPIRED, SUPERSEDED, FAILED
    }

    public enum ToolKind {
        CATALOG, COMPONENT
    }

    @Id
    private Long id;

    @Column("chat_id")
    private long chatId;

    @Column("thread_id")
    private String threadId;

    @Column("requested_by_user_id")
    private long requestedByUserId;

    @Column("tool_kind")
    private int toolKind;

    @Column("tool_name")
    private String toolName;

    @Column("component_name")
    private @Nullable String componentName;

    @Column("component_version")
    private @Nullable Integer componentVersion;

    @Column("connection_id")
    private @Nullable Long connectionId;

    @Column("arguments")
    private String arguments;

    @Column
    private int status;

    @Column
    private String mode;

    @Column
    private int environment;

    @Column("llm_provider")
    private @Nullable String llmProvider;

    @Column("llm_model")
    private @Nullable String llmModel;

    @Column("decided_by_user_id")
    private @Nullable Long decidedByUserId;

    @Column("decided_at")
    private @Nullable Instant decidedAt;

    @Column
    private @Nullable String comment;

    @Column("expires_at")
    private Instant expiresAt;

    @Column("execution_error")
    private @Nullable String executionError;

    @Column("created_by")
    @CreatedBy
    private String createdBy;

    @Column("created_date")
    @CreatedDate
    private Instant createdDate;

    @Version
    private int version;

    public AiHubToolApproval() {
    }

    public Status getStatus() {
        return Status.values()[status];
    }

    public void setStatus(Status status) {
        this.status = status.ordinal();
    }

    public ToolKind getToolKind() {
        return ToolKind.values()[toolKind];
    }

    public void setToolKind(ToolKind toolKind) {
        this.toolKind = toolKind.ordinal();
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    // plain getters and setters for every remaining field, one per field, no logic
}
```

Write every remaining getter/setter explicitly (Spotless does not generate them). `ToolKind` is declared here and reused by the rule via a static import so the ordinal contract lives in one place — do NOT declare a second copy on `AiHubToolApprovalRule`; that class references `AiHubToolApproval.ToolKind`.

- [ ] **Step 4: Write `AiHubToolApprovalRule`**

Same style, `@Table("ai_hub_tool_approval_rule")`, fields `id`, `workspaceId` (`long`, `@Column("workspace_id")`), `toolKind` (int ordinal of `AiHubToolApproval.ToolKind`), `componentName` (`@Nullable String`), `toolName` (`String`), `mode` (int ordinal), `createdBy`, `createdDate`, `lastModifiedBy`, `lastModifiedDate`, `version`. Nested `public enum Mode { REQUIRE, EXEMPT }`. Getters `getToolKind()`/`getMode()` convert through `values()[…]`; setters take the enum.

The ordinal test in Step 1 references `AiHubToolApprovalRule.ToolKind` — change those two assertions to `AiHubToolApproval.ToolKind` so the test matches this single-declaration decision.

- [ ] **Step 5: Run the test to verify it passes**

Same command as Step 2. Expected: `exit=0`, no FAILED lines.

- [ ] **Step 6: Write the two changelogs**

`ai-hub-service/src/main/resources/config/liquibase/changelog/automation/aihub/20260902000001_ai_hub_tool_approval_rule_init.xml` (copy the XML prologue verbatim from `aihub_execution/20260702000001_ai_hub_mcp_server_init.xml`):

```xml
    <changeSet id="20260902000001" author="Ivica Cardic">
        <createTable tableName="ai_hub_tool_approval_rule">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="workspace_id" type="BIGINT">
                <constraints nullable="false"/>
            </column>
            <column name="tool_kind" type="INT">
                <constraints nullable="false"/>
            </column>
            <column name="component_name" type="VARCHAR(255)"/>
            <column name="tool_name" type="VARCHAR(255)">
                <constraints nullable="false"/>
            </column>
            <column name="mode" type="INT">
                <constraints nullable="false"/>
            </column>
            <column name="created_by" type="VARCHAR(50)"/>
            <column name="created_date" type="TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="last_modified_by" type="VARCHAR(50)"/>
            <column name="last_modified_date" type="TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="version" type="INTEGER">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <sql>
            CREATE UNIQUE INDEX uk_ai_hub_tool_approval_rule ON ai_hub_tool_approval_rule
                (workspace_id, tool_kind, COALESCE(component_name, ''), tool_name)
        </sql>

        <createIndex tableName="ai_hub_tool_approval_rule" indexName="idx_ai_hub_tool_approval_rule_workspace">
            <column name="workspace_id"/>
        </createIndex>
    </changeSet>
```

The `COALESCE` is raw SQL because Liquibase's `addUniqueConstraint` cannot express an expression index, and a NULL `component_name` would otherwise never collide.

`20260902000002_ai_hub_tool_approval_init.xml`: `createTable ai_hub_tool_approval` with every column of Step 3 — `id` BIGINT PK autoIncrement; `chat_id` BIGINT NOT NULL; `thread_id` VARCHAR(255) NOT NULL; `requested_by_user_id` BIGINT NOT NULL; `tool_kind` INT NOT NULL; `tool_name` VARCHAR(255) NOT NULL; `component_name` VARCHAR(255); `component_version` INT; `connection_id` BIGINT; `arguments` TEXT NOT NULL; `status` INT NOT NULL; `mode` VARCHAR(16) NOT NULL; `environment` INT NOT NULL; `llm_provider` VARCHAR(255); `llm_model` VARCHAR(255); `decided_by_user_id` BIGINT; `decided_at` TIMESTAMP; `comment` TEXT; `expires_at` TIMESTAMP NOT NULL; `execution_error` TEXT; `created_by` VARCHAR(50); `created_date` TIMESTAMP NOT NULL; `version` INTEGER NOT NULL. Then `createIndex idx_ai_hub_tool_approval_chat_status` on `(chat_id, status)`. No foreign key to `ai_hub_chat` — the chat's `delete` path deletes approvals explicitly (Task 6), matching how `ai_hub_chat_tool` rows are cleaned.

Both files land in `aihub/` (configuration tier); they are picked up by the existing `includeAll` in `server/libs/config/liquibase-config/src/main/resources/config/liquibase/master.xml:161` with no edit.

- [ ] **Step 7: Write the repositories and register the package**

`ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/approval/repository/AiHubToolApprovalRepository.java`:

```java
@Repository
public interface AiHubToolApprovalRepository extends CrudRepository<AiHubToolApproval, Long> {

    List<AiHubToolApproval> findAllByChatIdOrderByCreatedDateDesc(long chatId);

    Optional<AiHubToolApproval> findFirstByChatIdAndStatus(long chatId, int status);

    List<AiHubToolApproval> findAllByChatIdAndStatus(long chatId, int status);

    void deleteAllByChatId(long chatId);
}
```

`AiHubToolApprovalRuleRepository`: `extends CrudRepository<AiHubToolApprovalRule, Long>` with `List<AiHubToolApprovalRule> findAllByWorkspaceId(long workspaceId)`.

In `AiHubJdbcRepositoryConfiguration.java` add `"com.bytechef.ee.ai.hub.approval.repository",` to the `basePackages` array (keep it alphabetical: after `"com.bytechef.ee.ai.hub.mcpserver.repository"` is wrong — `approval` sorts first, so put it first).

- [ ] **Step 8: Prove the schema builds**

The repository IntTest `AiHubChatRepositoryIntTest` boots Liquibase from scratch through Testcontainers, so it validates both changelogs:

```bash
./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:testIntegration --tests '*AiHubChatRepositoryIntTest' > /tmp/t1i.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED|Migration failed|ValidationFailed" /tmp/t1i.log | head
```

Expected: `exit=0`. (Docker must be running; see the Testcontainers memory about the OrbStack socket if it cannot connect.)

- [ ] **Step 9: Commit**

```bash
./gradlew spotlessApply > /tmp/sa.log 2>&1; echo "exit=$?"
git add server/ee/libs/ai/ai-hub/ai-hub-api/src/main/java/com/bytechef/ee/ai/hub/approval server/ee/libs/ai/ai-hub/ai-hub-api/src/test/java/com/bytechef/ee/ai/hub/approval server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/approval/repository server/ee/libs/ai/ai-hub/ai-hub-service/src/main/resources/config/liquibase/changelog/automation/aihub/20260902000001_ai_hub_tool_approval_rule_init.xml server/ee/libs/ai/ai-hub/ai-hub-service/src/main/resources/config/liquibase/changelog/automation/aihub/20260902000002_ai_hub_tool_approval_init.xml server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/config/AiHubJdbcRepositoryConfiguration.java
git commit -m "Add the AI Hub tool approval and approval rule tables" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

### Task 2: Policy — defaults, workspace rules, per-tool flag

**Files:**
- Create: `ai-hub-api/.../approval/AiHubToolApprovalPolicy.java`, `AiHubToolApprovalRuleService.java`
- Create: `ai-hub-service/.../approval/AiHubToolApprovalDefaults.java`, `AiHubToolApprovalPolicyImpl.java`, `AiHubToolApprovalRuleServiceImpl.java`
- Create: `ai-hub-service/.../approval/AiHubToolApprovalPolicyTest.java` (under `src/test/java`)
- Modify: `ai-hub-api/.../chat/AiHubChatTool.java` (add `requiresApproval`), `.../aihub_execution/20260902000003_ai_hub_chat_tool_add_requires_approval.xml` (create)

**Interfaces:**
- Consumes: `AiHubChatComponentRepository.findAllByChatId(long)`, `findAllByUserIdAndWorkspaceId(long, long)`; `AiHubChatToolRepository.findAllByChatComponentId(long)`; `com.bytechef.ee.ai.hub.toolsearch.ToolNameNormalizer.toToolName(String componentName, String clusterElementName)` (the same normaliser `AiHubChatBindingToolCallbackResolver.bindingToCallback` uses, so policy names match runtime tool names).
- Produces:
  - `AiHubToolApprovalPolicy { Set<String> gatedToolNames(long workspaceId, long userId, @Nullable Long chatId); }`
  - `AiHubToolApprovalRuleService { List<AiHubToolApprovalRule> getRules(long workspaceId); AiHubToolApprovalRule save(AiHubToolApprovalRule rule); void delete(long workspaceId, long ruleId); }`
  - `AiHubToolApprovalDefaults.isGatedByDefault(String toolName)` and `AiHubToolApprovalDefaults.DEFAULT_TOOL_NAMES`.
  - `AiHubChatTool.isRequiresApproval()` / `setRequiresApproval(boolean)`.

- [ ] **Step 1: Add the per-tool flag column and field**

`aihub_execution/20260902000003_ai_hub_chat_tool_add_requires_approval.xml` (copy the `addColumn` shape from `aihub/20260503000001_ai_hub_chat_add_auto_titled.xml`, without the `UPDATE` block):

```xml
    <changeSet id="20260902000003" author="Ivica Cardic">
        <addColumn tableName="ai_hub_chat_tool">
            <column name="requires_approval" type="BOOLEAN" defaultValueBoolean="false">
                <constraints nullable="false"/>
            </column>
        </addColumn>

        <rollback>
            <dropColumn columnName="requires_approval" tableName="ai_hub_chat_tool"/>
        </rollback>
    </changeSet>
```

In `AiHubChatTool.java`, after the `enabled` field (line 58-59):

```java
    @Column("requires_approval")
    private boolean requiresApproval;
```

plus `public boolean isRequiresApproval()` and `public void setRequiresApproval(boolean requiresApproval)`.

- [ ] **Step 2: Write the failing policy test**

`ai-hub-service/src/test/java/com/bytechef/ee/ai/hub/approval/AiHubToolApprovalPolicyTest.java`:

```java
class AiHubToolApprovalPolicyTest {

    private static final long WORKSPACE_ID = 7L;
    private static final long USER_ID = 3L;
    private static final long CHAT_ID = 11L;

    private final AiHubToolApprovalRuleRepository ruleRepository = mock(AiHubToolApprovalRuleRepository.class);
    private final AiHubChatComponentRepository componentRepository = mock(AiHubChatComponentRepository.class);
    private final AiHubChatToolRepository toolRepository = mock(AiHubChatToolRepository.class);

    private final AiHubToolApprovalPolicy policy = new AiHubToolApprovalPolicyImpl(
        ruleRepository, componentRepository, toolRepository);

    @Test
    void testBuiltInDefaultsAreGatedWithNoRules() {
        Set<String> gated = policy.gatedToolNames(WORKSPACE_ID, USER_ID, CHAT_ID);

        assertThat(gated).contains("deleteProject", "dropDataTable", "deleteKnowledgeBase");
        assertThat(gated).doesNotContain("listProjects");
    }

    @Test
    void testPrefixMatchGatesAnUnlistedDestructiveName() {
        assertThat(AiHubToolApprovalDefaults.isGatedByDefault("rollbackProjectDeployment")).isTrue();
        assertThat(AiHubToolApprovalDefaults.isGatedByDefault("promoteWorkflow")).isTrue();
        assertThat(AiHubToolApprovalDefaults.isGatedByDefault("getProject")).isFalse();
    }

    @Test
    void testWorkspaceExemptRemovesADefault() {
        when(ruleRepository.findAllByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(
            rule(AiHubToolApproval.ToolKind.CATALOG, null, "deleteProject", AiHubToolApprovalRule.Mode.EXEMPT)));

        assertThat(policy.gatedToolNames(WORKSPACE_ID, USER_ID, CHAT_ID)).doesNotContain("deleteProject");
    }

    @Test
    void testWorkspaceRequireAddsAComponentOperation() {
        when(ruleRepository.findAllByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(
            rule(AiHubToolApproval.ToolKind.COMPONENT, "gmail", "sendEmail", AiHubToolApprovalRule.Mode.REQUIRE)));

        assertThat(policy.gatedToolNames(WORKSPACE_ID, USER_ID, CHAT_ID))
            .contains(ToolNameNormalizer.toToolName("gmail", "sendEmail"));
    }

    @Test
    void testWorkspaceWildcardGatesEveryOperationOfAComponent() {
        when(ruleRepository.findAllByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(
            rule(AiHubToolApproval.ToolKind.COMPONENT, "gmail", "*", AiHubToolApprovalRule.Mode.REQUIRE)));
        AiHubChatComponent component = component(21L, "gmail");
        when(componentRepository.findAllByChatId(CHAT_ID)).thenReturn(List.of(component));
        when(toolRepository.findAllByChatComponentId(21L)).thenReturn(List.of(tool(21L, "sendEmail", false)));

        assertThat(policy.gatedToolNames(WORKSPACE_ID, USER_ID, CHAT_ID))
            .contains(ToolNameNormalizer.toToolName("gmail", "sendEmail"));
    }

    @Test
    void testChatOwnerFlagAddsButCannotRemove() {
        when(ruleRepository.findAllByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(
            rule(AiHubToolApproval.ToolKind.COMPONENT, "slack", "postMessage", AiHubToolApprovalRule.Mode.REQUIRE)));
        AiHubChatComponent gmail = component(21L, "gmail");
        AiHubChatComponent slack = component(22L, "slack");
        when(componentRepository.findAllByChatId(CHAT_ID)).thenReturn(List.of(gmail));
        when(componentRepository.findAllByUserIdAndWorkspaceId(USER_ID, WORKSPACE_ID)).thenReturn(List.of(slack));
        when(toolRepository.findAllByChatComponentId(21L)).thenReturn(List.of(tool(21L, "sendEmail", true)));
        when(toolRepository.findAllByChatComponentId(22L)).thenReturn(List.of(tool(22L, "postMessage", false)));

        Set<String> gated = policy.gatedToolNames(WORKSPACE_ID, USER_ID, CHAT_ID);

        assertThat(gated).contains(ToolNameNormalizer.toToolName("gmail", "sendEmail"));
        assertThat(gated).contains(ToolNameNormalizer.toToolName("slack", "postMessage"));
    }

    @Test
    void testRepositoryFailureFallsBackToDefaultsOnly() {
        when(ruleRepository.findAllByWorkspaceId(WORKSPACE_ID)).thenThrow(new IllegalStateException("db down"));

        Set<String> gated = policy.gatedToolNames(WORKSPACE_ID, USER_ID, CHAT_ID);

        assertThat(gated).containsAll(AiHubToolApprovalDefaults.DEFAULT_TOOL_NAMES);
    }

    private static AiHubToolApprovalRule rule(
        AiHubToolApproval.ToolKind toolKind, String componentName, String toolName, AiHubToolApprovalRule.Mode mode) {

        AiHubToolApprovalRule rule = new AiHubToolApprovalRule();

        rule.setWorkspaceId(WORKSPACE_ID);
        rule.setToolKind(toolKind);
        rule.setComponentName(componentName);
        rule.setToolName(toolName);
        rule.setMode(mode);

        return rule;
    }

    private static AiHubChatComponent component(long id, String componentName) {
        AiHubChatComponent component = new AiHubChatComponent();

        component.setId(id);
        component.setComponentName(componentName);
        component.setComponentVersion(1);

        return component;
    }

    private static AiHubChatTool tool(long componentId, String name, boolean requiresApproval) {
        AiHubChatTool tool = new AiHubChatTool(componentId, name, Map.of());

        tool.setRequiresApproval(requiresApproval);

        return tool;
    }
}
```

Imports: `static org.assertj.core.api.Assertions.assertThat`, `static org.mockito.Mockito.mock`, `static org.mockito.Mockito.when`, the repositories, `AiHubChatComponent`, `AiHubChatTool`, `ToolNameNormalizer`, `java.util.*`, `org.junit.jupiter.api.Test`. Check `AiHubChatComponent` has `setId(Long)` — if it does not, construct through whichever setter set the class exposes (it has `setComponentName`, `setComponentVersion`; if `setId` is absent, use reflection-free `AiHubChatComponent` no-arg + `ReflectionTestUtils.setField(component, "id", id)` from `org.springframework.test.util`).

- [ ] **Step 3: Run it to verify it fails**

```bash
./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:test --tests '*AiHubToolApprovalPolicyTest' > /tmp/t2.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED|cannot find symbol" /tmp/t2.log | head
```

Expected: `exit=1`, `cannot find symbol: class AiHubToolApprovalPolicyImpl`.

- [ ] **Step 4: Write the defaults**

`ai-hub-service/.../approval/AiHubToolApprovalDefaults.java`:

```java
public final class AiHubToolApprovalDefaults {

    public static final Set<String> DEFAULT_TOOL_NAMES = Set.of(
        "deleteProject", "deleteWorkflow", "deleteProjectDeployment", "dropDataTable", "deleteDataTableRow",
        "deleteKnowledgeBase", "deleteKnowledgeBaseDocument", "deleteCustomComponent", "deleteAiSkill",
        "deleteContextStore", "deleteContextStoreSource", "deleteAiAgentChannel", "deleteAiAgentElement",
        "rollbackProjectDeployment", "promoteWorkflow");

    private static final List<String> GATED_PREFIXES = List.of("delete", "drop", "rollback", "promote");

    private AiHubToolApprovalDefaults() {
    }

    public static boolean isGatedByDefault(String toolName) {
        if (DEFAULT_TOOL_NAMES.contains(toolName)) {
            return true;
        }

        for (String prefix : GATED_PREFIXES) {
            if (toolName.startsWith(prefix) && toolName.length() > prefix.length()
                && Character.isUpperCase(toolName.charAt(prefix.length()))) {

                return true;
            }
        }

        return false;
    }
}
```

The upper-case check after the prefix keeps `promoteWorkflow` gated and `dropdownOptions` ungated.

- [ ] **Step 5: Write the policy interface, rule service and impl**

`ai-hub-api/.../approval/AiHubToolApprovalPolicy.java`:

```java
public interface AiHubToolApprovalPolicy {

    Set<String> gatedToolNames(long workspaceId, long userId, @Nullable Long chatId);
}
```

`ai-hub-api/.../approval/AiHubToolApprovalRuleService.java` with the three methods from the Interfaces block; `AiHubToolApprovalRuleServiceImpl` (`@Service`, `@Transactional`, `@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")`) is a straight repository pass-through whose `delete(workspaceId, ruleId)` loads the row, throws `new NotFoundException("AiHubToolApprovalRule", ruleId)` when missing or when `getWorkspaceId() != workspaceId`, then deletes.

`ai-hub-service/.../approval/AiHubToolApprovalPolicyImpl.java`:

```java
@Component
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
public class AiHubToolApprovalPolicyImpl implements AiHubToolApprovalPolicy {

    private static final Logger log = LoggerFactory.getLogger(AiHubToolApprovalPolicyImpl.class);

    private static final String WILDCARD = "*";

    private final AiHubToolApprovalRuleRepository ruleRepository;
    private final AiHubChatComponentRepository componentRepository;
    private final AiHubChatToolRepository toolRepository;

    @SuppressFBWarnings("EI")
    public AiHubToolApprovalPolicyImpl(
        AiHubToolApprovalRuleRepository ruleRepository, AiHubChatComponentRepository componentRepository,
        AiHubChatToolRepository toolRepository) {

        this.ruleRepository = ruleRepository;
        this.componentRepository = componentRepository;
        this.toolRepository = toolRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> gatedToolNames(long workspaceId, long userId, @Nullable Long chatId) {
        Set<String> gated = new HashSet<>(AiHubToolApprovalDefaults.DEFAULT_TOOL_NAMES);

        try {
            List<AiHubToolApprovalRule> rules = ruleRepository.findAllByWorkspaceId(workspaceId);
            List<AiHubChatComponent> components = loadComponents(userId, workspaceId, chatId);

            applyCatalogRules(rules, gated);
            applyComponentRules(rules, components, gated);
            applyOwnerFlags(components, gated);
        } catch (RuntimeException exception) {
            log.warn(
                "Tool approval policy lookup failed for workspace={} chat={}; gating built-in defaults only",
                workspaceId, chatId, exception);
        }

        return gated;
    }

    private List<AiHubChatComponent> loadComponents(long userId, long workspaceId, @Nullable Long chatId) {
        List<AiHubChatComponent> components = new ArrayList<>(
            componentRepository.findAllByUserIdAndWorkspaceId(userId, workspaceId));

        if (chatId != null) {
            components.addAll(componentRepository.findAllByChatId(chatId));
        }

        return components;
    }

    private static void applyCatalogRules(List<AiHubToolApprovalRule> rules, Set<String> gated) {
        for (AiHubToolApprovalRule rule : rules) {
            if (rule.getToolKind() != AiHubToolApproval.ToolKind.CATALOG) {
                continue;
            }

            if (rule.getMode() == AiHubToolApprovalRule.Mode.REQUIRE) {
                gated.add(rule.getToolName());
            } else {
                gated.remove(rule.getToolName());
            }
        }
    }

    private void applyComponentRules(
        List<AiHubToolApprovalRule> rules, List<AiHubChatComponent> components, Set<String> gated) {

        for (AiHubToolApprovalRule rule : rules) {
            if (rule.getToolKind() != AiHubToolApproval.ToolKind.COMPONENT) {
                continue;
            }

            if (!WILDCARD.equals(rule.getToolName())) {
                apply(rule.getMode(), ToolNameNormalizer.toToolName(rule.getComponentName(), rule.getToolName()), gated);

                continue;
            }

            for (AiHubChatComponent component : components) {
                if (!Objects.equals(component.getComponentName(), rule.getComponentName())) {
                    continue;
                }

                for (AiHubChatTool tool : toolRepository.findAllByChatComponentId(component.getId())) {
                    apply(rule.getMode(), ToolNameNormalizer.toToolName(component.getComponentName(), tool.getName()),
                        gated);
                }
            }
        }
    }

    private void applyOwnerFlags(List<AiHubChatComponent> components, Set<String> gated) {
        for (AiHubChatComponent component : components) {
            for (AiHubChatTool tool : toolRepository.findAllByChatComponentId(component.getId())) {
                if (tool.isRequiresApproval()) {
                    gated.add(ToolNameNormalizer.toToolName(component.getComponentName(), tool.getName()));
                }
            }
        }
    }

    private static void apply(AiHubToolApprovalRule.Mode mode, String toolName, Set<String> gated) {
        if (mode == AiHubToolApprovalRule.Mode.REQUIRE) {
            gated.add(toolName);
        } else {
            gated.remove(toolName);
        }
    }
}
```

Order matters and is the "most restrictive wins" rule from the spec: exempts are applied by workspace rules, then the owner flags are applied LAST and only ever add — so an owner cannot un-gate a workspace `REQUIRE`, and a workspace `EXEMPT` of a default is honoured because the defaults were seeded first. Unit test `testWorkspaceExemptRemovesADefault` covers the first; `testChatOwnerFlagAddsButCannotRemove` the second. Note `applyCatalogRules` intentionally also lets a workspace `EXEMPT` remove a built-in prefix match: add the prefix matches for the tool names actually in play at call time — the wrapper (Task 3) calls `AiHubToolApprovalDefaults.isGatedByDefault(name)` for names not in the set, and consults an `exempt` set for that. To keep one decision point, have `gatedToolNames` ALSO return exemptions: change the interface to

```java
    Decision decide(long workspaceId, long userId, @Nullable Long chatId);

    record Decision(Set<String> required, Set<String> exempt) {

        public boolean isGated(String toolName) {
            if (exempt.contains(toolName)) {
                return false;
            }

            return required.contains(toolName) || AiHubToolApprovalDefaults.isGatedByDefault(toolName);
        }
    }
```

and update the impl to collect `EXEMPT` rules into the second set instead of removing (the `apply` helper adds to `required` or `exempt`). Update the test to call `policy.decide(...)` and assert through `isGated(...)`. This is the final shape; the earlier `gatedToolNames` sketch exists only to explain the ordering.

- [ ] **Step 6: Run the test to verify it passes**

Same command as Step 3. Expected: `exit=0`.

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply > /tmp/sa.log 2>&1; echo "exit=$?"
git add server/ee/libs/ai/ai-hub/ai-hub-api/src/main/java/com/bytechef/ee/ai/hub/approval server/ee/libs/ai/ai-hub/ai-hub-api/src/main/java/com/bytechef/ee/ai/hub/chat/AiHubChatTool.java server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/approval server/ee/libs/ai/ai-hub/ai-hub-service/src/test/java/com/bytechef/ee/ai/hub/approval server/ee/libs/ai/ai-hub/ai-hub-service/src/main/resources/config/liquibase/changelog/automation/aihub_execution/20260902000003_ai_hub_chat_tool_add_requires_approval.xml
git commit -m "Decide which AI Hub tools require approval from defaults, workspace rules and owner flags" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

### Task 3: The gate wrapper and its wiring

**Files:**
- Create: `ai-hub-api/.../approval/AiHubToolApprovalService.java`
- Create: `ai-hub-service/.../approval/AiHubToolApprovalServiceImpl.java`, `AiHubApprovalGateToolCallback.java`, `AiHubApprovalGate.java`, `metric/AiHubToolApprovalMetrics.java`
- Modify: `ai-hub-service/.../agent/AiHubToolCallbackWrappers.java:25-41`, `agent/AiHubSpringAIAgent.java` (Builder + `wrapToolCallback` + `toolContext`), `config/AiHubConfiguration.java` (both `AiHubSpringAIAgent.Builder` sites), `toolsearch/ToolSearchAdvisorConfiguration.java:252-271`
- Test: `ai-hub-service/src/test/java/com/bytechef/ee/ai/hub/approval/AiHubApprovalGateToolCallbackTest.java`

**Interfaces:**
- Consumes: `AgentToolInvocationContext.fromToolContext(ToolContext)` → `workspaceId()`, `userId()`, `conversationId()` (= thread id), `llmProvider()`, `llmModel()`; `AiHubToolInvocationContext.fromToolContext(ToolContext)` → `environmentId()`; `AiHubChatService.findByThreadId(String)`; `AiHubToolApprovalPolicy.decide(...)`.
- Produces:
  - `AiHubToolApprovalService { AiHubToolApproval createPending(AiHubToolApproval approval); Optional<AiHubToolApproval> findPending(long chatId); List<AiHubToolApproval> list(long chatId); AiHubToolApproval get(long approvalId); AiHubToolApproval save(AiHubToolApproval approval); int supersedePending(long chatId); void deleteByChat(long chatId); }`
  - `AiHubApprovalGate { ToolCallback wrap(ToolCallback callback); }` (Spring bean)
  - `AiHubApprovalGateToolCallback.ENVELOPE_KIND = "tool-approval-request"`, `TOOL_CONTEXT_MODE_KEY = "bytechef.aiHub.mode"`
  - `AiHubToolApprovalMetrics.record(String outcome)`

- [ ] **Step 1: Write the failing wrapper test**

`ai-hub-service/src/test/java/com/bytechef/ee/ai/hub/approval/AiHubApprovalGateToolCallbackTest.java` (same style as `NonEmptyToolCallbackTest`: plain `mock(...)`, no Mockito extension):

```java
class AiHubApprovalGateToolCallbackTest {

    private static final long WORKSPACE_ID = 7L;
    private static final long USER_ID = 3L;
    private static final long CHAT_ID = 11L;
    private static final String THREAD_ID = "thread-1";

    private final AiHubToolApprovalService approvalService = mock(AiHubToolApprovalService.class);
    private final AiHubChatService chatService = mock(AiHubChatService.class);
    private final AiHubToolApprovalMetrics metrics = mock(AiHubToolApprovalMetrics.class);
    private final ToolCallback delegate = callback("sendEmail", "{\"sent\":true}");

    @Test
    void testUngatedToolPassesThrough() {
        ToolCallback wrapped = gate(Set.of());

        assertThat(wrapped.call("{\"to\":\"a@b\"}", toolContext())).isEqualTo("{\"sent\":true}");
    }

    @Test
    void testGatedToolPersistsAPendingRowAndReturnsTheEnvelope() {
        when(approvalService.findPending(CHAT_ID)).thenReturn(Optional.empty());
        when(approvalService.createPending(any())).thenAnswer(invocation -> {
            AiHubToolApproval approval = invocation.getArgument(0);

            approval.setId(1234L);

            return approval;
        });

        String result = gate(Set.of("sendEmail")).call("{\"to\":\"a@b\"}", toolContext());

        Map<String, Object> envelope = JsonUtils.read(result, new TypeReference<>() {});

        assertThat(envelope.get("kind")).isEqualTo("tool-approval-request");
        assertThat(envelope.get("approvalId")).isEqualTo(1234);
        assertThat(envelope.get("toolName")).isEqualTo("sendEmail");
        assertThat(envelope.get("awaitingApproval")).isEqualTo(true);
        verify(delegate, never()).call(anyString(), any());

        ArgumentCaptor<AiHubToolApproval> captor = ArgumentCaptor.forClass(AiHubToolApproval.class);

        verify(approvalService).createPending(captor.capture());

        AiHubToolApproval saved = captor.getValue();

        assertThat(saved.getChatId()).isEqualTo(CHAT_ID);
        assertThat(saved.getRequestedByUserId()).isEqualTo(USER_ID);
        assertThat(saved.getArguments()).isEqualTo("{\"to\":\"a@b\"}");
        assertThat(saved.getMode()).isEqualTo("BUILD");
        assertThat(saved.getStatus()).isEqualTo(AiHubToolApproval.Status.PENDING);
    }

    @Test
    void testSecondGatedCallWhileOnePendingIsDeferred() {
        AiHubToolApproval pending = new AiHubToolApproval();

        pending.setId(9L);
        when(approvalService.findPending(CHAT_ID)).thenReturn(Optional.of(pending));

        String result = gate(Set.of("sendEmail")).call("{}", toolContext());

        assertThat(result).contains("\"deferred\":true");
        verify(approvalService, never()).createPending(any());
    }

    @Test
    void testPersistenceFailureRefusesTheTool() {
        when(approvalService.findPending(CHAT_ID)).thenReturn(Optional.empty());
        when(approvalService.createPending(any())).thenThrow(new IllegalStateException("db down"));

        String result = gate(Set.of("sendEmail")).call("{}", toolContext());

        assertThat(result).contains("approval could not be recorded");
        verify(delegate, never()).call(anyString(), any());
    }

    @Test
    void testUnknownThreadPassesThrough() {
        when(chatService.findByThreadId(THREAD_ID)).thenReturn(Optional.empty());

        assertThat(gate(Set.of("sendEmail")).call("{}", toolContext())).isEqualTo("{\"sent\":true}");
    }

    private ToolCallback gate(Set<String> required) {
        AiHubChat chat = new AiHubChat(USER_ID);

        chat.setId(CHAT_ID);
        chat.setWorkspaceId(WORKSPACE_ID);
        chat.setThreadId(THREAD_ID);
        when(chatService.findByThreadId(THREAD_ID)).thenReturn(Optional.of(chat));

        AiHubToolApprovalPolicy policy = (workspaceId, userId, chatId) -> new AiHubToolApprovalPolicy.Decision(
            required, Set.of());

        return new AiHubApprovalGateToolCallback(delegate, policy, approvalService, chatService, metrics);
    }

    private static ToolContext toolContext() {
        Map<String, Object> context = new HashMap<>(
            AgentToolInvocationContext.builder()
                .workspaceId(WORKSPACE_ID)
                .userId(USER_ID)
                .environmentId(0L)
                .conversationId(THREAD_ID)
                .llmProvider("anthropic")
                .llmModel("claude-fable-5-1")
                .build()
                .toToolContext());

        context.put(AiHubApprovalGateToolCallback.TOOL_CONTEXT_MODE_KEY, "BUILD");

        return new ToolContext(context);
    }

    private static ToolCallback callback(String name, String result) {
        ToolCallback callback = mock(ToolCallback.class);

        when(callback.getToolDefinition()).thenReturn(
            ToolDefinition.builder().name(name).description(name).inputSchema("{}").build());
        when(callback.call(anyString(), any())).thenReturn(result);

        return callback;
    }
}
```

`chat.setId(...)` — `AiHubChat` uses `@Id private Long id`; if there is no public `setId`, use `ReflectionTestUtils.setField(chat, "id", CHAT_ID)`. `JsonUtils` requires `@ExtendWith(ObjectMapperSetupExtension.class)` on the class (per CLAUDE.md) — add it and the import from `com.bytechef.test.extension`. `AiHubToolApproval.isGatedByDefault` is not consulted here because `Decision.isGated` is exercised through the policy lambda's `required` set only; `sendEmail` is not a default.

- [ ] **Step 2: Run it to verify it fails**

```bash
./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:test --tests '*AiHubApprovalGateToolCallbackTest' > /tmp/t3.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED|cannot find symbol" /tmp/t3.log | head
```

Expected: `exit=1`, `cannot find symbol: class AiHubApprovalGateToolCallback`.

- [ ] **Step 3: Write the approval service**

`ai-hub-api/.../approval/AiHubToolApprovalService.java` with the seven methods in the Interfaces block. `AiHubToolApprovalServiceImpl` (`@Service`, `@Transactional`, same `@ConditionalOnProperty` as the policy):

- `createPending`: sets `Status.PENDING`, `expiresAt = Instant.now(clock).plus(Duration.ofHours(24))` when null, saves.
- `findPending(chatId)`: `repository.findFirstByChatIdAndStatus(chatId, Status.PENDING.ordinal())`.
- `get(id)`: `findById` or `throw new NotFoundException("AiHubToolApproval", id)`.
- `supersedePending(chatId)`: loads `findAllByChatIdAndStatus(chatId, PENDING.ordinal())`, sets `SUPERSEDED` + `decidedAt`, saves each, returns the count.
- `deleteByChat(chatId)`: `repository.deleteAllByChatId(chatId)`.

Inject a `java.time.Clock` through a constructor overload defaulting to `Clock.systemUTC()`, the way `AiHubChatServiceImpl` does.

- [ ] **Step 4: Write the metrics component**

`ai-hub-service/.../metric/AiHubToolApprovalMetrics.java` — copy the shape of `AiHubToolAttachMetrics.recordAskUserQuestion` (lines 124-136): `@Component`, `ObjectProvider<MeterRegistry>`, one method `public void record(String outcome)` incrementing `Counter.builder("bytechef_ai_hub_tool_approval").tag("outcome", outcome)`, no-op when the registry is absent.

- [ ] **Step 5: Write the wrapper**

`ai-hub-service/.../approval/AiHubApprovalGateToolCallback.java`:

```java
public final class AiHubApprovalGateToolCallback implements DelegatingToolCallback {

    public static final String ENVELOPE_KIND = "tool-approval-request";
    public static final String TOOL_CONTEXT_MODE_KEY = "bytechef.aiHub.mode";

    private static final Logger log = LoggerFactory.getLogger(AiHubApprovalGateToolCallback.class);

    private static final String DEFERRED_RESULT =
        "{\"deferred\":true,\"reason\":\"Another tool call is awaiting approval. Retry after it is resolved.\"}";
    private static final String REFUSED_RESULT =
        "{\"error\":\"approval could not be recorded; the tool was not executed\"}";

    private final ToolCallback delegate;
    private final AiHubToolApprovalPolicy policy;
    private final AiHubToolApprovalService approvalService;
    private final AiHubChatService chatService;
    private final AiHubToolApprovalMetrics metrics;

    public AiHubApprovalGateToolCallback(
        ToolCallback delegate, AiHubToolApprovalPolicy policy, AiHubToolApprovalService approvalService,
        AiHubChatService chatService, AiHubToolApprovalMetrics metrics) {

        this.delegate = delegate;
        this.policy = policy;
        this.approvalService = approvalService;
        this.chatService = chatService;
        this.metrics = metrics;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolCallback getDelegate() {
        return delegate;
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        AgentToolInvocationContext invocationContext = AgentToolInvocationContext.fromToolContext(toolContext);
        String threadId = invocationContext.conversationId();
        Long workspaceId = invocationContext.workspaceId();
        Long userId = invocationContext.userId();

        if (threadId == null || workspaceId == null || userId == null) {
            return delegate.call(toolInput, toolContext);
        }

        Optional<AiHubChat> chatOptional = chatService.findByThreadId(threadId);

        if (chatOptional.isEmpty()) {
            return delegate.call(toolInput, toolContext);
        }

        AiHubChat chat = chatOptional.get();
        String toolName = getToolDefinition().name();
        AiHubToolApprovalPolicy.Decision decision = policy.decide(workspaceId, userId, chat.getId());

        if (!decision.isGated(toolName)) {
            return delegate.call(toolInput, toolContext);
        }

        if (approvalService.findPending(chat.getId()).isPresent()) {
            metrics.record("deferred");

            return DEFERRED_RESULT;
        }

        try {
            AiHubToolApproval approval = approvalService.createPending(
                toApproval(chat, userId, toolName, toolInput, invocationContext, toolContext));

            metrics.record("requested");

            return JsonUtils.write(toEnvelope(approval));
        } catch (RuntimeException exception) {
            log.warn("Could not record a tool approval for tool={} chat={}", toolName, chat.getId(), exception);
            metrics.record("refused");

            return REFUSED_RESULT;
        }
    }

    private AiHubToolApproval toApproval(
        AiHubChat chat, long userId, String toolName, String toolInput, AgentToolInvocationContext invocationContext,
        @Nullable ToolContext toolContext) {

        ToolCallback unwrapped = DelegatingToolCallback.unwrap(delegate);
        AiHubToolApproval approval = new AiHubToolApproval();

        approval.setChatId(chat.getId());
        approval.setThreadId(chat.getThreadId());
        approval.setRequestedByUserId(userId);
        approval.setToolName(toolName);
        approval.setArguments(toolInput == null ? "{}" : toolInput);
        approval.setMode(readMode(toolContext));
        approval.setEnvironment(chat.getEnvironment().ordinal());
        approval.setLlmProvider(invocationContext.llmProvider());
        approval.setLlmModel(invocationContext.llmModel());

        if (unwrapped instanceof ClusterElementToolCallback clusterElementToolCallback) {
            approval.setToolKind(AiHubToolApproval.ToolKind.COMPONENT);
            approval.setComponentName(clusterElementToolCallback.getComponentName());
            approval.setComponentVersion(clusterElementToolCallback.getComponentVersion());
            approval.setConnectionId(clusterElementToolCallback.getPinnedConnectionId());
        } else {
            approval.setToolKind(AiHubToolApproval.ToolKind.CATALOG);
        }

        return approval;
    }

    private static String readMode(@Nullable ToolContext toolContext) {
        if (toolContext == null) {
            return "ASK";
        }

        Object mode = toolContext.getContext()
            .get(TOOL_CONTEXT_MODE_KEY);

        return mode instanceof String stringMode && !stringMode.isBlank() ? stringMode : "ASK";
    }

    private static Map<String, Object> toEnvelope(AiHubToolApproval approval) {
        Map<String, Object> envelope = new LinkedHashMap<>();

        envelope.put("kind", ENVELOPE_KIND);
        envelope.put("approvalId", approval.getId());
        envelope.put("toolName", approval.getToolName());
        envelope.put("componentName", approval.getComponentName());
        envelope.put("arguments", JsonUtils.read(approval.getArguments(), Map.class));
        envelope.put("expiresAt", approval.getExpiresAt() == null ? null : approval.getExpiresAt().toString());
        envelope.put("awaitingApproval", true);

        return envelope;
    }
}
```

`ClusterElementToolCallback` needs three read accessors that do not exist yet — add `getComponentName()`, `getComponentVersion()` and `getPinnedConnectionId()` to `ai-hub-service/.../toolsearch/ClusterElementToolCallback.java` returning its existing private fields (check their exact names in that file; the 10-arg constructor at line 104 names them `componentName`, `componentVersion`, `pinnedConnectionId`).

- [ ] **Step 6: Write `AiHubApprovalGate` and wire it into the wrapper chain**

`ai-hub-service/.../approval/AiHubApprovalGate.java`:

```java
@Component
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
public class AiHubApprovalGate {

    private final AiHubToolApprovalPolicy policy;
    private final AiHubToolApprovalService approvalService;
    private final AiHubChatService chatService;
    private final AiHubToolApprovalMetrics metrics;

    @SuppressFBWarnings("EI")
    public AiHubApprovalGate(
        AiHubToolApprovalPolicy policy, AiHubToolApprovalService approvalService, AiHubChatService chatService,
        AiHubToolApprovalMetrics metrics) {

        this.policy = policy;
        this.approvalService = approvalService;
        this.chatService = chatService;
        this.metrics = metrics;
    }

    public ToolCallback wrap(ToolCallback callback) {
        if (callback instanceof AiHubApprovalGateToolCallback) {
            return callback;
        }

        return new AiHubApprovalGateToolCallback(callback, policy, approvalService, chatService, metrics);
    }
}
```

`AiHubToolCallbackWrappers.wrap` becomes:

```java
    public static ToolCallback wrap(
        ToolCallback callback, @Nullable SecurityContextRehydrator securityContextRehydrator,
        @Nullable AiHubApprovalGate approvalGate) {

        ToolCallback nonEmpty = NonEmptyToolCallback.wrap(callback);
        ToolCallback gated = approvalGate == null ? nonEmpty : approvalGate.wrap(nonEmpty);

        if (securityContextRehydrator == null) {
            return gated;
        }

        return RehydrateContextToolCallback.wrap(gated, securityContextRehydrator);
    }
```

Keep the old two-argument overload delegating with `null` so unrelated callers compile, then find every caller and pass the real bean:

```bash
grep -rn "AiHubToolCallbackWrappers.wrap(" server/ee/libs/ai/ai-hub --include='*.java' | grep -v /build/
```

- `AiHubSpringAIAgent`: add `private @Nullable AiHubApprovalGate approvalGate;` to the class and the `Builder` (setter `approvalGate(AiHubApprovalGate)`), assign in the constructor, and change `wrapToolCallback` to `AiHubToolCallbackWrappers.wrap(callback, securityContextRehydrator, approvalGate)`. Wherever the Builder's static path wraps `pendingToolCallbacks`, pass the same three arguments.
- `AiHubConfiguration`: both places that build an `AiHubSpringAIAgent.Builder` gain `.approvalGate(approvalGate)`; inject `ObjectProvider<AiHubApprovalGate> approvalGateProvider` into those `@Bean` methods and pass `approvalGateProvider.getIfAvailable()`.
- `ToolSearchAdvisorConfiguration.buildModeAdvisor` (the wrap at ~line 262): pass the gate the same way (inject `ObjectProvider<AiHubApprovalGate>` into the configuration's constructor).

Then in `AiHubSpringAIAgent.toolContext(RunAgentInput input)` add, before `return toolContext;`:

```java
        Object mode = input.state() == null ? null : input.state()
            .get("mode");

        if (mode instanceof String stringMode) {
            toolContext.put(AiHubApprovalGateToolCallback.TOOL_CONTEXT_MODE_KEY, stringMode);
        }
```

- [ ] **Step 7: Run the wrapper test and the whole module's unit tests**

```bash
./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:test > /tmp/t3b.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED|FAILED$" /tmp/t3b.log | head
```

Expected: `exit=0`. If `AiHubSpringAIAgentGuardrailsTest` or an `AiHubConfiguration*Test` fails on the new Builder field, that test builds the agent by hand — leave `approvalGate` null there (the wrapper is skipped when null).

- [ ] **Step 8: Commit**

```bash
./gradlew spotlessApply > /tmp/sa.log 2>&1; echo "exit=$?"
git add server/ee/libs/ai/ai-hub
git commit -m "Gate flagged AI Hub tool calls behind a pending approval instead of executing them" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Prompt instruction

**Files:**
- Modify: `ai-hub-service/src/main/resources/prompt_ai_hub_ask.txt` (after the "Clarifying questions" section, ~line 191), `prompt_ai_hub_build.txt` (~line 645)

- [ ] **Step 1: Append the paragraph to both prompts**

```
## Tool approvals

Some tools require a person's approval before they run. When a tool returns a JSON result with
"awaitingApproval": true, the tool did NOT run. Tell the user in one sentence what you asked to do
(name the tool and the key arguments), say that it is waiting for their approval, and end your turn.
Never call the same tool again in this turn, never claim the action happened, and never try a
different tool to achieve the same effect. A later user message starting with "[tool-approval #"
is generated by the platform, not typed by the user: it reports whether the approval was approved,
rejected, expired or failed, and includes the tool's result when it ran. Continue from that result
as if the tool had just returned it. A result with "deferred": true means another tool call is
already waiting; do not retry until the user resolves it.
```

- [ ] **Step 2: Verify the prompts still load**

`AiHubConfiguration` reads both files at startup; the existing `AiHubConfiguration*Test`s that construct the beans cover the read. Run:

```bash
./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:test --tests '*AiHubConfiguration*' > /tmp/t4.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t4.log
```

Expected: `exit=0`.

- [ ] **Step 3: Commit**

```bash
git add server/ee/libs/ai/ai-hub/ai-hub-service/src/main/resources/prompt_ai_hub_ask.txt server/ee/libs/ai/ai-hub/ai-hub-service/src/main/resources/prompt_ai_hub_build.txt
git commit -m "Tell the AI Hub agents how to behave around a pending tool approval" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

### Task 5: Workspace rules facade and GraphQL

**Files:**
- Create: `ai-hub-api/.../approval/AiHubToolApprovalRuleFacade.java`; `ai-hub-service/.../approval/AiHubToolApprovalRuleFacadeImpl.java`
- Create: `ai-hub-graphql/src/main/resources/graphql/ai-hub-tool-approval.graphqls`; `ai-hub-graphql/src/main/java/com/bytechef/ee/ai/hub/web/graphql/AiHubToolApprovalRuleGraphQlController.java`
- Modify: `ai-hub-service/.../audit/AiHubAuditEvent.java`
- Test: `ai-hub-service/src/test/.../approval/AiHubToolApprovalRuleFacadeTest.java`; `ai-hub-graphql/src/test/.../AiHubToolApprovalRuleGraphQlControllerTest.java`

**Interfaces:**
- Produces: `AiHubToolApprovalRuleFacade { List<AiHubToolApprovalRule> getRules(long workspaceId); List<String> getDefaultToolNames(); AiHubToolApprovalRule createRule(long workspaceId, AiHubToolApproval.ToolKind toolKind, @Nullable String componentName, String toolName, AiHubToolApprovalRule.Mode mode); void deleteRule(long workspaceId, long ruleId); }`
- Audit: `AI_HUB_TOOL_APPROVAL_RULE_CHANGED(true)` with payload `workspaceId`, `ruleId`, `toolName`, `mode`, `action` (`created` | `deleted`).

- [ ] **Step 1: Add the four audit events**

In `AiHubAuditEvent.java` append after `AI_HUB_CHAT_DELETED(true)`:

```java
    AI_HUB_TOOL_APPROVAL_REQUESTED(false),

    AI_HUB_TOOL_APPROVAL_APPROVED(true),

    AI_HUB_TOOL_APPROVAL_REJECTED(false),

    AI_HUB_TOOL_APPROVAL_RULE_CHANGED(true);
```

(the previous last entry loses its `;`). Javadoc each with its payload keys the way the existing entries do.

- [ ] **Step 2: Write the failing facade test**

`AiHubToolApprovalRuleFacadeTest`: constructs `new AiHubToolApprovalRuleFacadeImpl(ruleService, auditPublisher)` with mocks; asserts `createRule` rejects a blank `toolName` and a `COMPONENT` rule with a null `componentName` (`IllegalArgumentException`), saves an otherwise valid rule with `workspaceId` set from the argument (never from the caller's object), and publishes `AI_HUB_TOOL_APPROVAL_RULE_CHANGED` with `action=created`; `deleteRule` delegates to `ruleService.delete(workspaceId, ruleId)` and publishes `action=deleted`; `getDefaultToolNames` returns `AiHubToolApprovalDefaults.DEFAULT_TOOL_NAMES` sorted.

Run: `./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:test --tests '*AiHubToolApprovalRuleFacadeTest' > /tmp/t5.log 2>&1; echo "exit=$?"` — expected `exit=1`, missing class.

- [ ] **Step 3: Write the facade**

`AiHubToolApprovalRuleFacadeImpl` (`@Service`, `@Transactional`, `@ConditionalOnProperty` as before). Every method carries the same admin gate the workspace-settings facade uses:

```java
    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#workspaceId, 'Workspace', 'WORKSPACE_VIEW')")
    public List<AiHubToolApprovalRule> getRules(long workspaceId) {
        return ruleService.getRules(workspaceId);
    }

    @Override
    @PreAuthorize("hasPermission(#workspaceId, 'Workspace', 'WORKSPACE_MANAGE')")
    public AiHubToolApprovalRule createRule(
        long workspaceId, AiHubToolApproval.ToolKind toolKind, @Nullable String componentName, String toolName,
        AiHubToolApprovalRule.Mode mode) {

        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName is required");
        }

        if (toolKind == AiHubToolApproval.ToolKind.COMPONENT && (componentName == null || componentName.isBlank())) {
            throw new IllegalArgumentException("componentName is required for a component rule");
        }

        AiHubToolApprovalRule rule = new AiHubToolApprovalRule();

        rule.setWorkspaceId(workspaceId);
        rule.setToolKind(toolKind);
        rule.setComponentName(toolKind == AiHubToolApproval.ToolKind.COMPONENT ? componentName : null);
        rule.setToolName(toolName.trim());
        rule.setMode(mode);

        AiHubToolApprovalRule saved = ruleService.save(rule);

        publish(workspaceId, saved, "created");

        return saved;
    }
```

`deleteRule` loads through `ruleService.getRules(workspaceId)` to find the row (so the audit payload has the tool name), calls `ruleService.delete(workspaceId, ruleId)`, publishes `deleted`. `publish` builds a `Map<String, Object>` with `workspaceId`, `ruleId`, `toolName`, `mode` (`.name()`), `action` and calls `auditPublisher.publish(AiHubAuditEvent.AI_HUB_TOOL_APPROVAL_RULE_CHANGED, data)` — the publisher is `@Nullable` in the constructor exactly as in `AiHubChatServiceImpl` so unit tests pass `null`.

- [ ] **Step 4: Write the schema and controller**

`ai-hub-graphql/src/main/resources/graphql/ai-hub-tool-approval.graphqls`:

```graphql
type AiHubToolApprovalRule {
    id: ID!
    workspaceId: Long!
    toolKind: AiHubToolKind!
    componentName: String
    toolName: String!
    mode: AiHubToolApprovalRuleMode!
}

enum AiHubToolKind {
    CATALOG
    COMPONENT
}

enum AiHubToolApprovalRuleMode {
    REQUIRE
    EXEMPT
}

type AiHubToolApproval {
    id: ID!
    chatId: ID!
    requestedByUserId: Long!
    toolKind: AiHubToolKind!
    toolName: String!
    componentName: String
    arguments: String!
    status: AiHubToolApprovalStatus!
    decidedByUserId: Long
    decidedAt: Long
    comment: String
    expiresAt: Long!
    executionError: String
    createdDate: Long!
}

enum AiHubToolApprovalStatus {
    PENDING
    APPROVED
    REJECTED
    EXPIRED
    SUPERSEDED
    FAILED
}

type AiHubToolApprovalResolution {
    approval: AiHubToolApproval!
    continuationStarted: Boolean!
    runId: String
}

extend type Query {
    aiHubToolApprovalRules(workspaceId: ID!): [AiHubToolApprovalRule!]!
    aiHubToolApprovalDefaultToolNames: [String!]!
    aiHubToolApprovals(workspaceId: ID!, chatId: ID!): [AiHubToolApproval!]!
}

extend type Mutation {
    createAiHubToolApprovalRule(
        workspaceId: ID!, toolKind: AiHubToolKind!, componentName: String, toolName: String!,
        mode: AiHubToolApprovalRuleMode!
    ): AiHubToolApprovalRule!
    deleteAiHubToolApprovalRule(workspaceId: ID!, ruleId: ID!): Boolean!
    resolveAiHubToolApproval(workspaceId: ID!, approvalId: ID!, approved: Boolean!, comment: String): AiHubToolApprovalResolution!
}
```

`AiHubToolApprovalRuleGraphQlController` (`@Controller`, `@ConditionalOnEEVersion`, `@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")`, NO class-level `@PreAuthorize` — authorization lives on the facade, exactly like `AiHubWorkspaceSettingsGraphQlController`): `@QueryMapping aiHubToolApprovalRules(@Argument long workspaceId)`, `@QueryMapping aiHubToolApprovalDefaultToolNames()`, `@MutationMapping createAiHubToolApprovalRule(...)`, `@MutationMapping deleteAiHubToolApprovalRule(...)` returning `true`. Add `@SchemaMapping(typeName = "AiHubToolApprovalRule", field = "toolKind")` / `"mode"` resolvers returning the enums (the domain stores ordinals), mirroring the `status`/`kind` resolvers in `AiHubChatGraphQlController:333-360`.

The `aiHubToolApprovals` query and `resolveAiHubToolApproval` mutation are declared here but implemented in Task 6's controller; Spring GraphQL only fails at startup if a schema field has no mapping AND schema inspection is strict, so keep the two tasks in the same PR.

- [ ] **Step 5: Write the controller test and run both**

`AiHubToolApprovalRuleGraphQlControllerTest` mirrors `AiHubChatGraphQlControllerTest`: hand-construct the controller with a mocked facade, assert each mapping delegates with the same arguments and that `deleteAiHubToolApprovalRule` returns `true`.

```bash
./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:test --tests '*AiHubToolApprovalRuleFacadeTest' :server:ee:libs:ai:ai-hub:ai-hub-graphql:test > /tmp/t5b.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t5b.log
```

Expected: `exit=0`.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply > /tmp/sa.log 2>&1; echo "exit=$?"
git add server/ee/libs/ai/ai-hub
git commit -m "Let a workspace admin require or exempt AI Hub tools from approval" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Resolution — execute, continue, audit

**Files:**
- Create: `ai-hub-api/.../approval/AiHubToolApprovalFacade.java`; `ai-hub-service/.../approval/AiHubToolApprovalFacadeImpl.java`; `ai-hub-service/.../agent/AiHubRunState.java`
- Create: `ai-hub-graphql/.../AiHubToolApprovalGraphQlController.java`
- Modify: `ai-hub-rest/.../AiHubApiController.java:289-323` (`injectAuthenticatedContext`), `ai-hub-service/.../chat/AiHubChatServiceImpl.java` (`delete` cleans approvals; new turn supersedes)
- Test: `ai-hub-service/src/test/.../approval/AiHubToolApprovalFacadeTest.java`; `ai-hub-service/src/test/.../agent/AiHubRunStateTest.java`

**Interfaces:**
- Consumes: `AiHubChatStreamer.runAgent(LocalAgent, AgUiParameters, String threadId)`; `InFlightAiHubRunRegistry.isInFlight(String)`; `List<LocalAgent>` beans keyed `ai_hub_ask` / `ai_hub_build`; `AiHubChatBindingToolCallbackResolver.resolve(AiHubToolInvocationContext)`; the two `AiHubGlobalToolCatalog` beans (`@Qualifier("aiHubAskGlobalToolCatalog")`, `@Qualifier("aiHubBuildGlobalToolCatalog")`) — verify the bean names with `grep -n "GlobalToolCatalog(" ai-hub-service/.../config/AiHubConfiguration.java`; `SecurityContextRehydrator` bean; `AiHubAuditPublisher.publish(AiHubAuditEvent, Map)`; `ToolExecutionRecorder`.
- Produces:
  - `AiHubToolApprovalFacade { List<AiHubToolApproval> list(long workspaceId, long chatId); Resolution resolve(long workspaceId, long approvalId, boolean approved, @Nullable String comment); record Resolution(AiHubToolApproval approval, boolean continuationStarted, @Nullable String runId) {} }`
  - `AiHubRunState.inject(State state, long userId, long workspaceId, @Nullable String threadId, long environmentId, String tenantId)` and `AiHubRunState.CONTINUATION_PREFIX = "[tool-approval #"`.

- [ ] **Step 1: Extract `AiHubRunState` from the controller**

`ai-hub-service/.../agent/AiHubRunState.java`:

```java
public final class AiHubRunState {

    public static final String CONTINUATION_PREFIX = "[tool-approval #";

    private AiHubRunState() {
    }

    public static void inject(
        State state, long userId, long workspaceId, @Nullable String threadId, long environmentId, String tenantId) {

        state.set(AiHubStateKeys.AUTHENTICATED_USER_ID, userId);
        state.set(AiHubStateKeys.VERIFIED_WORKSPACE_ID, workspaceId);
        state.set(AiHubStateKeys.WORKSPACE_ID, workspaceId);
        state.set(AiHubStateKeys.USER_ID, userId);

        if (threadId != null) {
            state.set(AiHubStateKeys.VERIFIED_THREAD_ID, threadId);
            state.set(AiHubStateKeys.THREAD_ID, threadId);
        }

        state.set(AiHubStateKeys.VERIFIED_ENVIRONMENT_ID, environmentId);
        state.set(AiHubStateKeys.ENVIRONMENT_ID, environmentId);
        state.set(AiHubStateKeys.VERIFIED_TENANT_ID, tenantId);
    }

    public static long clampEnvironmentId(@Nullable Long rawEnvironmentId) {
        return rawEnvironmentId != null && rawEnvironmentId >= 0 && rawEnvironmentId < Environment.values().length
            ? rawEnvironmentId
            : 0L;
    }
}
```

`AiHubStateKeys` lives in `ai-hub-api` (`com.bytechef.ee.ai.hub.agent` — confirm with `grep -rl "class AiHubStateKeys" server/ee/libs/ai/ai-hub`). Rewrite `AiHubApiController.injectAuthenticatedContext` to create the `State` when null, compute `environmentId = AiHubRunState.clampEnvironmentId(readLong(agUiParameters, AiHubStateKeys.ENVIRONMENT_ID))`, and call `AiHubRunState.inject(state, userId, workspaceId, verifiedThreadId, environmentId, TenantContext.getCurrentTenantId())`. Behaviour is identical; `AiHubRunStateTest` asserts every key the old method set is still set.

- [ ] **Step 2: Write the failing facade test**

`AiHubToolApprovalFacadeTest` (mocks: `AiHubToolApprovalService`, `AiHubChatService`, `UserService`, `AiHubChatStreamer`, `InFlightAiHubRunRegistry`, `AiHubChatBindingToolCallbackResolver`, two `AiHubGlobalToolCatalog` records built with a `sendEmail` mock callback in the BUILD one, `AiHubAuditPublisher` = null, `ToolExecutionRecorder` = null, `SecurityContextRehydrator` = null, `List<LocalAgent>` with a mock whose `getAgentId()` is `ai_hub_build`, `Clock.fixed(...)`). Tests:

- `testStrangerCannotResolve`: chat owner 3, current user 4 without `ROLE_ADMIN` → `NotFoundException`.
- `testExpiredRowFlipsToExpired`: `expiresAt` before the clock → status `EXPIRED`, `ConflictException` with message containing `expired`.
- `testRejectRecordsDecisionAndStartsContinuationWithoutExecuting`: status `REJECTED`, `decidedByUserId`, `comment` set; delegate never called; `chatStreamer.runAgent` called once with an `AgUiParameters` whose single message content starts with `[tool-approval #` and contains `rejected`.
- `testApproveExecutesTheStoredArguments`: delegate called with exactly `approval.getArguments()`; status `APPROVED`; the continuation message contains the delegate's result.
- `testApproveWhenTheToolThrowsMarksFailed`: delegate throws → status `FAILED`, `executionError` set, continuation still started.
- `testContinuationSkippedWhenARunIsInFlight`: `registry.isInFlight(threadId)` true → `Resolution.continuationStarted()` false, `runAgent` never called, decision still persisted.

Set the security context in `@BeforeEach` with `SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("ivica", "n/a", List.of()))` and `userService.getCurrentUser()` returning a `User` with id 3; clear it in `@AfterEach`.

Run: `./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:test --tests '*AiHubToolApprovalFacadeTest' > /tmp/t6.log 2>&1; echo "exit=$?"` — expected `exit=1`, missing class.

- [ ] **Step 3: Write the facade**

`AiHubToolApprovalFacadeImpl` (`@Service`, `@ConditionalOnProperty` as before; NOT `@Transactional` at class level — the continuation must start after the decision commits, so `resolve` marks the row inside a `TransactionTemplate.execute(...)` and starts the run afterwards):

```java
    @Override
    public Resolution resolve(long workspaceId, long approvalId, boolean approved, @Nullable String comment) {
        long currentUserId = userService.getCurrentUser()
            .getId();
        AiHubToolApproval approval = approvalService.get(approvalId);
        AiHubChat chat = chatService.findByThreadId(approval.getThreadId())
            .orElseThrow(() -> new NotFoundException("AiHubToolApproval", approvalId));

        if (!Objects.equals(chat.getWorkspaceId(), workspaceId) || !canResolve(chat, currentUserId)) {
            throw new NotFoundException("AiHubToolApproval", approvalId);
        }

        Instant now = Instant.now(clock);

        if (approval.getStatus() != AiHubToolApproval.Status.PENDING) {
            throw new ConflictException("Approval " + approvalId + " is already " + approval.getStatus());
        }

        if (approval.isExpired(now)) {
            approval.setStatus(AiHubToolApproval.Status.EXPIRED);
            approvalService.save(approval);
            metrics.record("expired");

            throw new ConflictException("Approval " + approvalId + " has expired");
        }

        approval.setDecidedByUserId(currentUserId);
        approval.setDecidedAt(now);
        approval.setComment(comment);

        String toolResult = null;

        if (approved) {
            approval.setStatus(AiHubToolApproval.Status.APPROVED);
            toolResult = execute(approval, chat, currentUserId);
        } else {
            approval.setStatus(AiHubToolApproval.Status.REJECTED);
        }

        AiHubToolApproval decided = approvalService.save(approval);

        publishDecision(decided, approved);
        metrics.record(decided.getStatus().name().toLowerCase(Locale.ROOT));

        String runId = startContinuation(decided, chat, currentUserId, toolResult);

        return new Resolution(decided, runId != null, runId);
    }

    private boolean canResolve(AiHubChat chat, long userId) {
        return chat.getUserId() == userId || SecurityUtils.hasCurrentUserThisAuthority(AuthorityConstants.ADMIN);
    }
```

`execute(...)`:

```java
    private @Nullable String execute(AiHubToolApproval approval, AiHubChat chat, long userId) {
        Optional<ToolCallback> callbackOptional = findCallback(approval, chat, userId);

        if (callbackOptional.isEmpty()) {
            approval.setStatus(AiHubToolApproval.Status.FAILED);
            approval.setExecutionError("Tool " + approval.getToolName() + " is no longer available in this chat");

            return null;
        }

        ToolCallback callback = AiHubToolCallbackWrappers.wrap(callbackOptional.get(), securityContextRehydrator);
        ToolContext toolContext = new ToolContext(toolContextFor(approval, chat, userId));

        try {
            return toolExecutionRecorder == null
                ? callback.call(approval.getArguments(), toolContext)
                : toolExecutionRecorder.record(
                    ToolExecutionEvent
                        .builder(ToolExecutionSurface.AI_HUB, toolExecutionKind(approval), approval.getToolName())
                        .componentName(approval.getComponentName())
                        .componentVersion(approval.getComponentVersion())
                        .connectionId(approval.getConnectionId())
                        .environment(approval.getEnvironment())
                        .workspaceId(chat.getWorkspaceId()),
                    () -> callback.call(approval.getArguments(), toolContext));
        } catch (RuntimeException exception) {
            approval.setStatus(AiHubToolApproval.Status.FAILED);
            approval.setExecutionError(exception.getMessage() == null ? exception.toString() : exception.getMessage());

            return null;
        }
    }
```

Check `ToolExecutionSurface` has an `AI_HUB` constant and `ToolExecutionKind` has `COMPONENT` / `CATALOG`-equivalent constants: `grep -n "AI_HUB\|COMPONENT\|INTELLIGENT\|CATALOG" server/libs/platform/platform-tool-execution/platform-tool-execution-api/src/main/java/com/bytechef/platform/tool/execution/ToolExecution{Surface,Kind}.java`. Use whatever the enums actually define for the non-component kind; if there is no catalog-like kind, use the one `AiHubToolUsageContextResolver` already uses for catalog tools.

`findCallback` searches, in order: `chatBindingResolver.resolve(new AiHubToolInvocationContext(chat.getWorkspaceId(), userId, Source.AI_HUB.toAgentSourceOrdinal(), null, (long) approval.getEnvironment(), chat.getThreadId()))`, then the catalog for the row's `mode` (`"BUILD"` → build catalog else ask), matching `callback.getToolDefinition().name().equals(approval.getToolName())`. `toolContextFor` merges `AgentToolInvocationContext.builder().workspaceId(...).userId(userId).environmentId(...).conversationId(chat.getThreadId()).tenantId(TenantContext.getCurrentTenantId()).authentication(SecurityContextHolder.getContext().getAuthentication()).llmProvider(approval.getLlmProvider()).llmModel(approval.getLlmModel()).build().toToolContext()` with the `AiHubToolInvocationContext.toToolContext()` map — the same two maps `AiHubSpringAIAgent.toolContext` builds — plus `AiHubApprovalGateToolCallback.TOOL_CONTEXT_MODE_KEY`.

`startContinuation`:

```java
    private @Nullable String startContinuation(
        AiHubToolApproval approval, AiHubChat chat, long userId, @Nullable String toolResult) {

        if (inFlightRunRegistry.isInFlight(chat.getThreadId())) {
            return null;
        }

        LocalAgent localAgent = localAgentMap.get("BUILD".equals(approval.getMode()) ? "ai_hub_build" : "ai_hub_ask");

        if (localAgent == null) {
            return null;
        }

        String runId = UUID.randomUUID()
            .toString();
        UserMessage userMessage = new UserMessage();

        userMessage.setId(UUID.randomUUID()
            .toString());
        userMessage.setContent(continuationText(approval, toolResult));

        State state = new State();

        state.set("mode", approval.getMode());

        if (approval.getLlmProvider() != null) {
            state.set(AiHubStateKeys.USER_SELECTED_LLM_PROVIDER_KEY, approval.getLlmProvider());
        }

        if (approval.getLlmModel() != null) {
            state.set(AiHubStateKeys.USER_SELECTED_LLM_MODEL_KEY, approval.getLlmModel());
        }

        AiHubRunState.inject(
            state, userId, chat.getWorkspaceId(), chat.getThreadId(), approval.getEnvironment(),
            TenantContext.getCurrentTenantId());

        AgUiParameters parameters = new AgUiParameters();

        parameters.setThreadId(chat.getThreadId());
        parameters.setRunId(runId);
        parameters.setMessages(List.of(userMessage));
        parameters.setState(state);

        chatStreamer.runAgent(localAgent, parameters, chat.getThreadId());

        return runId;
    }

    static String continuationText(AiHubToolApproval approval, @Nullable String toolResult) {
        String decision = switch (approval.getStatus()) {
            case APPROVED -> "approved and executed";
            case REJECTED -> "rejected";
            case FAILED -> "approved but the tool failed";
            default -> approval.getStatus()
                .name()
                .toLowerCase(Locale.ROOT);
        };
        StringBuilder text = new StringBuilder()
            .append(AiHubRunState.CONTINUATION_PREFIX)
            .append(approval.getId())
            .append(" ")
            .append(decision)
            .append("]\nTool: ")
            .append(approval.getComponentName() == null ? "" : approval.getComponentName() + "/")
            .append(approval.getToolName());

        if (approval.getComment() != null && !approval.getComment().isBlank()) {
            text.append("\nComment: ")
                .append(approval.getComment());
        }

        if (toolResult != null) {
            text.append("\nResult:\n")
                .append(toolResult);
        }

        if (approval.getExecutionError() != null) {
            text.append("\nError: ")
                .append(approval.getExecutionError());
        }

        return text.toString();
    }
```

The `SseEmitter` returned by `runAgent` is dropped: the run registers in the in-flight registry and the client attaches through `GET …/attach`. `publishDecision` sends `AI_HUB_TOOL_APPROVAL_APPROVED` or `_REJECTED` with `approvalId`, `chatId`, `toolName`, `componentName`, `requestedByUserId`, `decidedByUserId` — never `arguments`. `list(workspaceId, chatId)` loads the chat by `chatService.getById(chatId, workspaceId, currentUserId)` (ownership) then `approvalService.list(chatId)`.

- [ ] **Step 4: Supersede on a new turn, clean up on delete, request audit**

- In `AiHubChatServiceImpl.delete` add `approvalService.deleteByChat(chatId)` before `chatRepository.delete(chat)` (inject `ObjectProvider<AiHubToolApprovalService>` and call `getIfAvailable()`, null-guarded, so the four hand-built test constructors keep compiling with one extra `null` argument — update `AiHubChatServiceTest`, `AiHubAgentConversationRecorderTest`, and the two recorder IntTests).
- In `AiHubApiController.chat(...)`, after `enforceThreadOwnership` and before `runAgent`: when `verifiedThreadId != null`, resolve the chat and call `approvalService.supersedePending(chat.getId())` (inject `ObjectProvider<AiHubToolApprovalService>`). A superseded row is audited as `AI_HUB_TOOL_APPROVAL_REJECTED` with `reason=superseded`? No — superseding is not a decision; do not audit it. Metrics only (`metrics.record("superseded")` inside `supersedePending`).
- In `AiHubApprovalGateToolCallback` (Task 3) the `requested` outcome is only a metric; publish `AI_HUB_TOOL_APPROVAL_REQUESTED` from `AiHubToolApprovalServiceImpl.createPending` (inject the `@Nullable AiHubAuditPublisher`), payload `approvalId`, `chatId`, `toolName`, `componentName`, `requestedByUserId`.

- [ ] **Step 5: Write the GraphQL controller**

`AiHubToolApprovalGraphQlController` (same annotations as the rules controller): `@QueryMapping aiHubToolApprovals(@Argument long workspaceId, @Argument long chatId)` → `facade.list`; `@MutationMapping resolveAiHubToolApproval(@Argument long workspaceId, @Argument long approvalId, @Argument boolean approved, @Argument @Nullable String comment)` → `facade.resolve`; `@SchemaMapping` resolvers for `AiHubToolApproval.status`, `toolKind`, and the three `Instant` → `Long` epoch-millis fields (`decidedAt`, `expiresAt`, `createdDate`), mirroring `AiHubChatGraphQlController`'s `createdAt`/`updatedAt` resolvers. Hand-built unit test `AiHubToolApprovalGraphQlControllerTest` asserting delegation.

- [ ] **Step 6: Run the module tests**

```bash
./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:test :server:ee:libs:ai:ai-hub:ai-hub-rest:test :server:ee:libs:ai:ai-hub:ai-hub-graphql:test > /tmp/t6b.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t6b.log
```

Expected: `exit=0`.

- [ ] **Step 7: Manual continuation check**

Start the server (`./gradlew -p server/apps/server-app bootRun` with the dev infra up) and the client, open a BUILD chat, attach the Gmail connector, ask the agent to send an email. Verify: the card renders; Approve executes and a new assistant bubble streams below it; reload shows `APPROVED by …` on the card and the `[tool-approval #…]` line as a status line, not a bubble. This is the one step no test covers, because it exercises the session-memory advisor's handling of a server-originated user message.

- [ ] **Step 8: Commit**

```bash
./gradlew spotlessApply > /tmp/sa.log 2>&1; echo "exit=$?"
git add server/ee/libs/ai/ai-hub
git commit -m "Resolve an AI Hub tool approval by executing the stored call and continuing the turn" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

### Task 7: The chat owner's per-tool switch

**Files:**
- Modify: `ai-hub-api/.../chat/AiHubChatToolBinding.java` (add `boolean requiresApproval` as the LAST component), `AiHubChatToolFacade.java` + `AiHubChatToolFacadeImpl.java` (`setToolRequiresApproval`), `ai-hub-graphql/src/main/resources/graphql/ai-hub-chat-tool.graphqls`, `AiHubChatToolGraphQlController.java`
- Test: extend `ai-hub-service/src/test/.../chat/AiHubChatToolFacadeImplTest.java`

**Interfaces:**
- Produces: `AiHubChatToolFacade.setToolRequiresApproval(long chatToolId, boolean requiresApproval)`; GraphQL `setAiHubChatToolRequiresApproval(workspaceId: ID!, chatToolId: ID!, requiresApproval: Boolean!): AiHubChatToolBinding!`; `AiHubChatToolBinding.requiresApproval: Boolean!`; `AiHubUserConnectorTool.requiresApproval: Boolean!`.

- [ ] **Step 1: Extend the binding record**

Append `boolean requiresApproval` to `AiHubChatToolBinding` and fix every constructor call:

```bash
grep -rn "new AiHubChatToolBinding(" server/ee/libs/ai/ai-hub --include='*.java' | grep -v /build/
```

Production sites (in `AiHubChatToolFacadeImpl.listChatTools` / `listUserTools`) pass `tool.isRequiresApproval()`; test sites pass `false`.

- [ ] **Step 2: Failing facade test**

In `AiHubChatToolFacadeImplTest` add `testSetToolRequiresApprovalFlipsTheFlag`: given `toolRepository.findById(5L)` returns a tool with the flag false, calling `facade.setToolRequiresApproval(5L, true)` saves a tool whose `isRequiresApproval()` is true; an unknown id throws `NotFoundException`.

- [ ] **Step 3: Implement**

`AiHubChatToolFacadeImpl.setToolRequiresApproval`: load via `chatToolRepository.findById(chatToolId).orElseThrow(() -> new NotFoundException("AiHubChatTool", chatToolId))`, set, save. Schema: add `requiresApproval: Boolean!` to `AiHubChatToolBinding` (line 12-22) and to `AiHubUserConnectorTool` (line 145-151); add the mutation under `extend type Mutation`. Controller: copy `removeAiHubChatTool` (lines 216-228) — `currentUserId()`, `WorkspaceAccessGuard.verifyUserCanAccessWorkspace`, `findToolBindingOrThrow(workspaceId, userId, chatToolId)`, then `chatToolFacade.setToolRequiresApproval(existing.chatToolId(), requiresApproval)` and return the re-read binding. For user-global connector tools, extend `setAiHubUserConnectorToolEnabled`'s sibling: add `setAiHubUserConnectorToolRequiresApproval(workspaceId: ID!, connectorId: ID!, toolName: String!, requiresApproval: Boolean!): Boolean!` following the exact shape of `setAiHubUserConnectorToolEnabled` (find the tool through `chatToolRepository.findByChatComponentIdAndName`, ownership through the same connector lookup that mutation uses). Wherever `AiHubUserConnectorTool` is built (grep `new AiHubUserConnectorTool(`), pass `tool.isRequiresApproval()`.

- [ ] **Step 4: Run and commit**

```bash
./gradlew :server:ee:libs:ai:ai-hub:ai-hub-service:test --tests '*AiHubChatToolFacadeImplTest' :server:ee:libs:ai:ai-hub:ai-hub-graphql:test > /tmp/t7.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t7.log
./gradlew spotlessApply > /tmp/sa.log 2>&1; echo "exit=$?"
git add server/ee/libs/ai/ai-hub
git commit -m "Let a chat owner require approval for an attached tool" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: Client — the card, the data part, resolution and continuation

**Files:**
- Create: `client/src/graphql/ai/aihub/tool-approval/aiHubToolApprovals.graphql`, `resolveAiHubToolApproval.graphql`
- Create: `client/src/shared/components/ai-chat/messages/ToolApprovalRequestMessage.tsx` + `tests/ToolApprovalRequestMessage.test.tsx`
- Modify: `client/src/shared/components/ai-chat/messages/aiChatDataComponents.tsx`, `toToolResultDataPart.ts:184-188`, `approvalResolutionContext.ts`
- Modify: `client/src/ee/pages/automation/ai-hub/runtime-providers/AiHubRuntimeProvider.tsx` (`resolveApproval` neighbourhood, lines 1711-1740; the attach effect at 1800-1917), `client/src/ee/pages/automation/ai-hub/messages/AiHubMessage.tsx:93-116`

**Interfaces:**
- Consumes: `attachToInFlightRun({onClose, subscriber, threadId})` and `buildAiHubSubscriber(...)` from `inFlightRunClient.ts` / the provider; `useAiHubStore.addMessage`; `aiHubChatsStore.setActivityState/clearActivityState`; generated `useResolveAiHubToolApprovalMutation`, `useAiHubToolApprovalsQuery`.
- Produces: `ToolApprovalRequestDataI {approvalId: number; arguments: Record<string, unknown>; awaitingApproval: boolean; componentName?: string | null; expiresAt?: string | null; kind: 'tool-approval-request'; toolName: string}`; `ApprovalResolutionContextI.resolveToolApproval?: (approvalId: number, approved: boolean, comment?: string) => Promise<void>`.

- [ ] **Step 1: Operations and codegen**

`client/src/graphql/ai/aihub/tool-approval/aiHubToolApprovals.graphql`:

```graphql
query aiHubToolApprovals($workspaceId: ID!, $chatId: ID!) {
    aiHubToolApprovals(workspaceId: $workspaceId, chatId: $chatId) {
        id
        chatId
        toolName
        componentName
        status
        decidedByUserId
        decidedAt
        comment
        expiresAt
        executionError
    }
}
```

`resolveAiHubToolApproval.graphql`:

```graphql
mutation resolveAiHubToolApproval($workspaceId: ID!, $approvalId: ID!, $approved: Boolean!, $comment: String) {
    resolveAiHubToolApproval(workspaceId: $workspaceId, approvalId: $approvalId, approved: $approved, comment: $comment) {
        continuationStarted
        runId
        approval {
            id
            status
            executionError
        }
    }
}
```

Run `cd client && npm run codegen`, commit the two `.graphql` files first (`client - Add the AI Hub tool approval operations`), then the regenerated `src/shared/middleware/graphql.ts` + `graphql-types.ts` (`client - Regenerate the GraphQL client`).

- [ ] **Step 2: Failing card test**

`client/src/shared/components/ai-chat/messages/tests/ToolApprovalRequestMessage.test.tsx` (copy the `withResolution` wrapper pattern from `tests/ApprovalRequestMessage.test.tsx`, providing `{resolveApproval: vi.fn(), resolveToolApproval}`):

```tsx
describe('ToolApprovalRequestMessage', () => {
    it('renders the tool name and arguments', () => {
        render(<ToolApprovalRequestMessage data={data()} />, {wrapper: withResolution(vi.fn())});

        expect(screen.getByText(/gmail\/sendEmail/)).toBeInTheDocument();
        expect(screen.getByText('to')).toBeInTheDocument();
        expect(screen.getByText('a@b.c')).toBeInTheDocument();
    });

    it('approves through the context and shows the approved state', async () => {
        const resolveToolApproval = vi.fn().mockResolvedValue(undefined);

        render(<ToolApprovalRequestMessage data={data()} />, {wrapper: withResolution(resolveToolApproval)});

        await userEvent.click(screen.getByRole('button', {name: 'Approve'}));

        expect(resolveToolApproval).toHaveBeenCalledWith(1234, true, undefined);
        expect(await screen.findByText(/Approved/)).toBeInTheDocument();
    });

    it('rejects with a comment', async () => {
        const resolveToolApproval = vi.fn().mockResolvedValue(undefined);

        render(<ToolApprovalRequestMessage data={data()} />, {wrapper: withResolution(resolveToolApproval)});

        await userEvent.type(screen.getByLabelText(/Comment/), 'not now');
        await userEvent.click(screen.getByRole('button', {name: 'Reject'}));

        expect(resolveToolApproval).toHaveBeenCalledWith(1234, false, 'not now');
    });

    it('renders a resolved overlay when the status is known', () => {
        render(<ToolApprovalRequestMessage data={{...data(), resolvedStatus: 'SUPERSEDED'}} />, {
            wrapper: withResolution(vi.fn()),
        });

        expect(screen.getByText(/Superseded/)).toBeInTheDocument();
        expect(screen.queryByRole('button', {name: 'Approve'})).not.toBeInTheDocument();
    });
});

const data = (): ToolApprovalRequestDataI => ({
    approvalId: 1234,
    arguments: {to: 'a@b.c'},
    awaitingApproval: true,
    componentName: 'gmail',
    kind: 'tool-approval-request',
    toolName: 'sendEmail',
});
```

Run: `cd client && npx vitest run src/shared/components/ai-chat/messages/tests/ToolApprovalRequestMessage.test.tsx` — expected: fails, module not found.

- [ ] **Step 3: Write the card and register the kind**

`ToolApprovalRequestMessage.tsx`: same skeleton as `ApprovalRequestMessage.tsx` (state `comment`, `resolved`, `submitError`, `submitting`; `useApprovalResolution()`), but no form variant and no resume mutation fallback. Props type `DataMessagePartProps<ToolApprovalRequestDataI & {resolvedStatus?: string; resolvedBy?: string}>`. Body: a header line `{componentName ? `${componentName}/${toolName}` : toolName}`; a two-column `<dl>` of `Object.entries(data.arguments)` with values rendered through `JSON.stringify` when not a string and collapsed behind a "Show more" button when longer than 200 characters; the optional comment `Textarea` (`id={`tool-approval-comment-${approvalId}`}`, label "Comment (optional)"); `Approve` / `Reject` buttons calling `approvalResolution?.resolveToolApproval?.(approvalId, approved, trimmedComment || undefined)`. If `resolvedStatus` is set OR `resolved !== null`, render the status line instead of the controls: `Approved — the tool ran.`, `Rejected.`, `Expired.`, `Superseded by a newer message.`, `Failed: {executionError}`, with `by {resolvedBy}` when present.

`aiChatDataComponents.tsx`: add `'tool-approval-request': (props: DataMessagePartProps<ToolApprovalRequestDataI>) => <ToolApprovalRequestMessage {...props} />` (keys stay alphabetical: it goes after `'select-property-option'`).

`toToolResultDataPart.ts`: in the payload-kind fallback at lines 184-188 add

```ts
    if (fallbackKind === 'tool-approval-request') {
        const payload = parseJson<ToolApprovalRequestDataI>(eventContent, 'tool-approval-request');

        if (payload && typeof payload.approvalId === 'number') {
            return {data: payload, ok: true, type: 'data-tool-approval-request'};
        }
    }
```

`approvalResolutionContext.ts`: add the optional `resolveToolApproval` member to `ApprovalResolutionContextI`.

- [ ] **Step 4: Implement resolution in the runtime provider**

In `AiHubRuntimeProvider.tsx`, next to `resolveApproval` (line 1711):

```tsx
    const resolveToolApprovalMutation = useResolveAiHubToolApprovalMutation();

    const resolveToolApproval = useCallback(
        async (approvalId: number, approved: boolean, comment?: string) => {
            const currentChatId = useAiHubStore.getState().chatId;

            if (currentChatId == null || currentWorkspaceId == null) {
                throw new Error('No active chat');
            }

            const result = await resolveToolApprovalMutation.mutateAsync({
                approvalId: String(approvalId),
                approved,
                comment,
                workspaceId: String(currentWorkspaceId),
            });

            aiHubChatsStore.getState().clearActivityState(currentChatId);

            if (!result.resolveAiHubToolApproval.continuationStarted) {
                return;
            }

            addMessage({content: '', role: 'assistant'});
            aiHubChatsStore.getState().setActivityState(currentChatId, 'running');
            attachToContinuation(currentChatId);
        },
        [addMessage, attachToContinuation, currentWorkspaceId, resolveToolApprovalMutation]
    );
```

`attachToContinuation(threadId)` is a new `useCallback` that does exactly what the attach effect at lines 1800-1917 does after its probe succeeds — build the subscriber with `buildAiHubSubscriber(...)` and call `attachToInFlightRun({onClose, subscriber, threadId})` — extracted into a shared function so the effect and this callback do not diverge. The synthetic `[tool-approval #…]` user message is NOT added locally: it arrives from session memory on the next reload, and during the live continuation only the assistant's reply matters. Extend the `approvalResolution` memo to `{resolveApproval, resolveToolApproval}`.

Reload painting: in the effect that loads messages for a chat (grep `aiHubChatMessages` / `setMessagesLoading(true)` in the provider), after messages are set, run `useAiHubToolApprovalsQuery` results through the loaded thread: for every assistant message content part of `type === 'data-tool-approval-request'`, look up the approval by `approvalId` and, when its status is not `PENDING`, set `resolvedStatus`/`resolvedBy` on the part's `data`. Simplest wiring: fetch the approvals list with the chat messages query (same `useEffect`), then map before `setMessages`.

Paused state: in `onToolCallResultEvent` (line 646), when the result parses with `kind === 'tool-approval-request'`, call `aiHubChatsStore.getState().setActivityState(subscriberChatId, 'paused')` — the store already refuses to overwrite `paused` with `running` and `onRunFinishedEvent` preserves it. The sidebar label for `paused` becomes "Needs your answer or approval".

- [ ] **Step 5: Status line for the synthetic message**

In `AiHubMessage.tsx`'s `AiHubUserMessage`, read the message text with assistant-ui's `useMessage((message) => message.content)`; if the first text part starts with `[tool-approval #`, render `<div className="mx-auto w-full max-w-[var(--thread-max-width)] px-3 py-1 text-xs text-muted-foreground" data-testid="tool-approval-status-line">{firstLine}</div>` instead of the bubble, where `firstLine` is the text up to the first `]` with the brackets stripped (e.g. `tool-approval #1234 approved and executed`). Add a test in `messages/tests/AiHubMessage.test.tsx` (create if absent, following `AiHubMessageContent.test.tsx`'s mocking of `@assistant-ui/react`) asserting the bubble is replaced.

- [ ] **Step 6: Run, check, commit**

```bash
cd client && npx vitest run src/shared/components/ai-chat src/ee/pages/automation/ai-hub/messages
cd client && npm run check
```

(tool timeout 600000). Expected: all green. Commit `client - Render and resolve AI Hub tool approval cards`.

### Task 9: Client — owner toggles on chips and connector rows

**Files:**
- Create: `client/src/graphql/ai/aihub/chat-tool/setAiHubChatToolRequiresApproval.graphql`, `setAiHubUserConnectorToolRequiresApproval.graphql`
- Modify: `client/src/graphql/ai/aihub/chat-tool/aiHubChatTools.graphql` (select `requiresApproval`), the user-connectors query (select `tools { … requiresApproval }`)
- Modify: `client/src/ee/pages/automation/ai-hub/tools/ChatToolChips.tsx`, `client/src/ee/pages/automation/ai-hub/context/AiHubConnectors.tsx` (`ConnectorRow`, ~lines 57-180)
- Test: `client/src/ee/pages/automation/ai-hub/tools/tests/ChatToolChips.test.tsx` (create if absent)

- [ ] **Step 1: Operations**

```graphql
mutation setAiHubChatToolRequiresApproval($workspaceId: ID!, $chatToolId: ID!, $requiresApproval: Boolean!) {
    setAiHubChatToolRequiresApproval(workspaceId: $workspaceId, chatToolId: $chatToolId, requiresApproval: $requiresApproval) {
        chatToolId
        requiresApproval
    }
}
```

```graphql
mutation setAiHubUserConnectorToolRequiresApproval($workspaceId: ID!, $connectorId: ID!, $toolName: String!, $requiresApproval: Boolean!) {
    setAiHubUserConnectorToolRequiresApproval(workspaceId: $workspaceId, connectorId: $connectorId, toolName: $toolName, requiresApproval: $requiresApproval)
}
```

Add `requiresApproval` to the selection sets of `aiHubChatTools` and of the user-connectors query's `tools` field. `npm run codegen`; commit operations, then generated client.

- [ ] **Step 2: Failing chip test**

`ChatToolChips.test.tsx`: mock `@/shared/middleware/graphql` with `vi.hoisted` refs for `useAiHubChatToolsQuery` (returning one binding with `requiresApproval: false`), `useRemoveAiHubChatToolMutation`, `useSetAiHubChatToolRequiresApprovalMutation` (`mutate: setRequiresApprovalMock`). Assert the chip renders a button with `aria-label="Require approval"`; clicking it calls `setRequiresApprovalMock` with `{chatToolId, requiresApproval: true, workspaceId}`; when the binding has `requiresApproval: true` the button's label is "Approval required" and the chip shows the `ShieldCheckIcon`.

- [ ] **Step 3: Implement**

In `ChatToolChips.tsx`, beside the existing remove `X` button on each chip, add a ghost icon button: `ShieldIcon` (not required) / `ShieldCheckIcon` (required) from `lucide-react`, `aria-label` as above, `onClick` → `setRequiresApprovalMutation.mutate({chatToolId: String(tool.chatToolId), requiresApproval: !tool.requiresApproval, workspaceId: String(workspaceId)}, {onSuccess: () => invalidate(chatId, workspaceId)})`. In `AiHubConnectors.tsx`'s per-tool rows (where `onToggleTool` lives), add a second `Switch` labelled "Require approval" wired to `useSetAiHubUserConnectorToolRequiresApprovalMutation` and invalidating `['aiHubUserConnectors']`.

- [ ] **Step 4: Run, check, commit**

```bash
cd client && npx vitest run src/ee/pages/automation/ai-hub/tools src/ee/pages/automation/ai-hub/context
cd client && npm run check
```

Commit `client - Let a chat owner flag an attached tool as requiring approval`.

---

### Task 10: Client — workspace Tool approvals settings page

**Files:**
- Create: `client/src/graphql/ai/aihub/tool-approval/aiHubToolApprovalRules.graphql`, `aiHubToolApprovalDefaultToolNames.graphql`, `createAiHubToolApprovalRule.graphql`, `deleteAiHubToolApprovalRule.graphql`
- Create: `client/src/ee/pages/automation/ai-hub/settings/ToolApprovals.tsx`, `tests/ToolApprovals.test.tsx`
- Modify: `client/src/routes.tsx` (lazy import near line 82; route in the workspace settings group near line 301; nav item near line 333)

- [ ] **Step 1: Operations**

Four documents matching the schema in Task 5 (rules query selects `id workspaceId toolKind componentName toolName mode`; defaults query selects the string list; create mutation returns the same rule fields; delete returns the boolean). Codegen; two commits.

- [ ] **Step 2: Failing page test**

`ToolApprovals.test.tsx`: mock the four generated hooks with `vi.hoisted`; render inside `MemoryRouter`; assert the "Built-in" section lists every default name with an "Exempt" switch, that toggling one calls `createAiHubToolApprovalRule` with `{mode: 'EXEMPT', toolKind: 'CATALOG', toolName, workspaceId}`, that an existing `EXEMPT` rule renders the switch on and toggling it off calls delete with the rule id, and that the "Workspace rules" section renders a `REQUIRE` component rule as `gmail / sendEmail` with a Delete button.

- [ ] **Step 3: Implement the page**

`ToolApprovals.tsx`: `const isFeatureFlagEnabled = useFeatureFlagsStore();` → `if (!isFeatureFlagEnabled('ff-ai-hub-tool-approvals')) return null;`. Read `currentWorkspaceId` from `useWorkspaceStore` (the same store `AiHubConnectors.tsx` uses — copy its import). Two cards:

1. **Built-in** — a table of `aiHubToolApprovalDefaultToolNames`, each row with a `Switch` labelled "Exempt" whose checked state is `rules.some((rule) => rule.toolKind === 'CATALOG' && rule.mode === 'EXEMPT' && rule.toolName === name)`; on → create an `EXEMPT` rule; off → delete that rule.
2. **Workspace rules** — the non-exempt-of-default rules in a table (`kind`, `component`, `tool`, `mode`, Delete) plus an "Add rule" `Dialog` with a `Select` for kind (`CATALOG` | `COMPONENT`), a text `Input` for component name (shown for `COMPONENT`), a text `Input` for tool name with helper text "Use `*` for every operation of the component", and a `Select` for mode. Submit → create mutation, invalidate `['aiHubToolApprovalRules']`.

Mutation controls are rendered only when the user holds `WORKSPACE_MANAGE` — use the same permission hook the Variables settings page uses (`grep -rn "VARIABLE_MANAGE" client/src/ee/pages/settings/automation/variables` and copy its hook import); otherwise render the tables read-only.

`routes.tsx`: `const AiHubToolApprovalsPage = lazy(() => import('@/ee/pages/automation/ai-hub/settings/ToolApprovals'));`; route `{element: <AiHubToolApprovalsPage />, path: 'ai-hub/tool-approvals'}` in the workspace settings group immediately after `ai/memories`; nav item `{href: 'ai-hub/tool-approvals', title: 'Tool Approvals'}` after the `AI Memories` item. Also add a `Tool approvals` entry to the sidebar's `More` menu in `AiHubChatsSidebar.tsx` (lines 592-657) linking to `/automation/settings/ai-hub/tool-approvals`.

- [ ] **Step 4: Run, check, commit**

```bash
cd client && npx vitest run src/ee/pages/automation/ai-hub/settings
cd client && npm run check
```

Commit `client - Add the workspace Tool approvals settings page`.

---

### Task 11: Docs

**Files:**
- Modify: `.agents/ai-hub.md` (new `### Tool approval gate (EE)` section after "AI Hub agent tool architecture"), `.agents/hitl-approvals.md` (replace the "AI Hub copilot chat is OUT of scope" line), `CLAUDE.md` (one pointer line in the "Agent HITL approvals" paragraph)

- [ ] **Step 1: Write the deep-dive section**

Record, in the style of the surrounding sections: the wrapper order and the single insertion point in `AiHubToolCallbackWrappers.wrap`; that gating is decided by `AiHubToolApprovalPolicy.decide` from defaults + workspace rules + owner flags with "owner flags only add"; that the envelope is an ordinary tool result so provider pairing rules hold; that resolution executes from the mutation under the resolver's context and starts a continuation whose user message carries `AiHubRunState.CONTINUATION_PREFIX`; one pending per chat and superseding on a new turn; fail-closed on persistence failure and why that differs from component rules; the four audit events; and the deviation list from this plan's header.

- [ ] **Step 2: Commit**

```bash
git add .agents/ai-hub.md .agents/hitl-approvals.md CLAUDE.md
git commit -m "Document the AI Hub tool approval gate" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## Self-review against the spec

| Spec section | Task |
|---|---|
| Policy: built-in defaults, workspace rules, chat owner switch; most restrictive wins; badge on gated rows | 2, 7, 9 |
| Wrapper in the wrap chain covering pinned, catalog, attached and MCP tools | 3 |
| One pending per turn → deferred envelope | 3 (per chat; deviation 1) |
| Envelope as tool result, `kind: tool-approval-request` | 3, 8 |
| Prompt paragraph | 4 |
| Fail closed on persistence failure | 3 |
| Mutation executes the stored arguments; requester context | 6 (resolver context; deviation 2) |
| Continuation turn with a synthetic user message | 6, 8 |
| Typing supersedes | 6 step 4 |
| Card, reload painting, sidebar paused state, tool panel badge, settings page | 8, 9, 10 |
| With shared sessions | deferred to the companion plan |
| Surface independence | the wrapper takes `AiHubToolApprovalPolicy`/`AiHubToolApprovalService`/`AiHubChatService` interfaces; a Copilot adopter supplies its own — no extra abstraction is introduced until a second surface exists (YAGNI) |
| Audit events and metrics | 5, 6, 3 |
| Distributed EE note | no code; recorded in Task 11 |
| Error handling table | 3 (refuse), 6 (expired, failed, in-flight, optimistic `version`) |
| Testing list | every task |
| Rollout flag `ff-ai-hub-tool-approvals` | 10 (settings page); the card itself is not flagged — a server that creates rows must always render a card, per the spec's own rollout note |

Type consistency checked: `AiHubToolApprovalPolicy.decide(...)` / `Decision.isGated` is the final API (Task 2 step 5 supersedes the earlier `gatedToolNames` sketch, and Task 3 calls `decide`); `AiHubToolApproval.ToolKind` is the single enum for both tables; `AiHubToolApprovalFacade.Resolution(approval, continuationStarted, runId)` matches the GraphQL `AiHubToolApprovalResolution` type; `AiHubRunState.CONTINUATION_PREFIX` matches the client's `[tool-approval #` check and the prompt text.

