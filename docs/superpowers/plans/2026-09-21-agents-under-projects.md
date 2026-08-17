# Agents Under Projects Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make an AI Agent a member of an ordinary, user-visible Project (next to that project's workflows), and replace the dedicated Agents / Agent Deployments pages with an Agents tab under Projects.

**Architecture:** `ai_agent` gains a `project_workflow_uuid` pointer to its generated workflow, and `project_workflow` gains a `type` marker (`WORKFLOW` / `AI_AGENT`) that replaces the `__AI_AGENT__` project-name prefix as the way listings tell generated agent workflows from user workflows. Two small SPIs in `automation-configuration-api` (`ProjectPublishPreListener`, `ProjectDeleteEventListener`) let the agent module take part in project publish and delete without `automation-configuration` depending on it. The client mounts the existing agent list and agent detail components under `/automation/projects/...` and deletes the standalone pages.

**Tech Stack:** Java 25, Spring Boot 4, Spring Data JDBC, Liquibase, Spring GraphQL, JUnit 5 + Testcontainers; React 19, TypeScript, react-router, TanStack Query, GraphQL codegen, Vitest.

**Spec:** `docs/superpowers/specs/2026-09-21-agents-under-projects-design.md`

**Base branch:** the integration branch that contains `server/libs/automation/automation-ai/automation-ai-agent/`. This plan does not apply to `master`, which has no `AiAgent` entity. Create the working branch/worktree from that integration branch before Task 1.

## Global Constraints

- `project_workflow.type` is `INT`, not null, default `0`; ordinals are `WORKFLOW = 0`, `AI_AGENT = 1`; append-only enum, read through a range-checked `toEnum`.
- `ai_agent.project_workflow_uuid` is `${uuid_type}`, not null, unique. It is added by editing `00000000000001_automation_ai_agent_init.xml` **in place** (the `ai_agent` tables are unreleased). No data migration.
- `project_workflow` is a released table: its column is added by a **new** changeset with a `MARK_RAN` precondition and an empty `<rollback/>`.
- No redirects from the removed client routes.
- Agent facade gates stay on the `AGENT_VIEW` / `AGENT_CREATE` / `AGENT_EDIT` / `AGENT_DELETE` scopes. `AiAgentFacadeAuthorizationTest` pins every `@PreAuthorize` string by exact method signature — every facade signature change in this plan must be mirrored there in the same task.
- Sub-agent and call-workflow references stay workspace-wide; do not add project-boundary checks.
- Java style (CLAUDE.md): blank line before control statements, blank line between a variable modification and its use, no chained calls outside the allowed fluent APIs, descriptive variable names, no `TODO:` comments, no trailing blank line in class bodies. Run `./gradlew spotlessApply` before every server commit.
- Client style (CLAUDE.md): object keys sorted ascending, interfaces end in `I` or `Props`, `Icon`-suffixed lucide imports, `twMerge` not `cn`, hook ordering (`useState` → `useRef` → stores → custom hooks → memo → `useEffect`). Run `npm run format` before every client commit.
- Commit messages: server `<ticket> <description>`, client `<ticket> client - <description>`. Use the ticket number of the issue filed for this spec; the examples below write it as `NNNN`. No `Co-Authored-By` trailer, no "Generated with" line.
- Integration tests run with `testIntegration` (it includes `**/*IntTest*`); unit tests with `test`. Never run two Gradle builds at once in one worktree. Read the Gradle exit code directly, not through a pipe.
- Dev databases that already applied the old `ai_agent` changeset must be reset before booting: `DROP TABLE ai_agent_tag, ai_agent_element, ai_agent_channel, ai_agent;` and `DELETE FROM databasechangelog WHERE id = '00000000000001' AND filename LIKE '%automation_ai_agent_init%';`.

## File Structure

Paths below use these abbreviations:

- `CONF-API` = `server/libs/automation/automation-configuration/automation-configuration-api/src/main/java/com/bytechef/automation/configuration`
- `CONF-SVC` = `server/libs/automation/automation-configuration/automation-configuration-service/src/main/java/com/bytechef/automation/configuration`
- `CONF-SVC-TEST` = same module, `src/test/java/com/bytechef/automation/configuration`
- `CONF-GQL` = `server/libs/automation/automation-configuration/automation-configuration-graphql/src/main`
- `AGENT-API` = `server/libs/automation/automation-ai/automation-ai-agent/automation-ai-agent-api/src/main/java/com/bytechef/automation/ai/agent`
- `AGENT-SVC` = `server/libs/automation/automation-ai/automation-ai-agent/automation-ai-agent-service/src/main/java/com/bytechef/automation/ai/agent`
- `AGENT-SVC-TEST` = same module, `src/test/java/com/bytechef/automation/ai/agent`
- `AGENT-GQL` = `server/libs/automation/automation-ai/automation-ai-agent/automation-ai-agent-graphql/src/main`
- `AGENT-TOOL` = `server/libs/automation/automation-ai/automation-ai-tool/src/main/java/com/bytechef/automation/ai/tool/aiagent`

| File | Responsibility |
|---|---|
| Create `CONF-API/domain/ProjectWorkflowType.java` | The `WORKFLOW` / `AI_AGENT` marker enum |
| Modify `CONF-API/domain/ProjectWorkflow.java` | `type` column, range-checked getter, constructors |
| Create `.../config/liquibase/changelog/automation/configuration/20260921100000_automation_configuration_added_project_workflow_type.xml` | Adds `project_workflow.type` |
| Modify `CONF-API/service/ProjectWorkflowService.java`, `CONF-SVC/service/ProjectWorkflowServiceImpl.java` | Typed `addWorkflow`; `publishWorkflow` carries `type` |
| Create `CONF-API/listener/ProjectPublishPreListener.java` | SPI: run before a project version is published |
| Create `CONF-API/listener/ProjectDeleteEventListener.java` | SPI: run before a project is deleted |
| Modify `CONF-SVC/service/ProjectServiceImpl.java`, `CONF-SVC/facade/ProjectFacadeImpl.java` | Invoke the two SPIs; workflow-level `type` filters |
| Modify `CONF-SVC/facade/ProjectWorkflowFacadeImpl.java`, `CONF-SVC/facade/ProjectDeploymentFacadeImpl.java`, `CONF-SVC/subflow/SubflowDataSourceImpl.java`, `CONF-GQL/java/.../ProjectWorkflowGraphQlController.java` | Workflow-level `type` filters |
| Modify `CONF-API/domain/SystemProjects.java` | Drop `AI_AGENT_NAME_PREFIX` |
| Modify `AGENT-API/domain/AiAgent.java`, agent init changelog, `AGENT-SVC/service/AiAgentServiceImpl.java`, `AGENT-SVC/repository/AiAgentRepository.java` | `projectWorkflowUuid`; many agents per project |
| Modify `AGENT-API/facade/AiAgentFacade.java`, `AGENT-SVC/facade/AiAgentFacadeImpl.java`, `AGENT-SVC/subflow/CallableAiAgentDataSourceImpl.java` | Real projects, uuid-based workflow resolution, new delete semantics, narrowed deployment read model |
| Create `AGENT-SVC/event/AiAgentProjectPublishPreListener.java`, `AGENT-SVC/event/AiAgentProjectDeleteEventListener.java` | Agent side of the two SPIs |
| Delete `AGENT-SVC/audit/AiAgentProjectAuditSubjectResolver.java` (+ its two tests) | Hidden-project audit relabelling is obsolete |
| Delete `server/ee/libs/automation/automation-ai/automation-ai-agent/` | Per-agent visibility is removed |
| Client: see Tasks 8–13 | Projects tabs, project-scoped agent route, sidebar section, deployments merge, page removal |

---

## Phase 1 — Server model

### Task 1: `project_workflow.type`

**Files:**
- Create: `CONF-API/domain/ProjectWorkflowType.java`
- Modify: `CONF-API/domain/ProjectWorkflow.java`
- Create: `server/libs/automation/automation-configuration/automation-configuration-service/src/main/resources/config/liquibase/changelog/automation/configuration/20260921100000_automation_configuration_added_project_workflow_type.xml`
- Modify: `CONF-API/service/ProjectWorkflowService.java`, `CONF-SVC/service/ProjectWorkflowServiceImpl.java:52-67,237-250`
- Test: `CONF-SVC-TEST/service/ProjectWorkflowServiceIntTest.java`

**Interfaces:**
- Produces: `enum ProjectWorkflowType { WORKFLOW, AI_AGENT }`; `ProjectWorkflowType ProjectWorkflow.getType()`; `void ProjectWorkflow.setType(ProjectWorkflowType)`; `ProjectWorkflow ProjectWorkflowService.addWorkflow(long projectId, int projectVersion, String workflowId, ProjectWorkflowType type)`.

- [ ] **Step 1: Write the failing tests**

Append to `ProjectWorkflowServiceIntTest` (it already has `projectRepository`, `projectWorkflowService`, `getProject()` and an `@AfterEach` cleanup):

```java
    @Test
    public void testAddWorkflowDefaultsToWorkflowType() {
        Project project = projectRepository.save(getProject());

        ProjectWorkflow projectWorkflow = projectWorkflowService.addWorkflow(
            Validate.notNull(project.getId(), "id"), project.getLastProjectVersion(), "workflow3");

        assertThat(projectWorkflow.getType()).isEqualTo(ProjectWorkflowType.WORKFLOW);
    }

    @Test
    public void testPublishWorkflowKeepsAgentTypeOnBothRows() {
        Project project = projectRepository.save(getProject());

        long projectId = Validate.notNull(project.getId(), "id");
        int initialVersion = project.getLastProjectVersion();

        ProjectWorkflow agentProjectWorkflow = projectWorkflowService.addWorkflow(
            projectId, initialVersion, "agentWorkflow1", ProjectWorkflowType.AI_AGENT);

        ProjectWorkflow projectWorkflowToPublish = projectWorkflowService.getProjectWorkflow(
            agentProjectWorkflow.getId());

        projectWorkflowToPublish.setProjectVersion(initialVersion + 1);
        projectWorkflowToPublish.setWorkflowId("agentWorkflow1_v2");

        projectWorkflowService.publishWorkflow(projectId, initialVersion, "agentWorkflow1", projectWorkflowToPublish);

        assertThat(projectWorkflowService.getProjectWorkflows(projectId, agentProjectWorkflow.getUuidAsString()))
            .hasSize(2)
            .extracting(ProjectWorkflow::getType)
            .containsOnly(ProjectWorkflowType.AI_AGENT);
    }
```

Add `import com.bytechef.automation.configuration.domain.ProjectWorkflowType;`.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :server:libs:automation:automation-configuration:automation-configuration-service:testIntegration --tests "com.bytechef.automation.configuration.service.ProjectWorkflowServiceIntTest"`
Expected: compilation failure — `ProjectWorkflowType` does not exist.

- [ ] **Step 3: Create the enum**

`ProjectWorkflowType.java` (copy the Apache license header from `ProjectWorkflow.java`):

```java
package com.bytechef.automation.configuration.domain;

/**
 * What a {@code project_workflow} row holds. {@link #AI_AGENT} rows are generated from an AI Agent's configuration and
 * are edited through the agent, never through the workflow editor, so workflow listings leave them out.
 *
 * @author Ivica Cardic
 */
public enum ProjectWorkflowType {

    // Persisted as INT ordinal - append new values at the end only.
    WORKFLOW, AI_AGENT
}
```

- [ ] **Step 4: Add the column to `ProjectWorkflow`**

After the `permissionExpression` field:

```java
    @Column("type")
    private int type = ProjectWorkflowType.WORKFLOW.ordinal();
```

Add two constructors beside the existing ones (keep the existing ones, they default to `WORKFLOW`):

```java
    public ProjectWorkflow(long projectId, int projectVersion, String workflowId, ProjectWorkflowType type) {
        this(projectId, projectVersion, workflowId);

        this.type = type.ordinal();
    }

    public ProjectWorkflow(
        long projectId, int projectVersion, String workflowId, UUID uuid, ProjectWorkflowType type) {

        this(projectId, projectVersion, workflowId, uuid);

        this.type = type.ordinal();
    }
```

Add accessors and the range-checked helper (same shape as `ApprovalTask.toEnum`):

```java
    public ProjectWorkflowType getType() {
        return toEnum(ProjectWorkflowType.values(), type, "type");
    }

    public void setType(ProjectWorkflowType type) {
        this.type = type.ordinal();
    }

    private static <T extends Enum<T>> T toEnum(T[] values, int ordinal, String propertyName) {
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalStateException(
                "Invalid %s value: %d, expected 0..%d".formatted(propertyName, ordinal, values.length - 1));
        }

        return values[ordinal];
    }
```

Add `", type=" + type +` to `toString()` after `projectVersion`.

- [ ] **Step 5: Add the changeset**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                   http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">
    <!--
        The empty <rollback/> is deliberate: a changeset guarded by a MARK_RAN precondition can be recorded as ran
        without having applied its change, so its auto-generated inverse (dropColumn) must never run.
    -->
    <changeSet id="20260921100000-1" author="Ivica Cardic">
        <preConditions onFail="MARK_RAN">
            <not>
                <columnExists tableName="project_workflow" columnName="type"/>
            </not>
        </preConditions>

        <addColumn tableName="project_workflow">
            <column name="type" type="INT" defaultValueNumeric="0">
                <constraints nullable="false"/>
            </column>
        </addColumn>
        <rollback/>
    </changeSet>
</databaseChangeLog>
```

