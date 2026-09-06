# Guardrail Violation Records — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give an operator per-detection drill-down — which rule fired, where, how confident, on whose request, and what was done about it — without ever storing the matched value.

**Architecture:** `AiGuardrails` already computes the winning spans and throws them away after counting. It hands them out on `GuardrailCheckResult` instead. `AiGuardrailsAdvisor` — the only place that knows the workspace, the surface and the resolved `BlockingMode` — assembles a record and submits it to an off-by-default, asynchronous writer. The writer persists to a new workspace-scoped table with a daily cap and a retention sweep.

**Tech Stack:** Java 25, Spring Boot 4, Spring Data JDBC, Liquibase, Spring for GraphQL, Micrometer, JUnit 5 + AssertJ + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-03-guardrail-violation-records-design.md`

## Global Constraints

- **A record never stores the matched value, in any field, in any mode.** This is the design's whole point (D1, D4). There is no debug flag, not even time-limited. The test that asserts this must inspect the *serialized* record, not named fields, or it only pins what someone remembered.
- **Off by default, per workspace** (D7). An unconfigured workspace writes nothing and pays nothing.
- **A record write must never delay or fail the guarded call** (D8). Dropped is acceptable; stalled is not. Drops are counted.
- **D3's keyed hash is out of scope here** — see the spec's §0. Do not add a hash column "for later"; an unused column on a table this shape invites the escape hatch §2 forbids.
- EE files: ByteChef Enterprise license header, `@version ee` Javadoc tag.
- Every new property is a field on `ApplicationProperties` — binding is strict.
- Run `./gradlew spotlessApply` before each commit; judge Gradle by `$?` on its own line plus `grep '^> Task .* FAILED'`.

## File Structure

**Create (EE api):**
- `…/platform-ai-guardrails-api/…/domain/AiGuardrailViolation.java` — the entity
- `…/platform-ai-guardrails-api/…/domain/AiGuardrailViolationAction.java` — enum, INT ordinals, append-only
- `…/platform-ai-guardrails-api/…/service/AiGuardrailViolationService.java`

**Create (EE service):**
- `…/platform-ai-guardrails-service/…/repository/AiGuardrailViolationRepository.java`
- `…/platform-ai-guardrails-service/…/service/AiGuardrailViolationServiceImpl.java`
- `…/platform-ai-guardrails-service/…/violation/AiGuardrailViolationRecorder.java` — the async writer
- `…/platform-ai-guardrails-service/…/config/AiGuardrailsJdbcRepositoryConfiguration.java`
- `…/platform-ai-guardrails-service/…/job/AiGuardrailViolationRetentionJob.java`
- `…/platform-ai-guardrails-service/src/main/resources/config/liquibase/changelog/platform/ai/guardrails/20260903000001_ai_guardrail_violation_init.xml`
- `…/platform-ai-guardrails-service/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

**Modify:**
- `…/platform-ai-guardrails-service/…/AiGuardrails.java` — `GuardrailCheckResult` gains the spans
- `…/platform-ai-guardrails-service/…/advisor/AiGuardrailsAdvisor.java` — assemble and submit
- `…/platform-ai-guardrails-service/…/AiGuardrailMetrics.java` — expose `surface()`, three new events
- `…/platform-ai-sensitive-data-api/…/SensitiveDataMetrics.java` — no change; these three events are EE-only and go through `record(String)`
- `server/libs/config/liquibase-config/…/master.xml` — the `includeAll` line
- `server/libs/config/app-config/…/ApplicationProperties.java` — retention and cap
- `.agents/ai-guardrails.md`

---

## Task 1: The spans escape the engine

**Files:**
- Modify: `…/platform-ai-guardrails-service/…/AiGuardrails.java`
- Modify: `…/platform-ai-guardrails-service/…/advisor/AiGuardrailsAdvisor.java` (construction sites only)
- Test: `…/platform-ai-guardrails-service/src/test/…/AiGuardrailsTest.java`

**Interfaces:**
- Produces: `GuardrailCheckResult(String text, String unmaskedText, String category, List<SensitiveSpan> spans)` — a four-component record. Every later task reads `spans()`.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void testCheckInputCarriesTheAcceptedSpansSoACallerCanRecordThem() {
        // The engine already computes these to decide which counters to increment and then discards them. A caller
        // that wants to say WHICH pattern fired, and where, has no other source: the counter carries only an event
        // name and a surface, by design.
        AiGuardrails guardrails = guardrails(null, true, false, "", false, false);

        when(settingsService.fetchSettings(7L)).thenReturn(Optional.empty());

        AiGuardrails.GuardrailCheckResult result = guardrails.checkInputs(
            List.of("mail bob@acme.io"), 7L, metrics)
            .getFirst();

        assertThat(result.spans())
            .singleElement()
            .satisfies(span -> {
                assertThat(span.category()).isEqualTo("EMAIL_ADDRESS");
                assertThat(span.start()).isEqualTo(5);
                assertThat(span.end()).isEqualTo(16);
            });
    }

    @Test
    void testSpansAreEmptyRatherThanNullWhenNothingWasDetected() {
        AiGuardrails guardrails = guardrails(null, true, false, "", false, false);

        when(settingsService.fetchSettings(7L)).thenReturn(Optional.empty());

        assertThat(guardrails.checkInputs(List.of("nothing here"), 7L, metrics)
            .getFirst()
            .spans()).isEmpty();
    }
