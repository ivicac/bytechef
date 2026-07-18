---
title: Manage Instance
description: Learn how to manage your ByteChef instance
---

# Manage Instance

Once ByteChef is deployed, day-to-day operations are the standard ones for a stateful Spring Boot service: keep the database backed up, apply schema upgrades on new releases, watch the health endpoints, and — if you scale out — coordinate a few instance-wide settings across replicas.

## Upgrading to a new version

ByteChef ships as a container image, so upgrading means pulling a newer tag and restarting:

1. Back up the database first (see below).
2. Update the image tag to the target version (or `latest`) in your `docker-compose.yml`, Helm values, or cloud service definition.
3. Restart the instance.

On startup ByteChef runs its **Liquibase** schema migrations automatically. This behavior is controlled by `BYTECHEF_UPGRADE_ENABLED` (default `true`):

- Leave it `true` on the instance that should own the schema.
- Set it to `false` on read-only replicas, or on every replica except one, so that only a single designated instance applies schema changes during a rolling upgrade of a multi-instance deployment.

Because migrations run at startup, always take a database backup before deploying a new version.

## Backing up and restoring

All persistent state lives in PostgreSQL, so a standard PostgreSQL backup captures everything except externally-stored files (see below):

```bash
# Back up
pg_dump -Fc -h <host> -U <user> -d bytechef > bytechef-backup.dump

# Restore into an empty database
pg_restore -h <host> -U <user> -d bytechef --clean bytechef-backup.dump
```

If you configured `BYTECHEF_FILE_STORAGE_PROVIDER=FILESYSTEM` or `BYTECHEF_DATA_STORAGE_PROVIDER=FILESYSTEM`, also back up the directory in `BYTECHEF_FILE_STORAGE_FILESYSTEM_BASE_DIR` (default `${user.home}/bytechef/data/file-storage`). With the `JDBC` provider (the default for data and output storage) that data is already in the database; with the `AWS` provider it lives in your S3 bucket and is covered by your bucket's own backup policy.

Also preserve the encryption key across restores — if `BYTECHEF_ENCRYPTION_PROVIDER=FILESYSTEM`, the generated key file must be restored alongside the database, or stored connection credentials become undecryptable. Using `BYTECHEF_ENCRYPTION_PROVIDER=PROPERTY` with a key you hold avoids this coupling.

## Health checks

ByteChef exposes Spring Boot Actuator health probes, split into Kubernetes-friendly groups:

- `GET /actuator/health/liveness` — is the process alive? Use for liveness probes and load-balancer up/down checks.
- `GET /actuator/health/readiness` — is it ready to serve traffic (database reachable, migrations applied)? Use for readiness probes.
- `GET /actuator/health` — full detail, visible only to authenticated admins.

See [Observability](/self-hosting/observability) for metrics, tracing, and the complete Actuator surface.

## Administrative access to the management surface

The Actuator endpoints that expose sensitive data — `/actuator/env`, `/actuator/configprops`, and full `/actuator/health` — are gated behind a **system administrator** account, separate from application users. Set its credentials with:

- `BYTECHEF_SECURITY_SYSTEM_USERNAME` (default `system_admin`)
- `BYTECHEF_SECURITY_SYSTEM_PASSWORD`

In production, also put the whole `/actuator/**` surface behind your ingress or network policy so it is reachable only by your platform team and health probes.

## Running multiple instances

To scale horizontally, run several ByteChef instances against the same database. A few settings must be aligned so the replicas cooperate rather than conflict:

| Concern | What to configure |
|---|---|
| **Encryption key** | Use `BYTECHEF_ENCRYPTION_PROVIDER=PROPERTY` with the same `BYTECHEF_ENCRYPTION_PROPERTY_KEY` on every instance, so all replicas can decrypt the same stored credentials. |
| **Schema migrations** | Keep `BYTECHEF_UPGRADE_ENABLED=true` on one instance only during upgrades to avoid concurrent migration attempts. |
| **Message broker** | Move off the default in-memory broker to a shared one (`BYTECHEF_MESSAGE_BROKER_PROVIDER` = `REDIS`, `KAFKA`, `AMQP`, …) so tasks dispatched on one node can be executed on another. |
| **Cache** | Set `BYTECHEF_CACHE_PROVIDER=REDIS` so cache state is shared instead of per-node. |
| **Remember-me key** | Set a single fixed `BYTECHEF_SECURITY_REMEMBER_ME_KEY` across all instances so sessions remain valid regardless of which node serves a request. |

For a fully distributed (microservices) topology — separate coordinator, worker, scheduler, webhook, and gateway processes — deploy the Enterprise Edition service images described in the [Kubernetes](/self-hosting/deployment/kubernetes) guide.

## Next steps

- [Configure Instance](/self-hosting/configuration/configure-instance) — how configuration is supplied and the essential settings.
- [Environment Variables](/self-hosting/configuration/environment-variables) — the complete configuration reference.
- [Observability](/self-hosting/observability) — health checks, metrics, and tracing.
