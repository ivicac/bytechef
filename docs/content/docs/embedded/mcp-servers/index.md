---
title: MCP Servers
description: Expose integration components as MCP-compatible tool servers.
---

![MCP Servers overview](mcp-servers-overview.png)

---

> **Coming soon.** Embedded MCP Servers are on the upcoming release track and are not yet available in the latest released version of ByteChef.

## Key Features

| Feature | Description |
|---|---|
| Component filtering | Filter MCP servers by the components they expose using the left sidebar. |
| Integration filtering | Filter by the integrations associated with the server. |
| Tag filtering | Organize and filter servers by assigned tags. |
| Environment selector | Choose the environment (e.g., Development) in the header. |
| Enable/Disable toggle | Activate or deactivate an MCP server without removing it. |

### MCP Server Details

Each server in the list displays:

- **Name** -- the server name.
- **Component count** -- how many components the server exposes as MCP tools.
- **Workflow count** -- how many workflows are associated with the server.
- **Tags** -- assigned tags shown as badges.
- **Enabled/Disabled status** -- whether the server is currently active.
- **Last modified date** -- when the server was last updated.

---

## How to Use

### Creating an MCP Server

1. Click the **New MCP Server** button in the top-right corner.
2. Provide a **Name** for the server.
3. Optionally toggle **Require authentication** and **Enforce tool authorization** to control how connected agents authenticate and which tools they may call.
4. Click **Save** to create the server.

The server is created empty — you add the components and workflows it exposes from the server row afterward (see below). Assign tags inline on the server row once it exists.

<!-- TODO screenshot: New MCP Server dialog showing the Name field and the Require authentication / Enforce tool authorization toggles -->

### Adding components and workflows to a server

Open the server row's ellipsis (⋮) menu:

- **Add Component** -- opens a two-step dialog: **Select Component** (pick the third-party component) then **Select Tools from &lt;component&gt;** (choose which of its actions are exposed as tools, and configure each tool's parameters).
- **Add Workflows** -- opens the workflow dialog to expose integration workflows as tools with per-workflow tool mapping.

For each exposed component action, you choose which parameters you fix yourself and which the connected agent fills at call time, using the `fromAi(...)` expression — the same mechanism as attaching tools to an AI Agent. See [Supplying tool parameters with fromAi](/platform/ai/agent#supplying-tool-parameters-with-fromai) for the syntax.

<!-- TODO screenshot: Add Component dialog on the Select Tools step, showing the list of the component's actions with per-tool selection and the tool properties popover -->

### Managing MCP Servers

Each server row has an **Enabled** switch and an ellipsis (⋮) menu:

- **Enable/Disable** -- flip the switch on the row to control availability.
- **Edit** -- update the server name and authentication toggles.
- **Delete** -- remove the server (confirmed via an alert dialog).

Tags are edited inline on the server row.

### Filtering MCP Servers

Use the left sidebar to filter the server list:

- **Components** -- select a component name to show only servers that expose that component.
- **Integrations** -- filter by integration.
- **Tags** -- click a tag to filter by that tag.

### Environment Selection

Use the environment selector at the top of the page (or the global one in the left sidebar) to switch between environments. MCP server configurations are scoped per environment.
