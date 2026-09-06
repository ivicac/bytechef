# Conversation-Scoped Token Sessions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make PII tokens survive across the turns of an AI Hub chat thread, so a token minted in turn 1 still restores in turn 5 — without letting any surface whose conversation id is author-supplied share a token store between users.

**Architecture:** `PiiTokenSession` gains a rehydrate factory and an ordinal high-water mark, so a session can be reconstructed from a persisted token map. A new CE SPI, `PiiTokenSessionStore`, is implemented in EE over an encrypted JDBC table keyed `(workspace_id, user_id, conversation_id)`. `AiGuardrailsAdvisor` reads the conversation id from the advisor context it already receives, but only trusts it when the caller also published a *platform-issued* marker — a flag a workflow author cannot set, because only platform code writes it. AI Hub publishes that marker for its chat threads; nothing else does, so every other surface keeps today's request-scoped behaviour untouched.

**Tech Stack:** Java 25, Spring AI 2.0.1 (`ChatClientRequest.context()`, `ChatMemory.CONVERSATION_ID`), Spring Data JDBC, Liquibase, `server/libs/core/encryption`, JUnit 5 + AssertJ + Mockito + Testcontainers, Gradle 9.7.

**Spec:** `docs/superpowers/specs/2026-08-25-guardrails-consolidation-design.md` — §7 (revised 2026-09-05, §7.0 "what reading the code changed") and §12 decisions D8, D22, D23, D24.

## Global Constraints

- **Conversation scope is enabled by PROVENANCE, never by the shape of an id.** The advisor trusts a conversation id only when the caller published `ConversationScope.PLATFORM_ISSUED_KEY` alongside it. A workflow author cannot reach the code that sets that flag; that is the entire security argument (spec D24).
- Enabled for **AI Hub chat threads only**. The Copilot panel, AI Hub delegation sub-agents, the canvas AI Agent, `AI_HUB` title generation, `API_CONNECTOR`, `AI_EVAL` and the MCP surfaces all keep request-scoped sessions, unchanged (spec §7 Decision table).
- Store key is `(workspaceId, userId, conversationId)` — all three, even though `ai_hub_chat.thread_id` is globally unique with an explicit cross-user conflict path. The extra two columns are defence-in-depth against that invariant weakening later (spec §7.0 finding 3).
- **The stored token map is sensitive data and is encrypted at rest** via `com.bytechef.encryption.Encryption` (`encrypt(String)` / `decrypt(String)`), stored as ONE encrypted blob per session rather than per-value columns.
- **Secrets are never in a session.** `SensitiveDataRedactor.tokenizeWithSpans` returns `span.placeholder()` for `SensitiveKind.SECRET` and only reaches `session.tokenFor` for PII, so nothing in this plan may add a secret to the store. Do not change that branch.
- **Ordinal continuation is load-bearing.** A rehydrated session must resume minting ABOVE the highest ordinal it already holds. Resuming at 1 would mint `[PII_EMAIL_ADDRESS_1_<nonce>]` for a second, different value, and turn 5 would restore turn 1's person's e-mail into turn 5's text. Every task that touches the session must preserve this.
- New EE files carry the ByteChef Enterprise license header and a `@version ee` javadoc tag; new CE files carry the Apache 2.0 header. Preserve existing `@author` tags.
- Java style: exactly one blank line before `if`/`for`/`while`/`switch`/`try`; one blank line between a variable modification and the next statement using it; no blank line before a class's closing brace; test names camelCase without underscores; no `_`-prefixed private methods; no cryptic variable names; javadoc never stacked (a documented member directly above another documented member fails Checkstyle's `InvalidJavadocPosition`).
- No inline code comments beyond what a step's own code block contains; rationale goes in the commit message.
- Enum ordinals are persisted as INT — append new values, never reorder.
- Every `./gradlew` invocation is prefixed with `JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce`; Bash calls that build or test need `timeout: 600000`.
- **Never judge a Gradle run by a piped exit code.** Redirect to a file, put `echo "exit=$?"` on its own line immediately after, then `grep '^> Task .* FAILED'` the file.
- Run each touched module's `check` task, not just `test` — `check` includes SpotBugs, Checkstyle and PMD. Read SpotBugs findings from `build/reports/spotbugs/*.html`, never the XML.
- Run `./gradlew spotlessApply` before every commit; commit by explicit path only (never `git add -A`); never amend; end every commit message with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- Read every file before editing or overwriting it.

---

## File Structure

**Created (CE)**
- `platform-ai-sensitive-data-api/.../sensitivedata/PiiTokenSessionStore.java` — the store SPI: load, save, evict.
- `platform-ai-api/.../ai/guardrails/ConversationScope.java` — the platform-issued marker key and the trusted-id reader.

**Created (EE)**
- `platform-ai-guardrails-api/.../domain/AiGuardrailTokenSession.java` — the persisted row.
- `platform-ai-guardrails-service/.../repository/AiGuardrailTokenSessionRepository.java`
- `platform-ai-guardrails-service/.../session/EncryptedPiiTokenSessionStore.java` — the SPI implementation.
- `platform-ai-guardrails-service/.../job/AiGuardrailTokenSessionRetentionJob.java` — TTL sweep.
- `.../resources/config/liquibase/changelog/platform/ai/guardrails/20260905000001_ai_guardrail_token_session_init.xml`

