---
title: AWS EC2
description: Learn how to deploy ByteChef on AWS EC2
---

# AWS EC2

ByteChef is distributed as a single container image, `docker.bytechef.io/bytechef/bytechef:latest`, listening on port `8080`. Running it on an EC2 instance is a matter of installing Docker, provisioning a PostgreSQL database, and starting the container with the required configuration.

## Prerequisites

- An EC2 instance (a general-purpose type such as `t3.large` or larger is a reasonable start) running a Docker-capable Linux AMI.
- A **PostgreSQL 15+** database. Use Amazon RDS for PostgreSQL for production, or run PostgreSQL in a second container for a quick evaluation.
- A security group that allows inbound traffic to port `8080` (or `443` if you front it with a load balancer / reverse proxy) and outbound access to the database.

## 1. Provision the database

Create an RDS for PostgreSQL instance (or a database named `bytechef` on an existing server), and note the JDBC URL, username, and password. Ensure the EC2 instance's security group can reach the database port.

## 2. Install Docker on the instance

```bash
sudo yum install -y docker      # Amazon Linux
sudo systemctl enable --now docker
```

## 3. Run ByteChef

Start the container, pointing it at your database and supplying the essential secrets:

```bash
sudo docker run --name bytechef -d -p 8080:8080 \
    --env BYTECHEF_DATASOURCE_URL=jdbc:postgresql://<rds-endpoint>:5432/bytechef \
    --env BYTECHEF_DATASOURCE_USERNAME=<db-user> \
    --env BYTECHEF_DATASOURCE_PASSWORD=<db-password> \
    --env BYTECHEF_ENCRYPTION_PROVIDER=property \
    --env BYTECHEF_ENCRYPTION_PROPERTY_KEY=<stable-encryption-key> \
    --env BYTECHEF_SECURITY_REMEMBER_ME_KEY=<stable-remember-me-key> \
    --env BYTECHEF_PUBLIC_URL=https://bytechef.example.com \
    docker.bytechef.io/bytechef/bytechef:latest
```

Schema migrations run automatically on first start. Set a stable `BYTECHEF_ENCRYPTION_PROPERTY_KEY` and `BYTECHEF_SECURITY_REMEMBER_ME_KEY` (see [Configure Instance](/self-hosting/configuration/configure-instance)) so credentials and sessions survive container replacement.

## 4. Access the instance

Browse to `http://<instance-public-dns>:8080/login` (or your `BYTECHEF_PUBLIC_URL` behind a load balancer) and click **Create account** to register the first user.

<!-- TODO screenshot: the ByteChef login screen reached at the EC2 instance's public URL, with the "Create account" link visible -->

## Production notes

- **TLS and reverse proxy:** terminate HTTPS at an Application Load Balancer or a reverse proxy (nginx, Caddy) and forward to port `8080`. Set `BYTECHEF_PUBLIC_URL` to the public HTTPS URL so webhook and OAuth2 redirect URLs are correct.
- **Health checks:** configure the target group / load balancer health check to `GET /actuator/health/readiness` on port `8080`.
- **Persistence:** all state is in PostgreSQL. If you use `BYTECHEF_FILE_STORAGE_PROVIDER=FILESYSTEM`, mount an EBS volume for the storage directory; otherwise the default `JDBC` storage keeps files in the database, and the `AWS` provider stores them in S3.
- **Restart policy:** add `--restart unless-stopped` to the `docker run` command, or manage the container with a systemd unit, so it comes back after a reboot.

## Next steps

- [Configure Instance](/self-hosting/configuration/configure-instance) — configuration mechanisms and essential settings.
- [Environment Variables](/self-hosting/configuration/environment-variables) — the complete configuration reference.
- [Manage Instance](/self-hosting/configuration/manage-instance) — upgrades, backups, and multi-instance setup.
