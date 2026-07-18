---
title: Azure
description: Learn how to deploy ByteChef on Microsoft Azure
---

# Azure

ByteChef runs on Azure as the container image `docker.bytechef.io/bytechef/bytechef:latest` on port `8080`. **Azure Container Apps** is the simplest managed target; the same image also runs on Azure Kubernetes Service (AKS) using the [Helm chart](/self-hosting/deployment/kubernetes) or on a plain VM as in the [AWS EC2](/self-hosting/deployment/aws-ec2) guide.

## Prerequisites

- An Azure subscription and the `az` CLI.
- An **Azure Database for PostgreSQL 15+** (Flexible Server) instance, with connectivity from the Container App and the `bytechef` database created.
- A Container Apps environment.

## 1. Provision the database

Create an Azure Database for PostgreSQL Flexible Server, add a `bytechef` database, and allow access from your Container Apps environment (VNet integration or the server's firewall rules). Note the host, username, and password.

## 2. Create the Container App

Deploy the image as a Container App exposing port `8080` externally:

```bash
az containerapp create \
  --name bytechef \
  --resource-group <resource-group> \
  --environment <container-apps-env> \
  --image docker.bytechef.io/bytechef/bytechef:latest \
  --target-port 8080 \
  --ingress external \
  --secrets db-password=<db-password> encryption-key=<stable-encryption-key> remember-me-key=<stable-remember-me-key> \
  --env-vars \
    BYTECHEF_DATASOURCE_URL="jdbc:postgresql://<pg-host>:5432/bytechef" \
    BYTECHEF_DATASOURCE_USERNAME="<db-user>" \
    BYTECHEF_DATASOURCE_PASSWORD=secretref:db-password \
    BYTECHEF_ENCRYPTION_PROVIDER=property \
    BYTECHEF_ENCRYPTION_PROPERTY_KEY=secretref:encryption-key \
    BYTECHEF_SECURITY_REMEMBER_ME_KEY=secretref:remember-me-key \
    BYTECHEF_PUBLIC_URL="https://bytechef.example.com"
```

Use **stable** values for the encryption and remember-me keys, held in Container Apps secrets, so stored credentials and sessions survive revision restarts.

## 3. Configure health probes and scaling

- Set the Container App's readiness/liveness probe to `GET /actuator/health/readiness` and `GET /actuator/health/liveness` on port `8080`.
- Set `BYTECHEF_PUBLIC_URL` to the Container App's public FQDN so webhook and OAuth2 redirect URLs resolve.
- Because schema migrations run at container startup, keep the app at a single replica during the first deploy and any upgrade. If you scale beyond one replica, apply the multi-instance settings in [Manage Instance](/self-hosting/configuration/manage-instance#running-multiple-instances) — a shared message broker and cache, and a shared encryption key.

## 4. Access the instance

Open the Container App's ingress URL and click **Create account** to register the first user.

<!-- TODO screenshot: the ByteChef first-login screen reached at the Azure Container App ingress URL, with the "Create account" link visible -->

## Storage note

Container Apps replicas have ephemeral local storage, so do not use `BYTECHEF_FILE_STORAGE_PROVIDER=FILESYSTEM`. Keep the default `JDBC` storage (files in the database), or attach durable object storage of your own.

## Next steps

- [Kubernetes](/self-hosting/deployment/kubernetes) — deploy on AKS with the Helm chart.
- [Configure Instance](/self-hosting/configuration/configure-instance) — configuration mechanisms and essential settings.
- [Environment Variables](/self-hosting/configuration/environment-variables) — the complete configuration reference.
