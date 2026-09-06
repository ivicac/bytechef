# Response-scanning categories and the restoration default — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the output direction of guardrails honour `redactPii`/`redactSecrets`, flip `restoreIntoWorkflowOutput` to default OFF, and remove the documentation both directions of the earlier decisions left behind.

**Architecture:** Three decisions reversed on one discovery — nothing has shipped, so every argument of the form "don't disturb existing deployments" was void. `scanResponseText` and the AI gateway's response path stop calling `redactAll`'s all-kinds form and use a category-aware sibling resolved from the same policy the input direction already uses. `AiGuardrails#isRestoreIntoWorkflowOutput` inverts its default, which also inverts its fail-open/fail-closed posture — and for the first time makes the two agree. The documentation written for the superseded decisions is removed rather than amended.

**Tech Stack:** Java 25, Spring Boot 4, JUnit 5 + Mockito + AssertJ, React 19 / TypeScript.

**Specs:**
- `docs/superpowers/specs/2026-09-05-per-category-response-scanning-design.md` (D1 = (c))
- `docs/superpowers/specs/2026-09-05-restoration-destination-boundary-design.md` (D3 = OFF)
- `docs/superpowers/specs/2026-09-05-embedded-guardrail-settings-scope-design.md` (D4 = moot)

## Global Constraints

- **Nothing has shipped.** `git ls-tree -r --name-only v0.31.4 | grep -c AiGuardrails` returns 0. No migration, no compatibility shim, no marker column, and no upgrade note is warranted anywhere in this plan. If you find yourself preserving old behaviour for existing deployments, stop — there are none.
- **`redactAll` keeps its all-kinds meaning.** Callers that genuinely want every `SensitiveKind` still get it. The change is a category-aware sibling, not a redefinition — check every caller before touching it.
- **Both response paths move together** (per-category spec, §"Two things (c) needs"). `AiGuardrails#scanResponseText` and `AiGatewayGuardrails`'s project-overlay response scan have the identical defect; fixing one leaves the other asymmetric, and the gateway is the surface carrying the customer's own traffic.
- **The input direction is already correct** and must not change. `redactPiiAndSecrets` conditionally adds `PII`/`SECRET` from the same two flags; that is the behaviour the output direction is being brought into line with.
- **D8 is untouched.** Streaming responses restore unconditionally — a realtime voice agent must never read a token back to the person who just spoke it. `StreamingResponseRedactor` and `adviseStream` are out of scope for the D3 flip.
- Files under `server/ee/` carry the **ByteChef Enterprise license header** and a `@version ee` Javadoc tag. Spotless applies the EE header only to files that already contain the literal tag, stamping Apache 2.0 otherwise — so for any new file, write the Javadoc with its tag FIRST, then run `spotlessApply`.
- Use `org.jspecify.annotations.Nullable`, never `org.springframework.lang.Nullable` (deprecated since Spring 7.0).
- Client lint that fails the build: object keys alphabetical (`sort-keys`, not auto-fixable), named imports sorted alphabetically within `{}`, `twMerge` not `cn()`, Lucide icons with the `Icon` suffix.
- Run `./gradlew spotlessApply` before every commit.
- Judge a Gradle run by `$?` on its own line plus `grep '^> Task .* FAILED'` on a redirected log — never by a piped `tail`, whose exit code is the filter's.
- Gradle needs `JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce`. Bash calls need `timeout: 600000`.
- Commit messages: `732 <description>` server, `732 client - <description>` client, `732 docs - <description>` docs.
- **Never amend, never `git reset`, never `git stash`, never `git branch -f`** — the maintainer commits in parallel and the stash stack is shared across worktrees.
- Stage **by path**, never `git add -A` or `git add .`.

---

### Task 1: The output direction honours the category switches

**Files:**
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/AiGuardrails.java`
- Modify: `server/ee/libs/automation/automation-ai/automation-ai-gateway/automation-ai-gateway-service/src/main/java/com/bytechef/ee/automation/ai/gateway/guardrail/AiGatewayGuardrails.java`
- Test: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/AiGuardrailsTest.java` (modify)
- Test: `server/ee/libs/automation/automation-ai/automation-ai-gateway/automation-ai-gateway-service/src/test/java/com/bytechef/ee/automation/ai/gateway/guardrail/AiGatewayGuardrailsTest.java` (modify)

**Interfaces:**
- Consumes: `EffectivePolicy`'s existing `redactPii()` / `redactSecrets()` / `scanResponses()` / `minConfidence()`.
- Produces: a category-aware redaction entry point on `AiGuardrails` that both response paths call. Name it for what it does, not for what it is not — e.g. `redactEnabledKinds(...)` — and keep `redactAll` as-is for callers wanting every kind.

- [ ] **Step 1: Write the failing tests**

In `AiGuardrailsTest`, replace `testScanResponseTextScrubsPiiAndSecretsWhenEnabled` — the test that pins today's behaviour — with tests that pin the new one. It currently constructs guardrails with **both** category flags `false`, `scanResponses` `true`, and asserts a secret and an e-mail are both redacted. Under (c) that is precisely the case that must now redact **neither**.