The directory is pulled in by `includeAll` in `master.xml`; no master edit.

- [ ] **Step 6: Typed `addWorkflow` and type-preserving `publishWorkflow`**

`ProjectWorkflowService.java` — add:

```java
    ProjectWorkflow addWorkflow(long projectId, int projectVersion, String workflowId, ProjectWorkflowType type);
```

`ProjectWorkflowServiceImpl.java` — replace the body of the 3-arg `addWorkflow` and add the 4-arg one. Both carry the gate because a self-call does not pass through the security proxy:

```java
    @Override
    @PreAuthorize("hasPermission(#projectId, 'Project', 'WORKFLOW_CREATE')")
    public ProjectWorkflow addWorkflow(long projectId, int projectVersion, String workflowId) {
        return addWorkflow(projectId, projectVersion, workflowId, ProjectWorkflowType.WORKFLOW);
    }

    @Override
    @PreAuthorize("hasPermission(#projectId, 'Project', 'WORKFLOW_CREATE')")
    public ProjectWorkflow addWorkflow(
        long projectId, int projectVersion, String workflowId, ProjectWorkflowType type) {

        ProjectWorkflow savedProjectWorkflow = projectWorkflowRepository.save(
            new ProjectWorkflow(projectId, projectVersion, workflowId, type));

        Map<String, Object> data = new HashMap<>();

        data.put("projectId", String.valueOf(savedProjectWorkflow.getProjectId()));
        data.put("workflowId", savedProjectWorkflow.getWorkflowId());

        projectWorkflowAuditPublisher.publish(
            ProjectWorkflowAuditEvent.WORKFLOW_CREATED, savedProjectWorkflow.getId(), data);

        return savedProjectWorkflow;
    }
```

In `publishWorkflow`, the historical row must keep the marker — replace the constructor call:

```java
        projectWorkflow = new ProjectWorkflow(
            projectId, oldProjectVersion, oldWorkflowId, UUID.fromString(projectWorkflow.getUuidAsString()),
            projectWorkflow.getType());
```

Do **not** add `type` to the field-copy list in `update(...)`; the surviving row keeps its own value.

- [ ] **Step 7: Run to verify they pass**

Run the Step 2 command. Expected: PASS (all tests in the class).

- [ ] **Step 8: Commit**

```bash
./gradlew spotlessApply
git add server/libs/automation/automation-configuration
git commit -m "NNNN Add project_workflow.type marking generated AI agent workflows"
```

---

### Task 2: Project lifecycle SPIs

**Files:**
- Create: `CONF-API/listener/ProjectPublishPreListener.java`, `CONF-API/listener/ProjectDeleteEventListener.java`
- Modify: `CONF-SVC/service/ProjectServiceImpl.java:184-208`, `CONF-SVC/facade/ProjectFacadeImpl.java:130,151,176,205-242`
- Test: `CONF-SVC-TEST/service/ProjectServiceIntTest.java`, `CONF-SVC-TEST/facade/ProjectFacadeIntTest.java`

**Interfaces:**
- Produces: `void ProjectPublishPreListener.onBeforePublishProject(long projectId)`; `void ProjectDeleteEventListener.onBeforeDeleteProject(long projectId)`.

`ProjectServiceImpl` looks its listeners up through `applicationContext` at call time — the same way it already finds `ProjectGitSyncEventListener` — because a listener implementation depends on `ProjectService`, and constructor injection would be a bean cycle. `ProjectFacadeImpl` takes a constructor-injected list, the way it already takes `List<WorkflowPreDeleteListener>`; a delete listener must therefore never depend on `ProjectFacade`.

- [ ] **Step 1: Write the failing tests**

In `ProjectServiceIntTest`, add a recording listener and a test. Put the nested configuration inside the test class and add it to the class with `@Import(ProjectServiceIntTest.RecordingListenerConfiguration.class)` (keep the existing `@Import` entries):

```java
    @TestConfiguration
    static class RecordingListenerConfiguration {

        static final List<Long> PUBLISHED_PROJECT_IDS = new CopyOnWriteArrayList<>();

        @Bean
        ProjectPublishPreListener recordingProjectPublishPreListener() {
            return PUBLISHED_PROJECT_IDS::add;
        }
    }

    @Test
    public void testPublishProjectInvokesPublishPreListeners() {
        Project project = projectRepository.save(getProject());

        long projectId = Validate.notNull(project.getId(), "id");

        RecordingListenerConfiguration.PUBLISHED_PROJECT_IDS.clear();

        projectService.publishProject(projectId, "first release", false);

        assertThat(RecordingListenerConfiguration.PUBLISHED_PROJECT_IDS).containsExactly(projectId);
    }
```

If the class has no `getProject()` helper, copy the one from `ProjectWorkflowServiceIntTest`. In `ProjectFacadeIntTest`, mirror it for delete:

```java
    @TestConfiguration
    static class RecordingDeleteListenerConfiguration {

        static final List<Long> DELETED_PROJECT_IDS = new CopyOnWriteArrayList<>();

        @Bean
        ProjectDeleteEventListener recordingProjectDeleteEventListener() {
            return DELETED_PROJECT_IDS::add;
        }
    }

    @Test
    public void testDeleteProjectInvokesDeleteListenersFirst() {
        ProjectDTO projectDTO = projectFacade.createProject(
            ProjectDTO.builder()
                .name("listener-project")
                .workspaceId(workspace.getId())
                .build());

        RecordingDeleteListenerConfiguration.DELETED_PROJECT_IDS.clear();

        projectFacade.deleteProject(projectDTO.id());

        assertThat(RecordingDeleteListenerConfiguration.DELETED_PROJECT_IDS).containsExactly(projectDTO.id());
    }
```

Use whatever project-creation helper `ProjectFacadeIntTest` already uses if it differs from `createProject(ProjectDTO)`; the assertion is the point.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :server:libs:automation:automation-configuration:automation-configuration-service:testIntegration --tests "*ProjectServiceIntTest" --tests "*ProjectFacadeIntTest"`
Expected: compilation failure — the listener types do not exist.

- [ ] **Step 3: Create the SPIs**

```java
package com.bytechef.automation.configuration.listener;

/**
 * Callback invoked at the very start of {@code ProjectService#publishProject}, before the draft version is stamped as
 * published and before its workflows are duplicated into the next draft.
 * <p>
 * A feature that generates workflows inside a project implements this to bring those drafts up to date, so that the
 * published snapshot is never older than the configuration it was generated from. An implementation runs inside the
 * caller's transaction and under the caller's security context, and throws to abort the publish.
 *
 * @author Ivica Cardic
 */
public interface ProjectPublishPreListener {

    void onBeforePublishProject(long projectId);
}
```

```java
package com.bytechef.automation.configuration.listener;

/**
 * Callback invoked at the very start of {@code ProjectFacade#deleteProject(long)}, before anything else touches the
 * project's rows.
 * <p>
 * A feature that owns rows referencing a project implements this to remove those rows itself; the foreign keys
 * pointing at {@code project} do not cascade. An implementation runs inside the caller's transaction and under the
 * caller's security context, throws rather than swallows, and must not depend on {@code ProjectFacade}.
 *
 * @author Ivica Cardic
 */
public interface ProjectDeleteEventListener {

    void onBeforeDeleteProject(long projectId);
}
```

- [ ] **Step 4: Invoke them**

`ProjectServiceImpl.publishProject` — insert as the first statements of the method body:

```java
        for (ProjectPublishPreListener projectPublishPreListener : applicationContext
            .getBeansOfType(ProjectPublishPreListener.class)
            .values()) {

            projectPublishPreListener.onBeforePublishProject(id);
        }

```

The rest of the method (`Project project = getProject(id); ...`) is unchanged and must stay *after* the loop, so it loads the project a listener may have touched.

`ProjectFacadeImpl` — add a field `private final List<ProjectDeleteEventListener> projectDeleteEventListeners;`, add a constructor parameter `List<ProjectDeleteEventListener> projectDeleteEventListeners` next to `workflowPreDeleteListeners`, assign it, and insert as the first statements of `deleteProject`:

```java
        for (ProjectDeleteEventListener projectDeleteEventListener : projectDeleteEventListeners) {
            projectDeleteEventListener.onBeforeDeleteProject(id);
        }

```

Fix every hand-constructed `ProjectFacadeImpl` in tests (`grep -rn "new ProjectFacadeImpl(" server`) by passing `List.of()`.

- [ ] **Step 5: Run to verify they pass**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add server/libs/automation/automation-configuration
git commit -m "NNNN Add project publish and delete listener SPIs"
```

---

### Task 3: Agent schema — `project_workflow_uuid`, many agents per project

**Files:**
- Modify: `server/libs/automation/automation-ai/automation-ai-agent/automation-ai-agent-service/src/main/resources/config/liquibase/changelog/automation/ai_agent/00000000000001_automation_ai_agent_init.xml`
- Modify: `AGENT-API/domain/AiAgent.java`, `AGENT-SVC/service/AiAgentServiceImpl.java:82-96`, `AGENT-SVC/repository/AiAgentRepository.java:41`, `AGENT-API/service/AiAgentService.java`
- Delete: `AGENT-SVC/audit/AiAgentProjectAuditSubjectResolver.java`, `AGENT-SVC-TEST/audit/AiAgentProjectAuditSubjectIntTest.java`, `AGENT-SVC-TEST/audit/AiAgentProjectAuditSubjectResolverTest.java`
- Test: `AGENT-SVC-TEST/service/AiAgentServiceIntTest.java`

**Interfaces:**
- Produces: `UUID AiAgent.getProjectWorkflowUuid()`, `void AiAgent.setProjectWorkflowUuid(UUID)`; `List<AiAgent> AiAgentRepository.findAllByProjectId(long projectId)`; `List<AiAgent> AiAgentService.getProjectAgents(long projectId)`. `findByProjectId` is removed.

- [ ] **Step 1: Write the failing test**

Append to `AiAgentServiceIntTest`, reusing whatever helper the class already uses to build a valid `AiAgent` (it must set `name`, `title`, `uuid`, `projectId`); add `setProjectWorkflowUuid(UUID.randomUUID())` to that helper, then:

```java
    @Test
    void testGetProjectAgentsReturnsEveryAgentOfTheProject() {
        AiAgent firstAgent = agentService.create(newAgent("first-agent", projectId));
        AiAgent secondAgent = agentService.create(newAgent("second-agent", projectId));

        assertThat(agentService.getProjectAgents(projectId))
            .extracting(AiAgent::getId)
            .containsExactlyInAnyOrder(firstAgent.getId(), secondAgent.getId());
    }

    @Test
    void testUpdateKeepsProjectWorkflowUuid() {
        AiAgent agent = agentService.create(newAgent("keeps-uuid", projectId));
        UUID projectWorkflowUuid = agent.getProjectWorkflowUuid();

        agent.setTitle("Renamed");

        AiAgent updatedAgent = agentService.update(agent);

        assertThat(updatedAgent.getProjectWorkflowUuid()).isEqualTo(projectWorkflowUuid);
    }
```

`newAgent(String name, long projectId)` and `projectId` stand for the class's existing fixture; rename to match it.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :server:libs:automation:automation-ai:automation-ai-agent:automation-ai-agent-service:testIntegration --tests "*AiAgentServiceIntTest"`
Expected: compilation failure — `getProjectAgents` / `setProjectWorkflowUuid` do not exist.

- [ ] **Step 3: Edit the init changelog in place**

Directly after the `project_id` column inside `<createTable tableName="ai_agent">`:

```xml
            <!-- The agent's generated workflow, by project_workflow.uuid — stable across project versions, which a
                 workflow_id is not. A project holds any number of agents and workflows, so project_id alone no
                 longer identifies it. Schema is unreleased, so the column is added to the init changelog in place. -->
            <column name="project_workflow_uuid" type="${uuid_type}">
                <constraints nullable="false" unique="true" uniqueConstraintName="uk_ai_agent_project_workflow_uuid"/>
            </column>
```

- [ ] **Step 4: Domain, repository, service**

`AiAgent.java` — after `projectId`:

```java
    @Column("project_workflow_uuid")
    private UUID projectWorkflowUuid;
```

with `getProjectWorkflowUuid()` / `setProjectWorkflowUuid(UUID projectWorkflowUuid)`. Update the class javadoc's first sentence: an agent is "a member of a project" rather than "project-scoped … hidden".

`AiAgentRepository.java` — replace `Optional<AiAgent> findByProjectId(long projectId);` with:

```java
    List<AiAgent> findAllByProjectId(long projectId);
```

`AiAgentService.java` / `AiAgentServiceImpl.java` — add:

```java
    List<AiAgent> getProjectAgents(long projectId);
```

```java
    @Override
    @Transactional(readOnly = true)
    public List<AiAgent> getProjectAgents(long projectId) {
        return agentRepository.findAllByProjectId(projectId);
    }
```

In `AiAgentServiceImpl.update(AiAgent)` add `curAgent.setProjectWorkflowUuid(agent.getProjectWorkflowUuid());` to the field-copy block (use the local variable name the method already uses for the loaded agent).

- [ ] **Step 5: Delete the audit subject resolver**

