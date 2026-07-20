---
title: Distributed (Coordinator/Worker)
description: Run ByteChef as separate coordinator, worker, and domain services instead of the monolith
---

# Distributed (Coordinator/Worker) Deployment

By default ByteChef runs as a single monolith process (`server-app`) that contains the workflow
coordinator, the task workers, all domain services, and the HTTP API. The Enterprise Edition can
instead be deployed as a set of cooperating microservices, so that task execution scales
independently from the API and coordination layers.

This page describes the services, the infrastructure they need, how they find each other, and the
order to bring them up. It assumes you build the apps from source — there is currently no published
Docker Compose file or Helm chart for the distributed set (the shipped `docker-compose.yml` and the
`kubernetes/helm/bytechef` chart deploy the monolith only), so you compose the deployment with your
own orchestration.

> **Edition note:** the distributed apps live under `server/ee/apps/` and run with
> `bytechef.edition: EE`. Distributed mode is an Enterprise Edition deployment model.

## The services

| Service | App | Role |
|---|---|---|
| Config server | `config-server-app` | Spring Cloud Config Server; serves the shared configuration for every other app. |
| API gateway | `api-gateway-app` | Edge router (Spring Cloud Gateway); routes `/api/**` and `/webhooks/**` to the backing services via service discovery. |
| Configuration | `configuration-app` | Projects, workflows, and platform configuration domain (REST + database). |
| Connection | `connection-app` | Connections domain (credentials to external services). |
| Execution | `execution-app` | Workflow-execution read APIs (jobs, task executions). |
| Coordinator | `coordinator-app` | The workflow engine's control plane: accepts jobs, runs task dispatchers (condition, loop, fork/join, each, map, parallel, subflow, approval), and dispatches task executions onto the message broker. Loads **no** components. |
| Worker | `worker-app` | Executes task executions consumed from the broker. This is the only app with the component modules (all 180+) on its classpath — scale it horizontally for throughput. |
| Scheduler | `scheduler-app` | Quartz-backed (JDBC job store) schedule and polling-trigger service. |
| Webhook | `webhook-app` | Receives inbound `/webhooks/**` requests and turns them into trigger events. |
| AI gateway | `ai-gateway-app` | Routes and meters model traffic (`/api/ai-gateway/**`). Optional. |
| AI copilot | `ai-copilot-app` | Workflow copilot assistant service. Optional; carries its own full local configuration. |
| Runtime job | `runtime-job-app` | One-shot workflow runner (an `ApplicationRunner`, not a long-lived server) with a self-contained in-memory configuration. Not part of the cluster wiring below. |

The split follows the engine architecture: the **coordinator** owns job state transitions and puts
task executions on broker queues; **workers** consume those queues, execute the component action,
and send completions back. Neither talks to the other over HTTP — all coordinator/worker traffic
goes through the message broker.

## Required infrastructure

- **PostgreSQL 15+** — one shared database. Each domain app applies its own Liquibase context
  (`configuration`, `connection`, `execution`, `scheduler`), so they can share a single database
  instance.
- **Redis** — required regardless of broker choice: it backs **service discovery** (apps register
  under their `spring.application.name`) and the cache provider, and is the default message broker.
- **Message broker** — `bytechef.message-broker.provider` set to one of `redis` (default in the EE
  config), `amqp` (RabbitMQ), `kafka`, or `jms`/`aws`. The `memory` provider is in-process only and
  **cannot** be used in distributed mode. All broker starters are on the worker/coordinator
  classpath, so the provider is switched purely by configuration.

For local experiments, `server/docker-compose.dev.infra.yml` starts PostgreSQL, Redis, and
RabbitMQ.

## Configuration model

Each app's own `application.yml` is nearly empty — it sets `spring.application.name` and imports
everything else from the config server:

```yaml
spring:
  application:
    name: coordinator-app
  config:
    import: optional:configserver:http://localhost:6111
  cloud:
    config:
      username: configserver
      password: ${BYTECHEF_CONFIG_SERVER_PASSWORD:dev-config-server-secret}
```

The real, shared configuration lives in `config-server-app`'s classpath at
`src/main/resources/config/apps/` — a shared `application.yml` (+ `-dev`/`-prod` profiles) plus
per-app override files such as `configuration-app.yml` or `worker-app.yml`. Although the import is
marked `optional:`, the config server is **effectively required**: the datasource coordinates,
broker provider, edition flag, and internal service token are only served from there. An app booted
without it comes up mis-configured, not degraded.

