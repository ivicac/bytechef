# Runtime-shared IDOR authorization — plan (T29, T30)

**Date:** 2026-06-21
**Spec:** `docs/superpowers/specs/2026-06-21-runtime-shared-idor-authorization-design.md`

Tiny, independently-verifiable commits. T29 (AiSkill) is fully actionable now; T30 instances need a
short investigation step each and are sequenced after.

## Phase A — T29: AiSkill per-user owner-isolation

### A1. Add `AiSkillApiFacade` interface
- New `com.bytechef.platform.ai.skill.facade.AiSkillApiFacade` in `platform-ai-skill-api`, declaring the
  10 entry-point methods from spec §2.2 (the subset the GraphQL + download controllers use). Reuse the
  existing `AiSkillFacade.AiSkillDownload` record type.
- **Verify:** `./gradlew :…:platform-ai-skill-api:compileJava`.

### A2. Implement `AiSkillApiFacadeImpl` (owner-isolation)
- New impl in `platform-ai-skill-service`, `@Service`, constructor-injects `AiSkillFacade`.
- Private `checkOwnerOrAdmin(long id)`: admin bypass via
  `SecurityUtils.hasCurrentUserThisAuthority(AuthorityConstants.ADMIN)`, else
  `SecurityUtils.checkCurrentUserLogin(aiSkillFacade.getAiSkill(id).getCreatedBy())`.
- By-id methods call `checkOwnerOrAdmin(id)` then delegate. `getAiSkills()` filters to
  `createdBy == fetchCurrentUserLogin()` (all if admin). `create*` delegate directly (owner set by
  `@CreatedBy`).
- Both symbols are already on the classpath via `platform-api`; no new dependency.
- **Verify:** `./gradlew :…:platform-ai-skill-service:compileJava`.

### A3. Unit test `AiSkillApiFacadeImplTest`
- Mock `AiSkillFacade`; drive `SecurityContextHolder` (or `SecurityUtils.runAs`) for owner / non-owner /
  admin / unauthenticated. Assert: owner & admin succeed; non-owner + `null createdBy` throw
  `AccessDeniedException` **and** the delegate mutation is never called; `getAiSkills()` filtering.
- **Verify:** `./gradlew :…:platform-ai-skill-service:test --tests '*AiSkillApiFacadeImplTest'`.

### A4. Route the two controllers through `AiSkillApiFacade`
- `AiSkillGraphQlController` (`platform-ai-skill-graphql`) — swap `AiSkillFacade` → `AiSkillApiFacade`
  for all 9 handlers.
- `AiSkillDownloadController` (`platform-ai-skill-rest`) — swap to `AiSkillApiFacade`;
  `downloadAiSkill` → `getAiSkillWithDownload`.
- Leave the AI runtime tools (`SkillsTools`, `ReadSkillsTools`, `AiAgentUtils*`) on `AiSkillFacade`.
- **Verify:** compile both controller modules + `:…:components:ai:agent:utils:compileJava` (runtime path
  unchanged); `spotlessApply`; checkstyle/pmd/spotbugs on the three AiSkill modules.

### A5. Mark T29 done in the tracker
- Flip `[ ] T29` → `[x]`, add the **Done** note (per-controller `AiSkillApiFacade`, login-based check,
  runtime path untouched, behavioural note about per-user skill lists).
- Commit message: `gecko Close AiSkill per-user IDOR via AiSkillApiFacade (T29)`.

## Phase B — T30: cluster (one commit each, investigate first)

### B1. `WorkflowEditorSpringAIAgent` (6.5) — workspace-scope the workflow read
- Investigate: confirm it runs with the editor's `SecurityContext`. If yes, scope the workflow lookup to
  the caller's accessible workspace; if it runs detached, apply the per-controller-facade pattern.

### B2. `ReadProjectWorkflowTools` (6.2) — scope the query
- Filter the workflow listing to the caller's accessible workspaces (mirror T25 search-provider
  scoping), not a `@PreAuthorize` gate.

### B3. `WebhookTriggerTest{ApiController,FacadeImpl}` (7.6/6.3/5.4) — separate runtime path, then gate
- Separate the runtime webhook-test entry (called from `WorkflowNodeTestOutputFacadeImpl.disableTrigger`)
  via a runtime-only method or self-invocation, then gate the editor methods with
  `hasWorkflowScope(#workflowId, …)` as in T22b.

### B4. Mark T30 done / update residuals; resolve the three needs-review items
- `AbstractApiKeyAuthenticationConverter`, `ConnectedUserProjectWorkflowApiController`,
  `PlatformConfigurationAuthorizeHttpRequestContributor` — verify each is a true gap or already covered;
  fix or document.

## Global verification gate
- `./gradlew spotlessApply check` for the touched modules (the app-context load is the regression net
  for `@PreAuthorize`/bean wiring), plus the existing agent-utils tests stay green to prove the runtime
  path is intact.
