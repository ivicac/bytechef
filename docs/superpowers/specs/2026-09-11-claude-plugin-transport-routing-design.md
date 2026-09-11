# Claude plugin transport routing — design

Date: 2026-09-11
Status: design approved, implementation plan pending

## Problem

ByteChef exposes its operations through two surfaces that a model can drive:

- the **Management MCP server** (`{PUBLIC_URL}/api/management/{SECRET_KEY}/mcp`), whose tools
  operate a running instance, and
- the **CLI** (`cli/commands`), which calls the public REST API from a shell and also scaffolds
  code locally.

The `bytechef-dev` Claude Code plugin has to tell Claude how to perform a job — but which surface
it should name depends on where Claude is running. Claude Code has a shell and (usually) an MCP
connection. Claude Desktop and claude.ai have the MCP connection and no shell at all. A ByteChef
contributor has a checkout and Gradle but may have no running instance.

Writing the skills against one surface silently breaks the other audiences. Writing them twice
guarantees divergence — and there is already a third hand-maintained copy of the same knowledge
inside the server (`ManagementMcpServerConfiguration.INSTRUCTIONS`).

## Decisions taken as input

1. **Audiences in scope:** ByteChef contributors working in this repo; ByteChef users operating
   their own instance; plain-Claude users on claude.ai / Desktop. Headless CI is out of scope —
   the CLI serves it directly and needs no skill.
2. **The two transports stay complementary, not mirrored.** The CLI will not grow the server-side
   intelligent tools (`buildWorkflow`, `importWorkflow`, `debugWorkflowExecution`, …), whose
   runtime and streaming shape belong to MCP.
3. **The plugin is canonical.** Skill markdown lives in `claude-code-plugin/`; the claude.ai
   bundle and the server's MCP instructions are generated from it.

## Surface map

Data tables and executions are moving onto MCP, so the overlap is wider than the current tree
suggests. What remains genuinely single-surface is the load-bearing part of this design.

| Operation family | MCP | CLI |
|---|---|---|
| Projects, project workflows | yes (definition-level editing) | archive `deploy` / `pull` only |
| Component / task / dispatcher / cluster-element introspection | yes | no |
| Intelligent tools (inner agent, minutes-long) | yes, EE only | never |
| Data tables | **planned** | yes (rows, filters, batch, CSV) |
| Executions | **planned** | yes (`list`/`get` with filters) |
| Embedded per-user (integrations, instances, tools, actions, connections) | no | yes |
| Component scaffolding into a checkout (`component init`) | impossible | yes |
| Gradle build / test of a scaffolded component | impossible | n/a (shell) |

The last two rows are why "route everything through MCP" cannot be the answer, and the
intelligent-tools row is why "route everything through the CLI" cannot be either.

## Transport taxonomy

Three tags, because two would conflate two different failure modes:

| Tag | Requires | Absent when |
|---|---|---|
| `mcp` | a reachable instance + connector | the user has a checkout but no instance |
| `cli` | a shell, the `bytechef` binary, a configured profile, an instance | plain Claude |
| `local` | a shell and a checkout; **no instance** | plain Claude, and users who are not contributors |

`local` is separated from `cli` deliberately. `component init`, Gradle runs and file edits need a
working tree and nothing else; `cli` operations need credentials and a live server. A contributor
with no instance can do everything `local` and nothing `cli`, and collapsing the two would make
that state unrepresentable.

### Routing rule

A skill names the surface per operation, in this order of preference:

1. `local` when the operation writes into the checkout — there is no alternative.
2. `mcp` when both surfaces can do it. MCP is the only transport all three audiences share, and
   for workflow tools it additionally carries MCP App UI metadata
   (`WORKFLOW_EDITOR_TOOL_NAMES`), so the host renders an interactive canvas that shelling out to
   a CLI would throw away.
3. `cli` only where MCP cannot reach: embedded per-user operations, archive deploy/pull, and bulk
   data work where streaming a CSV through a tool call would be worse than a file path.

