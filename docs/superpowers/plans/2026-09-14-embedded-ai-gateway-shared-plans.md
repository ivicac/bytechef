# Embedded AI Gateway Shared Plans Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make embedded AI gateway routing policies shareable plans assigned to many connected users, give each connected user an optional spend cap, and make customer provider credentials strictly owned.

**Architecture:** A new `ai_gateway_connected_user_settings` row (platform tier) holds a connected user's assigned routing policy and cap; `ai_gateway_routing_policy.connected_user_id` is removed. Provider credentials keep `connected_user_id`, bound at creation and never cleared. The embedded tier exposes one ADMIN-guarded `ConnectedUserAiGatewayFacade`; `AiGatewayFacadeImpl` resolves the plan and cap from the settings row and restricts an embedded caller's request-specified policy to its assigned plan.

**Tech Stack:** Java 25, Spring Boot 4, Spring Data JDBC, Liquibase, JUnit 5, Mockito, AssertJ, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-09-14-embedded-ai-gateway-shared-plans-design.md`

## Global Constraints

- The gateway init changelog is unreleased; edit `00000000000001_ai_gateway_init.xml` in place, never add a changeset.
- All new files under `server/ee/` use the ByteChef Enterprise license header and carry `@version ee`.
- Every `@Service` and `@Component` in the gateway carries `@ConditionalOnEEVersion` and `@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")`.
- Management methods on API facades carry `@PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")`.
- A foreign id and a missing id fail with the identical exception type and message.
- Embedded requests naming any policy other than their assigned plan fail with `Routing policy not found: <name>`.
- Automation behaviour does not change.
- Blank line before control statements and after a variable modification that the next statement uses; no method-chaining beyond builders/streams/Optional/AssertJ/Mockito.
- Test method names are camelCase with no underscores; unit tests end `Test`, integration tests end `IntTest`.
- Run Gradle with `JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/current`, redirect output to a file, check `$?`, then grep `^> Task .* FAILED`.
- Commit subjects are one line, no body, no trailer; this plan's commits continue the current group with a `- ` prefix.

## Gradle projects

- `:server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-service` (GW-SVC)
- `:server:ee:libs:embedded:embedded-connected-user:embedded-connected-user-service` (CU-SVC)
- `:server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service` (AUTO-GW)

## File map

| File | Responsibility | Task |
|---|---|---|
| `platform-ai-gateway-service/.../changelog/platform/ai/gateway/00000000000001_ai_gateway_init.xml` | schema | 1 |
| `platform-ai-gateway-api/.../domain/AiGatewayConnectedUserSettings.java` | entity | 1 |
| `platform-ai-gateway-api/.../repository/AiGatewayConnectedUserSettingsRepository.java` | repository | 1 |
| `platform-ai-gateway-api/.../service/AiGatewayConnectedUserSettingsService.java` | service contract | 1 |
| `platform-ai-gateway-service/.../service/AiGatewayConnectedUserSettingsServiceImpl.java` | service | 1 |
| `AiGatewayRoutingPolicyServiceImpl` | refuse deleting an assigned plan | 2 |
| `AiGatewayProviderService(Impl)`, `AiGatewayProviderRepository` | owned credentials | 3 |
| `embedded-connected-user-api/.../gateway/facade/ConnectedUserAiGatewayFacade.java` (+ `Impl`), `ConnectedUserAiGatewayFacadeIntTest` | ADMIN management facade | 4 |
| `embedded-connected-user-service/.../event/ConnectedUserBeforeDeleteEventListener.java` | deletion | 5 |
| `automation-ai-gateway-service/.../facade/AiGatewayFacadeImpl.java`, `EmbeddedAiGatewayPhase2IntTest` | resolution and cap | 6 |
| `AiGatewayRoutingPolicy`, its repository and service, the init changelog's policy column, `ConnectedUserAiGatewayRoutingPolicyFacade` (+ `Impl`, tests, binding IntTest), the register | remove the old binding, verify, record | 7 |

Tasks 2–6 each compile and pass on their own; the policy column and the old facade survive until Task 7 so that no task breaks another module's build.

Paths below abbreviate `server/ee/libs/platform/platform-ai/platform-ai-gateway/` as `GW/`, `server/ee/libs/embedded/embedded-connected-user/` as `CU/`, and `server/ee/libs/automation/automation-ai/automation-ai-gateway/automation-ai-gateway-service/` as `AUTO/`. Java packages: gateway `com.bytechef.ee.platform.ai.gateway`, connected user `com.bytechef.ee.embedded.connected.user`, automation gateway `com.bytechef.ee.automation.ai.gateway`.

---

### Task 1: Connected-user settings row

**Files:**
- Modify: `GW/platform-ai-gateway-service/src/main/resources/config/liquibase/changelog/platform/ai/gateway/00000000000001_ai_gateway_init.xml`
- Create: `GW/platform-ai-gateway-api/src/main/java/com/bytechef/ee/platform/ai/gateway/domain/AiGatewayConnectedUserSettings.java`
- Create: `GW/platform-ai-gateway-api/src/main/java/com/bytechef/ee/platform/ai/gateway/repository/AiGatewayConnectedUserSettingsRepository.java`
- Create: `GW/platform-ai-gateway-api/src/main/java/com/bytechef/ee/platform/ai/gateway/service/AiGatewayConnectedUserSettingsService.java`
- Create: `GW/platform-ai-gateway-service/src/main/java/com/bytechef/ee/platform/ai/gateway/service/AiGatewayConnectedUserSettingsServiceImpl.java`
- Test: `GW/platform-ai-gateway-service/src/test/java/com/bytechef/ee/platform/ai/gateway/service/AiGatewayConnectedUserSettingsServiceTest.java`

**Interfaces:**
- Produces (used by Tasks 2, 4, 5, 6):

```java
public interface AiGatewayConnectedUserSettingsService {

    void assignRoutingPolicy(long connectedUserId, long routingPolicyId);

    long countByRoutingPolicyId(long routingPolicyId);

    void deleteByConnectedUserId(long connectedUserId);

    Optional<AiGatewayConnectedUserSettings> fetchByConnectedUserId(long connectedUserId);

    void unassignRoutingPolicy(long connectedUserId);

    void updateBudgetCap(long connectedUserId, @Nullable BigDecimal budgetCap);
}
```

`AiGatewayConnectedUserSettings` getters: `Long getId()`, `long getConnectedUserId()`, `@Nullable Long getRoutingPolicyId()`, `@Nullable BigDecimal getBudgetCap()`; setters `setRoutingPolicyId(@Nullable Long)`, `setBudgetCap(@Nullable BigDecimal)`; constructor `AiGatewayConnectedUserSettings(long connectedUserId)`.

- [ ] **Step 1: Add the table to the init changelog**

In `00000000000001_ai_gateway_init.xml`, immediately after the `CREATE UNIQUE INDEX uk_ai_gateway_routing_policy_connected_user_id ...` `<sql>` element and before `<createTable tableName="ai_gateway_model_deployment">`, add:

```xml
        <!-- A connected user's assigned plan (routing policy) and optional spend cap. Many connected users may share
             one plan. No foreign key to connected_user: the platform tier does not reference the embedded tier's
             tables, so ConnectedUserBeforeDeleteEventListener deletes the row. No environment column: a connected
             user id is already per environment. No workspace_id: no workspace can own an embedded customer's
             settings. -->
        <createTable tableName="ai_gateway_connected_user_settings">
            <column name="id" type="BIGINT" autoIncrement="true" startWith="1050">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="connected_user_id" type="BIGINT">
                <constraints nullable="false" unique="true"
                             uniqueConstraintName="uk_ai_gateway_connected_user_settings_connected_user_id"/>
            </column>
            <column name="routing_policy_id" type="BIGINT">
                <constraints foreignKeyName="fk_ai_gateway_connected_user_settings_policy"
                             references="ai_gateway_routing_policy(id)"/>
            </column>
            <column name="budget_cap" type="NUMERIC(19, 6)"/>
            <column name="created_date" type="TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="last_modified_date" type="TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="version" type="BIGINT">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createIndex tableName="ai_gateway_connected_user_settings"
                     indexName="idx_ai_gateway_connected_user_settings_routing_policy_id">
            <column name="routing_policy_id"/>
        </createIndex>
```

The policy table's `connected_user_id` column, check constraint and unique index are removed in Task 2, not here, so this task compiles on its own.

- [ ] **Step 2: Create the entity**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A connected user's assigned routing policy (plan) and optional spend cap. Many connected users may be assigned the
 * same policy; a connected user has at most one row.
 *
 * @version ee
 */
@Table("ai_gateway_connected_user_settings")
public final class AiGatewayConnectedUserSettings {

    @Column("budget_cap")
    private @Nullable BigDecimal budgetCap;

    @Column("connected_user_id")
    private long connectedUserId;

    @Column("created_date")
    @CreatedDate
    private Instant createdDate;

    @Id
    private Long id;

    @Column("last_modified_date")
    @LastModifiedDate
    private Instant lastModifiedDate;

    @Column("routing_policy_id")
    private @Nullable Long routingPolicyId;

    @Version
    private int version;

    private AiGatewayConnectedUserSettings() {
    }

    public AiGatewayConnectedUserSettings(long connectedUserId) {
        this.connectedUserId = connectedUserId;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }

        if (!(object instanceof AiGatewayConnectedUserSettings settings)) {
            return false;
        }

        return Objects.equals(id, settings.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    public @Nullable BigDecimal getBudgetCap() {
        return budgetCap;
    }

    public long getConnectedUserId() {
        return connectedUserId;
    }

    public Long getId() {
        return id;
    }

    public @Nullable Long getRoutingPolicyId() {
        return routingPolicyId;
    }

    public void setBudgetCap(@Nullable BigDecimal budgetCap) {
        this.budgetCap = budgetCap;
    }

    public void setRoutingPolicyId(@Nullable Long routingPolicyId) {
        this.routingPolicyId = routingPolicyId;
    }

    @Override
    public String toString() {
        return "AiGatewayConnectedUserSettings{id=" + id + ", connectedUserId=" + connectedUserId +
            ", routingPolicyId=" + routingPolicyId + ", budgetCap=" + budgetCap + '}';
    }
}
```

`version` is `int`, matching `AiGatewayRoutingPolicy` (the column is `BIGINT` there too).

- [ ] **Step 3: Create the repository and service contract**

```java
package com.bytechef.ee.platform.ai.gateway.repository;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayConnectedUserSettings;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

/**
 * @version ee
 */
public interface AiGatewayConnectedUserSettingsRepository
    extends ListCrudRepository<AiGatewayConnectedUserSettings, Long> {

    long countByRoutingPolicyId(long routingPolicyId);

    void deleteByConnectedUserId(long connectedUserId);

    Optional<AiGatewayConnectedUserSettings> findByConnectedUserId(long connectedUserId);
}
```

```java
package com.bytechef.ee.platform.ai.gateway.service;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayConnectedUserSettings;
import java.math.BigDecimal;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A connected user's assigned plan and spend cap. Unguarded, as every gateway service is: authorization lives on the
 * embedded tier's {@code ConnectedUserAiGatewayFacade}.
 *
 * @version ee
 */
public interface AiGatewayConnectedUserSettingsService {

    /**
     * Assigns {@code routingPolicyId} as the connected user's plan, creating the row when absent and replacing any
     * previous plan. Does not validate the policy; callers do.
     */
    void assignRoutingPolicy(long connectedUserId, long routingPolicyId);

    long countByRoutingPolicyId(long routingPolicyId);

    void deleteByConnectedUserId(long connectedUserId);

    Optional<AiGatewayConnectedUserSettings> fetchByConnectedUserId(long connectedUserId);

    /**
     * Clears the connected user's plan and keeps the row, so a cap set on it survives. A no-op when no row exists.
     */
    void unassignRoutingPolicy(long connectedUserId);

    /**
     * Sets the connected user's spend cap in USD, creating the row when absent; {@code null} clears it. A negative cap
     * is refused with {@code IllegalArgumentException("budgetCap must not be negative")}.
     */
    void updateBudgetCap(long connectedUserId, @Nullable BigDecimal budgetCap);
}
```

All three files carry the Enterprise license header above the package line. The repository is picked up by the existing `@EnableJdbcRepositories(basePackages = "com.bytechef.ee.platform.ai.gateway.repository")`.

- [ ] **Step 4: Write the failing service test**

```java
package com.bytechef.ee.platform.ai.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayConnectedUserSettings;
import com.bytechef.ee.platform.ai.gateway.repository.AiGatewayConnectedUserSettingsRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * @version ee
 */
@ExtendWith(MockitoExtension.class)
class AiGatewayConnectedUserSettingsServiceTest {

    private static final long CONNECTED_USER_ID = 5L;

    @Mock
    private AiGatewayConnectedUserSettingsRepository aiGatewayConnectedUserSettingsRepository;

    private AiGatewayConnectedUserSettingsService aiGatewayConnectedUserSettingsService;

    @BeforeEach
    void setUp() {
        aiGatewayConnectedUserSettingsService =
            new AiGatewayConnectedUserSettingsServiceImpl(aiGatewayConnectedUserSettingsRepository);
    }

