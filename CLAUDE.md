# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Overview

ByteChef is an open-source, low-code API integration and workflow automation platform built on Spring Boot. It serves as both an automation solution and an embedded iPaaS (Integration Platform as a Service) for SaaS products.

**Maintaining this file:** client-only conventions live in `client/CLAUDE.md` and CLI usage in `cli/CLAUDE.md`, both
loaded only when working under those directories. This file is loaded into every session, so it stays a working guide — commands,
conventions, and cross-cutting gotchas. Feature deep-dives belong in `.agents/` with a one-line
pointer in the deep-dive index below; design rationale belongs in `docs/superpowers/specs/`.
When a landed feature produces a page of narrative detail, write it to `.agents/`, not here.

## Essential Development Commands

### Server Development

All server commands should be run from the project root directory:

```bash
# Build and compile the project
./gradlew clean compileJava

# Run the server locally (requires Docker infrastructure)
cd server
docker compose -f docker-compose.dev.infra.yml up -d
cd ..
./gradlew -p server/apps/server-app bootRun

# Code formatting (must run before commits)
./gradlew spotlessApply

# Run checks and tests
./gradlew check
./gradlew test && ./gradlew testIntegration

# Generate component documentation
./gradlew generateDocumentation
```

### Infrastructure Setup

```bash
# Start PostgreSQL, Redis, and other services
cd server
docker compose -f docker-compose.dev.infra.yml up -d

# Or run everything in Docker
docker compose -f docker-compose.dev.server.yml up -d
```

## Architecture Overview

### Core Technology Stack
Versions live in `gradle/libs.versions.toml` and `client/package.json`. Two things they do not say: Jackson is
mid-transition to 3.x, so both `com.fasterxml.jackson` and `tools.jackson` packages appear in deps; and
TailwindCSS is v4 through the Vite plugin, not PostCSS.

### Enterprise Edition (EE) Microservices
The `server/ee/` directory contains the microservices for distributed deployment.

**EE Code Conventions:**
- Use ByteChef Enterprise license header (not Apache 2.0) for all files under `server/ee/`
- Add `@version ee` Javadoc tag to all classes under `server/ee/`

### Available Components
ByteChef ships built-in components in `server/libs/modules/components/` covering CRM, project
management, communication, e-commerce, cloud storage, AI/ML, databases, and custom code execution.
Count them by **leaf module**, not top-level directory — several top-level entries are grouping
folders holding many components each (`google` holds 18, `microsoft` 7) and a few are not
components at all (`build`, `libs`). For the current number, count the generated reference pages:
`ls docs/content/docs/reference/components | wc -l`.

## Code Style and Best Practices

### Variable Naming
- Do not use short or cryptic variable names on both the server and client sides; prefer clear, descriptive names that communicate intent.
- Do not prefix private methods with `_` — use plain method names (e.g., `extractFrontmatter` not `_extractFrontmatter`)
- This applies everywhere, including arrow function parameters and loop variables.
- Examples:
  ```typescript
  // Bad
  const current = users.find((u) => u?.login === login);

  // Good
  const current = users.find((user) => user?.login === login);
  ```
  ```java
  // Bad
  for (Order o : orders) { ... }

  // Good
  for (Order order : orders) { ... }
  ```

### GraphQL Conventions
- Enum values must use SCREAMING_SNAKE_CASE (e.g., `DELETE`, `GET`, `QUERY`, `PATH`)
- Consistent with HttpMethod and other enums in `*.graphqls` files

### ID Generation
- Avoid `hashCode()` for generating unique identifiers (collision risk)
- Prefer SHA-256 with first 8 bytes for deterministic long IDs, or UUID for true uniqueness

### Blank Line Before Control Statements (Java)
- Insert exactly one empty line before control statements to improve visual separation of logic:
  - Applies to: `if`, `else if`, `else`, `for`, enhanced `for`, `while`, `do { ... } while (...)`, `switch`, `try`/`catch`/`finally`.
- Exceptions (do not add a blank line):
  - At the very start of a file, class, or method/block (immediately after an opening `{`).
  - When the control keyword directly continues the previous block on the same line (e.g., `} else {`, `} catch (...) {`, `} finally {`).
  - Immediately after another required blank line (avoid double blank lines).
  - Very short top-of-method guard clauses may omit the blank line for brevity when they appear immediately after the method signature.
  - If the automatic formatter (Spotless/Google Java Format) enforces a different layout, the formatter’s output wins.
