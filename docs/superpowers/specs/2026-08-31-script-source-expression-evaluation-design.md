# Script source must not be evaluated as an expression

Date: 2026-08-31
Status: Design, approved for planning

## Summary

A JavaScript template literal in a `script` component action — `` `Hi ${name}` `` — is fed to
the workflow expression evaluator, because `SpelEvaluator` treats every string in the task map
as a SpEL template delimited by `${` and `}`. Most such scripts **fail the task outright** with
an error that never mentions the script.

The fix is to stop evaluating the script source: register the `script` parameter key with the
existing `DeferredEvaluationParameterKeys` registry, so it reaches the worker verbatim.

This is a pre-existing bug. It affects the `script` component as shipped today, independent of
the task-runner work in
[`2026-08-31-script-task-runners-design.md`](2026-08-31-script-task-runners-design.md), which
is why it is specified separately.

## The bug

`SpelEvaluator.evaluate` handles every `String` value in the task map as a template with
`ACCESSOR_PREFIX = "${"` and `ACCESSOR_SUFFIX = "}"`. Before parsing, `validateTextExpression`
applies `INVALID_ACCESSOR_PATTERN`, which requires every `${…}` occurrence to be a bare
accessor path: an identifier, optionally with `.field`, `[0]` or `['key']` steps.

The workflow execution path — `TaskWorker` and `JobExecutor` calling
`TaskExecution.evaluate(context, evaluator)` — reaches the **two-argument** `evaluate`, which is
`lenient = false`.

The result, for the `script` string property of a `script/v1` task:

| Script source contains | What happens at execution today |
|---|---|
| `` `Hi ${name}` `` | passes the accessor grammar, so `name` is resolved against the **workflow context**. If a step, input or variable is named `name`, the value is silently substituted into the user's source. If nothing resolves, `isUnresolvedReference` returns the original. |
| `` `Total: ${a + b}` `` | fails the accessor grammar; `lenient = false` means `IllegalArgumentException: Invalid expression` — **the whole task fails** |
| `` `${x ? y : z}` ``, `` `${fn()}` ``, `` `${obj.m()}` `` | same — the task fails |

So the common case is not silent mis-interpolation but an outright failure whose message names
neither the script nor the property. The silent-substitution case is rarer and worse.

The same collision applies to POSIX shell, where `${VAR}` is ordinary parameter expansion. Any
future component whose parameters carry shell or template source inherits this bug.

## Why `expressionEnabled(false)` does not fix it

`expressionEnabled` is declared in the component DSL and carried through the property domain
objects, but a search of every non-test usage finds it only in DTO `toString()` methods, the
workflow-validator model, and the client. **Nothing in the evaluation path reads it.** It is a
UI and validation hint that has no effect at execution time.

Setting `expressionEnabled(false)` on the `script` property today would therefore change
nothing about the failure. It should still be set, as the honest declaration of intent and the
signal to the editor, but it is not the mechanism.

## The mechanism already exists

`TaskExecution.evaluate` already supports holding parameters back from the evaluator:

```java
Set<String> deferredKeys = DeferredEvaluationParameterKeys.forTaskType(workflowTask.getType());
```

When the set is non-empty, those keys are removed from the parameter map before evaluation and
put back, unevaluated, afterwards. `DeferredEvaluationParameterKeys` is a static registry keyed
by task-type prefix, populated today by the conditional dispatchers so that unselected branches
are not evaluated against the wrong context:

- `condition/` → `caseTrue`, `caseFalse`
- `branch/` → `cases`, `default`
- `onError/` → `mainBranch`, `onErrorBranch`

The fix is one registration per affected component:

```java
DeferredEvaluationParameterKeys.register("script/", "script");
```

For the dispatchers, "deferred" means *evaluated later, when the branch is dispatched*. For a
script body it means *never evaluated* — the raw string travels to the worker and is handed to
the interpreter, which is precisely the desired behaviour. The mechanism is identical; only the
intent differs. The registry's class javadoc, which currently describes only the branch case,
is updated to cover both.

## Design

### 1. Register the script source as deferred

`script/` → `script`. When the `commands` component lands, `commands/` → `commands` for the
same reason (POSIX `${VAR}` expansion).

### 2. Registration is a static seed in the registry, not an eager `@Configuration`

**Amended 2026-09-01.** This section originally prescribed registering `script/` from an
eagerly-instantiated `@Configuration` in the component's module, on the same footing as
`OnErrorTaskDispatcherConfiguration`. That does not work, and it was proven during phase 2.

