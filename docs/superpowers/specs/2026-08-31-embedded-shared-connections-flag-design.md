# Embedded shared connections: an explicit flag, not a host-declared id list

**Date:** 2026-08-31
**Status:** Draft — all five open decisions were resolved in brainstorming and are recorded under
"Decisions taken"; the residual noted in decision 5 is known and accepted
**Ticket:** 1051 (continues the connected-user membership work)
**Supersedes on this branch:** `c775f493da5` "Derive embedded shared connections from
configuration-level bindings" — unreleased, `HEAD`-only, never on `master`

## Summary

A **shared connection** is a connection a tenant admin provides for every connected user in an
environment to use — the vendor's own Slack app, a house OpenAI key — as opposed to a connection an
end user creates for themselves.

Today that concept is expressed by the host, at render time, as an array of connection ids passed to
the SDK:

```tsx
<EmbeddedWorkflowBuilder sharedConnectionIds={[12, 47]} … />
```

This replaces it with a flag the admin sets on the connection itself, ticked in `ConnectionDialog` on
the `/embedded/connections` page. `EmbeddedWorkflowBuilder.sharedConnectionIds` is removed; the server
stops treating the id list as a grant.

## Motivation

`sharedConnectionIds` reaches the server by way of the browser — declared over the `EMBED_INIT`
postMessage handshake, relayed into a `connectionIds` query parameter. On `master` the facade does
this:

```java
allConnectionIds.addAll(connectedUserConnectionService.getConnectionIds(connectedUser.getId()));
allConnectionIds.addAll(connectionIds);   // ← host-declared, unioned in unconditionally
```

The list is a caller assertion the server cannot verify, and it is unioned into the entitled set
without a check. Any connected user who can reach the endpoint can therefore read any embedded
connection in the tenant by guessing its id. That is the defect this work closes.

It is also, separately, poor ergonomics: "which connections are shared?" is answerable only by
reading the host application's source, and changing the answer requires a host redeploy.

A flag on the row fixes both at once. It is verifiable — the server reads it from its own table
rather than trusting the caller — and it is legible, because the admin who sets it can see it.

### Why not the configuration-binding derivation

`c775f493da5` closed the same hole differently: it ignored the parameter and derived sharing from
`IntegrationInstanceConfigurationWorkflowConnection` rows — the connections a tenant admin bound at
the integration instance configuration level, reached through the configurations the caller's own
instances derive from.

That is a real entitlement, but it is not the concept above, and it was chosen under a constraint
that no longer applies: no schema change. It has two problems this design removes.

- **It cannot express a connection no configuration binds.** A vendor's house connection, created on
  `/embedded/connections` and offered to users building their own workflows, is bound by nothing. It
  was reachable via `sharedConnectionIds` and unreachable after the derivation landed.
- **Sharing is inferred, never stated.** No admin ticks anything; a connection becomes shared as a
  side effect of a configuration binding made for another reason, and un-sharing means finding and
  unbinding it.

The derivation is unreleased, so it is deleted rather than superseded.

## Decisions taken

Each was put to the maintainer during brainstorming; the chosen option is recorded with the reasoning
that survived, including where the recommendation was overruled.

1. **The flag replaces the configuration-binding derivation; it does not join it.** Source 3 of
   `ConnectedUserConnectionMembership` is deleted. One rule, one place to look. Considered and
   rejected: keeping both, which restores the two-definitions-of-one-word problem that class exists
   to prevent.
2. **Scope is environment-wide.** Every connected user in the environment sees every connection
   flagged shared, still filtered by component name. Tenant is already implicit in the schema.
   Accepted consequence: this drops a capability the id list had. `sharedConnectionIds` was
   *per-embed*, so a host could hand different id sets to different users; a boolean on the row
   cannot. Expressing that again would need a grant table, not a flag.
3. **The flag is toggleable on an existing connection**, not create-only. This deliberately departs
   from `visibility`, which renders as a picker only when `!connection?.id` and as a read-only badge
   on edit. Changing `visibility` re-scopes an owned resource; `shared` is a distribution switch, and
   the recovery path for "I shared the wrong connection" must not be "delete the credentials and
   start over."
4. **Only `EmbeddedWorkflowBuilder` loses the prop.** `AutomationHub` keeps
   `sharedConnectionIds`. Recommendation was to remove both — `@bytechef/embedded` is at `0.2.0`, so
   a breaking change is cheap now — and it was overruled in favour of a staged removal.
5. **The `connectionIds` request parameter is ignored.** Considered: redefining it as a narrowing
   filter over the shared subset, which would have kept the hub's prop meaningful and preserved the
   per-embed partitioning lost in decision 2. Rejected in favour of the simpler rule.

   **Residual, accepted:** combined with decision 4 this leaves `AutomationHub.sharedConnectionIds`
   accepted, relayed through the store and onto the wire, and then discarded by the server — a prop
   that does nothing. It is marked `@deprecated` so the SDK says so, and the server logs
   unrecognised ids, but a host reading only the prop's type will not learn it is inert. Removing it
   is the follow-up decision 4 defers.