- Example:
  ```java
  void process(User user) {
      if (user == null) {
          return;
      }

      for (Order order : user.getOrders()) {
          // ...
      }

      try {
          doWork();
      } catch (IOException e) {
          handle(e);
      }
  }
  ```

### Blank Line After Variable Modification (Java)
- Insert exactly one empty line between a variable modification and a subsequent statement that uses that variable
- This improves readability by visually separating the setup from the usage
- Example:
  ```java
  // Bad
  document.setStatus(KnowledgeBaseDocument.STATUS_PROCESSING);
  knowledgeBaseDocumentService.saveKnowledgeBaseDocument(document);

  // Good
  document.setStatus(KnowledgeBaseDocument.STATUS_PROCESSING);

  knowledgeBaseDocumentService.saveKnowledgeBaseDocument(document);
  ```

### No Trailing Blank Line in Class Body (Java)
- Do not add an empty line between the last method (or field) and the closing `}` of a class

### Method Chaining
- Do not chain method calls except where this is natural and idiomatic
- Allowed exceptions (non-exhaustive):
  - Builder patterns (including Lombok `@Builder`)
  - Java Stream API (`stream()`, `map()`, `filter()`, `collect()`)
  - `Optional`
  - Query DSLs and criteria builders:
    - Spring Data JPA `Specification` (`where(...).and(...).or(...)`)
    - JPA Criteria API (fluent `CriteriaBuilder`/`Predicate` construction)
    - QueryDSL (`JPAQueryFactory.select(...).from(...).where(...).orderBy(...)`)
    - jOOQ (`dsl.select(...).from(...).where(...).orderBy(...)`)
  - Reactive operators: Project Reactor `Mono`/`Flux` (e.g., `map`, `flatMap`, `filter`, `onErrorResume`)
  - HTTP client builder/request DSLs: Spring `WebClient`, OkHttp
  - Testing/assertion DSLs: AssertJ, Mockito BDD APIs
  - Logging fluent APIs: SLF4J 2.x `log.atXxx()` fluent logger
  - JSON builders and similar fluent APIs: Jackson `ObjectNode`/`ArrayNode`, JSON‑P `JsonObjectBuilder`

- Formatting rules:
  - Break each chained step onto its own line for readability when there are 3+ operations or lines exceed the limit
  - Keep declarative chains (queries, reactive pipelines) as one logical block; prefer one operation per line
  - Avoid chaining when side effects are involved or intermediate values deserve names for clarity/debugging

### Code Quality Tool Patterns

**SpotBugs**:
- Don't use rough approximations of known constants (e.g., use `Math.PI` instead of `3.14`)
- Always check return values of methods like `CountDownLatch.await(long, TimeUnit)` - returns boolean
- Use try-with-resources for `Connection` objects to avoid resource leaks
- Catch specific exceptions (`SQLException`) instead of generic `Exception` when possible

**PMD**:
- Use `@SuppressWarnings("PMD.UnusedFormalParameter")` for interface-required but unused parameters
- Don't qualify static method calls with the class name when already inside that class (e.g., `builder()` not `ClassName.builder()`)

**Checkstyle**:
- Test method names must be camelCase without underscores (e.g., `testExecuteSuccess` not `testExecute_Success`)
- Naming rule applies to ALL methods in test sources (including private helpers), not just `@Test` methods
- Empty blocks are forbidden — a comment alone doesn't satisfy the `EmptyBlock` rule; add an executable statement
- `TODO:` comments are forbidden (`TodoComment` rule) — rewrite as plain comments describing intent, or implement the work

### Agent HITL approvals (chat cards + tool gate)

