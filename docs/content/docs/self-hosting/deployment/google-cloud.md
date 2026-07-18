---
title: Google Cloud
description: Learn how to deploy ByteChef on Google Cloud Platform
---

# Google Cloud

ByteChef runs on Google Cloud as the container image `docker.bytechef.io/bytechef/bytechef:latest` on port `8080`. **Cloud Run** is the simplest managed target; the same image also runs on Google Kubernetes Engine (GKE) using the [Helm chart](/self-hosting/deployment/kubernetes) or on a Compute Engine VM as in the [AWS EC2](/self-hosting/deployment/aws-ec2) guide. Pair it with **Cloud SQL for PostgreSQL**.

## Prerequisites

- A Google Cloud project and the `gcloud` CLI.
- A **Cloud SQL for PostgreSQL 15+** instance with a `bytechef` database. Connect to it from Cloud Run via the [Cloud SQL connector / Auth Proxy](https://cloud.google.com/sql/docs/postgres/connect-run).
- Secret Manager for the sensitive values.

## 1. Provision the database

Create a Cloud SQL for PostgreSQL instance and a `bytechef` database. Note the connection name, user, and password, and enable the Cloud SQL connection on the Cloud Run service so the database is reachable.

## 2. Deploy to Cloud Run

```bash
gcloud run deploy bytechef \
  --image=docker.bytechef.io/bytechef/bytechef:latest \
  --port=8080 \
  --allow-unauthenticated \
  --add-cloudsql-instances=<connection-name> \
  --set-env-vars=BYTECHEF_DATASOURCE_URL="jdbc:postgresql:///bytechef?cloudSqlInstance=<connection-name>&socketFactory=com.google.cloud.sql.postgres.SocketFactory",BYTECHEF_DATASOURCE_USERNAME="<db-user>",BYTECHEF_ENCRYPTION_PROVIDER=property,BYTECHEF_PUBLIC_URL="https://bytechef.example.com" \
  --set-secrets=BYTECHEF_DATASOURCE_PASSWORD=db-password:latest,BYTECHEF_ENCRYPTION_PROPERTY_KEY=encryption-key:latest,BYTECHEF_SECURITY_REMEMBER_ME_KEY=remember-me-key:latest
```

Use **stable** values for the encryption and remember-me keys in Secret Manager so stored credentials and sessions survive revision restarts.

## 3. Tune the service

- **Health checks:** point the Cloud Run startup / liveness probe at `GET /actuator/health/readiness` and `/actuator/health/liveness` on port `8080`.
- **Public URL:** set `BYTECHEF_PUBLIC_URL` to the Cloud Run service URL (or your mapped custom domain) so webhook and OAuth2 redirect URLs resolve.
- **Startup time:** ByteChef runs schema migrations on first start; give the startup probe a generous timeout so the first revision becomes ready.
- **Concurrency and instances:** because migrations run at startup, deploy a single revision for the first release and any upgrade. If you allow more than one instance, apply the multi-instance settings in [Manage Instance](/self-hosting/configuration/manage-instance#running-multiple-instances) — a shared message broker and cache, and a shared encryption key.

## 4. Access the instance

Open the Cloud Run service URL and click **Create account** to register the first user.

<!-- TODO screenshot: the ByteChef first-login screen reached at the Cloud Run service URL, with the "Create account" link visible -->

## Storage note

Cloud Run instances have ephemeral local storage, so do not use `BYTECHEF_FILE_STORAGE_PROVIDER=FILESYSTEM`. Keep the default `JDBC` storage (files in the database), or store files in Google Cloud Storage.

## Next steps

- [Kubernetes](/self-hosting/deployment/kubernetes) — deploy on GKE with the Helm chart.
- [Configure Instance](/self-hosting/configuration/configure-instance) — configuration mechanisms and essential settings.
- [Environment Variables](/self-hosting/configuration/environment-variables) — the complete configuration reference.