## Non-goals

- **Per-embed or per-customer partitioning of shared connections** — see decision 2. A host needing
  different shared sets for different end users is not served by this design.
- **Sharing on the automation side.** `shared` is read only on the embedded path. Automation
  connections use `visibility` + `resource_grant`, which answers a different question.
- **Making a shared connection mutable by end users.** Entitlement stays separate from ownership;
  see §3.
- **Migrating existing hosts off `sharedConnectionIds`.** Under decision 1 nothing was reachable via
  the flag before, so there is nothing to backfill — but a host currently passing ids to
  `EmbeddedWorkflowBuilder` on `master` loses those connections until an admin ticks the box. That is
  a release-note item, not a migration.

## Design

### §1 Data model

`Connection` gains one field:

```java
@Column
private boolean shared;
```

`ConnectionDTO` gains `boolean shared`, carried through `toConnection()` and the builder.

New changelog `20260831000001_platform_connection_shared.xml` in
`platform-connection-service/src/main/resources/config/liquibase/changelog/platform/connection/`.
That directory is pulled in by `<includeAll>` in `config/liquibase/master.xml`, so no master edit is
needed.

```xml
<addColumn tableName="connection">
    <column name="shared" type="BOOLEAN" defaultValueBoolean="false">
        <constraints nullable="false"/>
    </column>
</addColumn>
```

Additive, `DEFAULT FALSE`, no backfill (see non-goals). Rollback drops the column.

A column that only one `PlatformType` reads is an established shape on this table: `visibility` sits
beside it and is only meaningful on the automation path
(`visibilityFeatureEnabled = isEE && currentType === PlatformType.AUTOMATION`).

**Rejected alternatives.** Adding a value to `ResourceVisibility` — ordinals are persisted, and it
welds embedded distribution onto automation RBAC. A join table — a global boolean has no attributes
to hang off a row.

### §2 The entitlement rule

`ConnectedUserConnectionMembership.getConnectionIds` keeps sources 1 and 2 and replaces source 3:

```java
Set<Long> connectionIds = getOwnedConnectionIds(connectedUserId, integrationInstances);

connectionIds.addAll(connectionService.getSharedConnectionIds(environment));

return connectionIds;
```

backed by a repository method following the `findAllByVisibilityAndTypeOrderByName` precedent already
in `ConnectionRepository`:

```java
List<Connection> findAllBySharedIsTrueAndEnvironmentAndTypeOrderByName(int environment, int type);
```

with `type` pinned to `PlatformType.EMBEDDED`.

`getOwnedConnectionIds` is untouched. That is the load-bearing part: `shared` stays outside ownership,
so `requireOwned` keeps refusing an end user who tries to delete or reauthorize a house connection,
and `resolveConnection`'s `SHAREABLE_CONNECTION_SCOPES` split keeps meaning what it means.

`getIntegrationInstanceConfigurationConnectionIds` is deleted along with the
`IntegrationInstanceConfigurationWorkflowService` collaborator, and the class Javadoc is rewritten —
it currently argues at length for the derivation being removed.

Because `ConnectedUserConnectionFacadeImpl.getConnections` (what the picker SHOWS) and
`ConnectedUserResourceMembershipResolver.resolveConnection` (what authorization GRANTS) both route
through this class, this one edit moves both. They cannot drift into the failure mode where a
connection appears in the picker and then 403s.

### §3 Enforcement

The client-side gate in §4 is UX. The boundary is one line in
`ConnectedUserConnectionFacadeImpl.createConnectedUserConnection`, which forces `shared = false` on
whatever a connected user submits, unconditionally — a connected user marking their own connection
shared would hand their credentials to every other connected user in the environment.

`deleteConnectedUserConnection` and `reauthorizeConnectedUserConnection` already gate on
`requireOwned`, which reads the owned set; since `shared` never enters that set, both already refuse
a shared connection. Unchanged.