```

- [ ] **Step 2: Run it, expect a compile failure on `spans()`**

- [ ] **Step 3: Thread the spans out**

`redactPiiAndSecrets` currently returns `String`. Give it a private carrier so the spans it already has reach `checkInput`:

```java
    private record RedactedContent(String text, List<SensitiveSpan> accepted) {
    }
```

Change `redactPiiAndSecrets` to return `RedactedContent`, returning `new RedactedContent(redacted, accepted)` at its end. `checkAndRedact` — the throwing gateway path — takes `.text()` and ignores the spans, so its behaviour is untouched.

`GuardrailCheckResult` gains a fourth component. Update its javadoc and all five construction sites in `checkInput`; the `content == null` case carries `List.of()`.

- [ ] **Step 4: Run the tests, expect PASS**

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add -A && git commit -m "732 Carry the accepted spans out of the guardrail engine"
```

---

## Task 2: The table

**Files:**
- Create: the changelog, the entity, the action enum, the repository, the JDBC configuration, the `AutoConfiguration.imports`
- Modify: `master.xml`

- [ ] **Step 1: Write the changelog**

`…/changelog/platform/ai/guardrails/20260903000001_ai_guardrail_violation_init.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                   http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">
    <changeSet id="20260903000001" author="Ivica Cardic">
        <createTable tableName="ai_guardrail_violation">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="workspace_id" type="BIGINT"/>
            <column name="environment" type="INT"/>
            <column name="surface" type="VARCHAR(64)">
                <constraints nullable="false"/>
            </column>
            <column name="category" type="VARCHAR(64)">
                <constraints nullable="false"/>
            </column>
            <column name="kind" type="INT">
                <constraints nullable="false"/>
            </column>
            <column name="span_start" type="INT">
                <constraints nullable="false"/>
            </column>
            <column name="span_length" type="INT">
                <constraints nullable="false"/>
            </column>
            <column name="confidence" type="NUMERIC(3,2)">
                <constraints nullable="false"/>
            </column>
            <column name="action" type="INT">
                <constraints nullable="false"/>
            </column>
            <column name="principal" type="VARCHAR(256)"/>
            <column name="created_date" type="TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createIndex tableName="ai_guardrail_violation" indexName="idx_ai_guardrail_violation_workspace_created">
            <column name="workspace_id"/>
            <column name="created_date"/>
        </createIndex>
    </changeSet>
</databaseChangeLog>
```

**There is deliberately no column for the matched value, and no column reserved for one.** The daily cap counts rows per workspace per day, which the composite index above serves.

- [ ] **Step 2: Register it in `master.xml`**

Add, after the `platform/ai/eval` block, following the surrounding comment style:

```xml
    <!--
        platform/ai/guardrails holds ai_guardrail_violation — per-detection drill-down records. Carries its own
        nullable workspace_id column; there is no workspace_ai_guardrail_violation relation table. No FKs, so its
        position is free; kept beside the other platform/ai changelogs. NOTE the table deliberately has no column
        for the matched value, and none must be added — see
        docs/superpowers/specs/2026-09-03-guardrail-violation-records-design.md §2.
    -->
    <includeAll path="classpath:config/liquibase/changelog/platform/ai/guardrails" relativeToChangelogFile="false" errorIfMissingOrEmpty="false" contextFilter="mono or configuration or multitenant" />
```

- [ ] **Step 3: The action enum**

```java
    /**
     * What was done about the detection. Persisted as an INT ordinal, so append new values at the end only.
     *
     * <p>
     * A record under {@link #ALLOWED} is a counterfactual — observe mode saw the violation and forwarded the content
     * unmodified — and must be distinguishable at read time from an enforcement, or a week of observe-mode records
     * reads as a week of blocks.
     * </p>
     */
    public enum AiGuardrailViolationAction {

        BLOCKED,
        REDACTED,
        ALLOWED
    }
```

- [ ] **Step 4: The entity, repository and JDBC configuration**