Two disjoint primitives: **Approval** (a decision; a comment is valid on BOTH outcomes) and
**AskUserQuestion** (LLM clarification). Typing in chat NEVER resolves an approval. Delivery fans out
best-effort over 15 channel elements (chat, Slack, Discord, Telegram, Mattermost, Rocket.Chat, Gmail,
Outlook 365, SMTP email, WhatsApp via Meta/Twilio/Infobip, SMS via Twilio/Infobip, approval task), failing the
step only when every configured channel fails. Besides the chat card, five resolve in place through a signed
interactivity callback: Slack, Discord, Telegram, WhatsApp (Meta) and Mattermost. The gate is the
`aiAgentUtils/v1/approvalGateTool` cluster element: every tool nested in it is wrapped in
`ApprovalGateToolCallback`, which suspends via the sentinel protocol. On Agents, a TOOL row with
`requiresApproval: true` is generated into that gate, and only when the agent also has an `APPROVAL_GATE` row.
Expiry, reminders, and escalation each run a 15-minute platform-coordinator sweep. Never add notification logic
under `server/libs/atlas/`.
See `.agents/hitl-approvals.md`. The AI Hub chat has a separate, unrelated tool approval gate — see
`.agents/ai-hub.md`'s "Tool approval gate" section.

### Component Rules (EE)

`server/ee/libs/platform/platform-component-rule/`, sibling to `platform-component-policy`. Governs **AI agent tool
calls**, not workflow action executions — a rule is keyed on `(componentName, toolName, workspaceId)` of a TOOLS
cluster element, enforced by wrapping every tool callback an agent can call, not by hooking
`ActionDefinitionServiceImpl`. The condition is a ByteChef formula body, not free SpEL (`SpelEvaluator` parses full
SpEL only behind a `=` prefix, prepended at both save-time validation and evaluation; `T(`, `.method(` calls, and
`new` are rejected, so conditions use whitelisted evaluator functions like `contains`/`equalsIgnoreCase`/`size`). A
non-strict rule fails open on an unevaluable condition by design — a mis-authored rule must not take a tenant's
agents offline; `strict` inverts that per rule. The enforcement cache is keyed by **(tenantId, componentName,
workspaceId)**, not componentName alone, since component names are global.
Rules are workspace-scoped: `workspace_id` is a nullable column, null meaning every workspace in the tenant; the
page lives under Settings → AI → Agents → Rules, not Components. See `.agents/component-rules.md` for the wrapper
contract, the approval protocol, observe mode, strict evaluation, risk levels, workspace resolution and its
fail-narrow-to-tenant-wide invariant, and the worker-app distributed-deployment gap.

### Environment promotion (EE)

`server/ee/libs/automation/automation-promotion` promotes an API collection, MCP server, A2A server or
plain project deployment to its counterpart in another environment, matched by a lineage `uuid` rather
than by name. **Monolith only** (`server-app`). Re-promotion syncs ONLY the exposed surface; name, tags,
enabled flags, auth settings, secret keys and existing connection bindings are environment-local. A
created counterpart is always disabled. See `.agents/environment-promotion.md`.

### Spring Boot Project Conventions

- **Integration Test Naming**: All integration test classes must end with "IntTest" suffix (e.g., `WorkflowFacadeIntTest.java`)
- **Spring 7 Programmatic Bean Registration**:
    - Use `BeanRegistrar` + `@Import` instead of `BeanFactoryPostProcessor` for programmatic bean registration
    - Resolve collection dependencies via `context.beanProvider(Class).orderedStream().toList()` (replaces `beanFactory.getBeansOfType()`)
    - Resolve named beans via `context.bean("beanName", Class)` in supplier
    - Test `BeanRegistrar` specs by capturing `Consumer<Spec<T>>` with `ArgumentCaptor`, applying to mock `Spec`, and verifying fluent calls