**Modified**
- `platform-ai-sensitive-data-service/.../tokenization/PiiTokenSession.java` — rehydrate factory + ordinal high-water mark.
- `platform-ai-guardrails-service/.../advisor/AiGuardrailsAdvisor.java` — resolve the key, load-or-create, save-instead-of-discard.
- `platform-ai-guardrails-service/.../AiGuardrails.java` — a store-aware `newTokenSession` overload.
- `ai-hub-service/.../agent/AiHubSpringAIAgent.java` — publish the marker and the verified user id.
- `ai-hub-service/.../chat/AiHubChatServiceImpl.java` — evict on chat deletion.
- `app-config/.../ApplicationProperties.java` — `Ai.Guardrails.Session` properties.
- `.agents/ai-guardrails.md`, the consolidation spec's status line.

---

### Task 1: `PiiTokenSession` can be rehydrated, and resumes its ordinals

**Files:**
- Modify: `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service/src/main/java/com/bytechef/platform/ai/sensitivedata/tokenization/PiiTokenSession.java`
- Test: `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service/src/test/java/com/bytechef/platform/ai/sensitivedata/tokenization/PiiTokenSessionTest.java` (append)

**Interfaces:**
- Consumes: `PiiToken.parse(String)` → `Optional<PiiToken>` with `ordinal()`; `PiiTokenSession.tokens()` → `Map<String,String>` (token → value), added by the previous plan.
- Produces: `static PiiTokenSession rehydrate(String sessionId, Map<String, String> tokens)`.

- [ ] **Step 1: Write the failing tests**

Append to `PiiTokenSessionTest`:

```java
    @Test
    void testRehydratedSessionRestoresTheTokensItWasGiven() {
        PiiTokenSession original = PiiTokenSession.create();
        String token = original.tokenFor("EMAIL_ADDRESS", "bob@acme.io");

        PiiTokenSession rehydrated = PiiTokenSession.rehydrate(original.sessionId(), original.tokens());

        assertThat(rehydrated.restore("mail " + token)).isEqualTo("mail bob@acme.io");
        assertThat(rehydrated.sessionId()).isEqualTo(original.sessionId());
    }

    @Test
    void testRehydratedSessionMintsTheSameTokenForAValueItAlreadyHolds() {
        PiiTokenSession original = PiiTokenSession.create();
        String token = original.tokenFor("EMAIL_ADDRESS", "bob@acme.io");

        PiiTokenSession rehydrated = PiiTokenSession.rehydrate(original.sessionId(), original.tokens());

        assertThat(rehydrated.tokenFor("EMAIL_ADDRESS", "bob@acme.io"))
            .as("cross-turn coherence: the model must see one person, not two")
            .isEqualTo(token);
    }

    @Test
    void testRehydratedSessionResumesOrdinalsAboveTheHighestItHolds() {
        // The load-bearing one. Resuming at 1 would mint an ordinal a stored token already uses, and a later turn
        // would restore the FIRST value into the SECOND value's place -- a cross-value disclosure inside one session.
        PiiTokenSession original = PiiTokenSession.create();

        original.tokenFor("EMAIL_ADDRESS", "bob@acme.io");
        original.tokenFor("EMAIL_ADDRESS", "alice@acme.io");

        PiiTokenSession rehydrated = PiiTokenSession.rehydrate(original.sessionId(), original.tokens());

        String third = rehydrated.tokenFor("EMAIL_ADDRESS", "carol@acme.io");

        assertThat(original.tokens()).doesNotContainKey(third);
        assertThat(rehydrated.restore(third)).isEqualTo("carol@acme.io");
        assertThat(rehydrated.restore("mail " + original.tokenFor("EMAIL_ADDRESS", "bob@acme.io")))
            .isEqualTo("mail bob@acme.io");
    }

    @Test
    void testRehydratingAnEmptyMapBehavesLikeAFreshSession() {
        PiiTokenSession rehydrated = PiiTokenSession.rehydrate("abcd", Map.of());

        assertThat(rehydrated.size()).isZero();
        assertThat(rehydrated.tokenFor("EMAIL_ADDRESS", "bob@acme.io")).contains("_1_");
    }

    @Test
    void testRehydrateIgnoresAnEntryWhoseKeyIsNotAToken() {
        // A row could be corrupt or hand-edited. An unparseable key must not raise the ordinal watermark or throw --
        // it is dropped, because a token this session cannot parse is one it can never be asked to restore.
        PiiTokenSession rehydrated = PiiTokenSession.rehydrate("abcd", Map.of("not-a-token", "bob@acme.io"));

        assertThat(rehydrated.size()).isZero();
        assertThat(rehydrated.restore("not-a-token")).isEqualTo("not-a-token");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:test --tests '*PiiTokenSessionTest*' --console=plain > /tmp/s7-task1-red.log 2>&1; echo "exit=$?"
```
Expected: `exit=1`, `cannot find symbol ... rehydrate`.