Follow `platform-ai-eval` exactly: `@Table("ai_guardrail_violation")` entity in `…-api/domain`, `CrudRepository`/`ListCrudRepository` in `…-service/repository`, and

```java
@AutoConfiguration(afterName = "org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration")
@ConditionalOnBean(AbstractJdbcConfiguration.class)
@EnableJdbcRepositories(basePackages = "com.bytechef.ee.platform.ai.guardrails.repository")
public class AiGuardrailsJdbcRepositoryConfiguration {
}
```

registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

The repository needs three queries: insert (inherited), `countByWorkspaceIdAndCreatedDateAfter` for the cap, and `deleteByCreatedDateBefore` for retention.

- [ ] **Step 5: Verify the schema with an existing IntTest**

Per CLAUDE.md, the `liquibase` profile does not apply migrations via `bootRun`. Run an existing `*IntTest` that builds the schema from scratch under Testcontainers; it fails if the changelog is malformed or the `includeAll` path is wrong.

- [ ] **Step 6: Commit**

---

## Task 3: The recorder — off by default, async, capped

**Files:**
- Create: `…/violation/AiGuardrailViolationRecorder.java`
- Modify: `…/AiGuardrailMetrics.java`, `ApplicationProperties.java`

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void testNoMatchedValueReachesAWrittenRecord() {
        // The load-bearing test of the whole feature. It asserts over the SERIALIZED record rather than field by
        // field: a field-by-field assertion only pins the fields whoever wrote it remembered, and the failure this
        // guards against is precisely someone adding a field without thinking.
        …
        assertThat(serialized).doesNotContain("bob@acme.io");
    }

    @Test
    void testRecordingIsOffUnlessTheWorkspaceEnabledIt() { … }

    @Test
    void testAFailingWriteNeitherThrowsNorDelaysAndIsCounted() { … }

    @Test
    void testTheDailyCapStopsWritesAndFiresItsEventExactlyOnce() { … }

    @Test
    void testAnAllowedRecordIsDistinguishableFromAnEnforcedOne() { … }
```

- [ ] **Step 2–4:** implement the recorder; run; commit.

Three new events on `AiGuardrailMetrics`, through its existing `record(String)`: `violation_record_written`, `violation_record_dropped`, `violation_records_capped`. These are EE-only and do **not** go on the `SensitiveDataMetrics` seam — that seam exists for CE's redactor to report through, and none of these originate there.

`AiGuardrailMetrics` also gains a `surface()` accessor, since the advisor's record needs the surface and the field is private today.

---

## Task 4: The advisor submits

**Files:** `…/advisor/AiGuardrailsAdvisor.java`

The advisor already resolves `BlockingMode` and holds `workspaceId` and its surface-tagged metrics. It now also has `result.spans()`. One submission per checked message, mapping `BlockingMode` → `AiGuardrailViolationAction` (`BLOCK`→`BLOCKED`, `REDACT_AND_CONTINUE`→`REDACTED`, `ALLOW`→`ALLOWED`), and submitting **PII/secret spans too**, not only blocking violations — the drill-down question "which pattern fired" is mostly about redactions.

Note the `BLOCK` path throws before the loop, so its submission must happen before the throw or blocked calls are the one case with no record — which would be the worst possible gap.

---

## Task 5: Retention

Copy `AuditEventRetentionJob`: `@Scheduled(cron = "${bytechef.ai.guardrails.violation.retention-cron:0 30 2 * * *}")`, deleting rows older than `retention-days` (default 30 — shorter than audit's 365, because these are machine detections at request volume, not human actions).

---

## Task 6: Read surface and documentation

GraphQL only, admin-scoped, workspace-filtered. A record id from another workspace must read as not-found rather than forbidden — the probe-oracle rule this codebase already follows for variables. Then `.agents/ai-guardrails.md`.

## Self-Review

**Spec coverage.** §2's table of safe fields → Task 2's schema, minus the deferred hash. §2's forbidden escape hatch → the Global Constraints and the changelog comment. §3's new-table decision → Task 2. §4's three volume controls → Task 3. §5's read surface → Task 6. §6's three events → Task 3. §7's eight tests → Tasks 3, 4 and 6. §9 D1–D11 → all covered except D3, deferred in the spec's §0.

**Placeholder scan.** Tasks 3–6 are deliberately less granular than 1–2: they are conventional once the seam and the schema exist, and every one of them names its files, its behaviour and its tests. Task 3's test bodies are elided to their assertions because the fixture depends on the recorder's constructor, which Task 3 Step 2 defines.

**Type consistency.** `GuardrailCheckResult` is four components after Task 1. `AiGuardrailViolationAction` is `BLOCKED`/`REDACTED`/`ALLOWED`, ordinals 0/1/2, mapped from `BlockingMode` in Task 4.