    @Test
    void testAssignRoutingPolicyCreatesTheRowWhenAbsent() {
        when(aiGatewayConnectedUserSettingsRepository.findByConnectedUserId(CONNECTED_USER_ID))
            .thenReturn(Optional.empty());

        aiGatewayConnectedUserSettingsService.assignRoutingPolicy(CONNECTED_USER_ID, 10L);

        AiGatewayConnectedUserSettings saved = captureSaved();

        assertThat(saved.getConnectedUserId()).isEqualTo(CONNECTED_USER_ID);
        assertThat(saved.getRoutingPolicyId()).isEqualTo(10L);
        assertThat(saved.getBudgetCap()).isNull();
    }

    @Test
    void testAssignRoutingPolicyReplacesThePreviousPlanAndKeepsTheCap() {
        AiGatewayConnectedUserSettings existing = new AiGatewayConnectedUserSettings(CONNECTED_USER_ID);

        existing.setRoutingPolicyId(10L);
        existing.setBudgetCap(new BigDecimal("25.00"));

        when(aiGatewayConnectedUserSettingsRepository.findByConnectedUserId(CONNECTED_USER_ID))
            .thenReturn(Optional.of(existing));

        aiGatewayConnectedUserSettingsService.assignRoutingPolicy(CONNECTED_USER_ID, 11L);

        AiGatewayConnectedUserSettings saved = captureSaved();

        assertThat(saved.getRoutingPolicyId()).isEqualTo(11L);
        assertThat(saved.getBudgetCap()).isEqualByComparingTo("25.00");
    }

    @Test
    void testUnassignRoutingPolicyClearsThePlanAndKeepsTheRow() {
        AiGatewayConnectedUserSettings existing = new AiGatewayConnectedUserSettings(CONNECTED_USER_ID);

        existing.setRoutingPolicyId(10L);

        when(aiGatewayConnectedUserSettingsRepository.findByConnectedUserId(CONNECTED_USER_ID))
            .thenReturn(Optional.of(existing));

        aiGatewayConnectedUserSettingsService.unassignRoutingPolicy(CONNECTED_USER_ID);

        assertThat(captureSaved().getRoutingPolicyId()).isNull();
        verify(aiGatewayConnectedUserSettingsRepository, never()).deleteByConnectedUserId(CONNECTED_USER_ID);
    }

    @Test
    void testUnassignRoutingPolicyWithNoRowIsANoOp() {
        when(aiGatewayConnectedUserSettingsRepository.findByConnectedUserId(CONNECTED_USER_ID))
            .thenReturn(Optional.empty());

        aiGatewayConnectedUserSettingsService.unassignRoutingPolicy(CONNECTED_USER_ID);

        verify(aiGatewayConnectedUserSettingsRepository, never()).save(any());
    }

    @Test
    void testUpdateBudgetCapCreatesTheRowWhenAbsent() {
        when(aiGatewayConnectedUserSettingsRepository.findByConnectedUserId(CONNECTED_USER_ID))
            .thenReturn(Optional.empty());

        aiGatewayConnectedUserSettingsService.updateBudgetCap(CONNECTED_USER_ID, new BigDecimal("40.00"));

        AiGatewayConnectedUserSettings saved = captureSaved();

        assertThat(saved.getBudgetCap()).isEqualByComparingTo("40.00");
        assertThat(saved.getRoutingPolicyId()).isNull();
    }