- [ ] **Step 3: Add the factory**

Add at the END of `PiiTokenSession`'s method block (before the `RestoreResult` record, so no documented member is stacked above another):

```java
    /**
     * Reconstructs a session from a previously exported {@link #tokens()} map, so a conversation's tokens survive the
     * turn that minted them.
     *
     * <p>
     * Minting resumes ABOVE the highest ordinal the map holds, not at 1. Resuming at 1 would mint an ordinal a stored
     * token already uses, and a later turn would restore the first value into the second value's place — a
     * cross-value disclosure inside one session. An entry whose key does not parse as a token is dropped rather than
     * raising the watermark: a token this session cannot parse is one it can never be asked to restore.
     * </p>
     *
     * @param sessionId the discriminator the stored tokens were minted under
     * @param tokens    token text to value, as returned by {@link #tokens()}
     * @return a session holding those tokens
     */
    public static PiiTokenSession rehydrate(String sessionId, Map<String, String> tokens) {
        PiiTokenSession session = new PiiTokenSession(sessionId);
        int highestOrdinal = 0;

        for (Map.Entry<String, String> entry : tokens.entrySet()) {
            Optional<PiiToken> parsed = PiiToken.parse(entry.getKey());

            if (parsed.isEmpty()) {
                continue;
            }

            PiiToken token = parsed.get();

            session.tokenToValue.put(entry.getKey(), entry.getValue());
            session.valueToToken.put(entry.getValue(), entry.getKey());

            highestOrdinal = Math.max(highestOrdinal, token.ordinal());
        }

        session.nextOrdinal.set(highestOrdinal + 1);

        return session;
    }
```

Add `import java.util.Optional;` if absent.

- [ ] **Step 4: Run the tests to verify they pass**

Re-run the Step 2 command into `/tmp/s7-task1-green.log`. Expected `exit=0` and no `^> Task .* FAILED` lines.

- [ ] **Step 5: Format, check and commit**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:spotlessApply :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-service:check --console=plain > /tmp/s7-task1-check.log 2>&1; echo "exit=$?"
git add server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service/src/main/java/com/bytechef/platform/ai/sensitivedata/tokenization/PiiTokenSession.java server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-service/src/test/java/com/bytechef/platform/ai/sensitivedata/tokenization/PiiTokenSessionTest.java
git commit -m "732 Let a token session be rehydrated and resume its ordinals

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: The store SPI, and the trusted-id reader

**Files:**
- Create: `server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-api/src/main/java/com/bytechef/platform/ai/sensitivedata/PiiTokenSessionStore.java`
- Create: `server/libs/platform/platform-ai/platform-ai-api/src/main/java/com/bytechef/platform/ai/guardrails/ConversationScope.java`
- Test: `server/libs/platform/platform-ai/platform-ai-api/src/test/java/com/bytechef/platform/ai/guardrails/ConversationScopeTest.java`

**Interfaces:**
- Consumes: `org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID` (spring-ai is an `api` dependency of `platform-ai-api`).
- Produces: `PiiTokenSessionStore` with `Optional<Map<String,String>> load(SessionKey)`, `void save(SessionKey, Map<String,String>)`, `void evict(long workspaceId, String conversationId)`, and the nested `record SessionKey(long workspaceId, long userId, String conversationId)`; `ConversationScope.PLATFORM_ISSUED_KEY`, `ConversationScope.USER_ID_KEY`, and `Optional<ConversationScope.Key> ConversationScope.trustedKey(Map<String, ?> context, Long workspaceId)` returning a `record Key(long workspaceId, long userId, String conversationId)`.

- [ ] **Step 1: Write the failing test**

`ConversationScopeTest.java`:

```java
package com.bytechef.platform.ai.guardrails;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;

/**
 * @author Ivica Cardic
 */
class ConversationScopeTest {

    @Test
    void testATrustedKeyNeedsAllThreeOfIdMarkerAndUser() {
        assertThat(ConversationScope.trustedKey(contextOf("thread-1", true, 7L), 42L))
            .contains(new ConversationScope.Key(42L, 7L, "thread-1"));
    }

    @Test
    void testAnIdWithoutTheMarkerIsNotTrusted() {
        // The whole security argument: a workflow author can publish a conversation id, but cannot reach the code
        // that sets the marker. Without it the id is author-supplied and must never key a shared token store.
        assertThat(ConversationScope.trustedKey(contextOf("support-queue", false, 7L), 42L))
            .isEmpty();
    }

    @Test
    void testAMarkerWithoutAnIdIsNotTrusted() {
        assertThat(ConversationScope.trustedKey(contextOf(null, true, 7L), 42L)).isEmpty();
    }

    @Test
    void testAMarkerWithoutAUserIdIsNotTrusted() {
        assertThat(ConversationScope.trustedKey(contextOf("thread-1", true, null), 42L)).isEmpty();
    }

    @Test
    void testNoWorkspaceIsNotTrusted() {
        assertThat(ConversationScope.trustedKey(contextOf("thread-1", true, 7L), null)).isEmpty();
    }

    @Test
    void testAForeignTypeUnderAKeyIsIgnoredRatherThanThrowing() {
        Map<String, Object> context = contextOf("thread-1", true, 7L);

        context.put(ConversationScope.USER_ID_KEY, "not a number");

        assertThat(ConversationScope.trustedKey(context, 42L)).isEmpty();
    }

    private static Map<String, Object> contextOf(String conversationId, boolean platformIssued, Long userId) {
        Map<String, Object> context = new HashMap<>();

        if (conversationId != null) {
            context.put(ChatMemory.CONVERSATION_ID, conversationId);
        }

        if (platformIssued) {
            context.put(ConversationScope.PLATFORM_ISSUED_KEY, Boolean.TRUE);
        }

        if (userId != null) {
            context.put(ConversationScope.USER_ID_KEY, userId);
        }

        return context;
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:platform:platform-ai:platform-ai-api:test --tests '*ConversationScopeTest*' --console=plain > /tmp/s7-task2-red.log 2>&1; echo "exit=$?"
```
Expected: `exit=1`, `cannot find symbol ... ConversationScope`.