The admin path (`embedded-configuration-rest-impl`'s `ConnectionApiController`) accepts and persists
the flag on create and update.

`getConnections` keeps its `connectionIds` parameter and keeps ignoring it (decision 5), retaining
the bounded per-connected-user WARN that reports ids the caller is not entitled to.

### §4 Client

`ConnectionDialog` gains `showSharedOption?: boolean`, opt-in, mirroring the `showOrganizationOption`
convention already documented in that file: a surface may only offer the control if its create
mutation can actually write the value.

Opt-in rather than derived, because the obvious gate does not work. `currentType === PlatformType.EMBEDDED`
is a `persist`-ed zustand store on `bytechef.mode-type` in localStorage, and the builder iframe is
same-origin with the admin app — both the `/embedded/connections` admin page and the connected-user
builder read `EMBEDDED`. Gating on it would put the checkbox in front of end users.

- `ConnectionDialogFormProps` gains `shared: boolean`.
- Rendered as a `Switch` (already imported by the file) with the label **Shared Connection** and
  helper text naming the consequence: every connected user in this environment may use it.
- Rendered on create **and** edit (decision 3), unlike the `visibility` picker beside it.
- Passed by the two `/embedded/connections` call sites in `Connections.tsx` (header and empty-state)
  and by nothing else. `HubConnectionDialog`, `AiHubConnectConnectionDialog`, the workflow-editor
  pickers and the automation connections page all leave it unset.
- `ConnectionListItem` on `/embedded/connections` shows a **Shared** badge.

The read-only connection-id field with the clipboard button
(`ConnectionDialog.tsx`, gated on `connection?.id && currentType === PlatformType.EMBEDDED`) **stays**.
Its purpose is copying an id into the host's `sharedConnectionIds`, and under decision 4 `AutomationHub`
still takes that prop.

### §5 SDK and the relay

**`EmbeddedWorkflowBuilder`** loses the prop, its `propsRef` entries, and the sentence in the
`connectionDialogAllowed` Javadoc that explains itself in terms of it.

**`AutomationHub`** keeps the prop, marked `@deprecated` with a pointer to the checkbox, and the two
Javadoc paragraphs on `tabs.connections` and `connectionDialogAllowed` that describe behaviour in
terms of it are rewritten to describe it in terms of the flag.

The relay narrows to hub-only:

| File | Change |
| --- | --- |
| `useEmbedHandshake.ts` | `EmbedInitParamsI.sharedConnectionIds` **stays** — the hub still sends it |
| `useWorkflowBuilder.ts` | drop the `useState` and both assignment sites (handshake + `HubBuilderContext` effect) |
| `WorkflowBuilder.tsx` | drop it from the destructure and from `getConnectedUserConnectionsQuery(…)` |
| `hubBuilderContext.ts`, `HubBuilderView.tsx`, `useAutomationHubStore.ts`, `automationHub.queries.ts` | unchanged |

`getConnectedUserConnectionsQuery`'s optional `connectionIds` second parameter is **removed**, not
merely left unsupplied: `WorkflowBuilder.tsx` is its only caller in the client, and the hub reaches the
same endpoint through its own `useGetComponentConnectionsQuery` in `automationHub.queries.ts`. So the
builder's relay and the hub's are already disjoint, and decision 4 costs nothing in shared code.

### §6 Docs

- `docs/content/docs/platform/embedded/get-started/quick-start/index.mdx` — remove
  `sharedConnectionIds` from the copy-pasteable `WorkflowEditor` example and its prop type.
- `docs/content/docs/platform/embedded/build/automations/automation-workflows.mdx` — the table row
  stays but is rewritten as hub-only and deprecated, pointing at the checkbox.
- New: how an admin marks a connection shared on `/embedded/connections`, and that the flag is
  environment-scoped.
- `docs/superpowers/plans/2026-08-17-embedded-automation-hub.md` is **not** edited. It is a record of
  what was built then.

### §7 Testing

Server:

- `ConnectedUserConnectionFacadeTest` and the membership tests are rewritten — both currently assert
  the derivation being deleted.
- A shared connection reaches a connected user who has **no integration instance at all** — the case
  the derivation could not express, and the reason this design exists.
- A connected user's own create cannot set `shared` (§3), asserted against the facade, not the UI.
- `requireOwned` still refuses delete and reauthorize on a shared connection.
- Environment isolation: a connection shared in DEVELOPMENT is invisible to a PRODUCTION caller.
- `connectionIds` is ignored: passing an unentitled id grants nothing.

Client:

- `ConnectionDialog.test.tsx` — the switch renders only under `showSharedOption`, on both create and
  edit, and the value reaches the mutation.
- The hub tests seeding `sharedConnectionIds` keep passing unchanged (decision 4).
- Builder tests asserting the prop's relay are removed.

## Risks

- **A host on `master` passing `sharedConnectionIds` to `EmbeddedWorkflowBuilder` loses those
  connections on upgrade** until an admin ticks the box. Deliberate — the parameter was the
  vulnerability — but it is a breaking behavioural change and needs a release note, not just a
  changelog line.
- **`AutomationHub.sharedConnectionIds` becomes inert** (decision 5's residual). The `@deprecated`
  tag is the whole mitigation.
- **`shared` is a global switch with no confirmation step.** Ticking it on the wrong connection
  exposes those credentials to every connected user in the environment until it is unticked. The
  helper text in §4 names the consequence; a confirmation dialog is a reasonable follow-up.