Key properties served to all apps:

| Property | Value / purpose |
|---|---|
| `bytechef.edition` | `EE` |
| `bytechef.message-broker.provider` | `redis` (or `amqp`, `kafka`, `jms`, `aws`) |
| `bytechef.cache.provider` | `redis` |
| `bytechef.discovery-service.provider` | `redis` — the only supported discovery backend |
| `bytechef.data-storage.provider` / `bytechef.workflow.output-storage.provider` | `jdbc` |
| `bytechef.scheduler.provider` | `quartz` |
| `bytechef.internal.service-token` | `${BYTECHEF_INTERNAL_SERVICE_TOKEN}` — shared secret for internal `/remote` calls. **Fail-closed**: internal calls are rejected when unset. |
| `bytechef.worker.task.subscriptions.*` | per-queue worker consumer counts (see below) |

Secrets you must set in every app's environment:

- `BYTECHEF_CONFIG_SERVER_PASSWORD` (and the matching username) — basic auth to the config server.
- `BYTECHEF_INTERNAL_SERVICE_TOKEN` — the shared internal service token.
- The usual monolith secrets served through the config files: datasource credentials, encryption
  key, etc.

## Service discovery and internal calls

Apps register themselves in **Redis** on startup (instance id
`${spring.application.name}:${random.value}`) and resolve each other with Spring Cloud
LoadBalancer on top of the Redis discovery client. There are no static service URLs and no Eureka.

Cross-service calls use the *remote client* pattern: each domain module has a `*-remote-client`
(REST stubs used by consumers) and a `*-remote-rest` (the `/remote/**` controllers on the owning
app). The client sets the logical service name as the hostname — for example the coordinator
fetches workflow definitions from `http://configuration-app/remote/workflow-service/...` — and the
load balancer resolves it against the Redis registry. Every internal request carries `X-Tenant-Id`
and the internal service token; the receiving app validates both.

## API gateway routing

`api-gateway-app` uses Spring Cloud Gateway (WebMVC flavor) with discovery-based `lb://` URIs:

- `/api/automation/**`, `/api/embedded/**`, `/api/platform/**` → `configuration-app` (with
  `connection-app` and `execution-app` matching their own overlapping predicates — route order
  matters)
- `/api/ai-gateway/**` → `ai-gateway-app`
- `/webhooks/**` → `webhook-app`

## Worker queues and scaling

Workers subscribe to broker queues with per-queue concurrency configured under
`bytechef.worker.task.subscriptions`:

```yaml
bytechef:
  worker:
    task:
      subscriptions:
        default: 10        # concurrent consumers on the default task queue
```

A workflow task can target a dedicated queue via its `node` property; the queue is created on
worker bootstrap and only workers subscribing to it will execute those tasks. This lets you run
specialized worker pools (for example, a pool with more memory for file-heavy components) by
deploying `worker-app` instances with different subscription maps.

Coordinator and worker roles are controlled by `bytechef.coordinator.enabled` and
`bytechef.worker.enabled` (both default `true`); in practice the roles are determined by which app
you deploy, since only `worker-app` has the component modules and only `coordinator-app` has the
dispatcher stack.

## Bring-up order

1. Infrastructure: PostgreSQL, Redis, and the message broker (if not Redis).
2. `config-server-app` — everything else reads its configuration from it (dev port `6111`).
3. Domain services: `configuration-app`, `connection-app`, `execution-app`, `scheduler-app`
   (each runs its Liquibase context against the shared database).
4. `coordinator-app` and one or more `worker-app` instances.
5. `webhook-app`, and optionally `ai-gateway-app` / `ai-copilot-app`.
6. `api-gateway-app` last, once its route targets are registered in discovery.

## Current limitations

- **No shipped orchestration**: no Docker Compose file or Helm chart exists for the distributed
  set — the shipped compose files and the `bytechef` Helm chart are monolith-only.
- **Notifications and alert rules are monolith-only for now**: `coordinator-app` reaches
  notification services through remote client stubs that are not yet implemented
  (`platform-notification-remote-client` throws `UnsupportedOperationException`), so job-status
  notifications and workflow alert rules currently require the monolith deployment.
- **Config server reachability**: apps reach the config server via a static URL
  (`http://localhost:6111` in dev) rather than discovery; point
  `spring.config.import` at your config server's address in each app's environment.