- [ ] **Step 3: Write `ConversationScope`**

```java
package com.bytechef.platform.ai.guardrails;

import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.memory.ChatMemory;

/**
 * Decides whether a call's conversation id may key a token store that outlives the request.
 *
 * <p>
 * Every chat surface already publishes {@link ChatMemory#CONVERSATION_ID} as an advisor param, and Spring AI turns
 * advisor params into the request context every advisor reads — so an id is always visible. Visibility is not
 * trust: the canvas AI Agent's id is a workflow-author expression, and two end users sharing one such id would
 * share one token store, turning the privacy feature into a cross-user disclosure.
 * </p>
 *
 * <p>
 * The marker is what separates them. Only platform code that ISSUED the id sets {@link #PLATFORM_ISSUED_KEY}
 * alongside it; a workflow author cannot reach that code. An id without the marker is treated as author-supplied
 * and keys nothing.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class ConversationScope {

    /**
     * Set by the caller that issued the conversation id, asserting the platform can prove it identifies one
     * conversation of one user.
     */
    public static final String PLATFORM_ISSUED_KEY = "bytechef.guardrails.conversationPlatformIssued";

    /** The verified end user the conversation belongs to, published by the same caller as a {@code Long}. */
    public static final String USER_ID_KEY = "bytechef.guardrails.conversationUserId";

    private ConversationScope() {
    }

    /**
     * Returns the store key for this call, or empty when anything required is missing or untrusted.
     *
     * @param context     the advisor context
     * @param workspaceId the workspace the call resolved, or {@code null}
     * @return the key, or empty for a request-scoped call
     */
    public static Optional<Key> trustedKey(Map<String, ?> context, @Nullable Long workspaceId) {
        if (workspaceId == null || !Boolean.TRUE.equals(context.get(PLATFORM_ISSUED_KEY))) {
            return Optional.empty();
        }

        if (!(context.get(ChatMemory.CONVERSATION_ID) instanceof String conversationId) || conversationId.isBlank()) {
            return Optional.empty();
        }

        if (!(context.get(USER_ID_KEY) instanceof Long userId)) {
            return Optional.empty();
        }

        return Optional.of(new Key(workspaceId, userId, conversationId));
    }

    /**
     * @param workspaceId    the workspace
     * @param userId         the verified end user
     * @param conversationId the platform-issued conversation id
     */
    public record Key(long workspaceId, long userId, String conversationId) {
    }
}
```

- [ ] **Step 4: Write the store SPI**

`PiiTokenSessionStore.java` in `platform-ai-sensitive-data-api`:

```java
package com.bytechef.platform.ai.sensitivedata;

import java.util.Map;
import java.util.Optional;

/**
 * Persists a conversation's minted tokens between turns.
 *
 * <p>
 * Implementations hold sensitive data — the map's values ARE the caller's real PII — and must encrypt it at rest.
 * Secrets never reach here: the engine redacts a {@code SECRET} span irreversibly and only ever mints a token for a
 * {@code PII} one, so a secret has no token to store.
 * </p>
 *
 * <p>
 * An implementation that fails must fail SOFT — return empty from {@link #load} and swallow a {@link #save} error
 * after logging — because a store outage should cost cross-turn coherence, never the request itself. The caller then
 * behaves exactly as a request-scoped call.
 * </p>
 */
public interface PiiTokenSessionStore {

    /**
     * @param key the conversation
     * @return the stored token-to-value map, or empty when the conversation has none
     */
    Optional<Map<String, String>> load(SessionKey key);

    /**
     * Replaces the conversation's stored map. An empty map is stored as an absent session rather than an empty row.
     *
     * @param key    the conversation
     * @param tokens token text to value
     */
    void save(SessionKey key, Map<String, String> tokens);

    /**
     * Removes a conversation's tokens, on deletion of the conversation itself.
     *
     * @param workspaceId    the workspace
     * @param conversationId the conversation
     */
    void evict(long workspaceId, String conversationId);

    /**
     * @param workspaceId    the workspace
     * @param userId         the verified end user
     * @param conversationId the platform-issued conversation id
     */
    record SessionKey(long workspaceId, long userId, String conversationId) {
    }
}
```

