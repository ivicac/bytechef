# ByteChef CLI

A command-line interface for ByteChef. It scaffolds custom components locally and calls the
ByteChef **public REST API** for automation resources.

## Configure a profile

```bash
bytechef configure --host https://app.bytechef.io --token <public-api-token> \
  --environment PRODUCTION --workspace-id 1
```

Credentials are stored in `~/.bytechef/config` (INI format, file mode `600`), one section per named
profile. Select a profile with `--profile <name>` (defaults to `default`).

Every value can be overridden per command, in this precedence:

1. per-command flag (`--host`, `--token`, `--environment`, `--workspace-id`)
2. environment variable (`BYTECHEF_HOST`, `BYTECHEF_TOKEN`, `BYTECHEF_ENVIRONMENT`,
   `BYTECHEF_WORKSPACE_ID`)
3. the selected profile in `~/.bytechef/config`

## Automation commands

```bash
# List workflow executions (uses the profile's workspace-id unless overridden)
bytechef automation execution list --status COMPLETED --output table

# Fetch a single execution with full inputs/outputs/task detail
bytechef automation execution get --id 42

# Deploy a code-based project archive
bytechef automation project deploy --workspace-id 1 --project-file ./project.zip

# Pull a project from its git repository
bytechef automation project pull --id 5
```

Output is JSON by default; add `--output table` on `execution list` for a compact summary.

Requests are sent to `<host>/api/automation/v1` with `Authorization: Bearer <token>` and
`X-Environment: <environment>` headers.

## Component scaffolding

```bash
bytechef component init --name my-component --open-api-path ./openapi.yaml --output-path .
```

## Exit codes

| Code | Meaning |
|------|---------|
| 0 | success |
| 1 | generic error (bad request, missing file, unexpected failure) |
| 2 | authentication failure (HTTP 401/403) |
| 3 | not found (HTTP 404) |
| 4 | missing or invalid configuration |