```java
    @Test
    void testScanResponseTextRedactsOnlyTheKindsTheWorkspaceEnabled() {
        // redactPii = true, redactSecrets = false, scanResponses = true
        String scanned = guardrails.scanResponseText(
            "The leaked key is AKIAIOSFODNN7EXAMPLE and the contact is bob@acme.io",
            AiGuardrailsSettingsTarget.platform(), metrics);

        assertThat(scanned).contains("[REDACTED_EMAIL_ADDRESS]");
        assertThat(scanned).contains("AKIAIOSFODNN7EXAMPLE");
    }

    @Test
    void testScanResponseTextRedactsNothingWhenNeitherKindIsEnabled() {
        // redactPii = false, redactSecrets = false, scanResponses = true
        assertThat(scanned).isEqualTo(original);
    }

    @Test
    void testScanResponseTextStillRedactsNothingWhenScanResponsesIsOff() {
        // redactPii = true, redactSecrets = true, scanResponses = false
        assertThat(scanned).isEqualTo(original);
    }
```

The mirror-image case (`redactSecrets` on, `redactPii` off) belongs here too — write it out; the two are not symmetric in the detectors and asserting only one direction has misled this codebase before.

The third test matters as much as the first two: `scanResponses` remains the direction switch, and (c) must not turn it into a no-op that scans whenever a category is enabled.

In `AiGatewayGuardrailsTest`, add the equivalent for the project-overlay response path.

- [ ] **Step 2: Run them to verify they fail**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsTest' > /tmp/c1.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/c1.log
```

Expected: the first two FAIL on their assertions — today's code redacts both kinds regardless. The third passes already; it is a regression guard.

- [ ] **Step 3: Add the category-aware entry point**

Model it on `redactPiiAndSecrets`, which already builds a kind set conditionally from the same two flags — the point of (c) is that both directions use the same rule, so derive the set the same way rather than writing a second expression of it. Give it a Javadoc saying it is the output-direction counterpart of the input-direction set, and that `redactAll` remains for callers wanting every kind irrespective of policy.

- [ ] **Step 4: Point both response paths at it**

`AiGuardrails#scanResponseText` replaces its `redactAll(text, policy.minConfidence(), recordingMetrics)` call. `AiGatewayGuardrails`'s project-overlay branch replaces `aiGuardrails.redactAll(scanned, aiGuardrails.resolveMinConfidence(target))`.

The gateway call site carries a comment about a 2026-08-31 review fix (the min-confidence resolution). **Keep that fix and its comment** — this change is orthogonal to it, and the comment explains a defect that would otherwise be reintroduced.

- [ ] **Step 5: Run them to verify they pass**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:test --continue > /tmp/c1.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/c1.log
```

Any other test that goes red here is asserting the superseded all-or-nothing behaviour — read it before changing it, and say in your report which ones you changed and why.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/platform/platform-ai/platform-ai-guardrails server/ee/libs/automation/automation-ai/automation-ai-gateway
git commit -m "732 Make response scanning honour the workspace's category switches"
```

---

### Task 2: `restoreIntoWorkflowOutput` defaults OFF

**Files:**
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/AiGuardrails.java`
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-api/src/main/java/com/bytechef/ee/platform/ai/guardrails/domain/AiGuardrailsWorkspaceSettings.java` (Javadoc only)
- Test: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/AiGuardrailsRestoreIntoWorkflowOutputTest.java` (modify)

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `isRestoreIntoWorkflowOutput` returns `true` only for an explicit `true`.

**This task inverts a fail-open into a fail-closed. Read this before writing code.**

The method today is:

```java
        return settings == null || !Boolean.FALSE.equals(settings.restoreIntoWorkflowOutput());
```

Its Javadoc argues at length that it is deliberately fail-**open**, and contrasts it with `resolveMcpOutboundPolicy`'s fail-**closed** posture. That argument was: failing closed would replace the values every already-running workflow hands downstream with placeholders. **Nothing is running**, so the argument is void.

Inverting the default also inverts the posture, and for the first time the two agree: a lookup failure now yields OFF, which is both the default *and* the safe direction — no restoration, no PII to a downstream node the admin never authorised. That is a genuine improvement over today's tension, not a side effect to be papered over. The Javadoc must be rewritten to say so; leaving the fail-open rationale in place would leave the file arguing against its own code.

- [ ] **Step 1: Write the failing tests**

The existing test class pins the old default across four cases (unset, true, false, no row) plus a throwing lookup. Invert the expectations that change and keep the ones that do not:

| Case | Before | After |
|---|---|---|
| Setting is `true` | true | **true** (unchanged) |
| Setting is `false` | false | **false** (unchanged) |
| Setting is null / not set | true | **false** |
| No settings row at all | true | **false** |
| Settings lookup throws | true | **false** |

- [ ] **Step 2: Run them to verify they fail**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsRestoreIntoWorkflowOutputTest' > /tmp/c2.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/c2.log
```

Expected: the three changed rows FAIL; the two unchanged ones pass.

- [ ] **Step 3: Invert the default**

```java
        return settings != null && Boolean.TRUE.equals(settings.restoreIntoWorkflowOutput());
```