- [ ] **Step 5: Run both modules' checks and commit**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:platform:platform-ai:platform-ai-api:spotlessApply :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-api:spotlessApply :server:libs:platform:platform-ai:platform-ai-api:check :server:libs:platform:platform-ai:platform-ai-sensitive-data:platform-ai-sensitive-data-api:check --continue --console=plain > /tmp/s7-task2-check.log 2>&1; echo "exit=$?"
grep '^> Task .* FAILED' /tmp/s7-task2-check.log
git add server/libs/platform/platform-ai/platform-ai-api/src/main/java/com/bytechef/platform/ai/guardrails/ConversationScope.java server/libs/platform/platform-ai/platform-ai-api/src/test/java/com/bytechef/platform/ai/guardrails/ConversationScopeTest.java server/libs/platform/platform-ai/platform-ai-sensitive-data/platform-ai-sensitive-data-api/src/main/java/com/bytechef/platform/ai/sensitivedata/PiiTokenSessionStore.java
git commit -m "732 Add the token-session store SPI and the trusted-conversation reader

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: The encrypted JDBC store

**Files:**
- Create: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-api/src/main/java/com/bytechef/ee/platform/ai/guardrails/domain/AiGuardrailTokenSession.java`
- Create: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/repository/AiGuardrailTokenSessionRepository.java`
- Create: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/session/EncryptedPiiTokenSessionStore.java`
- Create: `.../platform-ai-guardrails-service/src/main/resources/config/liquibase/changelog/platform/ai/guardrails/20260905000001_ai_guardrail_token_session_init.xml`
- Test: `.../platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/session/EncryptedPiiTokenSessionStoreTest.java`
- Test: `.../platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/repository/AiGuardrailTokenSessionRepositoryIntTest.java`

**Interfaces:**
- Consumes: `PiiTokenSessionStore` (Task 2); `com.bytechef.encryption.Encryption`; `ObjectMapper` for the map⇄JSON step.
- Produces: `EncryptedPiiTokenSessionStore implements PiiTokenSessionStore`, a Spring `@Component` gated `@ConditionalOnEEVersion`.

Follow the exact shape of the two sibling features committed in the previous sub-projects — `AiGuardrailCustomRuleRepository` for the repository, `20260903000002_ai_guardrail_custom_rule_init.xml` for the changelog, and `AiGuardrailCustomRuleRepositoryIntTest` for the IntTest. Read all three before writing. The `includeAll` in `master.xml` already covers this directory, and it fails SILENTLY on a malformed file, which is why the IntTest below is mandatory rather than optional.

Table `ai_guardrail_token_session`: `id BIGSERIAL PK`, `workspace_id BIGINT NOT NULL`, `user_id BIGINT NOT NULL`, `conversation_id VARCHAR(256) NOT NULL`, `session_id VARCHAR(16) NOT NULL`, `tokens TEXT NOT NULL` (the encrypted blob), `create_date TIMESTAMP NOT NULL`, `last_modified_date TIMESTAMP NOT NULL`, with a UNIQUE constraint on `(workspace_id, user_id, conversation_id)` and an index on `last_modified_date` for the TTL sweep.

- [ ] **Step 1: Write the failing unit test**

```java
class EncryptedPiiTokenSessionStoreTest {

    private final Encryption encryption = new Encryption() {

        @Override
        public String encrypt(String content) {
            return "enc:" + content;
        }

        @Override
        public String decrypt(String encryptedString) {
            return encryptedString.substring(4);
        }
    };

    @Test
    void testWhatIsWrittenToTheRowIsNotThePlainValue() {
        // The invariant the whole table exists to keep: the map's values ARE the caller's PII.
        AiGuardrailTokenSessionRepository repository = new InMemoryRepository();
        EncryptedPiiTokenSessionStore store = new EncryptedPiiTokenSessionStore(repository, encryption, objectMapper());

        store.save(KEY, Map.of("[PII_EMAIL_ADDRESS_1_abcd]", "bob@acme.io"));

        AiGuardrailTokenSession row = ((InMemoryRepository) repository).only();

        assertThat(row.getTokens()).doesNotContain("bob@acme.io");
        assertThat(store.load(KEY)).contains(Map.of("[PII_EMAIL_ADDRESS_1_abcd]", "bob@acme.io"));
    }

    @Test
    void testSavingAnEmptyMapStoresNothing() {
        AiGuardrailTokenSessionRepository repository = new InMemoryRepository();
        EncryptedPiiTokenSessionStore store = new EncryptedPiiTokenSessionStore(repository, encryption, objectMapper());

        store.save(KEY, Map.of());

        assertThat(store.load(KEY)).isEmpty();
    }

    @Test
    void testASecondSaveReplacesRatherThanAccumulates() {
        AiGuardrailTokenSessionRepository repository = new InMemoryRepository();
        EncryptedPiiTokenSessionStore store = new EncryptedPiiTokenSessionStore(repository, encryption, objectMapper());

        store.save(KEY, Map.of("[PII_EMAIL_ADDRESS_1_abcd]", "bob@acme.io"));
        store.save(KEY, Map.of("[PII_EMAIL_ADDRESS_1_abcd]", "bob@acme.io", "[PII_US_SSN_2_abcd]", "123-45-6789"));

        assertThat(store.load(KEY)).hasValueSatisfying(tokens -> assertThat(tokens).hasSize(2));
        assertThat(((InMemoryRepository) repository).count()).isEqualTo(1);
    }