- **Five executors, no queues.** `AsyncConfiguration` defines `taskExecutor` (default `@Async`, limit
  `bytechef.async.concurrency-limit`), `workerExecutor` (task handlers + trigger executions, limit
  `bytechef.worker.task.subscriptions.default`), `syncWorkerExecutor` (the API-Platform sync path and the editor's
  Test button, limit `bytechef.worker.task.sync-concurrency-limit`), `appExecutor` (the actions, tools and proxy calls
  Apps request, limit `bytechef.app.execution.concurrency-limit`; registered only when that property is set, which only
  `server-app`'s `application-bytechef.yml` does) and `messageEventExecutor` (Spring-event→broker bridge, unbounded).
  The sync lane is a bulkhead, not extra capacity: without it a burst of scheduled runs saturates the worker lane and
  an interactive caller blocks on a permit while holding a request thread. The app lane is the same bulkhead one step
  further: apps are external, machine-driven callers, and on the sync lane a burst from them would starve the editor's
  Test button and the API-Platform sync path. App workflow runs do not use it — they go through the job path on
  `workerExecutor`. All are queue-less `SimpleAsyncTaskExecutor`s: a submitter waits for a permit. Anything that runs
  component code or blocks on I/O goes on `workerExecutor`, never the default. Virtual threads change only the thread
  factory — they do not lift a limit, which is why these are not `ThreadPoolTaskExecutor`s. The bridge stays unbounded
  because it only hands messages to the broker: starving it stalls every workflow, while the real work is already
  bounded at `workerExecutor` (and DB connections at Hikari's `maximum-pool-size`). Don't bound
  `AsyncMessageBroker`'s own dispatch executor either — it carries work-initiating and completion events together,
  and each `JobSyncExecutor` builds a private instance whose latch waits on events from it, so a bound there can
  wedge the API-Platform sync path and the editor's Test button. Its per-route ordered executors (single thread,
  bounded queue, blocking put) are a separate, deliberate case and stay bounded. `AsyncConfigurationTest`
  enumerates them, so it catches the decorator being dropped from `createExecutor` — a sixth executor built
  outside that factory isn't covered; every executor must go through `createExecutor`. The one deliberate exception is
  `WorkflowSubflowSyncExecutorConfiguration`, which builds its own unbounded executor: subflows nest through a
  `SubflowTaskDispatcher`, so a parent holds a permit while its child needs one, and a shared bounded lane would
  deadlock past the nesting depth.

## Feature deep-dives

These areas have enough hard-won detail that it lives in its own file rather than here. **Read the
relevant file before working in that area** — each records invariants whose violation is silent.

| Area | Read before touching |
|------|----------------------|
| `.agents/ai-hub.md` | AI Hub chats, agent tool tiers, subagent memory + interactive questions, auto-memory, Copilot module map |
| `.agents/agents.md` | Agents (automation): channels, generated workflows, elements, publishing |
| `.agents/data-sync.md` | Data Sync: rows-as-truth, generated draft in its project, fixed node names, publish/delete with the project, Run now path, form-mode wizard |
| `.agents/hitl-approvals.md` | Approval cards, the tool gate, delivery fan-out |
| `.agents/ai-guardrails.md` | Guardrails engine, advisor, per-surface coverage |
| `.agents/ai-gateway-guardrails.md` | Gateway adapter + project overlay |
| `.agents/ai-model-catalog.md` | CE snapshot vs EE persisted twin |
| `.agents/agentic-ai-component.md` | Embabel GOAP component (opt-in) |
| `.agents/code-artifacts.md` | Custom components & code workflows: draft/publish, perform context, declared connections |
| `.agents/mcp-a2a-servers.md` | MCP workflows-as-tools (fromAi), A2A servers |
| `.agents/environment-promotion.md` | Promoting API collections / MCP / A2A / deployments across environments |
| `.agents/embedded-bridge.md` | Embedded automation code workflow bridge; referenced catalog templates (visual and code) |
| `.agents/field-mapping.md` | Embedded field mapping: per-user input merge, the `field-mapping` component, `sampleMapping`, what was deliberately not built |
| `.agents/api-connectors.md` | API connectors (spec-as-source-of-truth) |
| `.agents/resource-visibility.md` | Workspace scoping, visibility/sharing, per-environment roles |
| `.agents/execution-reliability.md` | Execution retention, crash recovery, notifications, error workflow, URL signing |
| `.agents/component-wrappers.md` | Component wrapper patterns |
| `.agents/component-rules.md` | Component Rules: agent tool governance, approval, observe mode, strict, risk levels |
| `.agents/voice.md` | Browser voice: engine outside Atlas, Voice Agent cluster element, stage contract, tool policy path, no HITL in a call, `sessionId` protocol, node-affinity gap |
| `.agents/apps.md` | Apps (EE): governed AI-built apps, app secret key + `/api/app/v1` auth, execution lane, flat tool names, the connection proxy, connection-delete guard SPI |
| `.agents/variables.md` | Variables (EE): `property`-row storage, runtime seeding into `Job.inputs`, the distributed-EE gap, why they are not secrets |
| `.agents/fast-startup.md` | `fastStartup`: fast IntelliJ `server-app` startup with pre-built module JARs |

Design rationale for most of the above lives in `docs/superpowers/specs/`.

### Cross-cutting rules from those docs

These are the parts that apply outside their own area, so they stay here:

- **New platform-package entities get a nullable `workspace_id BIGINT` column** — not a
  `workspace_<entity>` relation table. The field is `Long`, never primitive; null is a real state.
  Create a relation table only for a genuinely many-to-many relationship with no owner concept
  (the `workspace_user` shape). Six pre-existing relation tables deliberately remain.
- **Every resource is created WORKSPACE-visible.** Visibility is a *precondition* of
  `hasResourceScope`, not a filter beside it. "Specific people" is `PRIVATE` + `resource_grant` rows.
  The one deliberate exception is the AI Hub chat, created `PRIVATE` — see `.agents/ai-hub.md`.
- **A `MessageRoute`'s exchange is its delivery contract, on every broker.** `MESSAGE` is a work queue
  (one instance processes each message; task and trigger dispatch). `CONTROL` is a broadcast (every
  instance sees every message; not retained for late subscribers). A signal whose consumer the
  publisher cannot know — cancel events, job-status and SSE stream events for whichever node holds
  the HTTP request or the awaiting future — must be `CONTROL`, or a second replica silently eats it
  and the waiting node times out (`SSE_STREAM_EVENTS` is the canonical case). Redis maps
  `CONTROL` to pub/sub and `MESSAGE` to a consumer-group stream read with a blocking `XREADGROUP`
  (no poll sleep); AMQP to an anonymous per-listener queue on the topic exchange vs a shared durable
  queue on the direct exchange; Kafka to a per-instance consumer group; JMS to the topic domain. On a
  `CONTROL` route every delegate registered in one JVM receives every message that JVM receives; on a `MESSAGE`
  route each message goes to a single delegate (round-robin).
  `RedisMessageBrokerIntTest` and `AmqpMessageBrokerIntTest` pin both semantics against real brokers.
- **Never add notification, admission, or approval logic under `server/libs/atlas/`** — the engine
  stays agnostic; those concerns live in `platform-coordinator` and the platform modules.
- **Enum ordinals are persisted as INT** — append new values at the end, never reorder.
- **A new task dispatcher must be registered in three places, not one.** Its own `@Bean`s cover the
  production coordinator, which autowires `List<TaskDispatcherResolverFactory>` — but
  `WorkflowTestConfiguration` (the editor's Test button) and `WebhookConfiguration` each build their
  chain from a literal `List.of(...)`. An unregistered type matches no resolver, falls through to the
  worker, and surfaces as `Component definition with name '<type>' ... not found`. Adding the Gradle
  dependency is not enough — `WorkflowTestConfigurationTest`/`WebhookConfigurationTest` scan
  `com.bytechef.task.dispatcher` and fail when a dispatcher or completion handler on the classpath is
  missing from either list.
- **The embedded iframes load their HTML entries by file name, with hash routing.** The SDK points
  `EmbeddedWorkflowBuilder` at `/workflow-builder.html#/embedded/builder/<uuid>` and `AutomationHub` at
  `/automation-hub.html#/embedded/hub`, and both entries (`src/ee/workflow-builder.tsx`,
  `src/ee/automation-hub.tsx`) use `createHashRouter`. That is what lets the Vite dev server serve them
  with no rewrite: an extension-less `/embedded/...` URL falls back to `index.html`, whose session check
  bounces the iframe to `/login`. `SpaWebFilter`'s `/embedded/builder/**` and `/embedded/hub` forwards
  only serve legacy links and have no dev counterpart, so a new embedded entry must follow the same
  `.html#` shape rather than lean on them.
- **A specialist subagent is for multi-step reasoning over a domain**, not for hiding the number of
  CRUD tools in one. Self-contained CRUD goes flat (on the AI Hub, pinned or catalog-demoted; on Copilot and MCP, just registered — tool search exists only on the hub).

## Access and Authentication

### API facade vs shared facade
Where a domain has both (e.g. `AiSkillApiFacade` / `AiSkillFacade`), the **API facade is the HTTP surface and
owns authorization** (`checkOwnerOrAdmin(id)` on every by-id operation). The shared facade is deliberately
unguarded because runtime agent tools call it with no security context. Controllers and GraphQL mappings must
go through the API facade — wiring one to the shared facade compiles fine and silently removes the ownership
check. When adding a method, add it to BOTH interfaces, not just the shared one.

Moving authorization down into a facade also changes its HTTP status silently: a facade-thrown
`AccessDeniedException` surfaces as **500, not 403**, unless the controller carries its own
`@ExceptionHandler`. See `.agents/resource-visibility.md`.

### Development Login Credentials
- **Admin**: admin@localhost.com / admin
- **User**: user@localhost.com / user

### Default Ports
- **Server**: 9555 in the `dev` profile, 8080 in `prod` — one app, not two services
- **Client**: 5173 (Vite dev server — `vite.config.ts` sets no `server.port`, so it takes Vite's default)
- **PostgreSQL**: 5432
- **Redis**: 6379
- **Mailpit**: 1025 (`axllent/mailpit`; the compose service is `mailpit`, not mailhog)

## Common Development Workflows

### Adding a New Component
1. Create component directory in `server/libs/modules/components/`
2. Add component to `settings.gradle.kts`
3. Implement `ComponentHandler` with actions/triggers
4. Add tests and run to generate JSON definition
5. Add documentation in README.md
6. Run `./gradlew generateDocumentation`

### Database Changes
- Use Liquibase for schema migrations
- Migration files are in `server/libs/config/liquibase-config/`
- Database changes are applied automatically on startup
- After renaming migration files, delete stale copies from `build/resources/` — Liquibase sees both old and new on classpath
- Before editing an init changelog **in place**, prove the schema is unreleased:
  `git ls-tree -r --name-only <latest-tag> | grep <module>` plus `git merge-base --is-ancestor <introducing-commit> master`.
  Unreleased ⇒ edit init directly; released ⇒ add a new changeset (never rewrite what customers have run)
- In-place init edits break local dev DBs two ways (schema drift + stale md5sums);
  `scripts/dev/sync-local-schema-after-collapse.sh` patches both, idempotently
- The `liquibase` Spring profile does **not** apply migrations via `bootRun` — it exits 0 having created nothing.
  Verify changelog edits with an existing `*IntTest` instead; Testcontainers builds the schema from scratch,
  which is stronger evidence anyway
- `--spring.profiles.active=X` *replaces* the value baked into `build/resources/main/config/application.yml`
  at processResources time (`profiles.active: dev`, `contexts: mono, dev`), silently dropping the dev
  `bytechef.*` defaults. Layer it instead: `--spring.profiles.active=dev,X`

### New Spring Data JDBC Modules
- Create `@AutoConfiguration` class with `@EnableJdbcRepositories(basePackages = "...")` + `@ConditionalOnBean(AbstractJdbcConfiguration.class)`
- Register in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Add `spring-boot-autoconfigure` dependency to `build.gradle.kts`

### Agent Component Handlers
- `@AutoService(ComponentHandler.class)` — ServiceLoader discovery, no Spring DI available
- `@Component("name_v1_ComponentHandler")` — Spring discovery, supports constructor injection (used by guardrails, RAG, chat-memory, and agent utils when Spring beans are needed)

### Lazy Component Loading & Build-time Index
- `ComponentDefinitionRegistry` loads NO ServiceLoader components at Spring startup — all registry beans are `lazyInit`, and the registry constructor only captures suppliers
- server-app's `generateComponentIndex` Gradle task writes `META-INF/bytechef/component-index.json` at build time; the components-list view is served from index stubs (zero handlers loaded), single components load on demand via recorded provider class names
- **When an index is present it is authoritative**: a ServiceLoader component missing from the index is invisible (both in the list and per-name resolution). Apps that assemble their classpath differently (EE apps) must either run the generator or ship without an index — absent/corrupt index falls back transparently to full loading on first registry access
- The first deep read (`getComponentDefinitions()` consumers, first task execution) still triggers a one-time full load; stubs never reach detail/execution paths
- **A derived flag is false on a stub unless the index carries its source data.** A plain `ModifiableComponentDefinition` cannot implement a platform interface (it is an SDK class), so `clusterRoot` read false for every component in list views until the index recorded each component's `clusterElementTypes` and `ComponentIndex` started wrapping stubs in `StubClusterRootComponentDefinition`. Any new list-visible flag computed from a capability interface needs the same treatment — check the stub path, not just the loaded one

### GraphQL Development Workflow
- Add schema path to `client/codegen.ts` `schema` array
- Create operation `.graphql` files in `client/src/graphql/<domain>/`
- Run `cd client && npx graphql-codegen` to regenerate `src/shared/middleware/graphql.ts`
- Commit operations and generated file separately

### Commit Message Convention
- Client-side changes: `<ticket_number> client - <description>`
- Server-side changes: `<ticket_number> <description>`
- Example client: `2898 client - Add EnvironmentSelect dropdown to automation page headers`
- Example server: `2898 Add environment selection endpoint`

### Code Quality Workflow
- When committing, only stage files directly modified by the current task — do not include pre-existing unstaged changes that are unrelated
- **Never judge a Gradle run piped into `tail`/`grep`** — the pipeline exit code is the filter's, not Gradle's,
  and `${PIPESTATUS[0]}` is already clobbered if another command ran. Redirect to a file, check `$?` on its own
  line, then grep the file. Use `--continue` so one failing task doesn't hide the rest, and grep
  `^> Task .* FAILED` — not `error:`, which matches module paths like `:server:libs:core:error:`
- After a rebase, always run `./gradlew compileJava compileTestJava --continue` **and** `cd client && npm run check`.
  Git merges non-overlapping lines cleanly even when one side renamed a field or replaced `useState` setters with
  a reducer — a clean merge is not a correct one

Before committing code, ensure:
```bash
# Server-side
./gradlew spotlessApply  # Format code
./gradlew check          # Run all checks

# Client-side
cd client
npm run format           # Format code
npm run check            # Run lint, typecheck, and tests
```

### Generated REST sources
**REST models must not carry field-level `@JsonInclude` / `@JsonSetter(nulls = ...)`.** Both would
change the wire format for reasons unrelated to any schema change — the first drops explicit
`"field": null` from every response, the second makes an incoming explicit null leave a field's default
in place. openapi-generator 7.24.0 emitted both unconditionally with no flag to opt out (the repo
vendored a forked `pojo.mustache` for a while because of it); 7.25.0 added
`generateJsonIncludeAnnotations` and `generateJsonSetterNullsAnnotations`, and
`com.bytechef.openapi-generator-conventions` sets both to `false` on every spring `GenerateTask` in the
repo. Nothing to remember per module — registering a spring generator task is what enrols it. A spec
that genuinely wants an annotation on one property uses the `x-jackson-json-include-policy` /
`x-jackson-json-setter-nulls` vendor extension, which the generator honors regardless of the flags.

**Generated REST sources are committed, so an openapi-generator bump is a two-step change**: bump
`gradle/libs.versions.toml`, then run `./gradlew generateOpenAPI` and commit the result. Skipping the
second step leaves the tree generated by the old version with nothing failing — check what the models
claim (`grep -r "Generator version" --include=*.java server | sort -u`) rather than assuming the
catalog version is what produced them.

### Claude Code plugin
The `bytechef-dev` Claude Code plugin (`claude-code-plugin/bytechef-dev/`) tags each skill operation
with the surface that performs it (`mcp`/`cli`/`local`) using HTML-comment transport fences; the
management MCP server's `instructions` are generated from the `mcp` fences. See
`claude-code-plugin/README.md` for the fence syntax and the regeneration command
(`./gradlew :claude-code-plugin:plugin-tools:generateMcpInstructions`), and
`docs/superpowers/specs/2026-09-11-claude-plugin-transport-routing-design.md` for the full design.

### Resolving PR Review Comments
- Use `gh api graphql` with `resolveReviewThread` mutation to close threads programmatically
- Get thread IDs via: `gh api graphql -f query='{ repository(owner: "X", name: "Y") { pullRequest(number: N) { reviewThreads(first: 20) { nodes { id isResolved path } } } }'`

## Build and Deployment

## Testing Strategies

### Unit Testing
- Unit test class names must end with `Test` suffix only (NOT `IntTest`) — e.g., `KnowledgeBaseFileStorageTest`
- Drop `Impl` from test class names — test the interface contract, not the implementation detail

### Integration Testing
- All integration test classes must end with `IntTest` suffix
- Test configuration: `src/test/resources/config/application-testint.yml`

### Component Testing
- Component tests auto-generate JSON definition files in `src/test/resources/definition/`
- Delete existing `.json` files AND `build/resources/test/definition/` before running tests to regenerate them (classpath serves from build output)
- **Regenerating takes two runs, and the first one crashes.** `JsonFileAssert` writes the snapshot to
  `src/test/resources/…` but reads it back off the classpath (`build/resources/test/…`). After deleting both,
  run once — the file is written to `src` and the test then throws `NullPointerException: url` on the
  still-missing classpath copy. Run again; `processTestResources` copies it across and it passes. That NPE is
  the expected midpoint, not a bug to debug
- **`anthropic`, `gemini` and `mistral` snapshots drift on their own.** Their model options are read off SDK
  enums (`AnthropicApi.Model`, `GoogleGenAiChatModel.ChatModel`, `MistralAiApi.ChatModel`), so an SDK bump
  stales them with no repo change. Treat the three as one class — regenerating only the one that failed leaves
  the others to fail later looking unrelated. `mistral` also filters SDK-deprecated constants, so a bump can
  silently remove a model from the picker
- Test both actions and triggers
- Verify connection configurations
- Test error handling and edge cases

### Test ObjectMapper Setup
- Use `@ExtendWith(ObjectMapperSetupExtension.class)` for tests that use `JsonUtils`, `MapUtils`, or `ConvertUtils` — do NOT manually call `setObjectMapper()` in test configurations

### Task Dispatcher Definition Snapshot Tests
- `DefinitionFactoryTest` classes use `JsonFileAssert` (snapshot pattern): if the JSON file is present it's compared; if missing it's written to `src/test/resources/` and the run then fails on the missing classpath copy — see the two-run note under Component Testing
- When task dispatcher definition models change (new fields), delete snapshot JSON files from BOTH `src/test/resources/definition/` and `build/resources/test/definition/`, then rerun tests

### EE Microservice Remote Client Pattern
- EE apps (`server/ee/apps/`) use remote client stubs instead of local service implementations
- When adding new SPI interfaces to platform modules, create corresponding `@Component @ConditionalOnEEVersion` stub classes in the relevant `*-remote-client` module (e.g., `automation-configuration-remote-client`)
- Stubs throw `UnsupportedOperationException` — they satisfy Spring DI; actual work is done via REST calls
- `@ConditionalOnEEVersion` requires `bytechef.edition=ee` in the app's config
- For lightweight EE apps (e.g., `runtime-job-app`) that can't pull in full remote client modules, use `@TestConfiguration` with mock/stub beans in the integration test
- `@Bean` declarations consumed across deployments (registries, SPI wiring) must live in the `*-api` module, not `*-service` — distributed EE apps (e.g., `configuration-app`) carry `*-api` + `*-remote-client` WITHOUT `*-service`, so a bean declared there fails their context at boot (bit once with `ResourceVisibilityPolicyRegistry`)
- Adding a constructor collaborator to a scanned `@Service` impl breaks OTHER modules' hand-assembled `@SpringBootTest(classes=...)` IntTest contexts with missing-bean errors — grep for `*IntTestConfiguration`/`@TestConfiguration` classes that assemble the impl and add mock `@Bean`s there

### Component Integration Test Configuration
- Component integration tests use `@ComponentIntTest` → `ComponentTestIntConfiguration` in `platform-component-test-int-support`
- `ComponentTestIntConfiguration` only scans `com.bytechef.platform.component` — beans from other packages (e.g., `com.bytechef.file.storage`) must be manually registered
- `Base64FileStorageService.getType()` returns `"JDBC"`, so test property `bytechef.file-storage.provider=jdbc` must be set to match

## Agent skills

### Issue tracker

Issues and PRDs are tracked on GitHub at `bytechefhq/bytechef` (pinned explicitly because this clone has multiple remotes). See `.agents/issue-tracker.md`.

### Triage labels

Five canonical triage roles mapped to this repo's labels — `needs triage` and `wontfix` reuse existing labels; `needs-info` / `ready-for-agent` / `ready-for-human` are new. See `.agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` + `docs/adr/` at the repo root. See `.agents/domain.md`.