- [ ] **Step 4: Rewrite both Javadocs**

On `isRestoreIntoWorkflowOutput`: it is now fail-closed, a lookup failure yields no restoration, and that is the safe direction as well as the default. Delete the fail-open contrast with `resolveMcpOutboundPolicy` — that method is still fail-closed and now agrees rather than contrasts, so the paragraph explaining the difference no longer describes anything.

On `AiGuardrailsWorkspaceSettings`'s `restoreIntoWorkflowOutput` component: its Javadoc currently explains that null means ON because defaulting it off "would silently rewrite the output of every running workflow with no error and no failed step". Both halves are now wrong — null means OFF, and there are no running workflows. Replace it with the D3 reasoning: the failure under OFF is visible where the failure under ON is silent, and a workspace that reached this setting has already opted into tokenization.

- [ ] **Step 5: Run the module suite**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --continue > /tmp/c2.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/c2.log
```

`AiGuardrailsAdvisorRestorationDestinationTest` stubs this method explicitly in most cases and should be unaffected — but any test that relied on the *default* rather than stubbing it will now flip. Read each before changing it, and name them in your report.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/platform/platform-ai/platform-ai-guardrails
git commit -m "732 Default restoreIntoWorkflowOutput to OFF, and fail closed with it"
```

---

### Task 3: Remove the documentation the superseded decisions left behind

**Files:**
- Modify: `.agents/ai-guardrails.md`
- Modify: `client/src/ee/pages/settings/automation/ai/guardrails/AiGuardrails.tsx`
- Modify: `client/src/ee/pages/settings/embedded/guardrails/EmbeddedGuardrails.tsx`
- Test: `client/src/ee/pages/settings/automation/ai/guardrails/AiGuardrails.test.tsx` (if it asserts on changed copy)
- Test: `client/src/ee/pages/settings/embedded/guardrails/EmbeddedGuardrails.test.tsx` (same)

**Interfaces:**
- Consumes: Tasks 1 and 2. Produces nothing.

Four pieces of prose now assert the opposite of what ships. Each was written deliberately, so remove each deliberately.

- [ ] **Step 1: The response-scanning asymmetry text**

`.agents/ai-guardrails.md` gained a paragraph recording the all-or-nothing output direction as decided. Under (c) there is no asymmetry left to record. **Replace it** with a short statement of the new rule — `scanResponses` decides whether the output direction is scanned, `redactPii`/`redactSecrets` decide which kinds, and they compose in both directions — rather than deleting it outright: the composition rule is worth stating in its own right.

- [ ] **Step 2: D4's upgrade note**

`.agents/ai-guardrails.md` carries an upgrade note beginning "**Upgrading:** embedded guardrail settings take effect on upgrade…", warning that a blocking mode set long ago will begin blocking. **Delete it.** Nothing has shipped, so no operator has such a setting, and the spec records D4 as moot for exactly that reason. Do not reword it into a first-release note — there is nothing on a first release to review.

- [ ] **Step 3: The restoration exposure text**

`.agents/ai-guardrails.md` describes `restoreIntoWorkflowOutput` as ON by default with the exposure live until an admin turns it off, and points at `pii_restored` as the metric for deciding. Rewrite for the OFF default: the exposure does not ship open, turning the setting **on** is what enables real values downstream, and `restore_suppressed` still distinguishes "withholding" from "nothing in flight".

- [ ] **Step 4: The settings-page copy, both pages**

Two strings changed by the superseded decisions:

- `Scan responses` says it covers PII and secrets "regardless of the Redact PII and Redact secrets switches above". Under (c) that is false — rewrite it to say it scans the model's responses for whichever kinds those switches enable.
- `Restore PII in workflow output` describes ON as the default and warns about turning it off. Under D3 the default is OFF — rewrite so it describes what turning it **on** does.

Per the per-category spec, also handle the new footgun: `scanResponses` on with both categories off is now a no-op that reads as enabled. Disable or grey the control in that state, or say plainly in its description that it does nothing until a category is enabled. Choose one, and say which in your report.

Check both test files for assertions on the changed copy and update rather than delete them.

- [ ] **Step 5: Verify**

```bash
cd client && npm run check
```

Full timeout; Node v24.15.0 is active and supported — do not change Node versions.

- [ ] **Step 6: Commit**

```bash
git add .agents/ai-guardrails.md
git commit -m "732 docs - Record the composed scanning rule and the OFF restoration default"
git add client/src/ee/pages/settings
git commit -m "732 client - Rewrite the scanning and restoration copy for the new defaults"
```

---

## Final verification

- [ ] Whole tree compiles:

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew compileJava compileTestJava --continue > /tmp/cfinal.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/cfinal.log
```

- [ ] No prose anywhere still asserts the superseded decisions:

```bash
grep -rn "regardless of the Redact PII\|all-or-nothing\|take effect on upgrade" .agents client/src/ee/pages/settings server/ee/libs/platform/platform-ai
```

Expected: no hits describing the old behaviour.

- [ ] `cd client && npm run check` clean.
- [ ] `./gradlew spotlessApply` re-run after any late edit — formatters run first in CI.