    @Test
    void testAFailingStoreLoadsEmptyRatherThanThrowing() {
        // Fail soft: a store outage costs cross-turn coherence, never the request.
        EncryptedPiiTokenSessionStore store = new EncryptedPiiTokenSessionStore(
            new ThrowingRepository(), encryption, objectMapper());

        assertThat(store.load(KEY)).isEmpty();

        assertThatCode(() -> store.save(KEY, Map.of("[PII_EMAIL_ADDRESS_1_abcd]", "bob@acme.io")))
            .doesNotThrowAnyException();
    }

    @Test
    void testAnUndecryptableRowLoadsEmptyRatherThanThrowing() {
        // A key rotation leaves rows nothing can read. They must degrade to "no stored session", not to a 500.
        AiGuardrailTokenSessionRepository repository = new InMemoryRepository();
        EncryptedPiiTokenSessionStore store = new EncryptedPiiTokenSessionStore(repository, encryption, objectMapper());

        store.save(KEY, Map.of("[PII_EMAIL_ADDRESS_1_abcd]", "bob@acme.io"));

        ((InMemoryRepository) repository).corruptOnly();

        assertThat(store.load(KEY)).isEmpty();
    }
}
```

Write `InMemoryRepository` and `ThrowingRepository` as private static nested classes implementing the repository interface, `only()`/`count()`/`corruptOnly()` as their helpers, `KEY` as a `PiiTokenSessionStore.SessionKey(42L, 7L, "thread-1")` constant, and `objectMapper()` returning a plain `new ObjectMapper()`.

- [ ] **Step 2: Run to verify it fails**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*EncryptedPiiTokenSessionStoreTest*' --console=plain > /tmp/s7-task3-red.log 2>&1; echo "exit=$?"
```
Expected: `exit=1` on the missing classes.

- [ ] **Step 3: Write the domain, repository, changelog and store**

The store's shape:

```java
    @Override
    public Optional<Map<String, String>> load(SessionKey key) {
        try {
            return aiGuardrailTokenSessionRepository
                .findByWorkspaceIdAndUserIdAndConversationId(key.workspaceId(), key.userId(), key.conversationId())
                .map(this::readTokens)
                .filter(tokens -> !tokens.isEmpty());
        } catch (Exception exception) {
            log.warn("Could not load the token session for conversation {}; continuing request-scoped",
                key.conversationId(), exception);

            return Optional.empty();
        }
    }
```

`readTokens` decrypts then reads the JSON, returning `Map.of()` on any failure and logging at WARN — that is what makes the undecryptable-row test pass. `save` serialises, encrypts, then upserts through the same finder (load-then-set, or insert), and deletes the row when the map is empty. `evict` deletes by `(workspaceId, conversationId)` across users, since a chat is deleted as a whole.

- [ ] **Step 4: Write the schema IntTest**

`AiGuardrailTokenSessionRepositoryIntTest`, modelled exactly on `AiGuardrailCustomRuleRepositoryIntTest`: save a row, read it back by the finder, assert the unique constraint rejects a second row with the same `(workspace_id, user_id, conversation_id)`, and assert `findByLastModifiedDateBefore` returns it. This test is what proves the changelog was actually picked up — `includeAll` fails silently.

- [ ] **Step 5: Run unit tests, the IntTest and check; commit**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:check :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:testIntegration --continue --console=plain > /tmp/s7-task3-green.log 2>&1; echo "exit=$?"
grep '^> Task .* FAILED' /tmp/s7-task3-green.log
```
Then `spotlessApply`, `git add` each created path explicitly, and commit as `732 Store a conversation's minted tokens, encrypted at rest`.

---

### Task 4: The advisor loads, mints into, and saves the conversation's session

**Files:**
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisor.java`
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/AiGuardrails.java`
- Test: `.../src/test/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisorConversationScopeTest.java`

**Interfaces:**
- Consumes: `ConversationScope.trustedKey(Map, Long)` (Task 2), `PiiTokenSessionStore` (Task 2/3), `PiiTokenSession.rehydrate` and `tokens()` (Task 1).
- Produces: `AiGuardrails#newTokenSession(ConversationScope.Key)` returning a rehydrated-or-fresh session, and `AiGuardrails#saveTokenSession(ConversationScope.Key, PiiTokenSession)`.

This is the heart of the plan. Three behaviours must hold together:

1. **A request with no trusted key behaves EXACTLY as today** — fresh session, cleared in `finally`, nothing stored. Every existing advisor test is the regression net for this; they must all still pass untouched.
2. **A request with a trusted key** loads the stored map, rehydrates, mints into it, restores from it, and **saves before closing**. The `finally`/`doFinally` still closes the in-memory session — closing clears the maps, so the save must happen first.
3. **A store failure degrades to (1)**, never to a failed request.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void testATrustedConversationRestoresATokenMintedInAnEarlierTurn() {
        // The feature, end to end: turn 1's token arrives in turn 2's prompt (out of retained chat history) and is
        // restored, where a request-scoped session would leave a dead token.
        RecordingStore store = new RecordingStore();

        store.save(KEY, Map.of("[PII_EMAIL_ADDRESS_1_abcd]", "bob@acme.io"));

        ...advisor over a real AiGuardrails wired to `store`...

        ChatClientResponse response = advisor.adviseCall(
            requestWithTrustedConversation("write to [PII_EMAIL_ADDRESS_1_abcd]"), echoingChain());

        assertThat(textOf(response)).isEqualTo("write to bob@acme.io");
    }

    @Test
    void testATrustedConversationSavesTheTokensItMinted() {
        RecordingStore store = new RecordingStore();

        ...advisor...

        advisor.adviseCall(requestWithTrustedConversation("mail bob@acme.io"), echoingChain());

        assertThat(store.load(KEY)).hasValueSatisfying(
            tokens -> assertThat(tokens).containsValue("bob@acme.io"));
    }

    @Test
    void testAnUntrustedConversationStoresNothing() {
        // Same request shape, marker absent -- the canvas agent's case. Nothing may be written, and the call must
        // still work request-scoped.
        RecordingStore store = new RecordingStore();

        ...advisor...

        advisor.adviseCall(requestWithUntrustedConversation("mail bob@acme.io"), echoingChain());

        assertThat(store.saveCount()).isZero();
    }

    @Test
    void testAStoreFailureLeavesTheRequestWorkingRequestScoped() {
        ...advisor over a throwing store...

        ChatClientResponse response = advisor.adviseCall(
            requestWithTrustedConversation("mail bob@acme.io"), echoingChain());

        assertThat(textOf(response)).isEqualTo("mail bob@acme.io");
    }

    @Test
    void testASecondTurnKeepsTheSameTokenForTheSameValue() {
        RecordingStore store = new RecordingStore();

        ...advisor...

        advisor.adviseCall(requestWithTrustedConversation("mail bob@acme.io"), echoingChain());
        advisor.adviseCall(requestWithTrustedConversation("mail bob@acme.io again"), echoingChain());

        assertThat(store.load(KEY)).hasValueSatisfying(tokens -> assertThat(tokens).hasSize(1));
    }
```

`requestWithTrustedConversation` builds a `ChatClientRequest` whose context carries `ChatMemory.CONVERSATION_ID`, `ConversationScope.PLATFORM_ISSUED_KEY=true` and `ConversationScope.USER_ID_KEY=7L`; the untrusted variant omits the marker. `echoingChain()` is a `CallAdvisorChain` mock returning the request's own user text as the assistant message, so the response path is exercised. Model these on the existing `AiGuardrailsAdvisorPublishesSpansTest` helpers.

- [ ] **Step 2: Run to verify they fail**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsAdvisorConversationScopeTest*' --console=plain > /tmp/s7-task4-red.log 2>&1; echo "exit=$?"
```

- [ ] **Step 3: Implement in the advisor**

In `adviseCall`, replace the opening line:

```java
        Optional<ConversationScope.Key> conversationKey =
            ConversationScope.trustedKey(chatClientRequest.context(), workspaceId);
        PiiTokenSession session = conversationKey
            .map(aiGuardrails::newTokenSession)
            .orElseGet(aiGuardrails::newTokenSession);

        try {
            ...unchanged...
        } finally {
            conversationKey.ifPresent(key -> aiGuardrails.saveTokenSession(key, session));

            session.close();
        }
```

`adviseStream` takes the same treatment, with the save inside the existing `doFinally` **before** `session.close()`.

In `AiGuardrails`, add the two methods at the END of the class:

```java
    public PiiTokenSession newTokenSession(ConversationScope.Key key) {
        if (piiTokenSessionStore == null) {
            return newTokenSession();
        }

        return piiTokenSessionStore.load(toSessionKey(key))
            .map(tokens -> PiiTokenSession.rehydrate(sessionIdOf(tokens), tokens))
            .orElseGet(PiiTokenSession::create);
    }

    public void saveTokenSession(ConversationScope.Key key, PiiTokenSession session) {
        if (piiTokenSessionStore != null) {
            piiTokenSessionStore.save(toSessionKey(key), session.tokens());
        }
    }
```

`sessionIdOf` reads the discriminator back off any stored token via `PiiToken.parse`, falling back to a fresh session when the map yields none — a rehydrated session MUST reuse the discriminator its stored tokens carry, or `restore` will not recognise them. `piiTokenSessionStore` is a new `@Nullable` constructor collaborator resolved through `ObjectProvider`, so CE and any context without the store behave exactly as before.

**Adding a constructor collaborator to a scanned `@Service` breaks other modules' hand-assembled `@SpringBootTest(classes = …)` contexts.** Grep for `*IntTestConfiguration` and `@TestConfiguration` classes that assemble `AiGuardrails` and add a mock bean to each.

- [ ] **Step 4: Run the whole module and commit**

The whole module's `check` must pass, not just the new test — the existing advisor tests are the regression net for "an untrusted request behaves exactly as before". Then `spotlessApply` and commit as `732 Key a token session to the conversation when the platform issued its id`.

