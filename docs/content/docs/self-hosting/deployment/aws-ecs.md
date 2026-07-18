---
title: AWS ECS
description: Learn how to deploy ByteChef on AWS ECS
---

# AWS ECS

Amazon ECS runs the ByteChef container image, `docker.bytechef.io/bytechef/bytechef:latest`, as a managed service — a good fit when you want AWS to handle scheduling, health, and rolling deployments rather than managing an EC2 host yourself. This guide targets **Fargate** (serverless), but the same task definition works on EC2-backed ECS.

## Prerequisites

- An ECS cluster (Fargate requires no EC2 hosts).
- An **Amazon RDS for PostgreSQL 15+** database reachable from the cluster's subnets/security groups.
- An Application Load Balancer (recommended) to terminate TLS and health-check the service.
- AWS Secrets Manager (or SSM Parameter Store) for the sensitive values.

## 1. Store secrets

Put the sensitive configuration in Secrets Manager so it is injected into the container rather than baked into the task definition: the database password, `BYTECHEF_ENCRYPTION_PROPERTY_KEY`, and `BYTECHEF_SECURITY_REMEMBER_ME_KEY`. Use **stable** values for the encryption and remember-me keys so credentials and sessions survive task replacement.

## 2. Create the task definition

Define a single container that runs the image and exposes port `8080`:

```json
{
  "family": "bytechef",
  "requiresCompatibilities": ["FARGATE"],
  "networkMode": "awsvpc",
  "cpu": "1024",
  "memory": "2048",
  "containerDefinitions": [
    {
      "name": "bytechef",
      "image": "docker.bytechef.io/bytechef/bytechef:latest",
      "portMappings": [{ "containerPort": 8080, "protocol": "tcp" }],
      "environment": [
        { "name": "BYTECHEF_DATASOURCE_URL", "value": "jdbc:postgresql://<rds-endpoint>:5432/bytechef" },
        { "name": "BYTECHEF_DATASOURCE_USERNAME", "value": "bytechef" },
        { "name": "BYTECHEF_ENCRYPTION_PROVIDER", "value": "property" },
        { "name": "BYTECHEF_PUBLIC_URL", "value": "https://bytechef.example.com" }
      ],
      "secrets": [
        { "name": "BYTECHEF_DATASOURCE_PASSWORD", "valueFrom": "<secret-arn>:password::" },
        { "name": "BYTECHEF_ENCRYPTION_PROPERTY_KEY", "valueFrom": "<secret-arn>:encryptionKey::" },
        { "name": "BYTECHEF_SECURITY_REMEMBER_ME_KEY", "valueFrom": "<secret-arn>:rememberMeKey::" }
      ]
    }
  ]
}
```

Adjust CPU/memory to your workload — 1 vCPU / 2 GB is a reasonable starting point.

## 3. Create the service behind a load balancer

Create an ECS service from the task definition and attach it to an ALB target group:

- **Target port:** `8080`.
- **Health check path:** `/actuator/health/readiness`.
- Set `BYTECHEF_PUBLIC_URL` to the ALB's public HTTPS URL so webhook and OAuth2 redirect URLs resolve correctly.

Because schema migrations run at container startup, keep the service at a single task during the first deploy and any upgrade, then scale out once the schema is current. When running multiple tasks, review the multi-instance settings in [Manage Instance](/self-hosting/configuration/manage-instance#running-multiple-instances) — a shared message broker and cache, and a shared encryption key.

## 4. Access the instance

Browse to the ALB URL and click **Create account** to register the first user.

<!-- TODO screenshot: the ECS service showing a healthy running task, or the first-login screen reached through the ALB URL -->

## Storage note

Fargate tasks have ephemeral local storage, so do **not** use `BYTECHEF_FILE_STORAGE_PROVIDER=FILESYSTEM`. Keep the default `JDBC` storage (files in the database) or set `BYTECHEF_FILE_STORAGE_PROVIDER=AWS` with `BYTECHEF_FILE_STORAGE_AWS_BUCKET` to store files in S3.

## Next steps

- [Configure Instance](/self-hosting/configuration/configure-instance) — configuration mechanisms and essential settings.
- [Environment Variables](/self-hosting/configuration/environment-variables) — the complete configuration reference.
- [Manage Instance](/self-hosting/configuration/manage-instance) — upgrades, backups, and multi-instance setup.