Delete the three files listed above. Then `grep -rn "AiAgentProjectAuditSubjectResolver" server` and remove the remaining javadoc mentions (`CONF-API/audit/ProjectAuditSubjectResolver.java`, `CONF-SVC/audit/ProjectAuditPublisher.java`) by rewording them to name no agent-specific implementation.

- [ ] **Step 6: Run to verify it passes**

Run the Step 2 command. Expected: PASS. (`AiAgentFacadeIntTest` is expected to be red until Task 4 — do not run it yet.)

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add server/libs/automation/automation-ai/automation-ai-agent server/libs/automation/automation-configuration
git commit -m "NNNN Point AI agents at their generated workflow by project workflow uuid"
```

---

### Task 4: Agent facade on ordinary projects

**Files:**
- Modify: `AGENT-API/facade/AiAgentFacade.java:48,177`, `AGENT-SVC/facade/AiAgentFacadeImpl.java` (`createAgent` 258-308, `importAgent` 490-546, `getDraftWorkflowId` 1002-1010, `regenerateAndSaveWorkflow` 1215-1230, `getVersionWorkflowId` 1337-1352, `toAgentDTO` 1356-1404, class javadoc 100-169)
- Modify: `AGENT-SVC/subflow/CallableAiAgentDataSourceImpl.java:134,170-180`
- Modify: `CONF-API/domain/SystemProjects.java:67-72,105-107`
- Modify: `AGENT-GQL/resources/graphql/ai-agent.graphqls`, `AGENT-GQL/java/.../AiAgentGraphQlController.java` (`CreateAiAgentInput` :467, `AiAgentPayload` :292-380, `importAiAgent` :269)
- Modify: `AGENT-TOOL/CreateAiAgentToolCallback.java`, `AGENT-TOOL/ListAiAgentsToolCallback.java`
- Test: `AGENT-SVC-TEST/facade/AiAgentFacadeIntTest.java`, `AGENT-SVC-TEST/facade/AiAgentFacadeAuthorizationTest.java`, `CONF-API` test `domain/SystemProjectsTest.java`

**Interfaces:**
- Consumes: `ProjectWorkflowService.addWorkflow(long, int, String, ProjectWorkflowType)` (Task 1); `AiAgent.setProjectWorkflowUuid(UUID)` (Task 3).
- Produces: `AiAgentDTO createAgent(String title, String description, long workspaceId, @Nullable Long projectId)`; `AiAgentDTO importAgent(long workspaceId, String json, @Nullable Long projectId)`; GraphQL `CreateAiAgentInput.projectId: ID`, `importAiAgent(workspaceId, json, projectId)`, `AiAgent.projectWorkflowUuid: String!`.

- [ ] **Step 1: Rewrite the pinned creation test and add the shared-project tests**

In `AiAgentFacadeIntTest`, replace `testCreateAgentProvisionsHiddenProjectAndDraftWorkflow` with:

```java
    @Test
    void testCreateAgentWithoutProjectProvisionsVisibleProjectNamedAfterAgent() {
        AiAgentDTO agentDTO = agentFacade.createAgent("Support Bot", "Handles support questions", workspaceId, null);

        AiAgent agent = agentDTO.agent();
        Project project = projectService.getProject(agent.getProjectId());

        assertThat(project.getName()).isEqualTo("Support Bot");
        assertThat(SystemProjects.isSystemProject(project)).isFalse();

        ProjectWorkflow projectWorkflow = projectWorkflowService
            .fetchProjectWorkflow(
                project.getId(), project.getLastProjectVersion(), String.valueOf(agent.getProjectWorkflowUuid()))
            .orElseThrow();

        assertThat(projectWorkflow.getType()).isEqualTo(ProjectWorkflowType.AI_AGENT);
        assertThat(agentDTO.unpublishedChanges()).isTrue();
        assertThat(agentDTO.lastPublishedVersion()).isZero();
    }

    @Test
    void testCreateAgentSuffixesProjectNameOnCollision() {
        agentFacade.createAgent("Support Bot", null, workspaceId, null);

        AiAgentDTO secondAgentDTO = agentFacade.createAgent("Support Bot", null, workspaceId, null);

        Project secondProject = projectService.getProject(
            secondAgentDTO.agent()
                .getProjectId());

        assertThat(secondProject.getName()).isEqualTo("Support Bot (2)");
    }

    @Test
    void testTwoAgentsAndAWorkflowShareOneProject() {
        AiAgentDTO firstAgentDTO = agentFacade.createAgent("First", null, workspaceId, null);

        long projectId = firstAgentDTO.agent()
            .getProjectId();

        AiAgentDTO secondAgentDTO = agentFacade.createAgent("Second", null, workspaceId, projectId);

        Project project = projectService.getProject(projectId);

        Workflow userWorkflow = workflowService.create(
            "{\"label\":\"User workflow\",\"tasks\":[]}", Workflow.Format.JSON, Workflow.SourceType.JDBC);

        projectWorkflowService.addWorkflow(projectId, project.getLastProjectVersion(), userWorkflow.getId());

        assertThat(secondAgentDTO.agent()
            .getProjectId()).isEqualTo(projectId);
        assertThat(agentFacade.getDraftWorkflowId(firstAgentDTO.agent()
            .getId()))
                .isNotEqualTo(agentFacade.getDraftWorkflowId(secondAgentDTO.agent()
                    .getId()));

        // Every draft-affecting mutation regenerates the agent's own workflow; with three workflows in the project
        // this is what getVersionWorkflowId's "exactly one" assumption used to reject.
        agentFacade.updateAgent(
            firstAgentDTO.agent()
                .getId(),
            "First", null, "You are the first agent");

        assertThat(draftDefinition(firstAgentDTO.agent())).contains("You are the first agent");
        assertThat(draftDefinition(secondAgentDTO.agent())).doesNotContain("You are the first agent");
    }

    @Test
    void testCreateAgentRejectsProjectOfAnotherWorkspace() {
        Workspace otherWorkspace = workspaceRepository.save(new Workspace("other-workspace"));

        AiAgentDTO otherAgentDTO = agentFacade.createAgent("Other", null, otherWorkspace.getId(), null);

        long otherProjectId = otherAgentDTO.agent()
            .getProjectId();

        assertThatThrownBy(() -> agentFacade.createAgent("Intruder", null, workspaceId, otherProjectId))
            .isInstanceOf(IllegalArgumentException.class);
    }