### The data-table and execution transition

Rule 2 is evaluated against what exists, not what is planned. Until the data-table and execution
MCP tools land, those operations are tagged `cli` and are therefore **absent from the claude.ai
bundle** — a plain-Claude user cannot list executions at all in the interim. When the tools land,
the fences are retagged `mcp` (keeping a `cli` fence for CSV import/export, where a file path
beats a tool call), and the operations appear in the bundle on the next generation with no
structural change. The drift check is what makes the retag safe: a tag naming a tool that has not
shipped yet fails assertion 1.

## Canonical source format

Skill files stay ordinary markdown under `claude-code-plugin/bytechef-dev/skills/`. Two additions.

### Frontmatter

```yaml
---
name: ByteChef Workflow Builder
description: ...
transports:
  required: [mcp]
  optional: [cli]
---
```

`required` drives whether the skill appears in a given generated output at all; `optional` marks
transports that enrich it. Nothing else is authored — the set of hosts a skill reaches is derived
from these two fields, never written by hand.

### Transport fences

Prose and code that belong to one surface are wrapped in an HTML comment fence, invisible in
rendered markdown and tolerated by the model:

```markdown
<!-- transport: mcp uses: createProjectWorkflow, buildWorkflow -->
Create the workflow with `createProjectWorkflow`, then call `buildWorkflow` with the returned
workflow id and a plain-language instruction.
<!-- /transport -->

<!-- transport: cli uses: automation data-table row import -->
Load the seed rows with `bytechef automation data-table row import --name orders --file ./rows.csv`.
<!-- /transport -->
```

`uses:` is **required** on `mcp` and `cli` fences and lists the exact tool names or command paths
the block depends on. It is the input to the drift check; a fence without it would be an unchecked
hole, which defeats the purpose. `local` fences may omit it.

An `edition: ee` attribute marks blocks that depend on EE-only tools. Default is `ce`.

Fences do not nest, and a fence may not span a heading. A block that needs two transports is
written as two adjacent fences, because either one may be removed independently.

**A fence body must stand alone — it may not reference another fence's tool.** The MCP instruction
fragments are assembled per registered tool: a `## tool: X` section is included only when `X` is
registered. So a sentence in the `createProjectWorkflow` section reading "the `workflowId` that
`buildWorkflow` keys on" dangles for any client whose server does not register `buildWorkflow`.
Write each fence as if it were the only one its reader will see. (Found while generating the
fragments — the first draft of the workflow-builder skill cross-referenced freely, which reads well
as a document and breaks as a set of independently-included sections.)

Authoring constraint: a fence must be removable without leaving a dangling sentence. Prose that
says "…then, using the CLI, …" across a fence boundary breaks the claude.ai output. Each fence
stands alone.

## Generated outputs

### 1. The Claude Code plugin — identity

`claude-code-plugin/bytechef-dev/` is both the source and the shipped plugin. Claude Code has all
three transports, so every fence is kept and the transport comments stay in place, harmless.
This means the plugin works straight from a checkout with no build step.

### 2. The claude.ai / Desktop skills — one per job

Generated to `build/claude-skills/bytechef/<skill-name>/`, **one standalone skill per job**,
mirroring the canonical skills one-to-one rather than merging them into a single bundle.

- `cli` and `local` fences are dropped along with their contents.
- A skill whose `transports.required` contains `cli` or `local` is dropped entirely — the
  in-repo component builder does not appear at all.