The reasoning about laziness is still right about the symptom. `ComponentDefinitionRegistry`
loads no components at Spring startup — every registry bean is `lazyInit`, and components load
on demand — so a static initialiser inside `ScriptComponentHandler` may not have run at the
moment a task is evaluated, and the deferral would silently not apply. That much stands.

What does not stand is the remedy. `JobExecutor` calls `TaskExecution.evaluate` for every
top-level task in the **coordinator**, and `server/ee/apps/coordinator-app/build.gradle.kts`
carries no component module at all. A registration living in a component module — eager
`@Configuration` or otherwise — therefore never runs there, and a distributed-EE deployment
keeps the bug: the coordinator fails the task with `Invalid expression` before the worker is
ever reached, while the monolith and the worker both work. That divergence is exactly what
makes the failure mode hard to see. The existing task dispatchers get away with a per-module
registration only because the coordinator depends on every dispatcher module.

The fix is instead a **static seed in `DeferredEvaluationParameterKeys` itself**, as plain
string literals:

```java
static {
    register("commands/", "commands");
    register("script/", "script");
}
```

This is correct by construction: `TaskExecution.evaluate` reads that registry, so every JVM
that evaluates a task necessarily carries and initialises the class. Literals are used because
`atlas` must not depend on a component module to name a parameter. The cost is that the registry
names two components it does not otherwise know about; the class javadoc carries the reason.

**A test must still assert that the key is registered in a context where the component itself
has not been loaded**, and must prove that rather than assume it — asserting first that
`Class.forName` on the component handler throws `ClassNotFoundException`. Otherwise the test
passes for the wrong reason and the bug returns in production only.

`DeferredEvaluationParameterKeysLoader` remains what it was: a forcing of classloading for the
dispatcher registrations, which still live in their own modules. It is not part of this path.

### 3. Set `expressionEnabled(false)` on the source properties

On the `script` property of every `script` action. This has no runtime effect today, but it is
the correct declaration, it drives the editor's affordance, and it is the natural source of
truth if the evaluator is ever taught to consult property metadata directly.

### 4. Document the data channel

The `input` object property is the designed way for workflow data to reach a script, and its
values continue to be evaluated normally. Nothing is lost; the path changes from interpolating
into source text to declaring an input. Both components' READMEs state this.

## Known gap: cluster elements

`TaskExecution.evaluate` removes **top-level** parameter keys only:

```java
Map<String, ?> originalParameters = taskMap.get(WorkflowConstants.PARAMETERS);
parametersForEvaluation.remove(deferredKey);
```

The Script cluster elements — Script Tool and the item processors — carry their source nested
inside a cluster element, not at `parameters.script`. They are therefore **not** covered by this
fix, and a template literal in a Script Tool keeps failing.

Closing that gap means either teaching the registry nested paths (a shape change: from a flat
key set to a path set, with a matcher walking the map) or deferring the whole cluster-element
sub-map. Both are larger than this change and neither is required to fix the reported problem.
This spec fixes the top-level actions, states the gap explicitly, and leaves the nested case to
a follow-up rather than pretending the coverage is complete.

## Breaking change

Anyone deliberately interpolating `${…}` into script source loses that behaviour. Given that
the accessor grammar restricts it to bare paths, and that anything more complex already fails
the task, the population relying on it is small — but it is not empty, and the change is
silent for them: the script starts receiving a literal `${name}` instead of a value.

This ships in a release note, not just a commit message, and the `script` property description
states that expressions are not evaluated in source and that `input` is the channel.

## A wart found in passing, not fixed here

`DeferredEvaluationParameterKeys.forTaskType` iterates a `ConcurrentHashMap` and returns the
first entry whose prefix matches, so overlapping prefixes resolve non-deterministically. No two
registered prefixes overlap today, and the registrations here do not introduce an overlap.
Noted so it is not rediscovered as a mystery; changing it is out of scope.

## Testing

- `SpelEvaluator` regression tests reproducing all three rows of the bug table, so the failure
  modes are pinned before the fix.
- A `TaskExecution` test asserting the `script` parameter survives evaluation verbatim while
  sibling parameters — including `input` values — are still evaluated.
- An eager-registration test in a context where the script component has **not** been loaded.
- A test asserting the cluster-element gap, so the known limitation is recorded as a passing
  assertion of current behaviour rather than an unstated absence.

## Out of scope

- Nested and cluster-element parameter paths (see Known gap)
- Making the evaluator consult `expressionEnabled` metadata directly, which would require
  plumbing component definitions into `atlas`
- The non-deterministic prefix match in `forTaskType`
- Any other change to `SpelEvaluator`. Changes there stay strictly additive — a broader
  improvement to that class is how the SpEL null-context regression happened before.