```

Change the private helpers `draftDefinition(long projectId)` / `draftWorkflowId(long projectId)` to take the `AiAgent` and resolve through `agentFacade.getDraftWorkflowId(agent.getId())`; update their call sites. Update every other `createAgent(...)` / `importAgent(...)` call in the test tree with a trailing `null` (`grep -rn "createAgent(\|importAgent(" server --include="*Test.java"`). Make sure the `@AfterEach` also deletes `other-workspace` (it already deletes all workspaces if it calls `workspaceRepository.deleteAll()`).

In `AiAgentFacadeAuthorizationTest`, change the `createAgent` and `importAgent` lookups to the new parameter lists: `("createAgent", String.class, String.class, long.class, Long.class)` and `("importAgent", long.class, String.class, Long.class)`.

In `SystemProjectsTest`, delete the assertions that name `AI_AGENT_NAME_PREFIX` and add:

```java
    @Test
    void testAgentNamedProjectIsNotASystemProject() {
        Project project = new Project();

        project.setName("__AI_AGENT__3f1c");

        assertThat(SystemProjects.isSystemProject(project)).isFalse();
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :server:libs:automation:automation-ai:automation-ai-agent:automation-ai-agent-service:testIntegration --tests "*AiAgentFacadeIntTest"`
Expected: compilation failure — `createAgent` has no 4-arg form.

- [ ] **Step 3: `SystemProjects`**

Delete the `AI_AGENT_NAME_PREFIX` constant and its javadoc, and remove it from `NAME_PREFIXES`:

```java
    private static final List<String> NAME_PREFIXES = List.of(
        KNOWLEDGE_BASE_NAME_PREFIX, CONTEXT_STORE_NAME_PREFIX, EMBEDDED_AUTOMATION_NAME_PREFIX,
        DATA_SYNC_NAME_PREFIX);
```

`DATA_SYNC_NAME_PREFIX`'s javadoc currently refers to `AI_AGENT_NAME_PREFIX` ("…the project exists only to hold the generated draft workflow…"); reword it to stand on its own. Then `grep -rn "AI_AGENT_NAME_PREFIX\|__AI_AGENT__" server client/src` and fix each hit: production javadoc/comments are reworded to describe agents as members of ordinary projects; tests that build a `__AI_AGENT__`-named project to prove it is hidden (`ProjectFacadeRowVisibilityTest`, `ProjectDeploymentFacadeTest`, `ProjectDeploymentServiceSystemProjectIntTest`, `ProjectServiceIntTest`, `SubflowDataSourceTest`, `ProjectAuditPublisherSubjectTest`, `WorkflowDeleteCascadeIntTest`, the three AI Hub tests) switch to `SystemProjects.DATA_SYNC_NAME_PREFIX` where they test hiding in general, or are deleted where they test agent hiding specifically.

- [ ] **Step 4: `createAgent` and `importAgent`**

`AiAgentFacade.java`:

```java
    AiAgentDTO createAgent(String title, String description, long workspaceId, @Nullable Long projectId);

    AiAgentDTO importAgent(long workspaceId, String json, @Nullable Long projectId);
```

`AiAgentFacadeImpl.createAgent` — replace lines 260-305 (project creation through `addWorkflow`) so that the method reads:

```java
    @Override
    @PreAuthorize("hasPermission(#workspaceId, 'Workspace', 'AGENT_CREATE')")
    public AiAgentDTO createAgent(String title, String description, long workspaceId, @Nullable Long projectId) {
        Objects.requireNonNull(title, "title");

        String name = uniqueName(slugify(title), workspaceId);

        Project project = projectId == null
            ? createAgentProject(title, description, workspaceId)
            : getWorkspaceProject(projectId, workspaceId);

        AiAgent agent = new AiAgent();

        agent.setName(name);
        agent.setTitle(title);
        agent.setDescription(description);
        agent.setWorkspaceId(workspaceId);
        agent.setProjectId(project.getId());
        agent.setUuid(UUID.randomUUID());

        // The workflow row is created first, from a placeholder definition, because ai_agent.project_workflow_uuid
        // is NOT NULL and the real definition cannot be generated until the agent has an id to generate from.
        Workflow workflow = workflowService.create(
            "{\"label\":\"" + name + "\",\"tasks\":[]}", Workflow.Format.JSON, Workflow.SourceType.JDBC);

        ProjectWorkflow projectWorkflow = projectWorkflowService.addWorkflow(
            project.getId(), project.getLastProjectVersion(), workflow.getId(), ProjectWorkflowType.AI_AGENT);

        agent.setProjectWorkflowUuid(projectWorkflow.getUuid());

        AiAgent savedAgent = agentService.create(agent);

        AiAgentChannel chatChannel = new AiAgentChannel(savedAgent.getId(), AiAgentChannelType.CHAT);

        chatChannel.setPosition(0);

        agentChannelService.create(chatChannel);

        AiAgentChannel workflowCallChannel = new AiAgentChannel(savedAgent.getId(), AiAgentChannelType.WORKFLOW_CALL);

        workflowCallChannel.setPosition(1);

        agentChannelService.create(workflowCallChannel);

        AiAgentElement chatMemoryElement = new AiAgentElement(savedAgent.getId(), AiAgentElement.KIND_CHAT_MEMORY);

        chatMemoryElement.setPosition(0);

        agentElementService.create(chatMemoryElement);

        regenerateAndSaveWorkflow(savedAgent);

        return toAgentDTO(savedAgent);
    }

    private Project createAgentProject(String title, @Nullable String description, long workspaceId) {
        Project project = new Project();

        project.setName(uniqueProjectName(title, workspaceId));
        project.setDescription(description);
        project.setWorkspaceId(workspaceId);

        return projectService.create(project);
    }

    private Project getWorkspaceProject(long projectId, long workspaceId) {
        Project project = projectService.getProject(projectId);

        if (!Objects.equals(project.getWorkspaceId(), workspaceId) || SystemProjects.isSystemProject(project) ||
            projectVisibilityFilter.filterVisible(List.of(project))
                .isEmpty()) {

            throw new IllegalArgumentException("Project " + projectId + " is not available in workspace " + workspaceId);
        }

        return project;
    }

    private String uniqueProjectName(String title, long workspaceId) {
        Set<String> projectNames = projectService.getProjects(null, null, null, null, null, workspaceId)
            .stream()
            .map(Project::getName)
            .collect(Collectors.toSet());

        if (!projectNames.contains(title)) {
            return title;
        }

        int suffix = 2;

        while (projectNames.contains(title + " (" + suffix + ")")) {
            suffix++;
        }

        return title + " (" + suffix + ")";
    }
```

`projectService.getProjects(Boolean, Long, Boolean, Long, Status, Long)` is the six-argument overload `ProjectFacadeImpl` already calls with a trailing `workspaceId`. If `regenerateAndSaveWorkflow` fails on the placeholder because `syncTestConnections` expects generated nodes, call `generateDefinition(savedAgent)` + `workflowService.update(workflow.getId(), definition, workflow.getVersion())` here instead and leave `syncTestConnections` to the first real mutation — that is what the old `createAgent` effectively did.

`importAgent` — add the `@Nullable Long projectId` parameter and pass it to `createAgent(...)`; the gate string is unchanged.

- [ ] **Step 5: Resolve the agent's workflow by uuid**

Replace `getVersionWorkflowId(long projectId, int projectVersion)` with:

```java
    /**
     * Resolves the agent's generated workflow in {@code projectVersion}. A project holds any number of workflows and
     * agents, so the agent's own row is found by {@code project_workflow.uuid}, which is stable across versions.
     */
    private String getVersionWorkflowId(AiAgent agent, int projectVersion) {
        return projectWorkflowService
            .fetchProjectWorkflow(
                agent.getProjectId(), projectVersion, String.valueOf(agent.getProjectWorkflowUuid()))
            .map(ProjectWorkflow::getWorkflowId)
            .orElseThrow(() -> new IllegalStateException(
                "Agent " + agent.getId() + " has no workflow in project " + agent.getProjectId() + " version "
                    + projectVersion));
    }
```

and update its three callers: `getDraftWorkflowId` (`getVersionWorkflowId(agent, project.getLastProjectVersion())`), `regenerateAndSaveWorkflow` (same), `toAgentDTO` (draft: `project.getLastProjectVersion()`, published: `lastPublishedVersion`).

`toAgentDTO` has one more shared-project case: an agent added *after* the project's last publish has no row in the published version. Wrap the published lookup:

```java
            Optional<ProjectWorkflow> publishedProjectWorkflow = projectWorkflowService.fetchProjectWorkflow(
                project.getId(), lastPublishedVersion, String.valueOf(agent.getProjectWorkflowUuid()));

            if (publishedProjectWorkflow.isEmpty()) {
                unpublishedChanges = true;
                lastPublishedVersion = 0;
                publishedDate = null;
            } else {
                // existing definition comparison, using publishedProjectWorkflow.get().getWorkflowId()
            }
```

`lastPublishedVersion` and `publishedDate` are assigned inside the branches, so declare them without initial values as today. Add a test for it:

```java
    @Test
    void testAgentAddedAfterProjectPublishReportsNeverPublished() {
        AiAgentDTO firstAgentDTO = createPublishableAgent("First");

        agentFacade.publishAgent(firstAgentDTO.agent()
            .getId(), "v1");

        AiAgentDTO lateAgentDTO = agentFacade.createAgent(
            "Late", null, workspaceId, firstAgentDTO.agent()
                .getProjectId());

        assertThat(lateAgentDTO.lastPublishedVersion()).isZero();
        assertThat(lateAgentDTO.unpublishedChanges()).isTrue();
    }
```

`createPublishableAgent` stands for the helper the class already uses before its `publishAgent` calls (an agent with a MODEL element); use its real name.

Apply the same replacement in `CallableAiAgentDataSourceImpl` (its private `getVersionWorkflowId` at :170 and the call at :134): resolve through `fetchProjectWorkflow(agent.getProjectId(), projectVersion, String.valueOf(agent.getProjectWorkflowUuid()))`.

- [ ] **Step 6: GraphQL and tools**

`ai-agent.graphqls`: add `projectId: ID` to `input CreateAiAgentInput`, add `projectWorkflowUuid: String!` to `type AiAgent`, and change the mutation to `importAiAgent(workspaceId: ID!, json: String!, projectId: ID): AiAgent!` (keep its existing return type).

`AiAgentGraphQlController`: `record CreateAiAgentInput(String title, @Nullable String description, long workspaceId, @Nullable Long projectId)`; pass `input.projectId()` to `createAgent`; add `@Argument @Nullable Long projectId` to `importAiAgent` and pass it through; add to `AiAgentPayload`:

```java
        public String projectWorkflowUuid() {
            return String.valueOf(agentDTO.agent()
                .getProjectWorkflowUuid());
        }
```

(match the accessor style the record already uses for `uuid()`.) Update `AiAgentGraphQlControllerTest` and `src/test/resources/graphql/test.graphqls` for the new input field.

`CreateAiAgentToolCallback`: add to `INPUT_SCHEMA.properties` `"projectId": {"type": "integer", "description": "Optional id of an existing project to add the agent to. When omitted a new project named after the agent is created."}`, extend the record to `CreateAiAgentInput(String title, @Nullable String description, @Nullable Long projectId)`, pass `input.projectId()` to `createAgent`, and extend the output to `CreateAiAgentOutput(Long id, String name, String title, long projectId)`. Rewrite the first sentence of `DESCRIPTION`: "Create a new AI Agent in the current workspace, inside an existing project (projectId) or a new project named after the agent."

`ListAiAgentsToolCallback`: add `long projectId` to `AiAgentSummary` (from `agent.getProjectId()`), mention it in `DESCRIPTION`, and update `ListAiAgentsToolCallbackTest`.

- [ ] **Step 7: Rewrite the class javadoc**

In `AiAgentFacadeImpl`'s class javadoc, replace the first paragraph (raw services / hidden `__AI_AGENT__` project) with: the agent's generated workflow lives in an ordinary project as a `ProjectWorkflowType.AI_AGENT` row found by `ai_agent.project_workflow_uuid`; draft mutations use the raw `WorkflowService` because the generated workflow is never edited through `ProjectWorkflowFacade`. In the **Visibility** paragraph replace "its hidden `__AI_AGENT__` project's" with "its project's". Leave the **Authorization** paragraphs as they are.

- [ ] **Step 8: Run to verify they pass**

```bash
./gradlew :server:libs:automation:automation-ai:automation-ai-agent:automation-ai-agent-service:test :server:libs:automation:automation-ai:automation-ai-agent:automation-ai-agent-service:testIntegration
./gradlew :server:libs:automation:automation-ai:automation-ai-agent:automation-ai-agent-graphql:test :server:libs:automation:automation-ai:automation-ai-tool:test
./gradlew :server:libs:automation:automation-configuration:automation-configuration-api:test :server:libs:automation:automation-configuration:automation-configuration-service:test :server:libs:automation:automation-configuration:automation-configuration-service:testIntegration
```

Expected: PASS. Then `./gradlew clean compileJava compileTestJava` — every other module that names the removed prefix or the old signatures must compile.

- [ ] **Step 9: Commit**

```bash
./gradlew spotlessApply
git add server
git commit -m "NNNN Create AI agents inside ordinary projects"
```

---

### Task 5: Hide generated agent workflows from workflow listings

Dropping the prefix made agent projects visible; these sites must now skip `AI_AGENT` rows individually.

**Files:**
- Modify: `CONF-SVC/facade/ProjectWorkflowFacadeImpl.java:393-437`, `CONF-SVC/facade/ProjectFacadeImpl.java:636-645,959`, `CONF-SVC/facade/ProjectDeploymentFacadeImpl.java:506-510`, `CONF-SVC/subflow/SubflowDataSourceImpl.java:135-149`, `CONF-GQL/java/com/bytechef/automation/configuration/web/graphql/ProjectWorkflowGraphQlController.java:116-121`
- Test: `CONF-SVC-TEST/facade/ProjectWorkflowFacadeIntTest.java`, `CONF-SVC-TEST/subflow/SubflowDataSourceTest.java`

**Interfaces:**
- Consumes: `ProjectWorkflow.getType()` (Task 1).

`ProjectWorkflowFacade.getProjectVersionWorkflows(...)` is deliberately **not** filtered: the deployment dialog lists a version's workflows from it, and an agent's workflow must be deployable.

- [ ] **Step 1: Write the failing tests**

`ProjectWorkflowFacadeIntTest`:

```java
    @Test
    public void testGetProjectWorkflowsLeavesOutAgentWorkflows() {
        ProjectWorkflow userProjectWorkflow = projectWorkflowFacade.addWorkflow(
            project.getId(), "{\"label\":\"User workflow\",\"tasks\":[]}");

        Workflow agentWorkflow = workflowService.create(
            "{\"label\":\"agent\",\"tasks\":[]}", Workflow.Format.JSON, Workflow.SourceType.JDBC);

        projectWorkflowService.addWorkflow(
            project.getId(), project.getLastProjectVersion(), agentWorkflow.getId(), ProjectWorkflowType.AI_AGENT);

        assertThat(projectWorkflowFacade.getProjectWorkflows(project.getId()))
            .extracting(ProjectWorkflowDTO::getProjectWorkflowId)
            .containsExactly(userProjectWorkflow.getId());

        assertThat(projectWorkflowFacade.getProjectVersionWorkflows(
            project.getId(), project.getLastProjectVersion(), false)).hasSize(2);
    }
```

(`project` = the class's existing project fixture.) `SubflowDataSourceTest` (Mockito): copy its existing "lists a callable workflow" test, stub the `ProjectWorkflow` with `setType(ProjectWorkflowType.AI_AGENT)`, and assert the option list is empty.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :server:libs:automation:automation-configuration:automation-configuration-service:testIntegration --tests "*ProjectWorkflowFacadeIntTest"` and `...:test --tests "*SubflowDataSourceTest"`
Expected: FAIL — the agent workflow is listed.

- [ ] **Step 3: Add the filters**

Each site already streams or loops over `ProjectWorkflow`; add the same predicate.

`ProjectWorkflowFacadeImpl.getProjectWorkflows()` — first filter in the stream:

```java
        return projectWorkflows.stream()
            .filter(projectWorkflow -> projectWorkflow.getType() == ProjectWorkflowType.WORKFLOW)
            .filter(projectWorkflow -> visibleProjectIds.contains(projectWorkflow.getProjectId()))
```

`ProjectWorkflowFacadeImpl.getProjectWorkflows(long projectId)` — directly after `.stream()` at :422: `.filter(projectWorkflow -> projectWorkflow.getType() == ProjectWorkflowType.WORKFLOW)`.

`ProjectFacadeImpl.getWorkspaceLatestProjectWorkflows` — inside the existing filter lambda at :639-643:

```java
                return project != null && project.getLastProjectVersion() == projectWorkflow.getProjectVersion() &&
                    projectWorkflow.getType() == ProjectWorkflowType.WORKFLOW;
```

`ProjectFacadeImpl.getProjects(...)` at :959 — so `ProjectDTO.projectWorkflowIds` (the "N workflows" counter) counts user workflows only:

```java
            List<ProjectWorkflow> allProjectWorkflows = projectWorkflowService.getProjectWorkflows(projectIds)
                .stream()
                .filter(projectWorkflow -> projectWorkflow.getType() == ProjectWorkflowType.WORKFLOW)
                .toList();
```

`ProjectWorkflowGraphQlController.eligibleErrorWorkflows` — add `.filter(projectWorkflow -> projectWorkflow.getType() == ProjectWorkflowType.WORKFLOW)` before the `hasErrorTrigger` filter.

`SubflowDataSourceImpl.getSubWorkflows` — first statement inside the `for` loop at :135, before the workflow is loaded:

```java
            if (projectWorkflow.getType() == ProjectWorkflowType.AI_AGENT) {
                continue;
            }

```

(Agents stay callable through `CallableAiAgentDataSourceImpl`; this keeps them from being listed twice.)

`ProjectDeploymentFacadeImpl.getWorkspaceChatWorkflows` — agents' hosted chats are listed by `AiAgentFacade.getWorkspaceChatAgents`, so drop them here. After `projectWorkflowMap` is built (:525-528), insert:

```java
        enabledProjectDeploymentWorkflows = enabledProjectDeploymentWorkflows.stream()
            .filter(projectDeploymentWorkflow -> {
                ProjectWorkflow projectWorkflow = projectWorkflowMap.get(projectDeploymentWorkflow.getWorkflowId());

                return projectWorkflow == null || projectWorkflow.getType() == ProjectWorkflowType.WORKFLOW;
            })
            .toList();
```

(`enabledProjectDeploymentWorkflows` is assigned once above; drop its `final`-ness if the compiler objects by introducing a new local `userProjectDeploymentWorkflows` and using it in the rest of the method.)

- [ ] **Step 4: Run to verify they pass**

Run the Step 2 commands plus `./gradlew :server:libs:automation:automation-configuration:automation-configuration-graphql:test`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add server/libs/automation/automation-configuration
git commit -m "NNNN Leave generated AI agent workflows out of workflow listings"
```

---

### Task 6: Publish and delete semantics

**Files:**
- Create: `AGENT-SVC/event/AiAgentProjectPublishPreListener.java`, `AGENT-SVC/event/AiAgentProjectDeleteEventListener.java`
- Modify: `AGENT-API/facade/AiAgentFacade.java`, `AGENT-SVC/facade/AiAgentFacadeImpl.java` (`deleteAgent` 310-361, `hasAnyDeployment` 363-379, `publishAgent` 988-1000, `validateForPublish` 1014)
- Modify: `AGENT-API/exception/AiAgentErrorType.java` (message only)
- Test: `AGENT-SVC-TEST/facade/AiAgentFacadeIntTest.java`, `AGENT-SVC-TEST/facade/AiAgentFacadeAuthorizationTest.java`

**Interfaces:**
- Consumes: `ProjectPublishPreListener`, `ProjectDeleteEventListener` (Task 2); `AiAgentService.getProjectAgents(long)` (Task 3).
- Produces: `void AiAgentFacade.prepareProjectPublish(long projectId)`; `void AiAgentFacade.deleteProjectAgents(long projectId)`. `publishAgent` stays in this phase (the existing client still calls it) and is removed in Task 12.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void testDeleteAgentKeepsProjectAndSiblingWorkflow() {
        AiAgentDTO agentDTO = agentFacade.createAgent("Doomed", null, workspaceId, null);

        long projectId = agentDTO.agent()
            .getProjectId();

        Project project = projectService.getProject(projectId);

        Workflow userWorkflow = workflowService.create(
            "{\"label\":\"Survivor\",\"tasks\":[]}", Workflow.Format.JSON, Workflow.SourceType.JDBC);

        projectWorkflowService.addWorkflow(projectId, project.getLastProjectVersion(), userWorkflow.getId());

        agentFacade.deleteAgent(agentDTO.agent()
            .getId());

        assertThat(projectService.fetchProject(projectId)).isPresent();
        assertThat(projectWorkflowService.getProjectWorkflowIds(projectId)).containsExactly(userWorkflow.getId());
    }

    @Test
    void testPrepareProjectPublishRejectsProjectWithAgentMissingModel() {
        AiAgentDTO agentDTO = agentFacade.createAgent("No Model", null, workspaceId, null);

        assertThatThrownBy(() -> agentFacade.prepareProjectPublish(agentDTO.agent()
            .getProjectId()))
                .isInstanceOf(ConfigurationException.class)
                .extracting("errorKey")
                .isEqualTo(AiAgentErrorType.MODEL_MISSING.getErrorKey());
    }

    @Test
    void testDeleteProjectAgentsRefusesWhileReferencedFromAnotherProject() {
        AiAgentDTO subAgentDTO = createPublishableAgent("Sub");
        AiAgentDTO parentAgentDTO = agentFacade.createAgent("Parent", null, workspaceId, null);

        agentFacade.publishAgent(subAgentDTO.agent()
            .getId(), "v1");

        agentFacade.addAgentElement(
            parentAgentDTO.agent()
                .getId(),
            AiAgentElement.KIND_SUB_AGENT, subAgentDTO.agent()
                .getId(),
            Map.of(), null);

        assertThatThrownBy(() -> agentFacade.deleteProjectAgents(subAgentDTO.agent()
            .getProjectId()))
                .isInstanceOf(ConfigurationException.class);
    }
```

Match the `errorKey` extraction to however the class already asserts `AiAgentErrorType` elsewhere (search the file for `MODEL_MISSING`). Add to `AiAgentFacadeAuthorizationTest`:

```java
    @Test
    void testPrepareProjectPublishIsGatedOnTheProject() {
        assertExpression("hasPermission(#projectId, 'Project', 'WORKFLOW_EDIT')", "prepareProjectPublish", long.class);
    }

    @Test
    void testDeleteProjectAgentsIsGatedOnTheProject() {
        assertExpression("hasPermission(#projectId, 'Project', 'PROJECT_DELETE')", "deleteProjectAgents", long.class);
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :server:libs:automation:automation-ai:automation-ai-agent:automation-ai-agent-service:testIntegration --tests "*AiAgentFacadeIntTest"`
Expected: compilation failure — the two new facade methods do not exist.

- [ ] **Step 3: Facade — publish preparation**

`AiAgentFacade.java`:

```java
    /**
     * Validates every agent of {@code projectId} and regenerates its draft workflow, so that the project version about
     * to be published snapshots the agents' current configuration. Throws the same errors publishing a single agent
     * raises, which aborts the project publish.
     */
    void prepareProjectPublish(long projectId);

    /**
     * Removes every agent of {@code projectId}, ahead of the project's own deletion. The agents' generated workflows
     * are left to the project delete, which removes every workflow of the project anyway.
     */
    void deleteProjectAgents(long projectId);
```

`AiAgentFacadeImpl`:

```java
    @Override
    @PreAuthorize("hasPermission(#projectId, 'Project', 'WORKFLOW_EDIT')")
    public void prepareProjectPublish(long projectId) {
        for (AiAgent agent : agentService.getProjectAgents(projectId)) {
            validateForPublish(agent);

            regenerateAndSaveWorkflow(agent);
        }
    }
```

`publishAgent` loses its own `validateForPublish` / `regenerateAndSaveWorkflow` calls — `publishProjectVersion` reaches `ProjectService.publishProject`, which now runs the listener for every agent of the project:

```java
    @Override
    @PreAuthorize("hasPermission(#id, 'AiAgent', 'AGENT_EDIT')")
    public int publishAgent(long id, String description) {
        AiAgent agent = agentService.getAgent(id);

        return publishProjectVersion(agent.getProjectId(), description);
    }
```

- [ ] **Step 4: Facade — delete**

Replace `deleteAgent`'s body after the sub-agent guard, and replace `hasAnyDeployment` with a per-workflow check:

```java
    @Override
    @PreAuthorize("hasPermission(#id, 'AiAgent', 'AGENT_DELETE')")
    public void deleteAgent(long id) {
        AiAgent agent = agentService.getAgent(id);

        if (isEnabledInAnyDeployment(agent)) {
            throw new ConfigurationException(
                "Agent " + id + " cannot be deleted while it is enabled in a deployment",
                AiAgentErrorType.AGENT_HAS_DEPLOYMENTS);
        }

        if (!agentService.getSubAgentReferencingAgents(id)
            .isEmpty()) {

            throw new ConfigurationException(
                "Agent " + id + " cannot be deleted while it is referenced as a sub-agent",
                AiAgentErrorType.AGENT_REFERENCED_AS_SUB_AGENT);
        }

        List<ProjectWorkflow> agentProjectWorkflows = projectWorkflowService.getProjectWorkflows(
            agent.getProjectId(), String.valueOf(agent.getProjectWorkflowUuid()));

        agentService.delete(id);

        // One row per project version: publishing duplicates the workflow into every new version.
        for (ProjectWorkflow agentProjectWorkflow : agentProjectWorkflows) {
            String workflowId = agentProjectWorkflow.getWorkflowId();

            projectWorkflowService.delete(
                agentProjectWorkflow.getProjectId(), agentProjectWorkflow.getProjectVersion(), workflowId);

            for (WorkflowPreDeleteListener workflowPreDeleteListener : workflowPreDeleteListeners) {
                workflowPreDeleteListener.onWorkflowPreDelete(workflowId);
            }

            workflowService.delete(workflowId);
        }
    }

    private boolean isEnabledInAnyDeployment(AiAgent agent) {
        for (Environment environment : Environment.values()) {
            ProjectDeployment projectDeployment = projectDeploymentService
                .fetchProjectDeployment(agent.getProjectId(), environment)
                .orElse(null);

            if (projectDeployment == null) {
                continue;
            }

            for (ProjectDeploymentWorkflow projectDeploymentWorkflow : agentDeploymentWorkflows(
                agent, projectDeployment)) {

                if (projectDeploymentWorkflow.isEnabled()) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * The deployment's rows for the agent's own generated workflow. A project deployment now covers every workflow and
     * agent of the project, so anything agent-specific has to pick its own row out.
     */
    private List<ProjectDeploymentWorkflow> agentDeploymentWorkflows(
        AiAgent agent, ProjectDeployment projectDeployment) {

        String agentWorkflowId = projectWorkflowService
            .fetchProjectWorkflow(
                agent.getProjectId(), projectDeployment.getProjectVersion(),
                String.valueOf(agent.getProjectWorkflowUuid()))
            .map(ProjectWorkflow::getWorkflowId)
            .orElse(null);

        if (agentWorkflowId == null) {
            return List.of();
        }

        return projectDeploymentWorkflowService.getProjectDeploymentWorkflows(projectDeployment.getId())
            .stream()
            .filter(projectDeploymentWorkflow -> agentWorkflowId.equals(projectDeploymentWorkflow.getWorkflowId()))
            .toList();
    }

    @Override
    @PreAuthorize("hasPermission(#projectId, 'Project', 'PROJECT_DELETE')")
    public void deleteProjectAgents(long projectId) {
        List<AiAgent> projectAgents = agentService.getProjectAgents(projectId);

        Set<Long> projectAgentIds = projectAgents.stream()
            .map(AiAgent::getId)
            .collect(Collectors.toSet());

        for (AiAgent projectAgent : projectAgents) {
            boolean referencedFromOutside = agentService.getSubAgentReferencingAgents(projectAgent.getId())
                .stream()
                .anyMatch(referencingAgent -> !projectAgentIds.contains(referencingAgent.getId()));

            if (referencedFromOutside) {
                throw new ConfigurationException(
                    "Project " + projectId + " cannot be deleted while its agent " + projectAgent.getId()
                        + " is referenced as a sub-agent from another project",
                    AiAgentErrorType.AGENT_REFERENCED_AS_SUB_AGENT);
            }
        }

        for (AiAgent projectAgent : projectAgents) {
            agentService.delete(projectAgent.getId());
        }
    }
```

Update the message attached to `AiAgentErrorType.AGENT_HAS_DEPLOYMENTS` if the enum carries one.

- [ ] **Step 5: The two listeners**

```java
package com.bytechef.automation.ai.agent.event;

import com.bytechef.automation.ai.agent.facade.AiAgentFacade;
import com.bytechef.automation.configuration.listener.ProjectPublishPreListener;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Brings every agent workflow of a project up to date before the project version is published.
 *
 * @author Ivica Cardic
 */
@Component
public class AiAgentProjectPublishPreListener implements ProjectPublishPreListener {

    private final AiAgentFacade aiAgentFacade;

    public AiAgentProjectPublishPreListener(@Lazy AiAgentFacade aiAgentFacade) {
        this.aiAgentFacade = aiAgentFacade;
    }

    @Override
    public void onBeforePublishProject(long projectId) {
        aiAgentFacade.prepareProjectPublish(projectId);
    }
}
```

`AiAgentProjectDeleteEventListener` is identical in shape, implements `ProjectDeleteEventListener`, and calls `aiAgentFacade.deleteProjectAgents(projectId)`. `@Lazy` is required on this one: `ProjectFacadeImpl` takes its listeners by constructor, and `AiAgentFacadeImpl` depends on project services. Add `@SuppressFBWarnings("EI_EXPOSE_REP2")` to both constructors if SpotBugs flags them, as the tool callbacks in this codebase do.

Both listeners are picked up by `AutomationAiAgentIntTestConfiguration`'s component scan. Add an end-to-end assertion that the wiring holds:

```java
    @Test
    void testProjectPublishRegeneratesAgentWorkflowThroughListener() {
        AiAgentDTO agentDTO = createPublishableAgent("Listener Bot");

        long projectId = agentDTO.agent()
            .getProjectId();

        agentFacade.updateAgent(agentDTO.agent()
            .getId(), "Listener Bot", null, "fresh instructions");

        projectService.publishProject(projectId, "v1", false);

        assertThat(draftDefinition(agentDTO.agent())).contains("fresh instructions");
    }
```

- [ ] **Step 6: Run to verify they pass**

```bash
./gradlew :server:libs:automation:automation-ai:automation-ai-agent:automation-ai-agent-service:test :server:libs:automation:automation-ai:automation-ai-agent:automation-ai-agent-service:testIntegration
```

Expected: PASS. `AiAgentFacadeVisibilityFilterTest` constructs `AiAgentFacadeImpl` by hand — it needs no change unless the constructor changed.

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add server/libs/automation/automation-ai/automation-ai-agent
git commit -m "NNNN Publish and delete AI agents with their project"
```

---

### Task 7: Narrow the agent deployment read model

**Files:**
- Modify: `AGENT-SVC/facade/AiAgentFacadeImpl.java` (`getWorkspaceChatAgents` 699-745, `toAgentDeploymentDTO` 1442-1457)
- Test: `AGENT-SVC-TEST/facade/AiAgentFacadeIntTest.java`

**Interfaces:**
- Consumes: `agentDeploymentWorkflows(AiAgent, ProjectDeployment)` (Task 6).

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void testAgentDeploymentListsOnlyTheAgentsOwnWorkflow() {
        AiAgentDTO agentDTO = createPublishableAgent("Deployed Bot");

        long projectId = agentDTO.agent()
            .getProjectId();

        Project project = projectService.getProject(projectId);

        Workflow userWorkflow = workflowService.create(
            "{\"label\":\"Sibling\",\"tasks\":[]}", Workflow.Format.JSON, Workflow.SourceType.JDBC);

        projectWorkflowService.addWorkflow(projectId, project.getLastProjectVersion(), userWorkflow.getId());

        int nextDraftVersion = agentFacade.publishAgent(agentDTO.agent()
            .getId(), "v1");

        deployProjectVersion(projectId, nextDraftVersion - 1);

        assertThat(agentFacade.getAgentDeployments(workspaceId))
            .singleElement()
            .satisfies(agentDeploymentDTO -> assertThat(agentDeploymentDTO.workflows()).hasSize(1));
    }
```

`deployProjectVersion` stands for the helper the class's existing `getAgentDeployments` tests use to create a `ProjectDeployment` with one `ProjectDeploymentWorkflow` per project workflow; extend it to cover every workflow of the version if it only covers one.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :server:libs:automation:automation-ai:automation-ai-agent:automation-ai-agent-service:testIntegration --tests "*AiAgentFacadeIntTest.testAgentDeploymentListsOnlyTheAgentsOwnWorkflow"`
Expected: FAIL — two workflows listed.

- [ ] **Step 3: Use the agent's own rows**

In `toAgentDeploymentDTO`, replace the first statement with:

```java
        List<ProjectDeploymentWorkflow> projectDeploymentWorkflows = agentDeploymentWorkflows(agent, projectDeployment);
```

In `getWorkspaceChatAgents`, replace the `projectDeploymentWorkflowService.getProjectDeploymentWorkflows(projectDeploymentId)` assignment with:

```java
            List<ProjectDeploymentWorkflow> projectDeploymentWorkflows = agentDeploymentWorkflows(
                agent, projectDeployment);
```

Rewrite the two javadoc sentences that say "An agent deploys a single generated workflow" to say the rows are the agent's own within a project-wide deployment.

- [ ] **Step 4: Run to verify it passes, then the whole module**

Run: `./gradlew :server:libs:automation:automation-ai:automation-ai-agent:automation-ai-agent-service:testIntegration`
Expected: PASS.

- [ ] **Step 5: Boot check and commit**

Reset the dev database as described in Global Constraints, then `./gradlew -p server/apps/server-app bootRun`; confirm startup completes and, in the still-existing Agents page, create an agent and see a project with the same name appear on the Projects page. Stop the server.

```bash
./gradlew spotlessApply
git add server/libs/automation/automation-ai/automation-ai-agent
git commit -m "NNNN Narrow agent deployments to the agent's own workflow"
```

---

## Phase 2 — Client move

Run all client commands from `client/`. After any `.graphqls` change run `npm run codegen` and commit `src/shared/middleware/graphql.ts` + `graphql-types.ts` **separately** from the operations that caused them.

### Task 8: Operations, codegen, and project-aware agent creation

**Files:**
- Modify: `client/src/graphql/automation/agent/aiAgents.graphql`, `aiAgent.graphql`, `importAiAgent.graphql`
- Modify: `client/src/pages/automation/agents/components/AgentDialog.tsx`, `AgentDialog.test.tsx`
- Create: `client/src/pages/automation/agents/utils/getAgentPath.ts`, `client/src/pages/automation/agents/utils/tests/getAgentPath.test.ts`

**Interfaces:**
- Produces: `getAgentPath(agent: {id: string; projectId: string}): string` → `/automation/projects/${projectId}/agents/${id}`; `AgentDialogProps.projectId?: number` (pre-selects and locks the project).

- [ ] **Step 1: Operations and codegen**

Add `projectWorkflowUuid` to the selection sets of `aiAgents.graphql` and `aiAgent.graphql`; add `projectId` to `createAiAgent.graphql`'s selection (`createAiAgent(input: $input) { id projectId }`) and to `importAiAgent.graphql` both as variable (`$projectId: ID`) and in the selection. Run `npm run codegen`.

```bash
git add src/graphql/automation/agent && git commit -m "NNNN client - Select agent project fields"
git add src/shared/middleware/graphql.ts src/shared/middleware/graphql-types.ts && git commit -m "NNNN client - Regenerate GraphQL types"
```

- [ ] **Step 2: Write the failing tests**

`getAgentPath.test.ts`:

```ts
import {describe, expect, it} from 'vitest';

import getAgentPath from '../getAgentPath';

describe('getAgentPath', () => {
    it('builds the project-scoped agent route', () => {
        expect(getAgentPath({id: '22', projectId: '7'})).toBe('/automation/projects/7/agents/22');
    });
});
```

In `AgentDialog.test.tsx` add (reusing the file's existing `wrap`, `mockUseCreateAiAgentMutation` and mutate-capturing pattern):

```tsx
    it('creates the agent in a new project by default and lands on the project-scoped route', async () => {
        const mutate = vi.fn((_variables, options) =>
            options.onSuccess({createAiAgent: {id: '99', projectId: '7'}})
        );

        mockUseCreateAiAgentMutation.mockReturnValue({isPending: false, mutate} as never);

        wrap(<AgentDialog open />);

        await userEvent.type(screen.getByLabelText('Name'), 'Support Bot');
        await userEvent.click(screen.getByRole('button', {name: 'Save'}));

        expect(mutate.mock.calls[0][0].input.projectId).toBeUndefined();
        expect(navigateMock).toHaveBeenCalledWith('/automation/projects/7/agents/99');
    });

    it('sends the locked project id when opened from inside a project', async () => {
        const mutate = vi.fn();

        mockUseCreateAiAgentMutation.mockReturnValue({isPending: false, mutate} as never);

        wrap(<AgentDialog open projectId={7} />);

        await userEvent.type(screen.getByLabelText('Name'), 'Support Bot');
        await userEvent.click(screen.getByRole('button', {name: 'Save'}));

        expect(mutate.mock.calls[0][0].input.projectId).toBe('7');
    });
```

Use the field label and submit-button name the existing tests in the file already query.

- [ ] **Step 3: Run to verify they fail**

Run: `npx vitest run src/pages/automation/agents/utils/tests/getAgentPath.test.ts src/pages/automation/agents/components/AgentDialog.test.tsx`
Expected: FAIL — module not found / `projectId` prop unknown.

- [ ] **Step 4: Implement**

`getAgentPath.ts`:

```ts
interface AgentPathAgentI {
    id: string;
    projectId: string;
}

const getAgentPath = ({id, projectId}: AgentPathAgentI): string => `/automation/projects/${projectId}/agents/${id}`;

export default getAgentPath;
```

`AgentDialog.tsx`:
- add `projectId?: number;` to `AgentDialogProps` (keep keys sorted) with the doc comment `/** Locks the target project — the dialog was opened from inside it. Absent = the user picks, defaulting to a new project. */`;
- add `projectId: z.string().optional()` to the zod schema, default `projectId ? String(projectId) : ''`;
- in create mode only, and only when the `projectId` prop is absent, render a project `Select` above the description, fed by `useGetWorkspaceProjectsQuery({id: currentWorkspaceId})` (the hook `ProjectsLeftSidebar` already uses), whose first option is `<SelectItem value="new">New project named after this agent</SelectItem>` followed by one item per project (`value={String(project.id)}`); form default `'new'`;
- in the create variables: `projectId: values.projectId && values.projectId !== 'new' ? values.projectId : undefined` (keys sorted: `description`, `projectId`, `title`, `workspaceId`);
- in `onSuccess`: `navigate(createdRedirectPath ?? getAgentPath(data.createAiAgent))`, and additionally invalidate `ProjectKeys.filteredProjects({id: currentWorkspaceId})` so a newly created project shows up.

- [ ] **Step 5: Run to verify they pass, then commit**

Run the Step 3 command, then `npm run lint && npm run typecheck`. Expected: PASS.

```bash
npm run format
git add src/pages/automation/agents
git commit -m "NNNN client - Create agents inside a chosen or new project"
```

---

### Task 9: Project-scoped agent page

**Files:**
- Create: `client/src/pages/automation/project/ProjectAgent.tsx`, `client/src/pages/automation/project/ProjectAgent.test.tsx`
- Modify: `client/src/routes.tsx` (after the `projects/:projectId/project-workflows/:projectWorkflowId` route, ~:976)
- Modify: `client/src/pages/automation/agents/components/detail/AgentDetailHeader.tsx:85-104`, `AgentDetailHeader.test.tsx`
- Modify: links in `client/src/ee/pages/automation/ai-hub/AiHubAiAgentViewer.tsx:19`, `client/src/pages/platform/workflow-editor/components/properties/components/CallAiAgentDetailDialog.tsx:53`, `client/src/pages/automation/agents/Agents.tsx:55`, `components/agent-list/AgentListItem.tsx:165,309` (+ their tests)

**Interfaces:**
- Consumes: `getAgentPath` (Task 8).
- Produces: route `/automation/projects/:projectId/agents/:agentId`.

- [ ] **Step 1: Write the failing test**

`ProjectAgent.test.tsx`:

```tsx
import {render, screen} from '@testing-library/react';
import {describe, expect, it, vi} from 'vitest';

import ProjectAgent from './ProjectAgent';

vi.mock('react-router-dom', () => ({
    useNavigate: () => vi.fn(),
    useParams: () => ({agentId: '22', projectId: '7'}),
}));

vi.mock('@/pages/automation/project/components/projects-sidebar/ProjectsLeftSidebar', () => ({
    default: ({projectId}: {projectId: number}) => <aside data-testid="projects-sidebar">{projectId}</aside>,
}));

vi.mock('@/pages/automation/agents/AgentDetailContent', () => ({
    default: ({agentId}: {agentId: string}) => <div data-testid="agent-detail-content">{agentId}</div>,
}));

vi.mock('@/pages/automation/agents/components/detail/AgentDetailHeader', () => ({
    default: ({title}: {title: string}) => <header>{title}</header>,
}));

vi.mock('@/pages/automation/agents/components/detail/AgentTestChatPanel', () => ({
    default: () => <div data-testid="agent-test-chat" />,
}));

vi.mock('@/shared/middleware/graphql', () => ({
    useAiAgentQuery: () => ({
        data: {
            aiAgent: {draftWorkflowId: 'wf', id: '22', lastPublishedVersion: 0, projectId: '7', title: 'Support Bot'},
        },
    }),
}));

describe('ProjectAgent', () => {
    it('renders the agent inside the project layout', () => {
        render(<ProjectAgent />);

        expect(screen.getByTestId('projects-sidebar')).toHaveTextContent('7');
        expect(screen.getByTestId('agent-detail-content')).toHaveTextContent('22');
        expect(screen.getByText('Support Bot')).toBeInTheDocument();
    });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/pages/automation/project/ProjectAgent.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement the page and the route**

`ProjectAgent.tsx`:

```tsx
import AgentDetailContent from '@/pages/automation/agents/AgentDetailContent';
import AgentDetailHeader from '@/pages/automation/agents/components/detail/AgentDetailHeader';
import AgentTestChatPanel from '@/pages/automation/agents/components/detail/AgentTestChatPanel';
import ProjectsLeftSidebar from '@/pages/automation/project/components/projects-sidebar/ProjectsLeftSidebar';
import {useAiAgentQuery} from '@/shared/middleware/graphql';
import {useRef, useState} from 'react';
import {type PanelImperativeHandle} from 'react-resizable-panels';
import {useNavigate, useParams} from 'react-router-dom';

const ProjectAgent = () => {
    const [testPanelOpen, setTestPanelOpen] = useState(true);

    // The sidebar closes the workflow editor's bottom panel after creating a workflow; this page has none.
    const bottomResizablePanelRef = useRef<PanelImperativeHandle | null>(null);

    const navigate = useNavigate();
    const {agentId, projectId} = useParams<{agentId: string; projectId: string}>();

    const {data} = useAiAgentQuery({id: agentId ?? ''}, {enabled: !!agentId});

    const agent = data?.aiAgent;

    return (
        <div className="flex size-full">
            <ProjectsLeftSidebar
                bottomResizablePanelRef={bottomResizablePanelRef}
                currentAgentId={agentId}
                currentWorkflowId=""
                onProjectClick={(clickedProjectId, projectWorkflowId) =>
                    navigate(`/automation/projects/${clickedProjectId}/project-workflows/${projectWorkflowId}`)
                }
                projectId={+(projectId ?? 0)}
            />

            <div className="flex min-w-0 flex-1 flex-col">
                {agent && (
                    <AgentDetailHeader
                        description={agent.description}
                        id={agent.id}
                        lastPublishedVersion={agent.lastPublishedVersion}
                        onToggleTestPanel={() => setTestPanelOpen((open) => !open)}
                        projectId={agent.projectId}
                        testPanelOpen={testPanelOpen}
                        title={agent.title}
                    />
                )}

                <div className="flex min-h-0 flex-1">
                    <div className="min-w-0 flex-1 overflow-y-auto">
                        {agentId && <AgentDetailContent agentId={agentId} />}
                    </div>

                    {agent && testPanelOpen && (
                        <div className="w-[450px] shrink-0 border-l border-l-border/50">
                            <AgentTestChatPanel key={agent.id} workflowId={agent.draftWorkflowId} />
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
};

export default ProjectAgent;
```

Import `PanelImperativeHandle` from wherever `Project.tsx` imports it. `currentAgentId` is added to the sidebar in Task 10; until then omit that prop so this task typechecks on its own.

`routes.tsx` — add `const ProjectAgent = lazy(() => import('@/pages/automation/project/ProjectAgent'));` and, directly after the project-workflows route object:

```tsx
                                {
                                    element: (
                                        <PrivateRoute hasAnyAuthorities={[AUTHORITIES.ADMIN, AUTHORITIES.USER]}>
                                            <LazyLoadWrapper>
                                                <ProjectAgent />
                                            </LazyLoadWrapper>
                                        </PrivateRoute>
                                    ),
                                    path: 'projects/:projectId/agents/:agentId',
                                },
```

- [ ] **Step 4: Point every agent link at the new route**

- `AgentDetailHeader.tsx:104` (navigation after delete) stays on `/automation/agents` in this task; Task 11 repoints it once `/automation/projects/agents` exists.
- `AgentListItem.tsx:165,309`, `Agents.tsx:55` (`getAgentPath(data.importAiAgent)`), `AiHubAiAgentViewer.tsx:19`, `CallAiAgentDetailDialog.tsx:53` — use `getAgentPath(...)`. The last two hold only an agent id: `AiHubAiAgentViewer` and `CallAiAgentDetailDialog` already render `AgentDetailContent`, which runs `useAiAgentQuery`; call the same hook in the parent (`useAiAgentQuery({id: agentId}, {enabled: !!agentId})`, deduplicated by TanStack Query) and build the href from `data.aiAgent` once loaded, rendering no link until then.
- Update the assertions in `Agents.test.tsx:226`, `AiHubAiAgentViewer.test.tsx:22`, `CallAiAgentDetailDialog.test.tsx:43`, `AgentListItem.test.tsx` to the new paths.

- [ ] **Step 5: Run to verify, then commit**

Run: `npx vitest run src/pages/automation/project src/pages/automation/agents src/ee/pages/automation/ai-hub src/pages/platform/workflow-editor/components/properties/components && npm run lint && npm run typecheck`
Expected: PASS.

```bash
npm run format
git add src
git commit -m "NNNN client - Open agents inside their project"
```

---

### Task 10: Agents section in the project sidebar

**Files:**
- Create: `client/src/pages/automation/project/components/projects-sidebar/components/ProjectAgentsList.tsx`, `.../projects-sidebar/tests/ProjectAgentsList.test.tsx`
- Modify: `.../projects-sidebar/ProjectsLeftSidebar.tsx:34-39,307-342`, `ProjectsLeftSidebar.test.tsx`, `client/src/pages/automation/project/ProjectAgent.tsx`

**Interfaces:**
- Produces: `ProjectsLeftSidebarProps.currentAgentId?: string`; `<ProjectAgentsList currentAgentId projectId />`.

- [ ] **Step 1: Write the failing test**

```tsx
import {TooltipProvider} from '@/components/ui/tooltip';
import {render, screen} from '@testing-library/react';
import {MemoryRouter} from 'react-router-dom';
import {describe, expect, it, vi} from 'vitest';

import ProjectAgentsList from '../components/ProjectAgentsList';

vi.mock('@/pages/automation/agents/hooks/useAgents', () => ({
    default: () => ({
        agents: [
            {id: '1', projectId: '7', title: 'Support Bot'},
            {id: '2', projectId: '8', title: 'Other Project Bot'},
        ],
        agentsIsLoading: false,
    }),
}));

describe('ProjectAgentsList', () => {
    it('lists only the agents of the given project, linking to the project-scoped route', () => {
        render(
            <MemoryRouter>
                <TooltipProvider>
                    <ProjectAgentsList currentAgentId="1" projectId={7} />
                </TooltipProvider>
            </MemoryRouter>
        );

        expect(screen.getByRole('link', {name: /Support Bot/})).toHaveAttribute(
            'href',
            '/automation/projects/7/agents/1'
        );
        expect(screen.queryByText('Other Project Bot')).not.toBeInTheDocument();
    });

    it('renders nothing when the project has no agents', () => {
        const {container} = render(
            <MemoryRouter>
                <ProjectAgentsList projectId={99} />
            </MemoryRouter>
        );

        expect(container).toBeEmptyDOMElement();
    });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/pages/automation/project/components/projects-sidebar/tests/ProjectAgentsList.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement**

```tsx
import useAgents from '@/pages/automation/agents/hooks/useAgents';
import getAgentPath from '@/pages/automation/agents/utils/getAgentPath';
import {BotIcon} from 'lucide-react';
import {useMemo} from 'react';
import {Link} from 'react-router-dom';
import {twMerge} from 'tailwind-merge';

interface ProjectAgentsListProps {
    currentAgentId?: string;
    /** 0 = "all projects", matching the sidebar's project select. */
    projectId: number;
}

const ProjectAgentsList = ({currentAgentId, projectId}: ProjectAgentsListProps) => {
    const {agents} = useAgents();

    const projectAgents = useMemo(
        () => agents.filter((agent) => projectId === 0 || +agent.projectId === projectId),
        [agents, projectId]
    );

    if (projectAgents.length === 0) {
        return null;
    }

    return (
        <li>
            <h3 className="px-2 pb-1 text-xs font-semibold uppercase text-muted-foreground">Agents</h3>

            <ul className="flex flex-col">
                {projectAgents.map((agent) => (
                    <li key={agent.id}>
                        <Link
                            className={twMerge(
                                'flex items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-surface-neutral-primary-hover',
                                agent.id === currentAgentId && 'bg-surface-brand-secondary font-semibold'
                            )}
                            to={getAgentPath(agent)}
                        >
                            <BotIcon className="size-4 shrink-0" />

                            <span className="truncate">{agent.title}</span>
                        </Link>
                    </li>
                ))}
            </ul>
        </li>
    );
};

export default ProjectAgentsList;
```

Take the selected/hover background classes from `WorkflowsListItem.tsx` so an agent row matches a workflow row.

`ProjectsLeftSidebar.tsx`: add `currentAgentId?: string;` to the props (sorted), add a **New Agent** item to the existing "New Workflow" dropdown that opens `<AgentDialog projectId={projectId} … />` (controlled with a `useState` flag, like the sidebar's other dialogs), label the existing workflows `<ul>` with a matching `Workflows` `<h3>`, and render `<ProjectAgentsList currentAgentId={currentAgentId} projectId={selectedProjectId} />` as the last child of the `<ul className="flex flex-col gap-4">` at :307. Add to `ProjectsLeftSidebar.test.tsx` a case asserting the `Agents` heading renders when `useAgents` returns an agent of the project (mock `@/pages/automation/agents/hooks/useAgents` as above). Pass `currentAgentId={agentId}` from `ProjectAgent.tsx`.

- [ ] **Step 4: Run to verify, then commit**

Run: `npx vitest run src/pages/automation/project && npm run lint && npm run typecheck`
Expected: PASS.

```bash
npm run format
git add src/pages/automation/project
git commit -m "NNNN client - List a project's agents in the project sidebar"
```

---

### Task 11: Agents tab on the Projects page

**Files:**
- Create: `client/src/pages/automation/projects/components/ProjectsTabs.tsx`, `.../components/ProjectsTabs.test.tsx`
- Modify: `client/src/pages/automation/projects/Projects.tsx:197-208`, `client/src/pages/automation/agents/Agents.tsx` (left sidebar + title), `client/src/routes.tsx` (`agents` route → `projects/agents`)
- Modify: `client/src/pages/automation/agents/components/agent-list/AgentListItem.tsx` (project badge), `client/src/pages/automation/projects/components/project-list/ProjectListItem.tsx:368-379`

**Interfaces:**
- Produces: route `/automation/projects/agents`; `<ProjectsTabs value="projects" | "agents" />`.

- [ ] **Step 1: Write the failing test**

```tsx
import {render, screen} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {describe, expect, it, vi} from 'vitest';

import ProjectsTabs from './ProjectsTabs';

const {navigateMock} = vi.hoisted(() => ({navigateMock: vi.fn()}));

vi.mock('react-router-dom', () => ({useNavigate: () => navigateMock}));

describe('ProjectsTabs', () => {
    it('marks the current tab and navigates to the other one', async () => {
        render(<ProjectsTabs value="projects" />);

        expect(screen.getByRole('tab', {name: 'Projects'})).toHaveAttribute('data-state', 'active');

        await userEvent.click(screen.getByRole('tab', {name: 'Agents'}));

        expect(navigateMock).toHaveBeenCalledWith('/automation/projects/agents');
    });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/pages/automation/projects/components/ProjectsTabs.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement the tab strip**

```tsx
import {Tabs, TabsList, TabsTrigger} from '@/components/ui/tabs';
import {BotIcon, FolderIcon} from 'lucide-react';
import {useNavigate} from 'react-router-dom';

const TAB_PATHS = {
    agents: '/automation/projects/agents',
    projects: '/automation/projects',
} as const;

interface ProjectsTabsProps {
    value: keyof typeof TAB_PATHS;
}

const ProjectsTabs = ({value}: ProjectsTabsProps) => {
    const navigate = useNavigate();

    return (
        <Tabs className="px-4 pb-3" onValueChange={(tab) => navigate(TAB_PATHS[tab as keyof typeof TAB_PATHS])} value={value}>
            <TabsList className="grid w-full grid-cols-2">
                <TabsTrigger value="projects">
                    <FolderIcon className="mr-2 size-4" />
                    Projects
                </TabsTrigger>

                <TabsTrigger value="agents">
                    <BotIcon className="mr-2 size-4" />
                    Agents
                </TabsTrigger>
            </TabsList>
        </Tabs>
    );
};

export default ProjectsTabs;
```

- [ ] **Step 4: Mount it on both pages and move the route**

`Projects.tsx` — wrap the sidebar body:

```tsx
            leftSidebarBody={
                <>
                    <ProjectsTabs value="projects" />

                    <CategoryTagLeftSidebarNav … unchanged props … />
                </>
            }
```

and add an `<AgentDialog triggerNode={<DropdownMenuItem onSelect={(event) => event.preventDefault()}><BotIcon className="mr-2 size-4" />New Agent</DropdownMenuItem>} />` entry to the "More create options" dropdown, so **New Agent** is reachable from the Projects tab.

`Agents.tsx` — same wrap with `<ProjectsTabs value="agents" />` above `AgentsLeftSidebarNav`, and change `leftSidebarHeader` to `<Header position="sidebar" title="Projects" />` so the two tabs share one sidebar title.

`AgentDetailHeader.tsx:104` — after a delete, `navigate('/automation/projects/agents')` (update the assertion in `AgentDetailHeader.test.tsx`).

`routes.tsx` — change the `Agents` route's `path` from `'agents'` to `'projects/agents'` and move the object directly after the `projects` route object; give it the same development-only `loader` as `projects` (copy it verbatim — it redirects to the deployments page outside Development). Delete the `agents/:agentId` route object and the `AgentDetail` lazy import.

`AgentListItem.tsx` — next to the title render the owning project as a link: resolve the name from `useGetWorkspaceProjectsQuery({id: currentWorkspaceId})` (find `project.id === +agent.projectId`) and render `<Badge … ><FolderIcon className="size-3" />{projectName}</Badge>` inside `<Link data-interactive to={'/automation/projects/' + agent.projectId + '/agents/' + agent.id}>`; add an assertion for the badge text in `AgentListItem.test.tsx` (mock the projects query to return `[{id: 7, name: 'Support'}]`).

`ProjectListItem.tsx:368-379` — extend the counter with the project's agent count from `useAgents()`:

```tsx
    const agentCount = useMemo(
        () => agents.filter((agent) => +agent.projectId === project.id).length,
        [agents, project.id]
    );
```

```tsx
                                    <div className="mr-1">
                                        {`${workflowCount} ${workflowCount === 1 ? 'workflow' : 'workflows'}`}

                                        {agentCount > 0 && ` · ${agentCount} ${agentCount === 1 ? 'agent' : 'agents'}`}
                                    </div>
```

with `const workflowCount = project.projectWorkflowIds?.length ?? 0;` as a derived value above the `useMemo`. In `ProjectList.tsx`'s `CollapsibleContent`, render `<ul className="px-2 pb-2"><ProjectAgentsList projectId={project.id!} /></ul>` under `<ProjectWorkflowList …/>` (the component from Task 10 returns `null` for a project without agents). `ProjectListItem.visibility.test.tsx` and `Projects.test.tsx` need `@/pages/automation/agents/hooks/useAgents` mocked to `{agents: [], agentsIsLoading: false}`.

- [ ] **Step 5: Run to verify, then commit**

Run: `npx vitest run src/pages/automation/projects src/pages/automation/agents && npm run lint && npm run typecheck`
Expected: PASS.

```bash
npm run format
git add src
git commit -m "NNNN client - Add an Agents tab to the Projects page"
```

---

### Task 12: Remove the standalone agent surfaces, per-agent publish and per-agent visibility

**Files (client):**
- Delete: `client/src/pages/automation/agents/AgentDetail.tsx` + test, `components/AgentsLeftSidebarDropdownMenu.tsx` (if only `AgentDetail` used it), `components/detail/AgentVisibilityDialog.tsx` + test, `components/AgentVisibilityCaveat.tsx`, `components/agent-list/AgentListItem.visibility.test.tsx`, `client/src/shared/hooks/useAiAgentVisibility.ts` (+ test), `client/src/graphql/automation/agent/{publishAiAgent,aiAgentGrants,grantAiAgentAccess,revokeAiAgentAccess,setAiAgentVisibility}.graphql`
- Modify: `client/src/shared/navigation/navigationItems.ts:58`, `developmentOnlyRoutes.ts:23` + both nav tests, `AgentDetailHeader.tsx`, `AgentListItem.tsx`, `client/codegen.ts` (drop the EE agent schema path)

**Files (server):**
- Delete: `server/ee/libs/automation/automation-ai/automation-ai-agent/` (three submodules), their `settings.gradle.kts:664-666` includes, and every `project(":server:ee:libs:automation:automation-ai:automation-ai-agent:...")` dependency (`grep -rn "ee:libs:automation:automation-ai:automation-ai-agent" --include="*.kts" .`)
- Delete: `AGENT-TOOL/PublishAiAgentToolCallback.java` + `PublishAiAgentToolCallbackTest.java`; remove it from `AiAgentToolCallbacksFactory` (+ test expectations, including `AiHubConfigurationAiAgentFlatCrudToolCallbacksTest` and `McpServerToolCallbackContributorConfigurationTest`)
- Modify: `AGENT-API/facade/AiAgentFacade.java`, `AGENT-SVC/facade/AiAgentFacadeImpl.java` (remove `publishAgent`, `publishProjectVersion`), `ai-agent.graphqls` + `AiAgentGraphQlController` (remove `publishAiAgent`), and their tests

**Interfaces:**
- Consumes: the REST publish mutation `usePublishProjectMutation` from `client/src/shared/mutations/automation/projects.mutations.ts` (the one `useProjectHeader.ts` uses).

- [ ] **Step 1: Switch the two Publish controls to the project publish**

In `AgentDetailHeader.tsx` and `AgentListItem.tsx` replace `usePublishAiAgentMutation` with the REST hook, exactly as `useProjectHeader.ts` calls it:

```tsx
    const publishProjectMutation = usePublishProjectMutation({
        onSuccess: () => {
            invalidateAgentQueries(queryClient);

            queryClient.invalidateQueries({queryKey: ProjectKeys.project(+projectId)});
        },
    });
```

```tsx
    publishProjectMutation.mutate({id: +projectId, publishProjectRequest: {description}});
```

Copy the variable shape from `useProjectHeader.ts`'s `handlePublishProjectSubmit` if it differs. Change `PublishPopover`'s `title="Publish Agent"` to `title="Publish Project"` and add the helper line "Publishes every workflow and agent in this project." if the popover accepts a description prop; otherwise leave the body as is. In `AgentListItem`, rename the menu item **Publish** → **Publish Project**. Remove the visibility badge dropdown (`AgentListItem.tsx:177-212`) and the **Who Can See This** item + dialog (`AgentDetailHeader.tsx:231-235,290-296`) and the `visibility` prop. Update `AgentDetailHeader.test.tsx` / `AgentListItem.test.tsx`: mock `@/shared/mutations/automation/projects.mutations` and assert `mutate` receives `{id: 7, …}`.

- [ ] **Step 2: Remove navigation and dead client files**

Delete the `{group: 'Build', href: '/automation/agents', …}` nav item and the `{fallbackHref: '/automation/agent-deployments', href: '/automation/agents'}` development-only entry. In `developmentOnlyRoutes.test.ts` and `useDevelopmentOnlyRouteGuard.test.tsx`, replace the agent cases with `/automation/projects/agents` → `/automation/deployments` (covered by the existing projects entry, because `getDevelopmentOnlyFallbackHref` matches `pathname.startsWith(href + '/')`). Delete the client files listed above, remove the EE agent schema line from `codegen.ts`, run `npm run codegen`.

- [ ] **Step 3: Remove the server surface**

Delete the EE module, the `publishAgent` facade method with `publishProjectVersion`, the GraphQL mutation and the tool callback. In `AiAgentFacadeIntTest` replace every `agentFacade.publishAgent(id, description)` with `publishProject(agent, description)` — a private helper:

```java
    private int publishProject(AiAgent agent, String description) {
        int oldProjectVersion = projectService.getProject(agent.getProjectId())
            .getLastProjectVersion();

        List<ProjectWorkflow> oldProjectWorkflows = projectWorkflowService.getProjectWorkflows(
            agent.getProjectId(), oldProjectVersion);

        int newProjectVersion = projectService.publishProject(agent.getProjectId(), description, false);

        for (ProjectWorkflow oldProjectWorkflow : oldProjectWorkflows) {
            String oldWorkflowId = oldProjectWorkflow.getWorkflowId();

            Workflow duplicatedWorkflow = workflowService.duplicateWorkflow(oldWorkflowId);

            oldProjectWorkflow.setProjectVersion(newProjectVersion);
            oldProjectWorkflow.setWorkflowId(duplicatedWorkflow.getId());

            projectWorkflowService.publishWorkflow(
                agent.getProjectId(), oldProjectVersion, oldWorkflowId, oldProjectWorkflow);
        }

        return newProjectVersion;
    }
```

(the test configuration deliberately does not load `ProjectFacadeImpl`, so the helper repeats its loop.) Do the same in `CallableAiAgentDataSourceIntTest:346`. Remove `publishAgent` from `AiAgentFacadeAuthorizationTest` and the GraphQL controller test. Update the `GetAiAgentToolCallback` / `AddAiAgentElementToolCallback` descriptions that tell the model to call `publishAiAgent` to say `publishProject` instead (`grep -rn "publishAiAgent" server`).

- [ ] **Step 4: Verify**

```bash
./gradlew clean compileJava compileTestJava
./gradlew :server:libs:automation:automation-ai:automation-ai-agent:automation-ai-agent-service:test :server:libs:automation:automation-ai:automation-ai-agent:automation-ai-agent-service:testIntegration :server:libs:automation:automation-ai:automation-ai-agent:automation-ai-agent-graphql:test :server:libs:automation:automation-ai:automation-ai-tool:test
cd client && npm run check
```

Expected: PASS. (`npm run check` needs an explicit long timeout — it runs the full vitest suite with coverage.)

- [ ] **Step 5: Commit (server, client operations, generated types — three commits)**

```bash
./gradlew spotlessApply
git add server settings.gradle.kts && git commit -m "NNNN Remove per-agent publish and per-agent visibility"
cd client && npm run format
git add src/shared/middleware/graphql.ts src/shared/middleware/graphql-types.ts && git commit -m "NNNN client - Regenerate GraphQL types"
git add . && git commit -m "NNNN client - Remove the standalone Agents page"
```

---

## Phase 3 — Deployments merge

### Task 13: Agent channels in Project Deployments; remove Agent Deployments

**Files (client):**
- Move: `client/src/pages/automation/agent-deployments/components/AgentDeploymentChannelList.tsx` (+ test) and `hooks/useAgentDeployments.ts` → `client/src/pages/automation/project-deployments/components/agent-deployment-channel-list/` and `client/src/pages/automation/project-deployments/hooks/`
- Create: `client/src/pages/automation/project-deployments/components/project-deployment-list/ProjectDeploymentAgentChannels.tsx` + test
- Modify: `.../project-deployment-list/ProjectDeploymentList.tsx:60-91`, `.../project-deployment-list/ProjectDeploymentListItem.tsx:143-154`
- Delete: the rest of `client/src/pages/automation/agent-deployments/`, its route + lazy import in `routes.tsx`, the `Deploy → Agents` nav item (`navigationItems.ts:69`), `client/src/graphql/automation/agent/{aiAgentDeploymentTags,updateAiAgentDeploymentTags}.graphql`
- Modify: `AgentListItem.tsx:330-342`, `AgentDetailHeader.tsx:187-193,271-283` — their **Deploy** buttons keep opening `ProjectDeploymentDialog` for the agent's `projectId` (no change needed beyond the label **Deploy Project**)

**Files (server):**
- Modify: `AGENT-API/facade/AiAgentFacade.java`, `AGENT-SVC/facade/AiAgentFacadeImpl.java` (remove `getAgentDeploymentTags` 426-439, `updateAgentDeploymentTags` 606-616), `ai-agent.graphqls` + controller (remove `aiAgentDeploymentTags`, `updateAiAgentDeploymentTags`, `UpdateAiAgentDeploymentTagsInput`; drop `tags` from `AiAgentDeployment`), `AiAgentDeploymentDTO` (drop `tags`), tests

**Interfaces:**
- Produces: `<ProjectDeploymentAgentChannels projectDeploymentId={number} />`.

- [ ] **Step 1: Write the failing test**

```tsx
import {render, screen} from '@testing-library/react';
import {describe, expect, it, vi} from 'vitest';

import ProjectDeploymentAgentChannels from './ProjectDeploymentAgentChannels';

vi.mock('@/pages/automation/project-deployments/hooks/useAgentDeployments', () => ({
    default: () => ({
        agentDeployments: [
            {agentId: '1', agentTitle: 'Support Bot', id: '50', workflows: []},
            {agentId: '2', agentTitle: 'Elsewhere Bot', id: '51', workflows: []},
        ],
    }),
}));

vi.mock('@/pages/automation/project-deployments/components/agent-deployment-channel-list/AgentDeploymentChannelList', () => ({
    default: ({title}: {title: string}) => <div data-testid="channel-list">{title}</div>,
}));

describe('ProjectDeploymentAgentChannels', () => {
    it('renders one channel list per agent deployed by this project deployment', () => {
        render(<ProjectDeploymentAgentChannels projectDeploymentId={50} />);

        expect(screen.getAllByTestId('channel-list')).toHaveLength(1);
        expect(screen.getByText('Support Bot')).toBeInTheDocument();
    });

    it('renders nothing for a deployment without agents', () => {
        const {container} = render(<ProjectDeploymentAgentChannels projectDeploymentId={999} />);

        expect(container).toBeEmptyDOMElement();
    });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/pages/automation/project-deployments/components/project-deployment-list/ProjectDeploymentAgentChannels.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement**

Move the two files (`git mv`), fix their imports, then:

```tsx
import AgentDeploymentChannelList from '@/pages/automation/project-deployments/components/agent-deployment-channel-list/AgentDeploymentChannelList';
import useAgentDeployments from '@/pages/automation/project-deployments/hooks/useAgentDeployments';
import {useMemo} from 'react';

interface ProjectDeploymentAgentChannelsProps {
    projectDeploymentId: number;
}

const ProjectDeploymentAgentChannels = ({projectDeploymentId}: ProjectDeploymentAgentChannelsProps) => {
    const {agentDeployments} = useAgentDeployments();

    const deploymentAgents = useMemo(
        () => agentDeployments.filter((agentDeployment) => +agentDeployment.id === projectDeploymentId),
        [agentDeployments, projectDeploymentId]
    );

    if (deploymentAgents.length === 0) {
        return null;
    }

    return (
        <div className="flex flex-col gap-3 border-t border-t-border/50 px-2 py-3">
            {deploymentAgents.map((agentDeployment) => (
                <section key={agentDeployment.agentId}>
                    <h3 className="pb-1 text-xs font-semibold uppercase text-muted-foreground">
                        {agentDeployment.agentTitle} channels
                    </h3>

                    <AgentDeploymentChannelList
                        projectDeploymentId={agentDeployment.id}
                        title={agentDeployment.agentTitle}
                        workflows={agentDeployment.workflows}
                    />
                </section>
            ))}
        </div>
    );
};

export default ProjectDeploymentAgentChannels;
```

`ProjectDeploymentList.tsx` — inside the existing `<CollapsibleContent>` at :60-91, after `<ProjectDeploymentWorkflowList …/>`, add `<ProjectDeploymentAgentChannels projectDeploymentId={projectDeployment.id!} />`.

`ProjectDeploymentWorkflowList` fetches `useGetProjectVersionWorkflowsQuery(projectId, projectVersion)`, which (deliberately, Task 5) includes agent workflows. Label them: build `agentTitleByWorkflowUuid` from `useAgents()` (`agent.projectWorkflowUuid` → `agent.title`) and pass the matching title into `ProjectDeploymentWorkflowListItem` as an optional `agentTitle?: string` prop that, when present, replaces the workflow label with `{agentTitle}` plus a small `<Badge>Agent</Badge>` and hides the "edit workflow" affordance (the generated workflow is edited through the agent). Add a test case to the item's existing test asserting the badge renders when `agentTitle` is passed.

- [ ] **Step 4: Remove Agent Deployments**

Client: delete the remaining `agent-deployments` files, the route object + lazy import, the nav item, and the two deployment-tag operations; run `npm run codegen`. Update `PublishAiAgent…`-free tool copy: `grep -rn "Agent Deployments" client/src server` and reword the hits (e.g. `GetAiAgentToolCallback`) to "the project's deployment on the Deployments page".

Server: remove the two tag methods, their GraphQL surface, the `tags` component of `AiAgentDeploymentDTO`, and their entries in `AiAgentFacadeAuthorizationTest` and `AiAgentGraphQlControllerTest`.

- [ ] **Step 5: Verify end to end**

```bash
./gradlew clean compileJava compileTestJava
./gradlew :server:libs:automation:automation-ai:automation-ai-agent:automation-ai-agent-service:test :server:libs:automation:automation-ai:automation-ai-agent:automation-ai-agent-service:testIntegration :server:libs:automation:automation-ai:automation-ai-agent:automation-ai-agent-graphql:test
cd client && npm run check
```

Then boot the server and client and walk the flow once: Projects → Agents tab → New Agent (default "new project") → lands on `/automation/projects/:id/agents/:id`; add a model; add a workflow to the same project from the sidebar; **Publish Project**; **Deploy Project**; Deployments page shows the project deployment with the workflow row, the agent row badged **Agent**, and the agent's channel list; delete the agent → refused while enabled, succeeds after disabling its row; the project and workflow remain.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add server && git commit -m "NNNN Remove agent deployment tags"
cd client && npm run format
git add src/shared/middleware/graphql.ts src/shared/middleware/graphql-types.ts && git commit -m "NNNN client - Regenerate GraphQL types"
git add . && git commit -m "NNNN client - Show agent channels in Project Deployments and remove Agent Deployments"
```

---

## Outstanding against the spec

These spec items are intentionally **not** covered by this plan and should be planned separately:

- **Project export/import carrying the project's agents.** Single-agent export/import is covered (Task 4); the project archive format is untouched.
- **`listAiAgents` `projectId` filter.** The tool now returns each agent's `projectId` (Task 4) but takes no filter; agent counts per workspace are small enough for the model to filter.
- **Left-sidebar project filter on the Agents tab.** The tab keeps today's agent/tag/scheduled filters and gains a per-row project badge (Task 11); a project filter group was not added.