One-per-job has a consequence that shapes the generator: on claude.ai there is no plugin manifest
and no sibling skills to lend context, so each skill's `description` is the entire routing layer.
A description that reads well inside `bytechef-dev` ("Build a workflow") will not fire in a
user's general-purpose skill list. The generator therefore **rewrites descriptions for the
claude.ai output**, prefixing the product and the precondition — "ByteChef: build a workflow on a
connected ByteChef instance (requires the ByteChef Management MCP connector)". The canonical
frontmatter keeps the plugin-appropriate wording; the rewrite rule lives in the generator and is
covered by its tests.
- EE-marked blocks are **kept and labelled inline**, not filtered. The bundle is generated once
  and downloaded by users whose instances may be CE or EE; edition is unknowable at generation
  time, so the text says so ("EE only — if this tool is not listed by your connector, your
  instance is CE").

### 3. The server's MCP instructions

`ManagementMcpServerConfiguration.INSTRUCTIONS` stops being a hand-written constant. The
generator emits a resource onto the `ai-mcp-server` classpath containing the `mcp` fences'
sequencing prose, split into **named fragments keyed by tool name**.

At runtime the bean assembles the instruction string by including only fragments whose tools are
actually present in `toolCallbackProvider().getToolCallbacks()`. This is the piece that fixes a
live defect: today `.instructions(INSTRUCTIONS)` is applied unconditionally while every
intelligent tool it describes comes from `McpServerToolCallbackContributor` beans that exist only
under `server/ee/`, so a CE instance ships instructions naming tools it does not have.

A simpler fallback, if fragment assembly proves fiddly: generate two resources (CE and EE) and
select on `mcpServerToolCallbackContributors.isEmpty()`. It fixes the same bug less precisely and
regresses the moment a contributor is partially present, so fragments are preferred.

Generated resources are **committed**, following the repo's convention for generated CLI clients,
and regenerated by an explicit task rather than wired into `compileJava`.

## Drift check

The check answers one question: does every `uses:` entry name something that actually exists?

**CLI inventory** — static. Extract `@Command(name = "...")` literals from
`cli/commands/**/*Command.java`. Reliable, no build required.

**MCP inventory** — must be the *registered* set, not the annotated set. `BraveWebSearchTools`,
`FirecrawlTools` and `SampleOutputTools` carry `@Tool` and are never exposed on the management
server, so a classpath scan for `@Tool` over-approximates and would let a bad tag pass — a false
negative, the dangerous direction. The inventory therefore comes from the tool callbacks the
configuration actually builds.

**Where it lives.** The EE contributors are under `server/ee/`, which the CE `ai-mcp-server`
module cannot depend on. The check runs from `server/apps/server-app`, the one module whose test
classpath sees CE and EE together, as `PluginSkillTagDriftIntTest`.

**Where it runs.** `com.bytechef.java-common-conventions.gradle.kts` declares
`check { dependsOn(test); dependsOn(testIntegration) }`, so an `*IntTest` is covered by
`./gradlew check` as well as `./gradlew testIntegration`. It is *not* covered by `./gradlew test`,
whose `testIntegration` sibling filters on `**/*IntTest*` — so a developer running `test` alone
sees no drift. No extra CI wiring is needed beyond the existing `check`.

Assertions:

1. Every `mcp:` `uses:` entry resolves to a registered tool name.
2. Every `cli:` `uses:` entry resolves to a `@Command` path.
3. Every entry marked `edition: ee` resolves only in the EE set, and every unmarked entry
   resolves in the CE set — this catches a CE skill quietly depending on an EE tool.
4. The committed generated instructions resource matches what the generator produces from the
   current skills (staleness check).

## Repo layout and build wiring

```
claude-code-plugin/bytechef-dev/          canonical source, shipped as-is
  .claude-plugin/plugin.json
  skills/*/SKILL.md                       frontmatter + transport fences
buildSrc/src/main/kotlin/
  com.bytechef.claude-plugin-generator.gradle.kts
server/libs/ai/ai-mcp/ai-mcp-server/
  src/main/resources/mcp-instructions.fragments   generated, committed
server/apps/server-app/src/test/java/.../PluginSkillTagDriftIntTest.java
```

Tasks, registered by the convention plugin on the root project, following the
`com.bytechef.documentation-generator.gradle.kts` precedent:

- `generateClaudeSkillBundle` — writes `build/claude-skills/bytechef/`
- `generateMcpInstructions` — writes the committed fragments resource

Neither is wired into `compileJava`. Both are rerun by hand; the drift test is what makes
forgetting visible.

## Naming

ByteChef already has an **AI Skills** entity (`SkillsTools`, `ReadSkillsTools`, the `authorSkill`
intelligent tool) — an agent-facing resource stored in the platform, unrelated to Claude skills.
Documentation and code touching this design say "plugin skill" or "Claude skill", never bare
"skill", wherever both could be meant.

## Testing

- `PluginSkillTagDriftIntTest` as above (four assertions).
- A generator unit test over a fixture skill directory: fence parsing, `cli`/`local` stripping,
  whole-skill dropping on unsatisfiable `transports.required`, EE labelling in the bundle.
- A fragment-assembly unit test on the MCP server bean: given a tool-callback set missing the EE
  tools, the assembled instructions must not name them. This is the regression test for the
  defect the design fixes, so it is written to fail against today's constant first.

## Deliberately not built

- **No `operations.yaml` registry.** The tags carry the same information at the point of use and
  read as documentation rather than generated reference.
- **No skills served as MCP resources.** The server can do it (`resources` capability is already
  declared), but it would make the instance the source of truth and version the guidance with the
  deployment rather than with the repo.
- **No runtime transport auto-detection beyond what the host already exposes.** A skill names a
  tool; if the connector does not list it, the model can see that without ceremony.
- **No CI/headless skill surface.** Pipelines call the CLI directly.
- **No generation step for the Claude Code plugin itself.** Identity output keeps a checkout
  usable with zero build.

## Resolved: no raw REST fallback

`cli` fences are dropped for plain Claude, not rewritten into documented raw REST calls. The
alternative was cheap to build and would have handed claude.ai users the embedded per-user
operations, but it would have created a fourth surface — hand-written REST prose — with no
inventory to check it against, which is precisely the drift this design exists to remove.

The accepted cost, stated plainly: **embedded per-user operations (integrations, integration
instances, per-user workflows, tools, actions, connections) are reachable only from Claude Code**,
and will stay that way until those operations gain MCP tools. A plain-Claude user asking for them
gets nothing, not a degraded path. If that becomes painful, the fix is MCP tools for the embedded
surface — not REST prose in a skill.

### Embedded code integration deploy: demoted to a statement of fact

`bytechef-code-workflow` names `POST /api/embedded/internal/integrations/deploy` (skill line 157) as a
deploy target with a curl fallback. That endpoint sits behind `/api/embedded/internal/**`, which
`EmbeddedApiKeySecurityConfigurer` matches with the connected-user authenticator: it requires a
`/v<n>/{externalUserId}/` path segment and grants zero authorities regardless of what it matches. A
profile bearer token can therefore never satisfy the facade's `ROLE_ADMIN` guard through this path, so
the endpoint cannot get a CLI command as the surface stands. The precedent for fixing it exists —
`EmbeddedPlatformUserApiKeySecurityConfigurer` already carves `/automation-project-code-workflows/**`
out of that same configurer and authenticates the profile token as a real ByteChef user with real
authorities — but extending that carve-out to the integrations-deploy path is an authentication-surface
widening, and the repo owner has declined to take it.

**Decision:** that block is demoted from an operational instruction to a statement of fact — the skill
will say the operation is reachable only from the admin console and has no automatable route, and will
stop showing a curl for it. Prose that tells Claude it *cannot* perform an operation names no operation
to perform, so it carries no `uses:` entry and there is nothing for the drift check to verify. The
invariant this design states — every operation a skill instructs Claude to *perform* resolves to a
checkable inventory entry — still holds, because this is no longer one of them.

The edit to `bytechef-code-workflow/SKILL.md` itself belongs to the second plan (the one implementing
the generator, the drift check, and the claude.ai output) and is not made by the plan that resolved
this question.

## Open questions

1. Where do the generated claude.ai skills get published, and on what cadence relative to ByteChef
   releases? Does not block implementation — the generator writes to `build/` either way.
