# Migration note: bridged workflows and the embedded resource pool

Companion to `2026-08-29-embedded-automation-resource-pools-design.md`, whose own **Migration** section
covers the schema step and says "no data step". That is true of the schema and false of the behaviour: this
note is the behaviour half.

**Where this lives, and why here.** This repository has no changelog for the application and no
per-version breaking-changes page. `docs/content/docs/platform/use-bytechef/self-hosted/management/upgrades.mdx` is a
generic how-to-upgrade guide — backups, `BYTECHEF_UPGRADE_ENABLED`, kinds of upgrade — and carries no record
of individual changes, so adding one there would be inventing a convention rather than following one. The
user-facing embedded automation-workflow pages are still `comingSoon: true`. So the note goes beside the
design it belongs to. When a release-notes convention exists, this is the text to lift into it.

## What breaks

An automation workflow dispatched under a connected user by the embedded bridge now resolves the
**EMBEDDED** resource pool.

Before, the pool followed `PlatformType` — where the workflow was *authored*. A bridged workflow is an
automation workflow, so it read the AUTOMATION pool. Now the pool follows **who the run is for**: an
automation run that belongs to a connected user reads EMBEDDED and only EMBEDDED
(`DataTableUtils.poolFor`, `KnowledgeBaseOptionsUtils.poolFor`).

So a bridged workflow step that reached an **automation** data table or knowledge base **by name** stops
finding it after the upgrade. The schema migration lands every existing row on AUTOMATION by default, which
is exactly why: the resources are all in the pool such a run no longer reads.

It **fails closed and loudly**. The step throws, naming the resource:

```
Data table 'orders' was not found in any data table pool this run may read
KnowledgeBase not found: product-docs
```

It never quietly reads a different table or knowledge base of the same name, and nothing is deleted, moved
or rewritten — the rows and documents are exactly where they were.

## Who is affected

Enterprise deployments running ByteChef **Embedded** that both:

1. dispatch **automation** workflows for connected users — that is, a project with a `connected_user_project`
   row, which is what the bridge creates; **and**
2. use the **Data Table** or **Knowledge Base** component inside one of those workflows, naming a resource
   that lives in the AUTOMATION pool.

Not affected:

- **Community deployments.** `OwnerResolver` is implemented only in Enterprise embedded, so a Community run
  never resolves an owner and keeps reading both pools.
- **The vendor's own automation runs.** A project deployment with no `connected_user_project` resolves to no
  owner, reads both pools, and is unchanged.
- **Integration workflows.** They were always embedded and already read the EMBEDDED pool.
- **Editor test runs by a workspace user.** Resolved from the security context, which is not a connected
  user, so again no owner.

## How to tell whether you are affected

Before upgrading, list the automation projects reachable through the bridge:

```sql
SELECT DISTINCT p.id, p.name
FROM connected_user_project cup
JOIN project p ON cup.project_id = p.id;
```

Then inspect those projects' workflow definitions for `dataTable/v1` or `knowledgeBase/v1` steps and note
the `table` / `knowledgeBaseId` each one names. Any of those names that exists only in the AUTOMATION pool
is a break:

```sql
SELECT name, platform_type FROM data_table     WHERE platform_type = 0;  -- 0 = AUTOMATION
SELECT name, platform_type FROM knowledge_base WHERE platform_type = 0;
```

After upgrading, the affected steps announce themselves: the execution fails with the message above, naming
the resource. There is no silent-wrong-answer case to hunt for.

## What to do about it

**The resource must exist in the EMBEDDED pool.** Two ways, and which one you want depends on whether the
data is the vendor's or the account's:

1. **Create it in the embedded pool and copy the data across.** A resource created there with **no owner**
   is *shared*: every connected user's run resolves it, which reproduces the old behaviour of one table
   serving every bridged run. A resource created **owned by an account** is that account's alone, and
   shadows a shared one of the same name for that account only — which is the feature the pool split exists
   to enable, and a drop-in override needing no workflow edit.
2. **Re-point the workflow.** Change the step to name a resource that already exists in the embedded pool,
   or stop dispatching that workflow through the bridge if it was only ever meant to be the vendor's own.

Two rules worth knowing while you do it:

- An account's own resource **wins** over a shared one of the same name, for that account.
- A run with no owner resolves **only** shared resources and never falls through to an account's.

There is deliberately no automatic data migration. Copying an automation table into the embedded pool is a
decision about who the data belongs to — one shared table, or one per account — and that is not something
an upgrade can infer.
