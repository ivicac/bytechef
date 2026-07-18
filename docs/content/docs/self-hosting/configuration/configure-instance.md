---
title: Configure Instance
description: Learn how to configure your ByteChef instance
---

# Configure Instance

ByteChef is configured entirely through **externalized configuration** — there is no settings screen inside the application for instance-wide options. Everything from the database connection to the AI provider keys is supplied before the process starts, so the same container image behaves differently per environment based on the values you pass in.

## How configuration is supplied

ByteChef is a Spring Boot application, so it reads configuration from the standard Spring sources, in order of increasing precedence:

1. The bundled defaults (documented on the [Environment Variables](/self-hosting/configuration/environment-variables) page).
2. An external `application.yml` / `application.properties` placed next to the process or pointed at with `--spring.config.additional-location`.
3. **Environment variables** — the recommended mechanism for containers, and the form every example in this documentation uses.

Environment variables and YAML properties are interchangeable through Spring's **relaxed binding**: the property `bytechef.datasource.url` is the same setting as the environment variable `BYTECHEF_DATASOURCE_URL`. Uppercase the name, and replace dots and dashes with underscores. Indexed list entries use a trailing number, e.g. `bytechef.feature-flags[0]` becomes `BYTECHEF_FEATURE_FLAGS_0`.

```yaml
# application.yml form
bytechef:
  datasource:
    url: jdbc:postgresql://postgres:5432/bytechef
    username: postgres
    password: postgres
```

```bash
# Environment-variable form (identical effect)
BYTECHEF_DATASOURCE_URL=jdbc:postgresql://postgres:5432/bytechef
BYTECHEF_DATASOURCE_USERNAME=postgres
BYTECHEF_DATASOURCE_PASSWORD=postgres
```

See the [Environment Variables](/self-hosting/configuration/environment-variables) reference for the complete, categorized list of every setting and its default.

## Minimum configuration for a production instance

Beyond the defaults, a single-node deployment needs only a handful of settings:

| Setting | Why it matters |
|---|---|
| `BYTECHEF_DATASOURCE_URL`, `BYTECHEF_DATASOURCE_USERNAME`, `BYTECHEF_DATASOURCE_PASSWORD` | Point ByteChef at your PostgreSQL 15+ database. Schema migrations run automatically on startup. |
| `BYTECHEF_SECURITY_REMEMBER_ME_KEY` | A fixed secret used to sign "remember me" tokens. Set it to a stable random value so existing sessions survive restarts. |
| `BYTECHEF_PUBLIC_URL` | The externally reachable base URL of the instance (default `http://127.0.0.1:8080`). It is the base for webhook URLs, the OAuth2 redirect URI, and links in outgoing mail. |
| `BYTECHEF_ENCRYPTION_PROVIDER` / `BYTECHEF_ENCRYPTION_PROPERTY_KEY` | Controls how stored credentials are encrypted at rest — see below. |

## Encryption of stored credentials

Connection credentials and other secrets are encrypted at rest. The provider is chosen with `BYTECHEF_ENCRYPTION_PROVIDER`:

- **`FILESYSTEM`** (default) — ByteChef generates an encryption key on first start and writes it to the local filesystem. This is fine for a single, persistent node, but it is **not** suitable for containers with ephemeral disks or for multi-instance deployments, because each replica would generate its own key and could not decrypt data written by the others.
- **`PROPERTY`** — you supply the key yourself via `BYTECHEF_ENCRYPTION_PROPERTY_KEY`. Use this for Kubernetes and any multi-replica setup so every instance shares one stable key. Store the key in a secret, never in source control.

## Edition, tenancy, and sign-up

A few instance-wide switches shape the whole deployment:

| Setting | Effect | Default |
|---|---|---|
| `BYTECHEF_EDITION` | Selects Community (`CE`) or Enterprise (`EE`) behavior. EE-only features (SSO, connection visibility scopes, microservices) require `EE`. | `EE` |
| `BYTECHEF_TENANT_MODE` | `SINGLE` for one tenant per instance, `MULTI` for multi-tenant. | `SINGLE` |
| `BYTECHEF_ENVIRONMENT` | Optionally pin the instance to a single environment (`DEVELOPMENT`, `STAGING`, `PRODUCTION`). | - |
| `BYTECHEF_SIGNUP_ENABLED` | Whether visitors can self-register from the login screen. | `true` |
| `BYTECHEF_SIGNUP_ACTIVATION_REQUIRED` | Whether new accounts must confirm via email before logging in (requires mail to be configured). | `false` |

## Verifying the resolved configuration

Once the instance is running, the effective, merged configuration is exposed through the Actuator `env` endpoint at `/actuator/env`. That endpoint is protected — it is reachable only with the system administrator credentials set via `BYTECHEF_SECURITY_SYSTEM_USERNAME` / `BYTECHEF_SECURITY_SYSTEM_PASSWORD`. See [Observability](/self-hosting/observability) for the full Actuator surface.

## Next steps

- [Environment Variables](/self-hosting/configuration/environment-variables) — the complete configuration reference.
- [Manage Instance](/self-hosting/configuration/manage-instance) — upgrades, backups, and running multiple instances.
- [Observability](/self-hosting/observability) — health checks, metrics, and tracing.