    @Test
    void testUpdateBudgetCapRejectsANegativeCap() {
        assertThatThrownBy(
            () -> aiGatewayConnectedUserSettingsService.updateBudgetCap(CONNECTED_USER_ID, new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("budgetCap must not be negative");

        verify(aiGatewayConnectedUserSettingsRepository, never()).save(any());
    }

    private AiGatewayConnectedUserSettings captureSaved() {
        ArgumentCaptor<AiGatewayConnectedUserSettings> settingsCaptor =
            ArgumentCaptor.forClass(AiGatewayConnectedUserSettings.class);

        verify(aiGatewayConnectedUserSettingsRepository).save(settingsCaptor.capture());

        return settingsCaptor.getValue();
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-service:test --tests '*AiGatewayConnectedUserSettingsServiceTest' > /tmp/t1.log 2>&1; echo $?`
Expected: non-zero; `compileTestJava FAILED` because `AiGatewayConnectedUserSettingsServiceImpl` does not exist.

- [ ] **Step 6: Implement the service**

```java
package com.bytechef.ee.platform.ai.gateway.service;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayConnectedUserSettings;
import com.bytechef.ee.platform.ai.gateway.repository.AiGatewayConnectedUserSettingsRepository;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @version ee
 */
@Service
@Transactional
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
@SuppressFBWarnings("EI")
class AiGatewayConnectedUserSettingsServiceImpl implements AiGatewayConnectedUserSettingsService {

    private final AiGatewayConnectedUserSettingsRepository aiGatewayConnectedUserSettingsRepository;

    AiGatewayConnectedUserSettingsServiceImpl(
        AiGatewayConnectedUserSettingsRepository aiGatewayConnectedUserSettingsRepository) {

        this.aiGatewayConnectedUserSettingsRepository = aiGatewayConnectedUserSettingsRepository;
    }

    @Override
    public void assignRoutingPolicy(long connectedUserId, long routingPolicyId) {
        AiGatewayConnectedUserSettings settings = getOrCreate(connectedUserId);

        settings.setRoutingPolicyId(routingPolicyId);

        aiGatewayConnectedUserSettingsRepository.save(settings);
    }

    @Override
    @Transactional(readOnly = true)
    public long countByRoutingPolicyId(long routingPolicyId) {
        return aiGatewayConnectedUserSettingsRepository.countByRoutingPolicyId(routingPolicyId);
    }

    @Override
    public void deleteByConnectedUserId(long connectedUserId) {
        aiGatewayConnectedUserSettingsRepository.deleteByConnectedUserId(connectedUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AiGatewayConnectedUserSettings> fetchByConnectedUserId(long connectedUserId) {
        return aiGatewayConnectedUserSettingsRepository.findByConnectedUserId(connectedUserId);
    }

    @Override
    public void unassignRoutingPolicy(long connectedUserId) {
        Optional<AiGatewayConnectedUserSettings> settingsOptional =
            aiGatewayConnectedUserSettingsRepository.findByConnectedUserId(connectedUserId);

        if (settingsOptional.isEmpty()) {
            return;
        }

        AiGatewayConnectedUserSettings settings = settingsOptional.get();

        settings.setRoutingPolicyId(null);

        aiGatewayConnectedUserSettingsRepository.save(settings);
    }

    @Override
    public void updateBudgetCap(long connectedUserId, @Nullable BigDecimal budgetCap) {
        if (budgetCap != null && budgetCap.signum() < 0) {
            throw new IllegalArgumentException("budgetCap must not be negative");
        }

        AiGatewayConnectedUserSettings settings = getOrCreate(connectedUserId);

        settings.setBudgetCap(budgetCap);

        aiGatewayConnectedUserSettingsRepository.save(settings);
    }

    private AiGatewayConnectedUserSettings getOrCreate(long connectedUserId) {
        return aiGatewayConnectedUserSettingsRepository.findByConnectedUserId(connectedUserId)
            .orElseGet(() -> new AiGatewayConnectedUserSettings(connectedUserId));
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: the Step 5 command.
Expected: `0`; `6 tests completed`, no `FAILED`.

- [ ] **Step 8: Commit**

```bash
git add server/ee/libs/platform/platform-ai/platform-ai-gateway
git commit -m "- Add the per-connected-user AI gateway settings row"
```

### Task 2: Refuse deleting an assigned plan

**Files:**
- Modify: `GW/platform-ai-gateway-service/src/main/java/com/bytechef/ee/platform/ai/gateway/service/AiGatewayRoutingPolicyServiceImpl.java`
- Test: `GW/platform-ai-gateway-service/src/test/java/com/bytechef/ee/platform/ai/gateway/service/AiGatewayRoutingPolicyServiceTest.java`

**Interfaces:**
- Consumes: `AiGatewayConnectedUserSettingsService.countByRoutingPolicyId(long)` (Task 1).
- Produces: `AiGatewayRoutingPolicyService.delete(long)` throws `IllegalArgumentException("Routing policy <id> is assigned to <n> connected users; reassign them first")` when `n > 0`. `AiGatewayRoutingPolicyServiceImpl`'s constructor becomes `(AiGatewayConnectedUserSettingsService, AiGatewayModelDeploymentService, AiGatewayRoutingPolicyRepository)`.

The policy's own `connected_user_id` stays until Task 7, so every existing caller still compiles.

- [ ] **Step 1: Write the failing tests**

In `AiGatewayRoutingPolicyServiceTest`, add `import static org.assertj.core.api.Assertions.assertThatThrownBy;`, add the mock, and change `setUp`:

```java
    @Mock
    private AiGatewayConnectedUserSettingsService aiGatewayConnectedUserSettingsService;

    @BeforeEach
    void setUp() {
        aiGatewayRoutingPolicyService = new AiGatewayRoutingPolicyServiceImpl(
            aiGatewayConnectedUserSettingsService, aiGatewayModelDeploymentService, aiGatewayRoutingPolicyRepository);
    }
```

Add the tests:

```java
    /**
     * Deleting a plan other connected users are assigned to would move every one of them onto the embedded default
     * with no signal to anyone -- they are bystanders the caller never named -- so it is refused.
     */
    @Test
    void testDeleteRefusesAPolicyAssignedToConnectedUsers() {
        when(aiGatewayConnectedUserSettingsService.countByRoutingPolicyId(10L)).thenReturn(3L);

        assertThatThrownBy(() -> aiGatewayRoutingPolicyService.delete(10L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Routing policy 10 is assigned to 3 connected users; reassign them first");

        verify(aiGatewayModelDeploymentService, never()).deleteByRoutingPolicyId(10L);
        verify(aiGatewayRoutingPolicyRepository, never()).deleteById(10L);
    }

    @Test
    void testDeleteRemovesAnUnassignedPolicyAndItsDeployments() {
        when(aiGatewayConnectedUserSettingsService.countByRoutingPolicyId(10L)).thenReturn(0L);

        aiGatewayRoutingPolicyService.delete(10L);

        verify(aiGatewayModelDeploymentService).deleteByRoutingPolicyId(10L);
        verify(aiGatewayRoutingPolicyRepository).deleteById(10L);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-service:test --tests '*AiGatewayRoutingPolicyServiceTest' > /tmp/t2.log 2>&1; echo $?`
Expected: non-zero; `compileTestJava FAILED` on the three-argument constructor.

- [ ] **Step 3: Implement**

In `AiGatewayRoutingPolicyServiceImpl`, add the field and constructor parameter first, and replace `delete`:

```java
    private final AiGatewayConnectedUserSettingsService aiGatewayConnectedUserSettingsService;
    private final AiGatewayModelDeploymentService aiGatewayModelDeploymentService;
    private final AiGatewayRoutingPolicyRepository aiGatewayRoutingPolicyRepository;

    public AiGatewayRoutingPolicyServiceImpl(
        AiGatewayConnectedUserSettingsService aiGatewayConnectedUserSettingsService,
        AiGatewayModelDeploymentService aiGatewayModelDeploymentService,
        AiGatewayRoutingPolicyRepository aiGatewayRoutingPolicyRepository) {

        this.aiGatewayConnectedUserSettingsService = aiGatewayConnectedUserSettingsService;
        this.aiGatewayModelDeploymentService = aiGatewayModelDeploymentService;
        this.aiGatewayRoutingPolicyRepository = aiGatewayRoutingPolicyRepository;
    }

    @Override
    public void delete(long id) {
        long assignedConnectedUserCount = aiGatewayConnectedUserSettingsService.countByRoutingPolicyId(id);

        if (assignedConnectedUserCount > 0) {
            throw new IllegalArgumentException(
                "Routing policy " + id + " is assigned to " + assignedConnectedUserCount +
                    " connected users; reassign them first");
        }

        aiGatewayModelDeploymentService.deleteByRoutingPolicyId(id);

        aiGatewayRoutingPolicyRepository.deleteById(id);
    }
```

- [ ] **Step 4: Find any other construction site**

Run: `grep -rn "new AiGatewayRoutingPolicyServiceImpl(\|AiGatewayRoutingPolicyServiceImpl.class" --include='*.java' server`
Expected: only `AiGatewayRoutingPolicyServiceTest`. A hand-assembled `@SpringBootTest(classes = ...)` or `@Import` that lists the implementation now needs an `AiGatewayConnectedUserSettingsService` bean: add a `@MockitoBean AiGatewayConnectedUserSettingsService` to that context. Update any `new` call to pass a mock first.

Then confirm the scanned integration context still starts with the new collaborator:

Run: `./gradlew :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:testIntegration --tests '*EmbeddedAiGatewayIntTest' > /tmp/t2-int.log 2>&1; echo $?`
Expected: `0`, no `FAILED`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: the Step 2 command.
Expected: `0`, no `FAILED`.

- [ ] **Step 6: Commit**

```bash
git add server/ee/libs/platform/platform-ai/platform-ai-gateway
git commit -m "- Refuse deleting an AI gateway routing policy assigned to connected users"
```

### Task 3: Owned provider credentials

**Files:**
- Modify: `GW/platform-ai-gateway-api/src/main/java/com/bytechef/ee/platform/ai/gateway/service/AiGatewayProviderService.java`
- Modify: `GW/platform-ai-gateway-api/src/main/java/com/bytechef/ee/platform/ai/gateway/repository/AiGatewayProviderRepository.java`
- Modify: `GW/platform-ai-gateway-service/src/main/java/com/bytechef/ee/platform/ai/gateway/service/AiGatewayProviderServiceImpl.java`
- Test: `GW/platform-ai-gateway-service/src/test/java/com/bytechef/ee/platform/ai/gateway/service/AiGatewayProviderServiceTest.java`
- Modify: `AUTO/src/test/java/com/bytechef/ee/automation/ai/gateway/facade/EmbeddedAiGatewayPhase2IntTest.java`

**Interfaces:**
- Produces (used by Tasks 4 and 5):

```java
    AiGatewayProvider createConnectedUserProvider(AiGatewayProvider provider, long connectedUserId);

    void disableByConnectedUserId(long connectedUserId);

    List<AiGatewayProvider> getProvidersByConnectedUserId(long connectedUserId);
```

- Removed: `void updateConnectedUserId(long id, @Nullable Long connectedUserId)` — a credential is bound at creation and never cleared, because clearing it makes a customer's key a tenant provider every customer routes through.

- [ ] **Step 1: Write the failing tests**

In `AiGatewayProviderServiceTest`, delete the four tests `testUpdateConnectedUserIdSetsConnectedUserId`, `testUpdateConnectedUserIdClearsConnectedUserIdWhenArgumentIsNull`, `testUpdateConnectedUserIdRejectsAWorkspaceScopedProviderAndDoesNotDisturbWorkspaceId` and `testUpdateConnectedUserIdClearingToNullIsAllowedOnAWorkspaceScopedProvider` with their Javadoc. Add `import static org.mockito.ArgumentMatchers.any;` and `import org.mockito.ArgumentCaptor;`, then add:

```java
    @Test
    void testCreateConnectedUserProviderBindsTheConnectedUserAtCreation() {
        AiGatewayProvider provider = new AiGatewayProvider("customer", AiGatewayProviderType.OPENAI, "sk-customer");

        when(aiGatewayProviderRepository.findByConnectedUserIdAndType(42L, AiGatewayProviderType.OPENAI.ordinal()))
            .thenReturn(Optional.empty());
        when(aiGatewayProviderRepository.save(provider)).thenReturn(provider);

        AiGatewayProvider created = aiGatewayProviderService.createConnectedUserProvider(provider, 42L);

        assertThat(created.getConnectedUserId()).isEqualTo(42L);
    }

    /**
     * {@code uk_ai_gateway_provider_connected_user_id_type} ignores {@code enabled}, so a disabled provider of the same
     * type still blocks a new one. Refused as a domain error naming the existing provider instead of surfacing as an
     * unmapped constraint violation.
     */
    @Test
    void testCreateConnectedUserProviderRefusesASecondProviderOfTheSameTypeEvenWhenDisabled() {
        AiGatewayProvider existing = newProviderWithId(7L);

        existing.setEnabled(false);

        when(aiGatewayProviderRepository.findByConnectedUserIdAndType(42L, AiGatewayProviderType.OPENAI.ordinal()))
            .thenReturn(Optional.of(existing));

        AiGatewayProvider provider = new AiGatewayProvider("customer", AiGatewayProviderType.OPENAI, "sk-customer");

        assertThatThrownBy(() -> aiGatewayProviderService.createConnectedUserProvider(provider, 42L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Connected user 42 already has a provider of type OPENAI (7); delete it first");

        verify(aiGatewayProviderRepository, never()).save(any());
    }

    @Test
    void testCreateConnectedUserProviderRefusesAWorkspaceScopedProvider() {
        AiGatewayProvider provider = new AiGatewayProvider("customer", AiGatewayProviderType.OPENAI, "sk-customer");

        provider.setWorkspaceId(9L);

        assertThatThrownBy(() -> aiGatewayProviderService.createConnectedUserProvider(provider, 42L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A connected user's provider cannot belong to a workspace");

        verify(aiGatewayProviderRepository, never()).save(any());
    }

    /**
     * Disabling keeps {@code connected_user_id}: clearing it would hand the key to every other customer. Both model
     * caches are evicted so a cached client built from the key stops serving immediately.
     */
    @Test
    void testDisableByConnectedUserIdDisablesEveryProviderAndEvictsBothCaches() {
        AiGatewayProvider firstProvider = newProviderWithId(7L);
        AiGatewayProvider secondProvider = newProviderWithId(8L);

        firstProvider.setConnectedUserId(42L);
        secondProvider.setConnectedUserId(42L);

        when(aiGatewayProviderRepository.findAllByConnectedUserId(42L))
            .thenReturn(List.of(firstProvider, secondProvider));

        aiGatewayProviderService.disableByConnectedUserId(42L);

        ArgumentCaptor<AiGatewayProvider> providerCaptor = ArgumentCaptor.forClass(AiGatewayProvider.class);

        verify(aiGatewayProviderRepository, org.mockito.Mockito.times(2)).save(providerCaptor.capture());

        assertThat(providerCaptor.getAllValues())
            .allSatisfy(provider -> {
                assertThat(provider.isEnabled()).isFalse();
                assertThat(provider.getConnectedUserId()).isEqualTo(42L);
            });

        verify(aiGatewayChatModelFactory).evict(7L);
        verify(aiGatewayChatModelFactory).evict(8L);
        verify(aiGatewayEmbeddingModelFactory).evict(7L);
        verify(aiGatewayEmbeddingModelFactory).evict(8L);
    }
```

`newProviderWithId(long)` already exists in this test class and builds an `OPENAI` provider.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-service:test --tests '*AiGatewayProviderServiceTest' > /tmp/t3.log 2>&1; echo $?`
Expected: non-zero; `compileTestJava FAILED` on `createConnectedUserProvider`.

- [ ] **Step 3: Implement**

`AiGatewayProviderRepository` — add:

```java
    List<AiGatewayProvider> findAllByConnectedUserId(long connectedUserId);
```

`AiGatewayProviderService` — delete `updateConnectedUserId` and add the three methods from **Interfaces**, each in alphabetical position among the existing methods.

`AiGatewayProviderServiceImpl` — delete `updateConnectedUserId` and its comment block, and add:

```java
    @Override
    public AiGatewayProvider createConnectedUserProvider(AiGatewayProvider provider, long connectedUserId) {
        Validate.notNull(provider, "'provider' must not be null");
        Validate.isTrue(provider.getId() == null, "'id' must be null");

        if (provider.getWorkspaceId() != null) {
            throw new IllegalArgumentException("A connected user's provider cannot belong to a workspace");
        }

        AiGatewayProviderType type = provider.getType();

        Optional<AiGatewayProvider> existingProvider =
            aiGatewayProviderRepository.findByConnectedUserIdAndType(connectedUserId, type.ordinal());

        if (existingProvider.isPresent()) {
            throw new IllegalArgumentException(
                "Connected user " + connectedUserId + " already has a provider of type " + type + " (" +
                    existingProvider.get()
                        .getId() +
                    "); delete it first");
        }

        provider.setConnectedUserId(connectedUserId);

        return aiGatewayProviderRepository.save(provider);
    }

    @Override
    public void disableByConnectedUserId(long connectedUserId) {
        for (AiGatewayProvider provider : aiGatewayProviderRepository.findAllByConnectedUserId(connectedUserId)) {
            provider.setEnabled(false);

            aiGatewayProviderRepository.save(provider);

            aiGatewayChatModelFactory.evict(provider.getId());
            aiGatewayEmbeddingModelFactory.evict(provider.getId());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiGatewayProvider> getProvidersByConnectedUserId(long connectedUserId) {
        return aiGatewayProviderRepository.findAllByConnectedUserId(connectedUserId);
    }
```

Remove the `org.jspecify.annotations.Nullable` import from both files if nothing else in them uses it.

- [ ] **Step 4: Move the remaining callers**

Run: `grep -rn "updateConnectedUserId" --include='*.java' server | grep -i provider`
Expected hit: `EmbeddedAiGatewayPhase2IntTest`, in `testConnectedUsersOwnProviderIsChosenOverTheTenantProviderOnTheDirectPath`. Replace:

```java
        AiGatewayProvider connectedUserProvider =
            createEnabledProvider("byok-customer-azure-openai", AiGatewayProviderType.AZURE_OPENAI);

        aiGatewayProviderService.updateConnectedUserId(connectedUserProvider.getId(), connectedUser.getId());
```

with:

```java
        AiGatewayProvider connectedUserProvider = aiGatewayProviderService.createConnectedUserProvider(
            new AiGatewayProvider(
                "byok-customer-azure-openai", AiGatewayProviderType.AZURE_OPENAI, "sk-test-" + UUID.randomUUID()),
            connectedUser.getId());
```

Change any other hit the same way: build the provider unscoped and pass it to `createConnectedUserProvider`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-service:test :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:compileTestJava --continue > /tmp/t3.log 2>&1; echo $?`
Expected: `0`, no `FAILED`.

- [ ] **Step 6: Commit**

```bash
git add server/ee/libs/platform/platform-ai/platform-ai-gateway server/ee/libs/automation/automation-ai/automation-ai-gateway/automation-ai-gateway-service/src/test
git commit -m "- Bind AI gateway customer credentials at creation and never release them"
```

### Task 4: Connected-user management facade

**Files:**
- Modify: `CU/embedded-connected-user-api/build.gradle.kts`
- Create: `CU/embedded-connected-user-api/src/main/java/com/bytechef/ee/embedded/connected/user/gateway/facade/ConnectedUserAiGatewayFacade.java`
- Create: `CU/embedded-connected-user-service/src/main/java/com/bytechef/ee/embedded/connected/user/gateway/facade/ConnectedUserAiGatewayFacadeImpl.java`
- Test: `CU/embedded-connected-user-service/src/test/java/com/bytechef/ee/embedded/connected/user/gateway/facade/ConnectedUserAiGatewayFacadeTest.java`
- Test: `CU/embedded-connected-user-service/src/test/java/com/bytechef/ee/embedded/connected/user/gateway/facade/ConnectedUserAiGatewayFacadeAuthorizationTest.java`
- Test: `AUTO/src/test/java/com/bytechef/ee/automation/ai/gateway/facade/ConnectedUserAiGatewayFacadeIntTest.java`

**Interfaces:**
- Consumes: `AiGatewayConnectedUserSettingsService` (Task 1), `AiGatewayProviderService.createConnectedUserProvider` / `getProvidersByConnectedUserId` / `delete` (Task 3), `AiGatewayRoutingPolicyService.getRoutingPolicy(long)`, `ConnectedUserService.fetchConnectedUser(long)`.
- Produces (used by Tasks 5, 6, 7):

```java
public interface ConnectedUserAiGatewayFacade {

    void assignRoutingPolicy(long connectedUserId, long routingPolicyId);

    AiGatewayProvider createProvider(long connectedUserId, AiGatewayProvider provider);

    void deleteProvider(long connectedUserId, long providerId);

    void unassignRoutingPolicy(long connectedUserId);

    void updateBudgetCap(long connectedUserId, BigDecimal budgetCap);
}
```

The old `ConnectedUserAiGatewayRoutingPolicyFacade` stays until Task 7: `EmbeddedAiGatewayPhase2IntTest` still calls it, and routing still reads the policy column until Task 6.

- [ ] **Step 1: Add the dependency**

The facade's signatures use `AiGatewayProvider`, so consumers of the API module need the gateway API too. In `CU/embedded-connected-user-api/build.gradle.kts`, add inside `dependencies`:

```kotlin
    api(project(":server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-api"))
```

- [ ] **Step 2: Create the interface**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.connected.user.gateway.facade;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProvider;
import java.math.BigDecimal;

/**
 * Vendor-admin management of a connected user's AI Gateway settings: the plan (routing policy) it is assigned, its
 * spend cap, and its own provider credentials. Every method requires {@code ROLE_ADMIN}. A connected user id from
 * another tenant is indistinguishable from a missing one: both fail with
 * {@code IllegalArgumentException("Connected user not found: <id>")}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface ConnectedUserAiGatewayFacade {

    /**
     * Assigns a tenant-level routing policy as the connected user's plan, replacing any previous plan. Many connected
     * users may share one plan. A workspace-scoped policy is refused.
     */
    void assignRoutingPolicy(long connectedUserId, long routingPolicyId);

    /**
     * Creates a provider credential owned by the connected user for its whole life. A second provider of the same type
     * is refused until the first is deleted.
     */
    AiGatewayProvider createProvider(long connectedUserId, AiGatewayProvider provider);

    /**
     * Deletes one of the connected user's own providers. A provider that is not the connected user's fails exactly as
     * a missing one does.
     */
    void deleteProvider(long connectedUserId, long providerId);

    /**
     * Clears the connected user's plan. A no-op when none is assigned.
     */
    void unassignRoutingPolicy(long connectedUserId);

    /**
     * Sets the connected user's spend cap in USD; {@code null} clears it back to the embedded default cap. A negative
     * cap is refused.
     */
    void updateBudgetCap(long connectedUserId, BigDecimal budgetCap);
}
```

- [ ] **Step 3: Write the failing unit test**

```java
package com.bytechef.ee.embedded.connected.user.gateway.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProvider;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProviderType;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingPolicy;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingStrategyType;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayConnectedUserSettingsService;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayProviderService;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayRoutingPolicyService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class ConnectedUserAiGatewayFacadeTest {

    private static final long CONNECTED_USER_ID = 5L;
    private static final long MISSING_CONNECTED_USER_ID = 777L;
    private static final long POLICY_ID = 10L;

    @Mock
    private AiGatewayConnectedUserSettingsService aiGatewayConnectedUserSettingsService;

    @Mock
    private AiGatewayProviderService aiGatewayProviderService;

    @Mock
    private AiGatewayRoutingPolicyService aiGatewayRoutingPolicyService;

    @Mock
    private ConnectedUserService connectedUserService;

    private ConnectedUserAiGatewayFacadeImpl connectedUserAiGatewayFacade;

    @BeforeEach
    void setUp() {
        connectedUserAiGatewayFacade = new ConnectedUserAiGatewayFacadeImpl(
            aiGatewayConnectedUserSettingsService, aiGatewayProviderService, aiGatewayRoutingPolicyService,
            connectedUserService);
    }

    @Test
    void testAssignRoutingPolicyAssignsATenantLevelPolicy() {
        when(connectedUserService.fetchConnectedUser(CONNECTED_USER_ID)).thenReturn(Optional.of(new ConnectedUser()));
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(POLICY_ID)).thenReturn(policy(POLICY_ID));

        connectedUserAiGatewayFacade.assignRoutingPolicy(CONNECTED_USER_ID, POLICY_ID);

        verify(aiGatewayConnectedUserSettingsService).assignRoutingPolicy(CONNECTED_USER_ID, POLICY_ID);
    }

    @Test
    void testAssignRoutingPolicyRefusesAWorkspaceScopedPolicy() {
        AiGatewayRoutingPolicy workspacePolicy = policy(POLICY_ID);

        workspacePolicy.setWorkspaceId(42L);

        when(connectedUserService.fetchConnectedUser(CONNECTED_USER_ID)).thenReturn(Optional.of(new ConnectedUser()));
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(POLICY_ID)).thenReturn(workspacePolicy);

        assertThatThrownBy(() -> connectedUserAiGatewayFacade.assignRoutingPolicy(CONNECTED_USER_ID, POLICY_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Routing policy 10 belongs to a workspace and cannot be assigned to a connected user");

        verify(aiGatewayConnectedUserSettingsService, never()).assignRoutingPolicy(anyLong(), anyLong());
    }

    /**
     * Every operation checks the connected user first and fails the same way for a foreign id and a missing one, so no
     * operation can be used as an enumeration oracle, and nothing is read or written for an unknown customer.
     */
    @Test
    void testEveryOperationRefusesAnUnknownConnectedUserIdenticallyAndTouchesNothing() {
        when(connectedUserService.fetchConnectedUser(MISSING_CONNECTED_USER_ID)).thenReturn(Optional.empty());

        List<ThrowingCallable> operations = List.of(
            () -> connectedUserAiGatewayFacade.assignRoutingPolicy(MISSING_CONNECTED_USER_ID, POLICY_ID),
            () -> connectedUserAiGatewayFacade.unassignRoutingPolicy(MISSING_CONNECTED_USER_ID),
            () -> connectedUserAiGatewayFacade.updateBudgetCap(MISSING_CONNECTED_USER_ID, BigDecimal.ONE),
            () -> connectedUserAiGatewayFacade.createProvider(
                MISSING_CONNECTED_USER_ID, new AiGatewayProvider("customer", AiGatewayProviderType.OPENAI, "sk")),
            () -> connectedUserAiGatewayFacade.deleteProvider(MISSING_CONNECTED_USER_ID, 3L));

        for (ThrowingCallable operation : operations) {
            assertThatThrownBy(operation)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Connected user not found: " + MISSING_CONNECTED_USER_ID);
        }

        verifyNoInteractions(
            aiGatewayConnectedUserSettingsService, aiGatewayProviderService, aiGatewayRoutingPolicyService);
    }

    @Test
    void testUpdateBudgetCapDelegatesForAKnownConnectedUser() {
        when(connectedUserService.fetchConnectedUser(CONNECTED_USER_ID)).thenReturn(Optional.of(new ConnectedUser()));

        connectedUserAiGatewayFacade.updateBudgetCap(CONNECTED_USER_ID, new BigDecimal("25.00"));

        verify(aiGatewayConnectedUserSettingsService).updateBudgetCap(CONNECTED_USER_ID, new BigDecimal("25.00"));
    }

    @Test
    void testCreateProviderCreatesAProviderOwnedByTheConnectedUser() {
        AiGatewayProvider provider = new AiGatewayProvider("customer", AiGatewayProviderType.OPENAI, "sk");

        when(connectedUserService.fetchConnectedUser(CONNECTED_USER_ID)).thenReturn(Optional.of(new ConnectedUser()));
        when(aiGatewayProviderService.createConnectedUserProvider(provider, CONNECTED_USER_ID)).thenReturn(provider);

        assertThat(connectedUserAiGatewayFacade.createProvider(CONNECTED_USER_ID, provider)).isSameAs(provider);
    }

    @Test
    void testDeleteProviderRefusesAProviderTheConnectedUserDoesNotOwn() {
        when(connectedUserService.fetchConnectedUser(CONNECTED_USER_ID)).thenReturn(Optional.of(new ConnectedUser()));
        when(aiGatewayProviderService.getProvidersByConnectedUserId(CONNECTED_USER_ID))
            .thenReturn(List.of(providerWithId(3L)));

        assertThatThrownBy(() -> connectedUserAiGatewayFacade.deleteProvider(CONNECTED_USER_ID, 4L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Provider not found: 4");

        verify(aiGatewayProviderService, never()).delete(anyLong());
    }

    @Test
    void testDeleteProviderDeletesTheConnectedUsersOwnProvider() {
        when(connectedUserService.fetchConnectedUser(CONNECTED_USER_ID)).thenReturn(Optional.of(new ConnectedUser()));
        when(aiGatewayProviderService.getProvidersByConnectedUserId(CONNECTED_USER_ID))
            .thenReturn(List.of(providerWithId(3L)));

        connectedUserAiGatewayFacade.deleteProvider(CONNECTED_USER_ID, 3L);

        verify(aiGatewayProviderService).delete(3L);
    }

    @Test
    void testUnassignRoutingPolicyDelegatesForAKnownConnectedUser() {
        when(connectedUserService.fetchConnectedUser(CONNECTED_USER_ID)).thenReturn(Optional.of(new ConnectedUser()));

        connectedUserAiGatewayFacade.unassignRoutingPolicy(CONNECTED_USER_ID);

        verify(aiGatewayConnectedUserSettingsService).unassignRoutingPolicy(CONNECTED_USER_ID);
    }

    private static AiGatewayRoutingPolicy policy(long id) {
        AiGatewayRoutingPolicy policy = new AiGatewayRoutingPolicy("plan-" + id, AiGatewayRoutingStrategyType.SIMPLE);

        ReflectionTestUtils.setField(policy, "id", id);

        return policy;
    }

    private static AiGatewayProvider providerWithId(long id) {
        AiGatewayProvider provider = new AiGatewayProvider("customer", AiGatewayProviderType.OPENAI, "sk");

        ReflectionTestUtils.setField(provider, "id", id);

        return provider;
    }
}
```

Keep the Enterprise license header above the package line in every new file.

- [ ] **Step 4: Write the failing authorization test**

```java
package com.bytechef.ee.embedded.connected.user.gateway.facade;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Pins {@link PreAuthorize} on every {@link ConnectedUserAiGatewayFacade} method. Unit tests construct the
 * implementation directly, so no other test exercises method security; this sweep catches a new method added without
 * a guard, or a guard weakened by a refactor.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class ConnectedUserAiGatewayFacadeAuthorizationTest {

    private static final String ADMIN_EXPRESSION = "hasAuthority(\"ROLE_ADMIN\")";

    @Test
    void testEveryFacadeMethodRequiresAdmin() throws NoSuchMethodException {
        Method[] interfaceMethods = ConnectedUserAiGatewayFacade.class.getMethods();

        assertThat(interfaceMethods)
            .as("Sanity check: the sweep is vacuous if the facade declares nothing")
            .hasSize(5);

        for (Method interfaceMethod : interfaceMethods) {
            Method implementationMethod = ConnectedUserAiGatewayFacadeImpl.class.getDeclaredMethod(
                interfaceMethod.getName(), interfaceMethod.getParameterTypes());

            PreAuthorize preAuthorize = implementationMethod.getAnnotation(PreAuthorize.class);

            assertThat(preAuthorize)
                .as("Method '%s' must have @PreAuthorize", interfaceMethod.getName())
                .isNotNull();
            assertThat(preAuthorize.value())
                .as("Method '%s' @PreAuthorize expression", interfaceMethod.getName())
                .isEqualTo(ADMIN_EXPRESSION);
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they fail**

Run: `./gradlew :server:ee:libs:embedded:embedded-connected-user:embedded-connected-user-service:test --tests '*ConnectedUserAiGatewayFacade*' > /tmp/t4.log 2>&1; echo $?`
Expected: non-zero; `compileTestJava FAILED` because `ConnectedUserAiGatewayFacadeImpl` does not exist.

- [ ] **Step 6: Implement the facade**

```java
package com.bytechef.ee.embedded.connected.user.gateway.facade;

import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProvider;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingPolicy;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayConnectedUserSettingsService;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayProviderService;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayRoutingPolicyService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.security.constant.AuthorityConstants;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link ConnectedUserAiGatewayFacade}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@Transactional
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
class ConnectedUserAiGatewayFacadeImpl implements ConnectedUserAiGatewayFacade {

    private final AiGatewayConnectedUserSettingsService aiGatewayConnectedUserSettingsService;
    private final AiGatewayProviderService aiGatewayProviderService;
    private final AiGatewayRoutingPolicyService aiGatewayRoutingPolicyService;
    private final ConnectedUserService connectedUserService;

    @SuppressFBWarnings("EI")
    ConnectedUserAiGatewayFacadeImpl(
        AiGatewayConnectedUserSettingsService aiGatewayConnectedUserSettingsService,
        AiGatewayProviderService aiGatewayProviderService,
        AiGatewayRoutingPolicyService aiGatewayRoutingPolicyService,
        ConnectedUserService connectedUserService) {

        this.aiGatewayConnectedUserSettingsService = aiGatewayConnectedUserSettingsService;
        this.aiGatewayProviderService = aiGatewayProviderService;
        this.aiGatewayRoutingPolicyService = aiGatewayRoutingPolicyService;
        this.connectedUserService = connectedUserService;
    }

    @Override
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public void assignRoutingPolicy(long connectedUserId, long routingPolicyId) {
        requireConnectedUserExists(connectedUserId);

        AiGatewayRoutingPolicy policy = aiGatewayRoutingPolicyService.getRoutingPolicy(routingPolicyId);

        if (policy.getWorkspaceId() != null) {
            throw new IllegalArgumentException(
                "Routing policy " + routingPolicyId + " belongs to a workspace and cannot be assigned to a " +
                    "connected user");
        }

        aiGatewayConnectedUserSettingsService.assignRoutingPolicy(connectedUserId, routingPolicyId);
    }

    @Override
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public AiGatewayProvider createProvider(long connectedUserId, AiGatewayProvider provider) {
        requireConnectedUserExists(connectedUserId);

        return aiGatewayProviderService.createConnectedUserProvider(provider, connectedUserId);
    }

    @Override
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public void deleteProvider(long connectedUserId, long providerId) {
        requireConnectedUserExists(connectedUserId);

        List<AiGatewayProvider> connectedUserProviders =
            aiGatewayProviderService.getProvidersByConnectedUserId(connectedUserId);

        boolean ownsProvider = connectedUserProviders.stream()
            .anyMatch(provider -> Objects.equals(provider.getId(), providerId));

        if (!ownsProvider) {
            throw new IllegalArgumentException("Provider not found: " + providerId);
        }

        aiGatewayProviderService.delete(providerId);
    }

    @Override
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public void unassignRoutingPolicy(long connectedUserId) {
        requireConnectedUserExists(connectedUserId);

        aiGatewayConnectedUserSettingsService.unassignRoutingPolicy(connectedUserId);
    }

    @Override
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public void updateBudgetCap(long connectedUserId, BigDecimal budgetCap) {
        requireConnectedUserExists(connectedUserId);

        aiGatewayConnectedUserSettingsService.updateBudgetCap(connectedUserId, budgetCap);
    }

    /**
     * Resolves the connected user within the caller's tenant schema: {@code BaseDataSource} sets the connection's
     * {@code search_path}, so a foreign id is invisible rather than rejected, and fails exactly as a missing one.
     */
    private void requireConnectedUserExists(long connectedUserId) {
        if (connectedUserService.fetchConnectedUser(connectedUserId)
            .isEmpty()) {

            throw new IllegalArgumentException("Connected user not found: " + connectedUserId);
        }
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: the Step 5 command.
Expected: `0`, no `FAILED`.

- [ ] **Step 8: Write the facade integration test**

`AiGatewayIntTestConfiguration` already scans `com.bytechef.ee.embedded.connected.user.gateway.facade`, so the new implementation is picked up. The test context does not enable method security; the facade is called directly, as the old binding IntTest does.

```java
package com.bytechef.ee.automation.ai.gateway.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bytechef.ee.automation.ai.gateway.config.AiGatewayIntTestConfiguration;
import com.bytechef.ee.automation.ai.gateway.service.AiGatewayIntTestConfigurationSharedMocks;
import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.embedded.connected.user.gateway.facade.ConnectedUserAiGatewayFacade;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProvider;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProviderType;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingPolicy;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingStrategyType;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayConnectedUserSettingsService;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayRoutingPolicyService;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link ConnectedUserAiGatewayFacade} against a real Postgres database: shared plans, the workspace refusal, and the
 * unique index on a customer's providers surfacing as a domain error rather than a constraint violation.
 *
 * @version ee
 */
@ActiveProfiles("testint")
@SpringBootTest(classes = AiGatewayIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
@AiGatewayIntTestConfigurationSharedMocks
class ConnectedUserAiGatewayFacadeIntTest {

    @Autowired
    private AiGatewayConnectedUserSettingsService aiGatewayConnectedUserSettingsService;

    @Autowired
    private AiGatewayRoutingPolicyService aiGatewayRoutingPolicyService;

    @Autowired
    private ConnectedUserAiGatewayFacade connectedUserAiGatewayFacade;

    @Autowired
    private ConnectedUserService connectedUserService;

    @Test
    void testTwoConnectedUsersShareOnePlanAndReassigningOneLeavesTheOtherAlone() {
        long environmentId = Environment.STAGING.ordinal();

        AiGatewayRoutingPolicy firstPlan = aiGatewayRoutingPolicyService.create(
            new AiGatewayRoutingPolicy("facade-it-first-plan", AiGatewayRoutingStrategyType.SIMPLE));
        AiGatewayRoutingPolicy secondPlan = aiGatewayRoutingPolicyService.create(
            new AiGatewayRoutingPolicy("facade-it-second-plan", AiGatewayRoutingStrategyType.SIMPLE));

        ConnectedUser firstCustomer = connectedUserService.createConnectedUser("facade-it-first", environmentId);
        ConnectedUser secondCustomer = connectedUserService.createConnectedUser("facade-it-second", environmentId);

        connectedUserAiGatewayFacade.assignRoutingPolicy(firstCustomer.getId(), firstPlan.getId());
        connectedUserAiGatewayFacade.assignRoutingPolicy(secondCustomer.getId(), firstPlan.getId());

        connectedUserAiGatewayFacade.assignRoutingPolicy(firstCustomer.getId(), secondPlan.getId());

        assertThat(aiGatewayConnectedUserSettingsService.fetchByConnectedUserId(firstCustomer.getId()))
            .hasValueSatisfying(settings -> assertThat(settings.getRoutingPolicyId()).isEqualTo(secondPlan.getId()));
        assertThat(aiGatewayConnectedUserSettingsService.fetchByConnectedUserId(secondCustomer.getId()))
            .as("Reassigning one customer must not move another customer on the same plan")
            .hasValueSatisfying(settings -> assertThat(settings.getRoutingPolicyId()).isEqualTo(firstPlan.getId()));
    }

    @Test
    void testAssigningAWorkspaceScopedPolicyIsRefusedAndWritesNothing() {
        AiGatewayRoutingPolicy workspacePolicy = aiGatewayRoutingPolicyService.create(
            new AiGatewayRoutingPolicy("facade-it-workspace-policy", AiGatewayRoutingStrategyType.SIMPLE));

        aiGatewayRoutingPolicyService.updateWorkspaceId(workspacePolicy.getId(), 909_778L);

        ConnectedUser connectedUser = connectedUserService.createConnectedUser(
            "facade-it-workspace-user", Environment.STAGING.ordinal());

        assertThatThrownBy(
            () -> connectedUserAiGatewayFacade.assignRoutingPolicy(connectedUser.getId(), workspacePolicy.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belongs to a workspace");

        assertThat(aiGatewayConnectedUserSettingsService.fetchByConnectedUserId(connectedUser.getId())).isEmpty();
    }

    @Test
    void testASecondProviderOfTheSameTypeIsRefusedWithADomainError() {
        ConnectedUser connectedUser = connectedUserService.createConnectedUser(
            "facade-it-provider-user", Environment.STAGING.ordinal());

        connectedUserAiGatewayFacade.createProvider(
            connectedUser.getId(),
            new AiGatewayProvider("facade-it-first-cohere", AiGatewayProviderType.COHERE, "sk-test-" + UUID.randomUUID()));

        assertThatThrownBy(
            () -> connectedUserAiGatewayFacade.createProvider(
                connectedUser.getId(),
                new AiGatewayProvider(
                    "facade-it-second-cohere", AiGatewayProviderType.COHERE, "sk-test-" + UUID.randomUUID())))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("already has a provider of type COHERE");
    }
}
```

- [ ] **Step 9: Run the integration test**

Run: `./gradlew :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:testIntegration --tests '*ConnectedUserAiGatewayFacadeIntTest' > /tmp/t4-int.log 2>&1; echo $?`
Expected: `0`, no `FAILED`.

- [ ] **Step 10: Commit**

```bash
git add server/ee/libs/embedded/embedded-connected-user server/ee/libs/automation/automation-ai/automation-ai-gateway/automation-ai-gateway-service/src/test
git commit -m "- Add the connected-user AI gateway management facade"
```

### Task 5: Connected-user deletion

**Files:**
- Modify: `CU/embedded-connected-user-service/src/main/java/com/bytechef/ee/embedded/connected/user/event/ConnectedUserBeforeDeleteEventListener.java`
- Test: `CU/embedded-connected-user-service/src/test/java/com/bytechef/ee/embedded/connected/user/event/ConnectedUserBeforeDeleteEventListenerTest.java`
- Modify: `AUTO/src/test/java/com/bytechef/ee/automation/ai/gateway/facade/ConnectedUserAiGatewayFacadeIntTest.java` (created in Task 4)
- Modify: `AUTO/src/test/java/com/bytechef/ee/automation/ai/gateway/facade/ConnectedUserAiGatewayRoutingPolicyBindingIntTest.java`

**Interfaces:**
- Consumes: `AiGatewayConnectedUserSettingsService.deleteByConnectedUserId(long)` (Task 1), `AiGatewayProviderService.disableByConnectedUserId(long)` (Task 3).
- Produces: `ConnectedUserBeforeDeleteEventListener(AiGatewayConnectedUserSettingsService, AiGatewayProviderService)`.

- [ ] **Step 1: Rewrite the listener test**

Replace the body of `ConnectedUserBeforeDeleteEventListenerTest` from the class declaration down, and its imports, with:

```java
package com.bytechef.ee.embedded.connected.user.event;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayConnectedUserSettingsService;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayProviderService;
import org.junit.jupiter.api.Test;
import org.springframework.data.relational.core.mapping.event.BeforeDeleteEvent;
import org.springframework.data.relational.core.mapping.event.Identifier;

/**
 * Unit tests for {@link ConnectedUserBeforeDeleteEventListener}, a plain unit test invoking {@code onBeforeDelete}
 * directly, since Spring Data JDBC's relational event publication is infrastructure this listener does not own.
 * {@code ConnectedUserAiGatewayFacadeIntTest} proves the real delete fires it.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class ConnectedUserBeforeDeleteEventListenerTest {

    private static final long CONNECTED_USER_ID = 42L;

    private final AiGatewayConnectedUserSettingsService aiGatewayConnectedUserSettingsService =
        mock(AiGatewayConnectedUserSettingsService.class);
    private final AiGatewayProviderService aiGatewayProviderService = mock(AiGatewayProviderService.class);

    private final ConnectedUserBeforeDeleteEventListener connectedUserBeforeDeleteEventListener =
        new ConnectedUserBeforeDeleteEventListener(aiGatewayConnectedUserSettingsService, aiGatewayProviderService);

    @Test
    void testDeletingAConnectedUserDeletesItsSettingsRow() {
        connectedUserBeforeDeleteEventListener.onBeforeDelete(event());

        verify(aiGatewayConnectedUserSettingsService).deleteByConnectedUserId(CONNECTED_USER_ID);
    }

    /**
     * Disabled, not released: clearing {@code connected_user_id} would turn the customer's key into a tenant provider
     * every other customer routes through.
     */
    @Test
    void testDeletingAConnectedUserDisablesItsProviders() {
        connectedUserBeforeDeleteEventListener.onBeforeDelete(event());

        verify(aiGatewayProviderService).disableByConnectedUserId(CONNECTED_USER_ID);
    }

    @SuppressWarnings("unchecked")
    private static BeforeDeleteEvent<ConnectedUser> event() {
        BeforeDeleteEvent<ConnectedUser> beforeDeleteEvent = mock(BeforeDeleteEvent.class);
        Identifier identifier = mock(Identifier.class);

        when(beforeDeleteEvent.getId()).thenReturn(identifier);
        when(identifier.getValue()).thenReturn(CONNECTED_USER_ID);

        return beforeDeleteEvent;
    }
}
```

Keep the Enterprise license header above the package line.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:ee:libs:embedded:embedded-connected-user:embedded-connected-user-service:test --tests '*ConnectedUserBeforeDeleteEventListenerTest' > /tmp/t5.log 2>&1; echo $?`
Expected: non-zero; `compileTestJava FAILED` on the two-argument constructor.

- [ ] **Step 3: Implement the listener**

Replace the class Javadoc, fields, constructor and `onBeforeDelete` in `ConnectedUserBeforeDeleteEventListener`:

```java
/**
 * Removes a connected user's AI Gateway state before the connected user row is removed: deletes its settings row
 * (assigned plan and spend cap) and disables -- never releases -- its own provider credentials. Releasing a credential
 * by clearing {@code connected_user_id} would make the key a tenant provider every other customer routes through;
 * deleting it automatically would destroy a credential an admin may still need to audit or rotate. The assigned plan
 * itself is a shared, vendor-owned routing policy and is untouched.
 *
 * <p>
 * A Spring Data JDBC relational event hook rather than a database foreign key, because the platform tier's schema
 * does not reference the embedded tier's {@code connected_user} table.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
public class ConnectedUserBeforeDeleteEventListener extends AbstractRelationalEventListener<ConnectedUser> {

    private final AiGatewayConnectedUserSettingsService aiGatewayConnectedUserSettingsService;
    private final AiGatewayProviderService aiGatewayProviderService;

    @SuppressFBWarnings("EI")
    public ConnectedUserBeforeDeleteEventListener(
        AiGatewayConnectedUserSettingsService aiGatewayConnectedUserSettingsService,
        AiGatewayProviderService aiGatewayProviderService) {

        this.aiGatewayConnectedUserSettingsService = aiGatewayConnectedUserSettingsService;
        this.aiGatewayProviderService = aiGatewayProviderService;
    }

    @Override
    protected void onBeforeDelete(BeforeDeleteEvent<ConnectedUser> event) {
        Identifier identifier = event.getId();

        long connectedUserId = (Long) identifier.getValue();

        aiGatewayConnectedUserSettingsService.deleteByConnectedUserId(connectedUserId);
        aiGatewayProviderService.disableByConnectedUserId(connectedUserId);
    }
}
```

Replace the `AiGatewayRoutingPolicy`, `AiGatewayRoutingPolicyService` and `java.util.Optional` imports with `AiGatewayConnectedUserSettingsService` and `AiGatewayProviderService`.

- [ ] **Step 4: Run the test to verify it passes**

Run: the Step 2 command.
Expected: `0`, no `FAILED`.

- [ ] **Step 5: Replace the old delete IntTest with the new one**

In `ConnectedUserAiGatewayRoutingPolicyBindingIntTest`, delete `testDeletingAConnectedUserUnbindsButKeepsThePolicy` and the Javadoc paragraph in the class comment that names it. The listener no longer clears the policy column, so that test now fails by design.

In `ConnectedUserAiGatewayFacadeIntTest`, add `import com.bytechef.ee.platform.ai.gateway.service.AiGatewayProviderService;` (the provider domain imports and `UUID` are already there from Task 4), autowire `AiGatewayProviderService aiGatewayProviderService`, and add:

```java
    /**
     * The listener only fires through Spring Data JDBC's real relational event publication, so only a real delete
     * proves it: the settings row is gone, the customer's credential is disabled but still scoped to the deleted
     * customer (never released tenant-wide), and the shared plan is untouched.
     */
    @Test
    void testDeletingAConnectedUserDeletesItsSettingsAndDisablesButKeepsItsProviders() {
        ConnectedUser connectedUser = connectedUserService.createConnectedUser(
            "delete-settings-user", Environment.PRODUCTION.ordinal());

        AiGatewayRoutingPolicy plan = aiGatewayRoutingPolicyService.create(
            new AiGatewayRoutingPolicy("delete-settings-plan", AiGatewayRoutingStrategyType.SIMPLE));

        connectedUserAiGatewayFacade.assignRoutingPolicy(connectedUser.getId(), plan.getId());

        AiGatewayProvider provider = connectedUserAiGatewayFacade.createProvider(
            connectedUser.getId(),
            new AiGatewayProvider("delete-settings-groq", AiGatewayProviderType.GROQ, "sk-test-" + UUID.randomUUID()));

        connectedUserService.deleteConnectedUser(connectedUser.getId());

        assertThat(aiGatewayConnectedUserSettingsService.fetchByConnectedUserId(connectedUser.getId())).isEmpty();

        AiGatewayProvider reloadedProvider = aiGatewayProviderService.getProvider(provider.getId());

        assertThat(reloadedProvider.isEnabled()).isFalse();
        assertThat(reloadedProvider.getConnectedUserId()).isEqualTo(connectedUser.getId());
        assertThat(aiGatewayRoutingPolicyService.getRoutingPolicy(plan.getId())).isNotNull();
    }
```

- [ ] **Step 6: Run the integration tests**

Run: `./gradlew :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:testIntegration --tests '*ConnectedUserAiGatewayFacadeIntTest' --tests '*ConnectedUserAiGatewayRoutingPolicyBindingIntTest' > /tmp/t5-int.log 2>&1; echo $?`
Expected: `0`, no `FAILED`. Docker must be running.

- [ ] **Step 7: Commit**

```bash
git add server/ee/libs/embedded/embedded-connected-user server/ee/libs/automation/automation-ai/automation-ai-gateway/automation-ai-gateway-service/src/test
git commit -m "- Delete a connected user's AI gateway settings and disable its credentials on delete"
```

### Task 6: Resolve the plan and cap from the settings row

**Files:**
- Modify: `AUTO/src/main/java/com/bytechef/ee/automation/ai/gateway/facade/AiGatewayFacadeImpl.java`
- Test: `AUTO/src/test/java/com/bytechef/ee/automation/ai/gateway/facade/AiGatewayFacadeTest.java`
- Test: `AUTO/src/test/java/com/bytechef/ee/automation/ai/gateway/facade/EmbeddedAiGatewayPhase2IntTest.java`

**Interfaces:**
- Consumes: `AiGatewayConnectedUserSettingsService.fetchByConnectedUserId(long)` (Task 1); `ConnectedUserAiGatewayFacade.assignRoutingPolicy(long connectedUserId, long routingPolicyId)` and `updateBudgetCap(long, BigDecimal)` (Task 4).
- Produces: `AiGatewayFacadeImpl`'s constructor gains `AiGatewayConnectedUserSettingsService aiGatewayConnectedUserSettingsService` directly after `AiGatewayChatModelFactory aiGatewayChatModelFactory`. Behaviour: level 2 of the routing chain is the connected user's assigned, enabled plan; an embedded request naming any policy other than its assigned plan fails with `Routing policy not found: <name>`; a model or embedded default pointing at a workspace-scoped policy falls back to direct routing for embedded traffic; the connected user's `budgetCap` overrides the embedded default cap.

`requireRoutingPolicyUsableByConnectedUser` (the old cross-customer check on the routed paths) is left in place; Task 7 removes it with the policy column.

- [ ] **Step 1: Wire the new collaborator into the unit test**

In `AiGatewayFacadeTest`, add imports `com.bytechef.ee.platform.ai.gateway.domain.AiGatewayConnectedUserSettings` and `com.bytechef.ee.platform.ai.gateway.service.AiGatewayConnectedUserSettingsService`, the mock, and pass it in `buildFacade` directly after `aiGatewayChatModelFactory`:

```java
    @Mock
    private AiGatewayConnectedUserSettingsService aiGatewayConnectedUserSettingsService;
```

```java
        return new AiGatewayFacadeImpl(
            aiEvalExecutor, aiGatewayBudgetChecker, aiGatewayRateLimitChecker,
            aiGatewayChatModelFactory, aiGatewayConnectedUserSettingsService, aiGatewayContextCompressor,
```

Add the helper next to `authenticateApiKeyPrincipal`:

```java
    private static AiGatewayConnectedUserSettings connectedUserSettings(
        long connectedUserId, Long routingPolicyId, BigDecimal budgetCap) {

        AiGatewayConnectedUserSettings settings = new AiGatewayConnectedUserSettings(connectedUserId);

        settings.setRoutingPolicyId(routingPolicyId);
        settings.setBudgetCap(budgetCap);

        return settings;
    }
```

- [ ] **Step 2: Rewrite the tests whose premise was a customer-owned policy**

Apply each change inside the named test:

1. `testModelDefaultBoundToAnotherConnectedUserFallsBackToDirectRouting` — rename to `testModelDefaultBelongingToAWorkspaceFallsBackToDirectRoutingForEmbeddedTraffic`; replace `otherCustomersPolicy.setConnectedUserId(7L);` with `otherCustomersPolicy.setWorkspaceId(7L);`; delete the `when(aiGatewayRoutingPolicyService.fetchRoutingPolicyByConnectedUserId(5L)).thenReturn(Optional.empty());` stub. Replace its Javadoc's first paragraph with: `A model default may point at a workspace-scoped policy, which embedded traffic has no workspace to route through. The default is dropped and the request routes direct, exactly as a deleted default does.`

2. `testConnectedUserPolicyWinsOverTheModelDefaultAndTheEmbeddedDefault` — delete `connectedUserPolicy.setConnectedUserId(5L);` and replace the two stubs `fetchRoutingPolicyByConnectedUserId(5L)` and `getRoutingPolicy(7L)` with:

```java
        when(aiGatewayConnectedUserSettingsService.fetchByConnectedUserId(5L))
            .thenReturn(Optional.of(connectedUserSettings(5L, 7L, null)));
        // First call: the enabled check on the assigned plan. Second: the chain's rewrite of the request, made to fail
        // so the request falls back to direct dispatch and the assertion stays on precedence.
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(7L))
            .thenReturn(connectedUserPolicy)
            .thenThrow(new IllegalArgumentException("policy not found"));
```

3. `testDisabledConnectedUserPolicyFallsThroughToTheEmbeddedDefault` — delete `connectedUserPolicy.setConnectedUserId(5L);` and replace the `fetchRoutingPolicyByConnectedUserId(5L)` stub with:

```java
        when(aiGatewayConnectedUserSettingsService.fetchByConnectedUserId(5L))
            .thenReturn(Optional.of(connectedUserSettings(5L, 7L, null)));
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(7L)).thenReturn(connectedUserPolicy);
```

4. `testNullConnectedUserIdSkipsConnectedUserPolicyResolutionEntirely` — replace `verify(aiGatewayRoutingPolicyService, never()).fetchRoutingPolicyByConnectedUserId(anyLong());` with `verifyNoInteractions(aiGatewayConnectedUserSettingsService);`.

5. `testChatCompletionAttributesSpendWhenRequestCarriesExplicitRoutingPolicy`, `testChatCompletionUsesConnectedUsersOwnProviderOverTheTenantProviderOnTheRoutedPath` and `testChatCompletionRejectsATenantProviderScopedToADifferentConnectedUser` — each names `my-routing-policy` (id 10) as customer 5. Directly after the `getRoutingPolicyByName("my-routing-policy")` stub in each, add:

```java
        when(aiGatewayConnectedUserSettingsService.fetchByConnectedUserId(5L))
            .thenReturn(Optional.of(connectedUserSettings(5L, 10L, null)));
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(10L)).thenReturn(routingPolicy);
```

6. `testChatCompletionRejectsARoutingPolicyBoundToADifferentConnectedUser` — replace the whole test, Javadoc included, with:

```java
    /**
     * An embedded caller may name only its own assigned plan. The endpoint accepts end-user JWTs, so letting a caller
     * name any plan would be a self-service upgrade. No plan lookup by name happens at all.
     */
    @Test
    void testEmbeddedRequestNamingAPlanItIsNotAssignedIsRejected() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, false, "premium-plan", null, null, null, null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> aiGatewayFacade.chatCompletion(request, null, null, 5L));

        assertEquals("Routing policy not found: premium-plan", exception.getMessage());

        verify(aiGatewayRoutingPolicyService, never()).getRoutingPolicyByName(any());
        verifyNoInteractions(aiGatewayModelDeploymentService, aiGatewayChatModelFactory);
    }
```

7. `testChatCompletionWithConnectedUserScopedRoutingPolicyIsUnaffectedForAutomationTraffic` — rename to `testChatCompletionWithRoutingPolicyIsUnaffectedForAutomationTraffic` and delete `routingPolicy.setConnectedUserId(99L);`.

8. `testUnknownRoutingPolicyAndAnotherConnectedUsersRoutingPolicyProduceIdenticalErrors` — replace the whole test, Javadoc included, with:

```java
    /**
     * A plan that does not exist and a plan that exists but is not the caller's are indistinguishable to the caller:
     * same exception type, same message template.
     */
    @Test
    void testUnknownPlanAndAnUnassignedPlanProduceIdenticalErrors() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayRoutingPolicy assignedPlan = new AiGatewayRoutingPolicy("assigned-plan", AiGatewayRoutingStrategyType.SIMPLE);

        ReflectionTestUtils.setField(assignedPlan, "id", 12L);

        when(aiGatewayConnectedUserSettingsService.fetchByConnectedUserId(5L))
            .thenReturn(Optional.of(connectedUserSettings(5L, 12L, null)));
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(12L)).thenReturn(assignedPlan);

        IllegalArgumentException missingPlanException = assertThrows(IllegalArgumentException.class,
            () -> aiGatewayFacade.chatCompletion(requestNamingPlan("missing-plan"), null, null, 5L));
        IllegalArgumentException unassignedPlanException = assertThrows(IllegalArgumentException.class,
            () -> aiGatewayFacade.chatCompletion(requestNamingPlan("other-plan"), null, null, 5L));

        assertEquals("Routing policy not found: missing-plan", missingPlanException.getMessage());
        assertEquals("Routing policy not found: other-plan", unassignedPlanException.getMessage());
    }

    private static AiGatewayChatCompletionRequest requestNamingPlan(String planName) {
        return new AiGatewayChatCompletionRequest(
            "openai/gpt-4", List.of(new AiGatewayChatMessage("user", "Hello")),
            null, null, null, false, planName, null, null, null, null);
    }
```

- [ ] **Step 3: Add the cap tests**

```java
    @Test
    void testConnectedUsersOwnCapOverridesTheEmbeddedDefaultCap() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        when(embeddedSettingsService.find(ENVIRONMENT_ID))
            .thenReturn(Optional.of(
                new AiGatewayEmbeddedSettings(
                    ENVIRONMENT_ID, null, null, null, null, null, null, null, new BigDecimal("50.00"))));
        when(aiGatewayConnectedUserSettingsService.fetchByConnectedUserId(5L))
            .thenReturn(Optional.of(connectedUserSettings(5L, null, new BigDecimal("5.00"))));
        when(aiGatewaySpendService.getTotalCostByConnectedUserId(eq(5L), any(), any()))
            .thenReturn(Money.usd(new BigDecimal("10.00")));

        assertThrows(BudgetExceededException.class,
            () -> aiGatewayFacade.chatCompletion(createConnectedUserRequest(), null, null, 5L));

        verifyNoInteractions(aiGatewayChatModelFactory);
    }

    @Test
    void testEmbeddedDefaultCapAppliesWhenTheConnectedUserSetsNoCap() {
        authenticateApiKeyPrincipal(ENVIRONMENT_ID);

        AiGatewayChatCompletionRequest request = createConnectedUserRequest();

        stubDirectChatCompletion(request);

        when(embeddedSettingsService.find(ENVIRONMENT_ID))
            .thenReturn(Optional.of(
                new AiGatewayEmbeddedSettings(
                    ENVIRONMENT_ID, null, null, null, null, null, null, null, new BigDecimal("50.00"))));
        when(aiGatewayConnectedUserSettingsService.fetchByConnectedUserId(5L))
            .thenReturn(Optional.of(connectedUserSettings(5L, null, null)));
        when(aiGatewaySpendService.getTotalCostByConnectedUserId(eq(5L), any(), any()))
            .thenReturn(Money.usd(new BigDecimal("10.00")));

        assertNotNull(aiGatewayFacade.chatCompletion(request, null, null, 5L));
    }
```

- [ ] **Step 4: Run the unit tests to verify they fail**

Run: `./gradlew :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:test --tests '*AiGatewayFacadeTest' > /tmp/t6.log 2>&1; echo $?`
Expected: non-zero; `compileTestJava FAILED` on the new constructor argument.

- [ ] **Step 5: Implement in `AiGatewayFacadeImpl`**

1. Add imports `com.bytechef.ee.platform.ai.gateway.domain.AiGatewayConnectedUserSettings` and `com.bytechef.ee.platform.ai.gateway.service.AiGatewayConnectedUserSettingsService`. Add the field `private final AiGatewayConnectedUserSettingsService aiGatewayConnectedUserSettingsService;`, the constructor parameter directly after `AiGatewayChatModelFactory aiGatewayChatModelFactory,`, and `this.aiGatewayConnectedUserSettingsService = aiGatewayConnectedUserSettingsService;`.

2. Add the record at the end of the class, beside the existing private records:

```java
    /**
     * The two settings rows an embedded request reads, each resolved once at request entry: the environment's embedded
     * settings (default plan, default cap) and the connected user's own settings (assigned plan, cap override). Both
     * are empty for automation traffic.
     */
    private record EmbeddedRequestSettings(
        Optional<AiGatewayEmbeddedSettings> embeddedSettings,
        Optional<AiGatewayConnectedUserSettings> connectedUserSettings) {

        private static final EmbeddedRequestSettings NONE =
            new EmbeddedRequestSettings(Optional.empty(), Optional.empty());
    }
```

3. Replace `resolveEmbeddedSettingsAndCheckBudget` (keep its Javadoc, changing "the settings row" to "both settings rows"):

```java
    private EmbeddedRequestSettings resolveEmbeddedSettingsAndCheckBudget(
        @Nullable Long connectedUserId, long environmentId) {

        if (connectedUserId == null) {
            return EmbeddedRequestSettings.NONE;
        }

        EmbeddedRequestSettings requestSettings = new EmbeddedRequestSettings(
            resolveEmbeddedSettingsOnce(connectedUserId, environmentId),
            aiGatewayConnectedUserSettingsService.fetchByConnectedUserId(connectedUserId));

        checkConnectedUserBudget(connectedUserId, requestSettings);

        return requestSettings;
    }
```

4. In `chatCompletion` and `chatCompletionStreamInternal`, change

```java
        Optional<AiGatewayEmbeddedSettings> embeddedSettings =
            resolveEmbeddedSettingsAndCheckBudget(connectedUserId, environmentId);
```

to `EmbeddedRequestSettings requestSettings = resolveEmbeddedSettingsAndCheckBudget(connectedUserId, environmentId);`, and in each method change the `applyRoutingPolicyPrecedence(..., connectedUserId, embeddedSettings)` argument to `requestSettings`. Run `grep -n "embeddedSettings" AiGatewayFacadeImpl.java` afterwards: no remaining use may sit in those two methods.

5. Replace the head of `checkConnectedUserBudget`, from its signature through `if (cap == null) { return; }`, with:

```java
    private void checkConnectedUserBudget(long connectedUserId, EmbeddedRequestSettings requestSettings) {
        BigDecimal cap = resolveBudgetCap(requestSettings);

        if (cap == null) {
            return;
        }
```

and add:

```java
    /**
     * The connected user's own cap when set, otherwise the environment's default cap, otherwise none.
     */
    private static @Nullable BigDecimal resolveBudgetCap(EmbeddedRequestSettings requestSettings) {
        BigDecimal connectedUserCap = requestSettings.connectedUserSettings()
            .map(AiGatewayConnectedUserSettings::getBudgetCap)
            .orElse(null);

        if (connectedUserCap != null) {
            return connectedUserCap;
        }

        return requestSettings.embeddedSettings()
            .map(AiGatewayEmbeddedSettings::defaultConnectedUserBudgetCap)
            .orElse(null);
    }
```

6. Replace `applyRoutingPolicyPrecedence` and `resolveConnectedUserRoutingPolicyId` (keep the precedence Javadoc, replacing "the connected user's own policy" with "the connected user's assigned plan"):

```java
    private AiGatewayChatCompletionRequest applyRoutingPolicyPrecedence(
        AiGatewayChatCompletionRequest request, @Nullable Long connectedUserId,
        EmbeddedRequestSettings requestSettings) {

        String requestedRoutingPolicy = request.routingPolicy();

        if (requestedRoutingPolicy != null) {
            requireRequestedRoutingPolicyIsAssignedPlan(requestedRoutingPolicy, connectedUserId, requestSettings);

            return request;
        }

        Long routingPolicyId = resolveAssignedRoutingPolicyId(requestSettings);

        if (routingPolicyId == null) {
            routingPolicyId = resolveModelDefaultRoutingPolicyId(request.model());
        }

        if (routingPolicyId == null) {
            routingPolicyId = resolveEmbeddedDefaultRoutingPolicyId(connectedUserId, requestSettings.embeddedSettings());
        }

        return applyResolvedRoutingPolicy(request, routingPolicyId, connectedUserId);
    }

    /**
     * The connected user's assigned plan, or {@code null} when none is assigned or the plan is disabled -- a disabled
     * plan falls through to the next level rather than routing through a policy an operator turned off.
     */
    private @Nullable Long resolveAssignedRoutingPolicyId(EmbeddedRequestSettings requestSettings) {
        Long assignedRoutingPolicyId = requestSettings.connectedUserSettings()
            .map(AiGatewayConnectedUserSettings::getRoutingPolicyId)
            .orElse(null);

        if (assignedRoutingPolicyId == null) {
            return null;
        }

        try {
            AiGatewayRoutingPolicy assignedPolicy =
                aiGatewayRoutingPolicyService.getRoutingPolicy(assignedRoutingPolicyId);

            return assignedPolicy.isEnabled() ? assignedRoutingPolicyId : null;
        } catch (IllegalArgumentException missingPolicy) {
            return null;
        }
    }

    /**
     * An embedded caller may name only its own assigned plan. The embedded endpoint accepts end-user JWTs as well as
     * the vendor's secret key, and the principal does not record which, so any other rule lets an end user route onto
     * a plan they were never given. Every failure carries the same message a missing policy does, so plans cannot be
     * enumerated. Automation traffic is not checked.
     */
    private void requireRequestedRoutingPolicyIsAssignedPlan(
        String requestedRoutingPolicy, @Nullable Long connectedUserId, EmbeddedRequestSettings requestSettings) {

        if (connectedUserId == null) {
            return;
        }

        Long assignedRoutingPolicyId = requestSettings.connectedUserSettings()
            .map(AiGatewayConnectedUserSettings::getRoutingPolicyId)
            .orElse(null);

        if (assignedRoutingPolicyId == null) {
            throw new IllegalArgumentException("Routing policy not found: " + requestedRoutingPolicy);
        }

        AiGatewayRoutingPolicy assignedPolicy = aiGatewayRoutingPolicyService.getRoutingPolicy(assignedRoutingPolicyId);

        if (!requestedRoutingPolicy.equals(assignedPolicy.getName())) {
            throw new IllegalArgumentException("Routing policy not found: " + requestedRoutingPolicy);
        }
    }
```

7. In `applyResolvedRoutingPolicy`, replace the `policyConnectedUserId` block (from `Long policyConnectedUserId = policy.getConnectedUserId();` through its `return request;` and closing brace) with:

```java
            if (connectedUserId != null && policy.getWorkspaceId() != null) {
                log.warn(
                    "Resolved routing_policy_id={} for model '{}' belongs to workspace {}, which embedded traffic " +
                        "cannot route through; falling back to direct routing",
                    routingPolicyId, request.model(), policy.getWorkspaceId());

                return request;
            }
```

Update that method's Javadoc: "a policy bound to some OTHER connected user" becomes "a workspace-scoped policy".

- [ ] **Step 6: Run the unit tests to verify they pass**

Run: the Step 4 command.
Expected: `0`, no `FAILED`.

- [ ] **Step 7: Move the phase-2 IntTest onto plans**

In `EmbeddedAiGatewayPhase2IntTest`:

1. Replace the `ConnectedUserAiGatewayRoutingPolicyFacade connectedUserAiGatewayRoutingPolicyFacade` field and import with `ConnectedUserAiGatewayFacade connectedUserAiGatewayFacade`.

2. In `testRoutingPolicyPrecedenceCascadesThroughAllFourLevels`, replace `connectedUserAiGatewayRoutingPolicyFacade.bind(connectedUserPolicy.getId(), connectedUser.getId());` with `connectedUserAiGatewayFacade.assignRoutingPolicy(connectedUser.getId(), connectedUserPolicy.getId());`, and replace Stage 4 (from its comment through its assertion) with:

```java
        // Stage 4: naming a plan the connected user is not assigned is refused, never routed -- even while that plan
        // exists and is enabled.
        AiGatewayChatCompletionRequest unassignedPlanRequest =
            buildRequest("anthropic/chain-gpt-4", explicitPolicy.getName());

        assertThatThrownBy(
            () -> aiGatewayFacadeImpl.chatCompletion(unassignedPlanRequest, null, null, connectedUser.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Routing policy not found: " + explicitPolicy.getName());
```

Rename the variable `explicitPolicy` to `unassignedPolicy` throughout the test, and change the test's Javadoc sentence about Stage 4 to match.

3. In `testUnknownIdsAreIndistinguishableFromMissingForPolicyBindingProviderAndSpend`, replace the two `bind` assertions with:

```java
        assertThatThrownBy(() -> connectedUserAiGatewayFacade.assignRoutingPolicy(connectedUser.getId(), unknownId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Routing policy not found");

        assertThatThrownBy(() -> connectedUserAiGatewayFacade.assignRoutingPolicy(unknownId, policy.getId()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Connected user not found");
```

4. Add, next to the cap test:

```java
    @Test
    void testTwoConnectedUsersAssignedOnePlanBothRouteThroughItAndThePlanCannotBeDeleted() {
        AiGatewayProvider provider =
            createEnabledProvider("shared-plan-tenant-gemini", AiGatewayProviderType.GOOGLE_GEMINI);
        AiModel model = createModel(provider.getId(), "shared-plan-model");
        AiGatewayRoutingPolicy plan = createPolicyWithDeployment("shared-premium-plan", model.getId());

        ConnectedUser firstCustomer = connectedUserService.createConnectedUser("shared-plan-first", ENVIRONMENT_ID);
        ConnectedUser secondCustomer = connectedUserService.createConnectedUser("shared-plan-second", ENVIRONMENT_ID);

        connectedUserAiGatewayFacade.assignRoutingPolicy(firstCustomer.getId(), plan.getId());
        connectedUserAiGatewayFacade.assignRoutingPolicy(secondCustomer.getId(), plan.getId());

        AiGatewayChatCompletionRequest request = buildRequest("google-gemini/shared-plan-model", null);

        AiGatewayChatCompletionResponse firstResponse =
            aiGatewayFacadeImpl.chatCompletion(request, null, null, firstCustomer.getId());
        AiGatewayChatCompletionResponse secondResponse =
            aiGatewayFacadeImpl.chatCompletion(request, null, null, secondCustomer.getId());

        assertThat(firstResponse.gatewayMetadata()
            .routingPolicy()).isEqualTo(plan.getName());
        assertThat(secondResponse.gatewayMetadata()
            .routingPolicy()).isEqualTo(plan.getName());

        assertThatThrownBy(() -> aiGatewayRoutingPolicyService.delete(plan.getId()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Routing policy " + plan.getId() + " is assigned to 2 connected users; reassign them first");
    }

    @Test
    void testAConnectedUsersOwnCapOverridesTheEmbeddedDefaultCap() {
        AiGatewayProvider provider = createEnabledProvider("cap-override-tenant-openai", AiGatewayProviderType.OPENAI);

        createModel(provider.getId(), "cap-override-model");

        ConnectedUser cappedCustomer = connectedUserService.createConnectedUser("cap-override-capped", ENVIRONMENT_ID);
        ConnectedUser uncappedCustomer =
            connectedUserService.createConnectedUser("cap-override-uncapped", ENVIRONMENT_ID);

        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse chatResponse = mockChatResponseWithUsage(1_000_000, 0);

        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);
        when(aiGatewayChatModelFactory.getChatModel(any())).thenReturn(chatModel);

        stubEmbeddedSettings(ENVIRONMENT_ID, null, new BigDecimal("100.00"));

        connectedUserAiGatewayFacade.updateBudgetCap(cappedCustomer.getId(), new BigDecimal("0.50"));

        AiGatewayChatCompletionRequest request = buildRequest("openai/cap-override-model", null);

        // Minute-aligned, not hour-aligned: the workspace-less rollup skips a period start that already has a summary,
        // and the other cap test in this class rolls up the hour-aligned window.
        Instant periodStart = Instant.now()
            .truncatedTo(ChronoUnit.MINUTES);

        aiGatewayFacadeImpl.chatCompletion(request, null, null, cappedCustomer.getId());
        aiGatewayFacadeImpl.chatCompletion(request, null, null, uncappedCustomer.getId());

        AiGatewaySpendRollupJob rollupJob = new AiGatewaySpendRollupJob(
            aiGatewaySpendService, aiLlmUsageService, workspaceAiGatewaySpendService);

        rollupJob.rollUp(periodStart, periodStart.plus(2, ChronoUnit.HOURS));

        assertThatThrownBy(() -> aiGatewayFacadeImpl.chatCompletion(request, null, null, cappedCustomer.getId()))
            .as("$1 of spend is over the customer's own $0.50 cap")
            .isInstanceOf(BudgetExceededException.class);

        assertThat(aiGatewayFacadeImpl.chatCompletion(request, null, null, uncappedCustomer.getId()))
            .as("The same $1 is well under the $100 embedded default a customer with no cap of its own gets")
            .isNotNull();
    }
```

- [ ] **Step 8: Run the integration tests**

Run: `./gradlew :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:testIntegration --tests '*EmbeddedAiGateway*IntTest' --tests '*ConnectedUserAiGatewayFacadeIntTest' > /tmp/t6-int.log 2>&1; echo $?`
Expected: `0`, no `FAILED`.

- [ ] **Step 9: Commit**

```bash
git add server/ee/libs/automation/automation-ai/automation-ai-gateway/automation-ai-gateway-service
git commit -m "- Route embedded AI gateway traffic through assigned plans and per-customer caps"
```

### Task 7: Remove the per-customer policy binding, verify, record

**Files:**
- Modify: `GW/platform-ai-gateway-service/src/main/resources/config/liquibase/changelog/platform/ai/gateway/00000000000001_ai_gateway_init.xml`
- Modify: `GW/platform-ai-gateway-api/src/main/java/com/bytechef/ee/platform/ai/gateway/domain/AiGatewayRoutingPolicy.java`
- Modify: `GW/platform-ai-gateway-api/src/main/java/com/bytechef/ee/platform/ai/gateway/repository/AiGatewayRoutingPolicyRepository.java`
- Modify: `GW/platform-ai-gateway-api/src/main/java/com/bytechef/ee/platform/ai/gateway/service/AiGatewayRoutingPolicyService.java`
- Modify: `GW/platform-ai-gateway-service/src/main/java/com/bytechef/ee/platform/ai/gateway/service/AiGatewayRoutingPolicyServiceImpl.java`
- Test: `GW/platform-ai-gateway-service/src/test/java/com/bytechef/ee/platform/ai/gateway/service/AiGatewayRoutingPolicyServiceTest.java`
- Modify: `AUTO/src/main/java/com/bytechef/ee/automation/ai/gateway/facade/AiGatewayFacadeImpl.java`
- Modify: `AUTO/src/test/java/com/bytechef/ee/automation/ai/gateway/config/AiGatewayIntTestConfiguration.java`
- Delete: `CU/embedded-connected-user-api/src/main/java/com/bytechef/ee/embedded/connected/user/gateway/facade/ConnectedUserAiGatewayRoutingPolicyFacade.java`
- Delete: `CU/embedded-connected-user-service/src/main/java/com/bytechef/ee/embedded/connected/user/gateway/facade/ConnectedUserAiGatewayRoutingPolicyFacadeImpl.java`
- Delete: `CU/embedded-connected-user-service/src/test/java/com/bytechef/ee/embedded/connected/user/gateway/facade/ConnectedUserAiGatewayRoutingPolicyFacadeTest.java`
- Delete: `CU/embedded-connected-user-service/src/test/java/com/bytechef/ee/embedded/connected/user/gateway/facade/ConnectedUserAiGatewayRoutingPolicyFacadeAuthorizationTest.java`
- Delete: `AUTO/src/test/java/com/bytechef/ee/automation/ai/gateway/facade/ConnectedUserAiGatewayRoutingPolicyBindingIntTest.java`
- Modify: `docs/superpowers/specs/2026-09-03-embedded-ai-gateway-remaining-work.md`

**Interfaces:**
- Removes: `AiGatewayRoutingPolicy.getConnectedUserId()` / `setConnectedUserId(Long)`, `AiGatewayRoutingPolicyRepository.findByConnectedUserId(long)`, `AiGatewayRoutingPolicyService.fetchRoutingPolicyByConnectedUserId(long)` and `updateConnectedUserId(long, Long)`, `ConnectedUserAiGatewayRoutingPolicyFacade`, and `AiGatewayFacadeImpl.requireRoutingPolicyUsableByConnectedUser`. Nothing added.

This task is a deletion, so its failing test is the compiler: Step 1 removes the column from the entity, and every remaining reference is a compile error to follow to its source.

- [ ] **Step 1: Remove the column from the schema and the entity**

In `00000000000001_ai_gateway_init.xml`, inside `<createTable tableName="ai_gateway_routing_policy">`, delete the comment beginning `A routing policy belongs to at most one workspace or one connected user` and the `<column name="connected_user_id" type="BIGINT"/>` line after it. Delete the `<sql>` element that adds `ck_ai_gateway_routing_policy_workspace_connected_user_not_both`, and the comment beginning `One default routing policy per connected user` together with the `<sql>` element creating `uk_ai_gateway_routing_policy_connected_user_id`. Leave the `ai_gateway_provider` constraints alone.

In `AiGatewayRoutingPolicy`, delete the `connectedUserId` field and its comment block, `getConnectedUserId()`, `setConnectedUserId(...)`, and `", connectedUserId=" + connectedUserId +` from `toString()`.

- [ ] **Step 2: Remove the service and repository methods**

Delete `findByConnectedUserId` from `AiGatewayRoutingPolicyRepository`; `fetchRoutingPolicyByConnectedUserId` and `updateConnectedUserId` from `AiGatewayRoutingPolicyService` and `AiGatewayRoutingPolicyServiceImpl`. Remove the `java.util.Optional` and `org.jspecify.annotations.Nullable` imports from each file if nothing else in it uses them.

In `AiGatewayRoutingPolicyServiceTest`, delete `testFetchRoutingPolicyByConnectedUserIdDelegatesToRepository`, `testFetchRoutingPolicyByConnectedUserIdReturnsEmptyWhenNoneBound`, `testUpdateConnectedUserIdSetsConnectedUserIdAndPersists`, `testUpdateConnectedUserIdClearsConnectedUserIdWhenArgumentIsNull` and `testUpdateConnectedUserIdDoesNotDisturbWorkspaceId`.

- [ ] **Step 3: Remove the old cross-customer check from the routed paths**

In `AiGatewayFacadeImpl`, run `grep -n "requireRoutingPolicyUsableByConnectedUser" AiGatewayFacadeImpl.java`. At each of the two call sites, replace the wrapper with its first argument, e.g.

```java
        AiGatewayRoutingPolicy routingPolicy = requireRoutingPolicyUsableByConnectedUser(
            aiGatewayRoutingPolicyService.getRoutingPolicyByName(request.routingPolicy()), connectedUserId,
            request.routingPolicy());
```

becomes

```java
        AiGatewayRoutingPolicy routingPolicy =
            aiGatewayRoutingPolicyService.getRoutingPolicyByName(request.routingPolicy());
```

Then delete the `requireRoutingPolicyUsableByConnectedUser` method and its Javadoc. The embedded restriction now runs earlier, in `requireRequestedRoutingPolicyIsAssignedPlan` (Task 6), before any lookup by name.

- [ ] **Step 4: Delete the old facade and its tests**

```bash
git rm server/ee/libs/embedded/embedded-connected-user/embedded-connected-user-api/src/main/java/com/bytechef/ee/embedded/connected/user/gateway/facade/ConnectedUserAiGatewayRoutingPolicyFacade.java \
  server/ee/libs/embedded/embedded-connected-user/embedded-connected-user-service/src/main/java/com/bytechef/ee/embedded/connected/user/gateway/facade/ConnectedUserAiGatewayRoutingPolicyFacadeImpl.java \
  server/ee/libs/embedded/embedded-connected-user/embedded-connected-user-service/src/test/java/com/bytechef/ee/embedded/connected/user/gateway/facade/ConnectedUserAiGatewayRoutingPolicyFacadeTest.java \
  server/ee/libs/embedded/embedded-connected-user/embedded-connected-user-service/src/test/java/com/bytechef/ee/embedded/connected/user/gateway/facade/ConnectedUserAiGatewayRoutingPolicyFacadeAuthorizationTest.java \
  server/ee/libs/automation/automation-ai/automation-ai-gateway/automation-ai-gateway-service/src/test/java/com/bytechef/ee/automation/ai/gateway/facade/ConnectedUserAiGatewayRoutingPolicyBindingIntTest.java
```

In `AiGatewayIntTestConfiguration`, change the scan comment above `"com.bytechef.ee.embedded.connected.user.gateway.facade"` to: `// ConnectedUserAiGatewayFacadeImpl: the vendor-admin entry point for plans, caps and customer credentials.` Keep the package in the scan list.

- [ ] **Step 5: Confirm nothing still references the removed binding**

Run: `grep -rn "fetchRoutingPolicyByConnectedUserId\|ConnectedUserAiGatewayRoutingPolicyFacade\|requireRoutingPolicyUsableByConnectedUser\|uk_ai_gateway_routing_policy_connected_user_id" --include='*.java' --include='*.xml' --include='*.graphqls' server`
Expected: no output.

Run: `grep -rn "RoutingPolicy.*ConnectedUserId\|[Pp]olicy\.setConnectedUserId\|[Pp]olicy\.getConnectedUserId" --include='*.java' server`
Expected: no output. Provider and spend-summary `connectedUserId` accessors are unaffected and must stay.

- [ ] **Step 6: Format and run every affected module's checks**

Run:

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/current ./gradlew \
  :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-api:spotlessApply \
  :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-service:spotlessApply \
  :server:ee:libs:embedded:embedded-connected-user:embedded-connected-user-api:spotlessApply \
  :server:ee:libs:embedded:embedded-connected-user:embedded-connected-user-service:spotlessApply \
  :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:spotlessApply \
  > /tmp/t7-spotless.log 2>&1; echo $?
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/current ./gradlew \
  :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-api:check \
  :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-service:check \
  :server:ee:libs:embedded:embedded-connected-user:embedded-connected-user-api:check \
  :server:ee:libs:embedded:embedded-connected-user:embedded-connected-user-service:check \
  :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:check \
  :server:apps:server-app:compileJava \
  --continue > /tmp/t7-check.log 2>&1; echo $?
grep -E "^> Task .* FAILED|Results: " /tmp/t7-check.log
```

Expected: both `0`; every `Results:` line `SUCCESS`; no `FAILED` task. `server-app:compileJava` proves nothing outside these modules used a removed method.

- [ ] **Step 7: Run the integration tests against a schema built from scratch**

Run: `JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/current ./gradlew :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:testIntegration --tests '*EmbeddedAiGateway*IntTest' --tests '*ConnectedUserAiGatewayFacadeIntTest' > /tmp/t7-int.log 2>&1; echo $?`
Expected: `0`, no `FAILED`. Testcontainers builds the edited init changelog from scratch, which proves the schema edit.

Local development databases that already ran the old init changelog fail Liquibase validation until `scripts/dev/sync-local-schema-after-collapse.sh` runs. The dev Postgres is shared across worktrees, so do not run it from this task; note it in the handoff.

- [ ] **Step 8: Commit the code**

```bash
git add server/ee/libs/platform/platform-ai/platform-ai-gateway server/ee/libs/embedded/embedded-connected-user server/ee/libs/automation/automation-ai/automation-ai-gateway/automation-ai-gateway-service
git commit -m "- Remove the per-connected-user binding from AI gateway routing policies"
```

- [ ] **Step 9: Record the outcome in the register**

In `docs/superpowers/specs/2026-09-03-embedded-ai-gateway-remaining-work.md`:

1. Change the heading `### 2.1 Policy names are unique per tenant, not per customer` to `### 2.1 ~~Policy names are unique per tenant, not per customer~~ — RESOLVED 2026-09-14`, and add below it: `Resolved by shared plans (`2026-09-14-embedded-ai-gateway-shared-plans-design.md`): a routing policy is a plan many customers are assigned to, so a tenant-unique name identifies a plan. No schema change to names.`
2. Change `### 2.2 Policy and provider binding disagree` the same way, adding: `Resolved by modelling two relationships: plans are assigned through `ai_gateway_connected_user_settings`; credentials are owned, bound at creation, disabled rather than released when their customer is deleted, and managed through `ConnectedUserAiGatewayFacade`.`
3. Change `### 2.3 May an embedded caller name a workspace-scoped policy?` the same way, adding: `No. An embedded caller may name only its own assigned plan; any other name fails as a missing policy. The endpoint accepts end-user JWTs, so a looser rule would be a self-service plan upgrade.`
4. In §1.2's table, change the policy-binding and spend-cap "What is missing" cells to `service and ADMIN facade exist (`ConnectedUserAiGatewayFacade`); no REST or GraphQL surface`, and the BYOK row's to the same.

- [ ] **Step 10: Commit the register**

```bash
git add docs/superpowers/specs/2026-09-03-embedded-ai-gateway-remaining-work.md
git commit -m "- docs - Record AI gateway shared plans in the embedded gateway register"
```