---

### Task 5: AI Hub publishes the marker and the verified user

**Files:**
- Modify: `server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/agent/AiHubSpringAIAgent.java` (the `advisorParams` block that already puts `ChatMemory.CONVERSATION_ID`)
- Test: `.../ai-hub-service/src/test/java/com/bytechef/ee/ai/hub/agent/AiHubSpringAIAgentConversationScopeTest.java`

**Interfaces:**
- Consumes: `ConversationScope.PLATFORM_ISSUED_KEY`, `ConversationScope.USER_ID_KEY` (Task 2).
- Produces: nothing new; this is the one caller that opts in.

This task is the entire enablement. Until it lands, the store is dead code and every surface stays request-scoped — which is the correct order: the mechanism ships provably inert, then one caller turns it on.

The marker may be published **only** alongside a thread id the platform issued, and **only** with the controller-verified user id — never a user id derived from the thread itself, which would make the key circular. `AiHubSpringAIAgent` already prefers `VERIFIED_USER_ID` from state and falls back to a thread-based resolver "as belt-and-braces"; the marker is set only on the verified path.

- [ ] **Step 1: Write the failing test**

Assert three things over the built advisor params: with a verified user id and a thread id, all three keys are present and `PLATFORM_ISSUED_KEY` is `Boolean.TRUE`; with a thread id but no verified user id, neither the marker nor the user id is published (the id alone is not enough); with no thread id, none of the three is published.

- [ ] **Step 2: Run to verify it fails**, then **Step 3: publish the two params** in the existing `if (threadId != null && !threadId.isBlank())` block, guarded on the verified user id being non-null. **Step 4:** run `:server:ee:libs:ai:ai-hub:ai-hub-service:check`. **Step 5:** `spotlessApply`, commit as `732 Turn on conversation-scoped tokens for AI Hub chat threads`.

---

### Task 6: Eviction on chat deletion, and the TTL sweep

**Files:**
- Modify: `server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/chat/AiHubChatServiceImpl.java` (the delete path)
- Create: `.../platform-ai-guardrails-service/.../job/AiGuardrailTokenSessionRetentionJob.java`
- Modify: `server/libs/config/app-config/.../ApplicationProperties.java` — add `Ai.Guardrails.Session` with `retentionDays` and `retentionCron`
- Test: the chat-service test for eviction; a unit test for the job's cutoff arithmetic

**Interfaces:**
- Consumes: `PiiTokenSessionStore#evict` (Task 2), `AiGuardrailTokenSessionRepository` (Task 3).

Model the job exactly on `AiGuardrailViolationRetentionJob`: `@Component`, `@ConditionalOnEEVersion`, `@Scheduled(cron = "${bytechef.ai.guardrails.session.retention-cron:0 0 3 * * *}")`, a `@Value` retention window. **Default the window to 30 days and run at 03:00** — an hour after the violation sweep at 02:30 and half an hour after the audit sweep, so the three do not contend.

`ApplicationProperties` binds all of `bytechef.*` with `ignoreUnknownFields = false`, so a property that is not a field there fails startup. Add the fields.

Eviction is called from the chat-delete path with `(workspaceId, threadId)` — across users, because a chat is deleted as a whole. It must not fail the delete: wrap in the store's own soft-fail, and assert in the test that a throwing store still lets the chat delete succeed.

Commit as `732 Evict a conversation's tokens on chat deletion and after a TTL`.

---

### Task 7: Documentation

**Files:**
- Modify: `.agents/ai-guardrails.md` — a `## Conversation-scoped token sessions` section placed directly after the `## Advisor ordering rule` section written by the previous sub-project
- Modify: `docs/superpowers/specs/2026-08-25-guardrails-consolidation-design.md` — status line to "sub-project 3 implemented"

The section must state: which surface is enabled and that it is the only one; that provenance, not the id's shape, is the test; that the marker is trustworthy because a workflow author cannot reach the code that sets it; that the canvas agent keeps request scope permanently for an explicit author-supplied `conversationId`, and needs nothing for its other two configurations; that ordinals resume above the stored high-water mark and why; that the store fails soft; and that secrets are never in it.

Commit as `docs - Write down where conversation-scoped tokens apply and why only there`.

---

### Task 8: Whole-branch verification

No commit. Run, each with `timeout: 600000` and the redirect-then-grep discipline:

1. `./gradlew compileJava compileTestJava --continue`
2. `check` on every touched module in one `--continue` invocation: `platform-ai-api`, `platform-ai-sensitive-data-api`, `platform-ai-sensitive-data-service`, `platform-ai-guardrails-api`, `platform-ai-guardrails-service`, `ai-hub-service`, `app-config`.
3. `:server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:testIntegration` — the schema proof.
4. `./gradlew test --continue` repo-wide. `testPythonStarterLoadsThroughLoader`'s 30 s timeout is a known unrelated failure; any other failure is a real finding.
5. A grep proving no surface other than AI Hub publishes `ConversationScope.PLATFORM_ISSUED_KEY`.
6. `git status --short` clean; report the commit list.
