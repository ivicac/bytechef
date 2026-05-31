# Notification WEBHOOK Channel — Phase 1 Implementation Plan (EE)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the WEBHOOK notification channel actually deliver — as an **Enterprise (EE) feature**. Implement webhook delivery (sender, payload handler, async+retry+timeout, optional HMAC signing, SSRF guard, encrypted secret) in a new EE module gated by `@ConditionalOnEEVersion`, expose it in the client only behind `<EEVersion>`, and keep CE safe when the sender bean is absent.

**Architecture:** The CE `platform-notification` module keeps the `WebhookNotificationHandler` interface, the `WEBHOOK` enum value, and the enriched `NotificationHandlerContext` (all inert without a sender). A new EE module — `server/ee/libs/platform/platform-notification/platform-notification-webhook` (package `com.bytechef.ee.platform.notification.webhook`) — holds the sender, signing/validation/envelope utilities, and the concrete `JobStatusWebhookNotificationHandler`, with `@ConditionalOnEEVersion` on the bean classes so they register only when `bytechef.edition=ee`. CE notification listeners skip when no sender is registered for a notification's type.

**Tech Stack:** Java 25, Spring Boot 4, Java `HttpClient`, `org.springframework.core.retry.RetryTemplate` + `ExponentialBackOff`, `com.bytechef.encryption.Encryption`, `JsonUtils`, `@ConditionalOnEEVersion` (= `@ConditionalOnProperty(bytechef.edition=ee)`), JUnit 5 + `com.sun.net.httpserver.HttpServer` + Spring `ApplicationContextRunner`, React 19 + `<EEVersion>` + Zod (client).

**Spec:** `docs/superpowers/specs/2026-05-31-notification-webhook-channel-design.md` (Phase 1: §5.1–§5.6, §5.9 EE placement). Phase 2 (task events) is a separate plan.

**Decisions baked in:** D1 = **WEBHOOK is EE-only** (sender/handlers in EE module, `@ConditionalOnEEVersion`, client `<EEVersion>` gate). D3 = signing secret encrypted in `settings`. Delivery-status persistence (§5.4) is the final, optional task.

**EE file conventions (apply to every file under `server/ee/`):** use the ByteChef Enterprise license header (shown in the code blocks below) — NOT the Apache header — and add a `@version ee` Javadoc tag to each class.

---

## File Structure

**`platform-notification-api`** (CE — interfaces/domain):
- Modify `handler/WebhookNotificationHandler.java` — add `getPayload(ctx)`.
- Modify `handler/NotificationHandlerContext.java` — add status/timestamp/taskExecutionId/taskName/error.

**`platform-notification-webhook`** (NEW EE module, `com.bytechef.ee.platform.notification.webhook`):
- Create `build.gradle.kts`; register in `settings.gradle.kts`.
- Create `WebhookSignatures.java`, `WebhookUrlValidator.java`, `WebhookEnvelope.java`,
  `WebhookNotificationProperties.java`, `WebhookNotificationSender.java`,
  `WebhookNotificationConfiguration.java`, `JobStatusWebhookNotificationHandler.java`.

**`platform-coordinator`** (CE):
- Modify `event/listener/NotificationJobStatusApplicationEventListener.java` — populate status/timestamp
  AND skip when the resolved sender is null.

**`platform-notification-service`** (CE):
- Modify `facade/NotificationFacadeImpl.java` — encrypt secret on create/update.

**Client:**
- Modify `components/NotificationDialog.tsx` — gate WEBHOOK behind `<EEVersion>`, add Secret field.
- Modify `hooks/useNotifications.tsx` — drop `ff_1132`, extend schema.

---

## Task 1: Extend `WebhookNotificationHandler` with a payload method (CE)

**Files:**
- Modify: `server/libs/platform/platform-notification/platform-notification-api/src/main/java/com/bytechef/platform/notification/handler/WebhookNotificationHandler.java`

- [ ] **Step 1: Replace the empty marker interface with a payload contract**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.platform.notification.handler;

import java.util.Map;

/**
 * @author Matija Petanjek
 */
public interface WebhookNotificationHandler extends NotificationHandler {

    Map<String, Object> getPayload(NotificationHandlerContext notificationHandlerContext);
}
```

- [ ] **Step 2: Compile the api module**

Run: `./gradlew :server:libs:platform:platform-notification:platform-notification-api:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add server/libs/platform/platform-notification/platform-notification-api/src/main/java/com/bytechef/platform/notification/handler/WebhookNotificationHandler.java
git commit -m "732 Add getPayload to WebhookNotificationHandler"
```

---

## Task 2: Enrich `NotificationHandlerContext` (CE)

**Files:**
- Modify: `server/libs/platform/platform-notification/platform-notification-api/src/main/java/com/bytechef/platform/notification/handler/NotificationHandlerContext.java`
- Test: `server/libs/platform/platform-notification/platform-notification-api/src/test/java/com/bytechef/platform/notification/handler/NotificationHandlerContextTest.java`

- [ ] **Step 1: Write the failing test**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.platform.notification.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.notification.domain.NotificationEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationHandlerContextTest {

    @Test
    void testBuilderPopulatesAllFields() {
        Instant timestamp = Instant.parse("2026-05-31T12:00:00Z");

        NotificationHandlerContext context = new NotificationHandlerContext.Builder()
            .eventType(NotificationEvent.Type.JOB_FAILED)
            .jobId(123L)
            .jobName("Sync customers")
            .status("FAILED")
            .timestamp(timestamp)
            .taskExecutionId(456L)
            .taskName("httpRequest")
            .error("boom")
            .build();

        assertThat(context.getEventType()).isEqualTo(NotificationEvent.Type.JOB_FAILED);
        assertThat(context.getJobId()).isEqualTo(123L);
        assertThat(context.getJobName()).isEqualTo("Sync customers");
        assertThat(context.getStatus()).isEqualTo("FAILED");
        assertThat(context.getTimestamp()).isEqualTo(timestamp);
        assertThat(context.getTaskExecutionId()).isEqualTo(456L);
        assertThat(context.getTaskName()).isEqualTo("httpRequest");
        assertThat(context.getError()).isEqualTo("boom");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:libs:platform:platform-notification:platform-notification-api:test --tests "com.bytechef.platform.notification.handler.NotificationHandlerContextTest"`
Expected: FAIL — compile error, new builder methods/getters absent.

- [ ] **Step 3: Replace the class body (keep license header)**

```java
package com.bytechef.platform.notification.handler;

import com.bytechef.platform.notification.domain.NotificationEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * @author Matija Petanjek
 */
public class NotificationHandlerContext {

    public static class Builder {

        private NotificationEvent.Type eventType;
        private Long jobId;
        private String jobName;
        private String status;
        private Instant timestamp;
        private Long taskExecutionId;
        private String taskName;
        private String error;

        public NotificationHandlerContext build() {
            NotificationHandlerContext notificationHandlerContext = new NotificationHandlerContext();

            notificationHandlerContext.eventType = eventType;
            notificationHandlerContext.jobId = jobId;
            notificationHandlerContext.jobName = jobName;
            notificationHandlerContext.status = status;
            notificationHandlerContext.timestamp = timestamp;
            notificationHandlerContext.taskExecutionId = taskExecutionId;
            notificationHandlerContext.taskName = taskName;
            notificationHandlerContext.error = error;

            return notificationHandlerContext;
        }

        public Builder eventType(NotificationEvent.Type eventType) {
            this.eventType = eventType;

            return this;
        }

        public Builder jobId(Long jobId) {
            this.jobId = jobId;

            return this;
        }

        public Builder jobName(String jobName) {
            this.jobName = jobName;

            return this;
        }

        public Builder status(String status) {
            this.status = status;

            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;

            return this;
        }

        public Builder taskExecutionId(Long taskExecutionId) {
            this.taskExecutionId = taskExecutionId;

            return this;
        }

        public Builder taskName(String taskName) {
            this.taskName = taskName;

            return this;
        }

        public Builder error(String error) {
            this.error = error;

            return this;
        }
    }

    private NotificationEvent.Type eventType;
    private Long jobId;
    private String jobName;
    private @Nullable String status;
    private @Nullable Instant timestamp;
    private @Nullable Long taskExecutionId;
    private @Nullable String taskName;
    private @Nullable String error;

    private NotificationHandlerContext() {
    }

    public NotificationEvent.Type getEventType() {
        return eventType;
    }

    public Long getJobId() {
        return jobId;
    }

    public String getJobName() {
        return jobName;
    }

    public @Nullable String getStatus() {
        return status;
    }

    public @Nullable Instant getTimestamp() {
        return timestamp;
    }

    public @Nullable Long getTaskExecutionId() {
        return taskExecutionId;
    }

    public @Nullable String getTaskName() {
        return taskName;
    }

    public @Nullable String getError() {
        return error;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :server:libs:platform:platform-notification:platform-notification-api:test --tests "com.bytechef.platform.notification.handler.NotificationHandlerContextTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/libs/platform/platform-notification/platform-notification-api/src/main/java/com/bytechef/platform/notification/handler/NotificationHandlerContext.java server/libs/platform/platform-notification/platform-notification-api/src/test/java/com/bytechef/platform/notification/handler/NotificationHandlerContextTest.java
git commit -m "732 Enrich NotificationHandlerContext with status, timestamp, task fields"
```

---

## Task 3: Create the EE webhook module

**Files:**
- Create: `server/ee/libs/platform/platform-notification/platform-notification-webhook/build.gradle.kts`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Create the module build file**

```kotlin
dependencies {
    implementation("org.springframework:spring-context")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation(project(":server:libs:core:commons:commons-util"))
    implementation(project(":server:libs:core:encryption:encryption-api"))
    implementation(project(":server:libs:platform:platform-api"))
    implementation(project(":server:libs:platform:platform-notification:platform-notification-api"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":server:libs:core:encryption:encryption-impl"))
    testImplementation(project(":server:libs:test:test-support"))
}
```

> `NotificationService` lives in `platform-notification-api`, so the api dependency is sufficient (no
> dependency on `platform-notification-service`). JUnit/Mockito/AssertJ come from the shared test
> convention plugin.

- [ ] **Step 2: Register the module in `settings.gradle.kts`**

Add this line immediately after the existing
`include("server:ee:libs:platform:platform-notification:platform-notification-remote-client")` line:

```kotlin
include("server:ee:libs:platform:platform-notification:platform-notification-webhook")
```

- [ ] **Step 3: Verify Gradle recognizes the module**

Run: `./gradlew :server:ee:libs:platform:platform-notification:platform-notification-webhook:tasks --all -q | head -n 5`
Expected: the project resolves (no "project not found" error). It has no sources yet, which is fine.

- [ ] **Step 4: Commit**

```bash
git add settings.gradle.kts server/ee/libs/platform/platform-notification/platform-notification-webhook/build.gradle.kts
git commit -m "732 Add EE platform-notification-webhook module"
```

---

## Task 4: HMAC signature helper `WebhookSignatures` (EE)

**Files:**
- Create: `server/ee/libs/platform/platform-notification/platform-notification-webhook/src/main/java/com/bytechef/ee/platform/notification/webhook/WebhookSignatures.java`
- Test: `server/ee/libs/platform/platform-notification/platform-notification-webhook/src/test/java/com/bytechef/ee/platform/notification/webhook/WebhookSignaturesTest.java`

- [ ] **Step 1: Write the failing test (published HMAC-SHA256 vector)**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.notification.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class WebhookSignaturesTest {

    // Published HMAC-SHA256 test vector (Wikipedia):
    // key="key", message="The quick brown fox jumps over the lazy dog"
    @Test
    void testSignMatchesKnownVector() {
        String signature = WebhookSignatures.sign(
            "The quick brown fox jumps over the lazy dog", "key");

        assertThat(signature).isEqualTo(
            "sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void testSignIsDeterministic() {
        assertThat(WebhookSignatures.sign("body", "secret"))
            .isEqualTo(WebhookSignatures.sign("body", "secret"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:ee:libs:platform:platform-notification:platform-notification-webhook:test --tests "com.bytechef.ee.platform.notification.webhook.WebhookSignaturesTest"`
Expected: FAIL — `WebhookSignatures` does not exist.

- [ ] **Step 3: Implement the helper**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.notification.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.util.Assert;

/**
 * Computes the {@code X-ByteChef-Signature} header value over a raw webhook body using HMAC-SHA256.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class WebhookSignatures {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private WebhookSignatures() {
    }

    public static String sign(String body, String secret) {
        Assert.notNull(body, "'body' must not be null");
        Assert.hasText(secret, "'secret' must not be empty");

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);

            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));

            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));

            return "sha256=" + toHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Unable to compute webhook signature", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];

        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;

            chars[i * 2] = HEX[value >>> 4];
            chars[i * 2 + 1] = HEX[value & 0x0F];
        }

        return new String(chars);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :server:ee:libs:platform:platform-notification:platform-notification-webhook:test --tests "com.bytechef.ee.platform.notification.webhook.WebhookSignaturesTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/ee/libs/platform/platform-notification/platform-notification-webhook/src/main/java/com/bytechef/ee/platform/notification/webhook/WebhookSignatures.java server/ee/libs/platform/platform-notification/platform-notification-webhook/src/test/java/com/bytechef/ee/platform/notification/webhook/WebhookSignaturesTest.java
git commit -m "732 Add WebhookSignatures HMAC-SHA256 helper"
```

---

## Task 5: SSRF guard `WebhookUrlValidator` (EE)

**Files:**
- Create: `server/ee/libs/platform/platform-notification/platform-notification-webhook/src/main/java/com/bytechef/ee/platform/notification/webhook/WebhookUrlValidator.java`
- Test: `server/ee/libs/platform/platform-notification/platform-notification-webhook/src/test/java/com/bytechef/ee/platform/notification/webhook/WebhookUrlValidatorTest.java`

- [ ] **Step 1: Write the failing test**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.notification.webhook;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class WebhookUrlValidatorTest {

    private final WebhookUrlValidator validator = new WebhookUrlValidator(false);

    @Test
    void testAcceptsPublicHttpsUrl() {
        assertThatCode(() -> validator.validate("https://example.com/hook"))
            .doesNotThrowAnyException();
    }

    @Test
    void testRejectsLoopbackIp() {
        assertThatThrownBy(() -> validator.validate("http://127.0.0.1/x"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testRejectsLocalhostHostname() {
        assertThatThrownBy(() -> validator.validate("http://localhost/x"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testRejectsPrivateRangeIp() {
        assertThatThrownBy(() -> validator.validate("https://10.1.2.3/x"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate("https://192.168.0.5/x"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testRejectsLinkLocalMetadataIp() {
        assertThatThrownBy(() -> validator.validate("http://169.254.169.254/latest/meta-data"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testRejectsNonHttpScheme() {
        assertThatThrownBy(() -> validator.validate("ftp://example.com"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testRejectsMalformedUrl() {
        assertThatThrownBy(() -> validator.validate("not a url"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testAllowInternalFlagSkipsChecks() {
        WebhookUrlValidator permissive = new WebhookUrlValidator(true);

        assertThatCode(() -> permissive.validate("http://127.0.0.1/x"))
            .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:ee:libs:platform:platform-notification:platform-notification-webhook:test --tests "com.bytechef.ee.platform.notification.webhook.WebhookUrlValidatorTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement the validator (Java 25 `InetAddress.ofLiteral`, no DNS). Constructed by the EE config in Task 8 — no `@Component`.**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.notification.webhook;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import org.jspecify.annotations.Nullable;

/**
 * Rejects webhook URLs that target loopback, link-local, site-local or otherwise non-routable hosts
 * to mitigate SSRF. Literal IP addresses are checked without DNS resolution; bare hostnames are not
 * resolved (only the {@code localhost} literal is rejected) so validation stays deterministic.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class WebhookUrlValidator {

    private final boolean allowInternalHosts;

    public WebhookUrlValidator(boolean allowInternalHosts) {
        this.allowInternalHosts = allowInternalHosts;
    }

    public void validate(String urlString) {
        URI uri;

        try {
            uri = new URI(urlString);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid webhook URL: " + urlString, e);
        }

        String scheme = uri.getScheme();

        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException("Webhook URL must use http or https: " + urlString);
        }

        String host = uri.getHost();

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Webhook URL has no host: " + urlString);
        }

        if (allowInternalHosts) {
            return;
        }

        if (host.equalsIgnoreCase("localhost")) {
            throw new IllegalArgumentException("Webhook URL host is not allowed: " + host);
        }

        InetAddress address = toLiteralAddress(host);

        if (address != null && (address.isLoopbackAddress() || address.isAnyLocalAddress() ||
            address.isLinkLocalAddress() || address.isSiteLocalAddress())) {

            throw new IllegalArgumentException("Webhook URL host is not allowed: " + host);
        }
    }

    private static @Nullable InetAddress toLiteralAddress(String host) {
        try {
            return InetAddress.ofLiteral(host);
        } catch (IllegalArgumentException notLiteralIp) {
            return null;
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :server:ee:libs:platform:platform-notification:platform-notification-webhook:test --tests "com.bytechef.ee.platform.notification.webhook.WebhookUrlValidatorTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/ee/libs/platform/platform-notification/platform-notification-webhook/src/main/java/com/bytechef/ee/platform/notification/webhook/WebhookUrlValidator.java server/ee/libs/platform/platform-notification/platform-notification-webhook/src/test/java/com/bytechef/ee/platform/notification/webhook/WebhookUrlValidatorTest.java
git commit -m "732 Add WebhookUrlValidator SSRF guard"
```

---

## Task 6: Envelope record + config properties (EE)

**Files:**
- Create: `server/ee/libs/platform/platform-notification/platform-notification-webhook/src/main/java/com/bytechef/ee/platform/notification/webhook/WebhookEnvelope.java`
- Create: `server/ee/libs/platform/platform-notification/platform-notification-webhook/src/main/java/com/bytechef/ee/platform/notification/webhook/WebhookNotificationProperties.java`

- [ ] **Step 1: Create the envelope record**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.notification.webhook;

import java.util.Map;

/**
 * Stable, versioned webhook delivery envelope. The {@code data} block carries the per-event payload
 * produced by the {@code WebhookNotificationHandler}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public record WebhookEnvelope(
    String schemaVersion, String id, String event, String source, String timestamp,
    Map<String, Object> data) {
}
```

- [ ] **Step 2: Create the configuration properties**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.notification.webhook;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ConfigurationProperties(prefix = "bytechef.notification.webhook")
public class WebhookNotificationProperties {

    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration requestTimeout = Duration.ofSeconds(10);
    private boolean allowInternalHosts = false;
    private final Retry retry = new Retry();

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public boolean isAllowInternalHosts() {
        return allowInternalHosts;
    }

    public void setAllowInternalHosts(boolean allowInternalHosts) {
        this.allowInternalHosts = allowInternalHosts;
    }

    public Retry getRetry() {
        return retry;
    }

    public static class Retry {

        private Duration initialInterval = Duration.ofSeconds(2);
        private double multiplier = 2.0;
        private int maxAttempts = 5;

        public Duration getInitialInterval() {
            return initialInterval;
        }

        public void setInitialInterval(Duration initialInterval) {
            this.initialInterval = initialInterval;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }
}
```

- [ ] **Step 3: Compile the EE module**

Run: `./gradlew :server:ee:libs:platform:platform-notification:platform-notification-webhook:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add server/ee/libs/platform/platform-notification/platform-notification-webhook/src/main/java/com/bytechef/ee/platform/notification/webhook/WebhookEnvelope.java server/ee/libs/platform/platform-notification/platform-notification-webhook/src/main/java/com/bytechef/ee/platform/notification/webhook/WebhookNotificationProperties.java
git commit -m "732 Add webhook envelope record and notification webhook properties"
```

---

## Task 7: Implement `WebhookNotificationSender` (EE, `@ConditionalOnEEVersion`)

**Files:**
- Create: `server/ee/libs/platform/platform-notification/platform-notification-webhook/src/main/java/com/bytechef/ee/platform/notification/webhook/WebhookNotificationSender.java`
- Test: `server/ee/libs/platform/platform-notification/platform-notification-webhook/src/test/java/com/bytechef/ee/platform/notification/webhook/WebhookNotificationSenderTest.java`

- [ ] **Step 1: Write the failing test (real local HTTP stub, synchronous executor, identity Encryption)**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.notification.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.commons.util.JsonUtils;
import com.bytechef.encryption.Encryption;
import com.bytechef.platform.notification.domain.Notification;
import com.bytechef.platform.notification.domain.NotificationEvent;
import com.bytechef.platform.notification.handler.NotificationHandlerContext;
import com.bytechef.platform.notification.handler.WebhookNotificationHandler;
import com.bytechef.platform.notification.service.NotificationService;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
class WebhookNotificationSenderTest {

    private HttpServer httpServer;
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedSignature = new AtomicReference<>();
    private String baseUrl;

    private final NotificationService notificationService = mock(NotificationService.class);
    private final Encryption encryption = new Encryption() {
        @Override
        public String encrypt(String content) {
            return content;
        }

        @Override
        public String decrypt(String encryptedString) {
            return encryptedString;
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        httpServer.createContext("/hook", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedSignature.set(exchange.getRequestHeaders().getFirst("X-ByteChef-Signature"));

            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        httpServer.start();

        baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        httpServer.stop(0);
    }

    @Test
    void testSendPostsSignedEnvelope() {
        WebhookNotificationProperties properties = new WebhookNotificationProperties();

        WebhookUrlValidator urlValidator = new WebhookUrlValidator(true);

        WebhookNotificationSender sender = new WebhookNotificationSender(
            Runnable::run, encryption, notificationService, urlValidator, properties);

        Notification notification = mock(Notification.class);

        when(notification.getId()).thenReturn(1L);
        when(notification.getSettings()).thenReturn(
            Map.of("webhook", baseUrl + "/hook", "secret", "topsecret"));

        WebhookNotificationHandler handler = ctx -> Map.of(
            "jobId", 123L, "jobName", "Sync customers", "status", "FAILED");

        NotificationHandlerContext context = new NotificationHandlerContext.Builder()
            .eventType(NotificationEvent.Type.JOB_FAILED)
            .jobId(123L)
            .jobName("Sync customers")
            .status("FAILED")
            .timestamp(Instant.parse("2026-05-31T12:00:00Z"))
            .build();

        sender.send(notification, handler, context);

        String body = receivedBody.get();

        assertThat(body).isNotNull();

        Map<String, Object> envelope = JsonUtils.read(body, Map.class);

        assertThat(envelope.get("event")).isEqualTo("JOB_FAILED");
        assertThat(envelope.get("source")).isEqualTo("JOB");
        assertThat(envelope.get("schemaVersion")).isEqualTo("1");
        assertThat(receivedSignature.get()).isEqualTo(WebhookSignatures.sign(body, "topsecret"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:ee:libs:platform:platform-notification:platform-notification-webhook:test --tests "com.bytechef.ee.platform.notification.webhook.WebhookNotificationSenderTest"`
Expected: FAIL — `WebhookNotificationSender` does not exist.

- [ ] **Step 3: Implement the sender**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.notification.webhook;

import com.bytechef.commons.util.JsonUtils;
import com.bytechef.encryption.Encryption;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.notification.domain.Notification;
import com.bytechef.platform.notification.domain.NotificationEvent;
import com.bytechef.platform.notification.handler.NotificationHandlerContext;
import com.bytechef.platform.notification.handler.NotificationSender;
import com.bytechef.platform.notification.handler.WebhookNotificationHandler;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class WebhookNotificationSender implements NotificationSender<WebhookNotificationHandler> {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotificationSender.class);
    private static final String SCHEMA_VERSION = "1";

    private final Executor taskExecutor;
    private final Encryption encryption;
    private final NotificationService notificationService;
    private final WebhookUrlValidator webhookUrlValidator;
    private final WebhookNotificationProperties properties;
    private final HttpClient httpClient;

    @SuppressFBWarnings("EI2")
    public WebhookNotificationSender(
        @Qualifier("taskExecutor") Executor taskExecutor, Encryption encryption,
        com.bytechef.platform.notification.service.NotificationService notificationService,
        WebhookUrlValidator webhookUrlValidator, WebhookNotificationProperties properties) {

        this.taskExecutor = taskExecutor;
        this.encryption = encryption;
        this.notificationService = notificationService;
        this.webhookUrlValidator = webhookUrlValidator;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.getConnectTimeout())
            .build();
    }

    public Notification.Type getType() {
        return Notification.Type.WEBHOOK;
    }

    @Override
    public void send(
        Notification notification, WebhookNotificationHandler webhookNotificationHandler,
        NotificationHandlerContext notificationHandlerContext) {

        Map<String, Object> settings = notification.getSettings();

        String url = (String) settings.get("webhook");

        webhookUrlValidator.validate(url);

        String body = JsonUtils.write(buildEnvelope(webhookNotificationHandler, notificationHandlerContext));
        String signature = sign(body, (String) settings.get("secret"));

        long notificationId = notification.getId();
        NotificationEvent.Type eventType = notificationHandlerContext.getEventType();

        taskExecutor.execute(() -> deliver(notificationId, url, body, signature, eventType));
    }

    private WebhookEnvelope buildEnvelope(
        WebhookNotificationHandler webhookNotificationHandler,
        NotificationHandlerContext notificationHandlerContext) {

        NotificationEvent.Type eventType = notificationHandlerContext.getEventType();

        Instant timestamp = notificationHandlerContext.getTimestamp();

        return new WebhookEnvelope(
            SCHEMA_VERSION, UUID.randomUUID()
                .toString(),
            eventType.name(), eventTypeSource(eventType),
            (timestamp == null ? Instant.now() : timestamp).toString(),
            webhookNotificationHandler.getPayload(notificationHandlerContext));
    }

    private static String eventTypeSource(NotificationEvent.Type eventType) {
        return eventType.name()
            .startsWith("TASK_") ? "TASK" : "JOB";
    }

    private @Nullable String sign(String body, @Nullable String encryptedSecret) {
        if (encryptedSecret == null || encryptedSecret.isBlank()) {
            return null;
        }

        return WebhookSignatures.sign(body, encryption.decrypt(encryptedSecret));
    }

    private void deliver(
        long notificationId, String url, String body, @Nullable String signature,
        NotificationEvent.Type eventType) {

        RetryTemplate retryTemplate = new RetryTemplate(
            RetryPolicy.builder()
                .backOff(new ExponentialBackOff(
                    properties.getRetry()
                        .getInitialInterval()
                        .toMillis(),
                    properties.getRetry()
                        .getMultiplier()))
                .maxRetries(properties.getRetry()
                    .getMaxAttempts())
                .build());

        try {
            retryTemplate.execute(() -> post(url, body, signature, eventType));

            recordSuccess(notificationId);
        } catch (RetryException e) {
            log.warn("Webhook delivery to {} failed after retries", url, e);

            recordFailure(notificationId, e.getMessage());
        }
    }

    private String post(String url, String body, @Nullable String signature, NotificationEvent.Type eventType) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(properties.getRequestTimeout())
            .header("Content-Type", "application/json")
            .header("X-ByteChef-Event", eventType.name())
            .header("X-ByteChef-Delivery", UUID.randomUUID()
                .toString())
            .POST(HttpRequest.BodyPublishers.ofString(body));

        if (signature != null) {
            requestBuilder.header("X-ByteChef-Signature", signature);
        }

        try {
            HttpResponse<String> httpResponse = httpClient.send(
                requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            int statusCode = httpResponse.statusCode();

            if (statusCode >= 500) {
                throw new IllegalStateException("Webhook endpoint returned " + statusCode);
            }

            if (statusCode >= 400) {
                log.warn("Webhook endpoint {} returned client error {} - not retrying", url, statusCode);
            }

            return httpResponse.body();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Webhook request to " + url + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();

            throw new IllegalStateException("Webhook request to " + url + " was interrupted", e);
        }
    }

    private void recordSuccess(long notificationId) {
        // delivery-status persistence is wired in the optional final task; no-op until then
    }

    private void recordFailure(long notificationId, @Nullable String message) {
        // delivery-status persistence is wired in the optional final task; no-op until then
    }
}
```

> Add the missing `NotificationService` import at the top (`import com.bytechef.platform.notification.service.NotificationService;`) and change the constructor parameter type from the fully-qualified name to `NotificationService` — the FQN inline above is only to make the dependency explicit in this plan. The `recordSuccess`/`recordFailure` no-ops keep the `notificationService` field used; they get bodies in the optional delivery-status task. If the `EmptyBlock`/unused-field rules complain, add a `log.trace(...)` line in each and keep the field.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :server:ee:libs:platform:platform-notification:platform-notification-webhook:test --tests "com.bytechef.ee.platform.notification.webhook.WebhookNotificationSenderTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/ee/libs/platform/platform-notification/platform-notification-webhook/src/main/java/com/bytechef/ee/platform/notification/webhook/WebhookNotificationSender.java server/ee/libs/platform/platform-notification/platform-notification-webhook/src/test/java/com/bytechef/ee/platform/notification/webhook/WebhookNotificationSenderTest.java
git commit -m "732 Implement EE WebhookNotificationSender with async delivery, retry and HMAC signing"
```

---

## Task 8: EE configuration (`@ConditionalOnEEVersion`)

**Files:**
- Create: `server/ee/libs/platform/platform-notification/platform-notification-webhook/src/main/java/com/bytechef/ee/platform/notification/webhook/WebhookNotificationConfiguration.java`

- [ ] **Step 1: Create the configuration**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.notification.webhook;

import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Configuration
@ConditionalOnEEVersion
@EnableConfigurationProperties(WebhookNotificationProperties.class)
public class WebhookNotificationConfiguration {

    @Bean
    WebhookUrlValidator webhookUrlValidator(WebhookNotificationProperties webhookNotificationProperties) {
        return new WebhookUrlValidator(webhookNotificationProperties.isAllowInternalHosts());
    }
}
```

- [ ] **Step 2: Compile the module**

Run: `./gradlew :server:ee:libs:platform:platform-notification:platform-notification-webhook:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add server/ee/libs/platform/platform-notification/platform-notification-webhook/src/main/java/com/bytechef/ee/platform/notification/webhook/WebhookNotificationConfiguration.java
git commit -m "732 Add EE webhook notification configuration"
```

---

## Task 9: `JobStatusWebhookNotificationHandler` (EE, `@ConditionalOnEEVersion`)

**Files:**
- Create: `server/ee/libs/platform/platform-notification/platform-notification-webhook/src/main/java/com/bytechef/ee/platform/notification/webhook/JobStatusWebhookNotificationHandler.java`
- Test: `server/ee/libs/platform/platform-notification/platform-notification-webhook/src/test/java/com/bytechef/ee/platform/notification/webhook/JobStatusWebhookNotificationHandlerTest.java`

- [ ] **Step 1: Write the failing test**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.notification.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.notification.domain.NotificationEvent;
import com.bytechef.platform.notification.handler.NotificationHandlerContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class JobStatusWebhookNotificationHandlerTest {

    private final JobStatusWebhookNotificationHandler handler = new JobStatusWebhookNotificationHandler();

    @Test
    void testGetPayloadContainsJobFields() {
        NotificationHandlerContext context = new NotificationHandlerContext.Builder()
            .eventType(NotificationEvent.Type.JOB_FAILED)
            .jobId(123L)
            .jobName("Sync customers")
            .status("FAILED")
            .build();

        Map<String, Object> payload = handler.getPayload(context);

        assertThat(payload)
            .containsEntry("jobId", 123L)
            .containsEntry("jobName", "Sync customers")
            .containsEntry("status", "FAILED");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:ee:libs:platform:platform-notification:platform-notification-webhook:test --tests "com.bytechef.ee.platform.notification.webhook.JobStatusWebhookNotificationHandlerTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement the handler**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.notification.webhook;

import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.notification.domain.NotificationEvent.Type;
import com.bytechef.platform.notification.handler.NotificationEventType;
import com.bytechef.platform.notification.handler.NotificationHandlerContext;
import com.bytechef.platform.notification.handler.WebhookNotificationHandler;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
@NotificationEventType({
    Type.JOB_CANCELLED, Type.JOB_CREATED, Type.JOB_COMPLETED, Type.JOB_FAILED, Type.JOB_STARTED
})
public class JobStatusWebhookNotificationHandler implements WebhookNotificationHandler {

    @Override
    public Map<String, Object> getPayload(NotificationHandlerContext notificationHandlerContext) {
        Map<String, Object> payload = new HashMap<>();

        payload.put("jobId", notificationHandlerContext.getJobId());
        payload.put("jobName", notificationHandlerContext.getJobName());

        if (notificationHandlerContext.getStatus() != null) {
            payload.put("status", notificationHandlerContext.getStatus());
        }

        return payload;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :server:ee:libs:platform:platform-notification:platform-notification-webhook:test --tests "com.bytechef.ee.platform.notification.webhook.JobStatusWebhookNotificationHandlerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/ee/libs/platform/platform-notification/platform-notification-webhook/src/main/java/com/bytechef/ee/platform/notification/webhook/JobStatusWebhookNotificationHandler.java server/ee/libs/platform/platform-notification/platform-notification-webhook/src/test/java/com/bytechef/ee/platform/notification/webhook/JobStatusWebhookNotificationHandlerTest.java
git commit -m "732 Add EE JobStatusWebhookNotificationHandler"
```

---

## Task 10: Populate status/timestamp AND add null-sender guard in the job listener (CE)

**Files:**
- Modify: `server/libs/platform/platform-coordinator/src/main/java/com/bytechef/platform/coordinator/event/listener/NotificationJobStatusApplicationEventListener.java`

- [ ] **Step 1: Update `getNotificationHandlerContext` to set status and timestamp**

Replace the `getNotificationHandlerContext` method with:

```java
    private NotificationHandlerContext getNotificationHandlerContext(
        NotificationEvent.Type eventType, Job job, Job.Status status) {

        return new NotificationHandlerContext.Builder()
            .eventType(eventType)
            .jobId(job.getId())
            .jobName(job.getLabel())
            .status(status.toString())
            .timestamp(java.time.Instant.now())
            .build();
    }
```

- [ ] **Step 2: Update the call site to pass `status`**

In `onApplicationEvent`, change the context construction to:

```java
            NotificationHandlerContext notificationHandlerContext = getNotificationHandlerContext(
                eventType, job, status);
```

- [ ] **Step 3: Add the null-sender guard in the dispatch loop**

Inside the `for (Notification notification : notifications)` loop, change:

```java
                NotificationSender notificationSender = notificationSenderRegistry.getNotificationSender(
                    notification.getType());

                NotificationHandler notificationHandler = notificationHandlerRegistry.getNotificationHandler(
                    eventType, notification.getType());

                notificationSender.send(notification, notificationHandler, notificationHandlerContext);
```

to:

```java
                NotificationSender notificationSender = notificationSenderRegistry.getNotificationSender(
                    notification.getType());

                if (notificationSender == null) {
                    continue; // EE-only channel (e.g. WEBHOOK) not registered in this edition
                }

                NotificationHandler notificationHandler = notificationHandlerRegistry.getNotificationHandler(
                    eventType, notification.getType());

                notificationSender.send(notification, notificationHandler, notificationHandlerContext);
```

- [ ] **Step 4: Compile the module**

Run: `./gradlew :server:libs:platform:platform-coordinator:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add server/libs/platform/platform-coordinator/src/main/java/com/bytechef/platform/coordinator/event/listener/NotificationJobStatusApplicationEventListener.java
git commit -m "732 Set status/timestamp and skip absent EE senders in notification listener"
```

---

## Task 11: EE gating context test (`@ConditionalOnEEVersion`)

**Files:**
- Test: `server/ee/libs/platform/platform-notification/platform-notification-webhook/src/test/java/com/bytechef/ee/platform/notification/webhook/WebhookNotificationEditionGatingTest.java`

> Verifies the sender bean registers only when `bytechef.edition=ee`. Uses Spring `ApplicationContextRunner`
> (no Testcontainers, no DB) — the sender's behavior is already covered by Task 7's unit test.

- [ ] **Step 1: Write the test**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.notification.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.encryption.Encryption;
import com.bytechef.platform.notification.service.NotificationService;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class WebhookNotificationEditionGatingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestDependenciesConfiguration.class, WebhookNotificationConfiguration.class)
        .withBean(WebhookNotificationSender.class)
        .withBean(JobStatusWebhookNotificationHandler.class);

    @Test
    void testSenderRegistersWhenEditionIsEe() {
        contextRunner
            .withPropertyValues("bytechef.edition=ee")
            .run(context -> assertThat(context).hasSingleBean(WebhookNotificationSender.class));
    }

    @Test
    void testSenderAbsentWhenEditionNotEe() {
        contextRunner
            .run(context -> assertThat(context).doesNotHaveBean(WebhookNotificationSender.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependenciesConfiguration {

        @Bean
        Executor taskExecutor() {
            return Runnable::run;
        }

        @Bean
        Encryption encryption() {
            return Mockito.mock(Encryption.class);
        }

        @Bean
        NotificationService notificationService() {
            return Mockito.mock(NotificationService.class);
        }
    }
}
```

> `@ConditionalOnEEVersion` is `@ConditionalOnProperty(bytechef.edition=ee)`; without the property the
> conditional fails and the `@ConditionalOnEEVersion`-annotated `WebhookNotificationSender` /
> `JobStatusWebhookNotificationHandler` beans (and the `WebhookNotificationConfiguration`) are not
> created. The `@Bean(taskExecutor)` is named to satisfy the sender's `@Qualifier("taskExecutor")`.
> If `AutoConfigurations` is unused after final edits, drop the import.

- [ ] **Step 2: Run the test**

Run: `./gradlew :server:ee:libs:platform:platform-notification:platform-notification-webhook:test --tests "com.bytechef.ee.platform.notification.webhook.WebhookNotificationEditionGatingTest"`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add server/ee/libs/platform/platform-notification/platform-notification-webhook/src/test/java/com/bytechef/ee/platform/notification/webhook/WebhookNotificationEditionGatingTest.java
git commit -m "732 Add EE edition gating test for webhook sender"
```

---

## Task 12: Encrypt the webhook signing secret at persistence (CE)

**Files:**
- Modify: `server/libs/platform/platform-notification/platform-notification-service/src/main/java/com/bytechef/platform/notification/facade/NotificationFacadeImpl.java`
- Modify: `server/libs/platform/platform-notification/platform-notification-service/build.gradle.kts`
- Test: `server/libs/platform/platform-notification/platform-notification-service/src/test/java/com/bytechef/platform/notification/facade/NotificationFacadeTest.java`

> Encryption lives in CE so the secret is never stored in plaintext even though WEBHOOK is EE-only. The
> EE sender decrypts at send time. `encryption-api` is already a transitive test dep here; add it as an
> `implementation` dep for the facade.

- [ ] **Step 1: Add the `encryption-api` dependency**

In `build.gradle.kts`, add to the `implementation(...)` block:

```kotlin
    implementation(project(":server:libs:core:encryption:encryption-api"))
```

- [ ] **Step 2: Write the failing test**

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.platform.notification.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.encryption.Encryption;
import com.bytechef.platform.notification.domain.Notification;
import com.bytechef.platform.notification.service.NotificationEventService;
import com.bytechef.platform.notification.service.NotificationService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationFacadeTest {

    private final NotificationService notificationService = mock(NotificationService.class);
    private final NotificationEventService notificationEventService = mock(NotificationEventService.class);
    private final Encryption encryption = new Encryption() {
        @Override
        public String encrypt(String content) {
            return "enc(" + content + ")";
        }

        @Override
        public String decrypt(String encryptedString) {
            return encryptedString;
        }
    };

    @Test
    void testCreateEncryptsWebhookSecret() {
        NotificationFacadeImpl facade = new NotificationFacadeImpl(
            encryption, notificationEventService, notificationService);

        Notification input = new Notification();

        input.setType(Notification.Type.WEBHOOK);
        input.setName("test");
        input.setSettings(Map.of("webhook", "https://example.com/hook", "secret", "topsecret"));

        when(notificationEventService.getNotificationEvents(any())).thenReturn(List.of());
        when(notificationService.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        facade.createNotification(input);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);

        verify(notificationService).create(captor.capture());

        assertThat(captor.getValue()
            .getSettings()).containsEntry("secret", "enc(topsecret)");
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :server:libs:platform:platform-notification:platform-notification-service:test --tests "com.bytechef.platform.notification.facade.NotificationFacadeTest"`
Expected: FAIL — constructor does not take `Encryption`.

- [ ] **Step 4: Add encryption to the facade (keep license header)**

```java
package com.bytechef.platform.notification.facade;

import com.bytechef.encryption.Encryption;
import com.bytechef.platform.notification.domain.Notification;
import com.bytechef.platform.notification.dto.NotificationDTO;
import com.bytechef.platform.notification.service.NotificationEventService;
import com.bytechef.platform.notification.service.NotificationService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * @author Ivica Cardic
 */
@Service
public class NotificationFacadeImpl implements NotificationFacade {

    private final Encryption encryption;
    private final NotificationEventService notificationEventService;
    private final NotificationService notificationService;

    @SuppressFBWarnings("EI")
    public NotificationFacadeImpl(
        Encryption encryption, NotificationEventService notificationEventService,
        NotificationService notificationService) {

        this.encryption = encryption;
        this.notificationEventService = notificationEventService;
        this.notificationService = notificationService;
    }

    @Override
    public List<NotificationDTO> getNotifications() {
        return notificationService.getNotifications()
            .stream()
            .map(notification -> new NotificationDTO(
                notification, notificationEventService.getNotificationEvents(notification.getNotificationEventIds())))
            .toList();
    }

    @Override
    public NotificationDTO createNotification(Notification notification) {
        encryptSecret(notification);

        notification = notificationService.create(notification);

        return new NotificationDTO(
            notification,
            notificationEventService.getNotificationEvents(notification.getNotificationEventIds()));
    }

    @Override
    public NotificationDTO updateNotification(Notification notification) {
        encryptSecret(notification);

        notification = notificationService.update(notification);

        return new NotificationDTO(
            notification,
            notificationEventService.getNotificationEvents(notification.getNotificationEventIds()));
    }

    private void encryptSecret(Notification notification) {
        if (notification.getType() != Notification.Type.WEBHOOK) {
            return;
        }

        Map<String, Object> settings = notification.getSettings();

        Object secret = settings.get("secret");

        if (secret instanceof String secretString && !secretString.isBlank()) {
            Map<String, Object> updatedSettings = new HashMap<>(settings);

            updatedSettings.put("secret", encryption.encrypt(secretString));

            notification.setSettings(updatedSettings);
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :server:libs:platform:platform-notification:platform-notification-service:test --tests "com.bytechef.platform.notification.facade.NotificationFacadeTest"`
Expected: PASS

> **Re-encryption note:** on update the client sends the plaintext secret from a fresh input field, so
> re-encrypting on every save is correct. If the field is later changed to show a masked stored value,
> add a "secret unchanged" sentinel to avoid double-encrypting ciphertext.

- [ ] **Step 6: Commit**

```bash
git add server/libs/platform/platform-notification/platform-notification-service/src/main/java/com/bytechef/platform/notification/facade/NotificationFacadeImpl.java server/libs/platform/platform-notification/platform-notification-service/build.gradle.kts server/libs/platform/platform-notification/platform-notification-service/src/test/java/com/bytechef/platform/notification/facade/NotificationFacadeTest.java
git commit -m "732 Encrypt webhook signing secret at persistence"
```

---

## Task 13: Client — gate WEBHOOK behind `<EEVersion>` and add the Secret field

**Files:**
- Modify: `client/src/pages/settings/platform/notifications/hooks/useNotifications.tsx`
- Modify: `client/src/pages/settings/platform/notifications/components/NotificationDialog.tsx`

- [ ] **Step 1: Remove the `ff_1132` flag and extend the Zod schema in the hook**

In `useNotifications.tsx`:

- Delete `import {useFeatureFlagsStore} from '@/shared/stores/useFeatureFlagsStore';`
- Delete `const ff_1132 = useFeatureFlagsStore()('ff-1132');`
- Delete `ff_1132,` from the returned object.
- Replace the `settings` schema line:

```ts
    settings: z.record(z.string(), z.any()),
```

with:

```ts
    settings: z
        .object({
            email: z.string().optional(),
            secret: z.string().optional(),
            webhook: z.string().optional(),
        })
        .passthrough(),
```

- [ ] **Step 2: Run typecheck to surface the failing references**

Run: `cd client && npm run typecheck`
Expected: FAIL — `NotificationDialog.tsx` still references `ff_1132`.

- [ ] **Step 3: Gate WEBHOOK with `<EEVersion>` and add the Secret input in the dialog**

In `NotificationDialog.tsx`:

- Add the import (alphabetical within the import group):

```tsx
import EEVersion from '@/shared/edition/EEVersion';
```

- Remove `ff_1132,` from the destructured `useNotifications()` call.
- Replace the `ff_1132`-gated webhook `SelectItem` block:

```tsx
                                                {ff_1132 && (
                                                    <SelectItem
                                                        key={NotificationTypeEnum.Webhook.toString()}
                                                        value={NotificationTypeEnum.Webhook.toString()}
                                                    >
                                                        {NotificationTypeEnum.Webhook.toString()}
                                                    </SelectItem>
                                                )}
```

with (`<EEVersion hidden>` renders nothing in CE):

```tsx
                                                <EEVersion hidden>
                                                    <SelectItem
                                                        key={NotificationTypeEnum.Webhook.toString()}
                                                        value={NotificationTypeEnum.Webhook.toString()}
                                                    >
                                                        {NotificationTypeEnum.Webhook.toString()}
                                                    </SelectItem>
                                                </EEVersion>
```

- Add a Secret field after the existing Webhook URL `FormField` (still inside the
  `notificationType === NotificationTypeEnum.Webhook` conditional region):

```tsx
                        {notificationType === NotificationTypeEnum.Webhook && (
                            <FormField
                                control={control}
                                name="settings.secret"
                                render={({field}) => (
                                    <FormItem>
                                        <FormLabel>Secret (optional)</FormLabel>

                                        <FormControl>
                                            <Input
                                                autoComplete="off"
                                                onChange={(e) => field.onChange(e.target.value)}
                                                type="password"
                                                value={(field.value as string) || ''}
                                            />
                                        </FormControl>

                                        <FormMessage />
                                    </FormItem>
                                )}
                            />
                        )}
```

- [ ] **Step 4: Run the full client check**

Run: `cd client && npm run check`
Expected: PASS (lint + typecheck + tests). Fix any `sort-keys` / import-order issues the linter flags.

- [ ] **Step 5: Commit**

```bash
git add client/src/pages/settings/platform/notifications/hooks/useNotifications.tsx client/src/pages/settings/platform/notifications/components/NotificationDialog.tsx
git commit -m "732 client - Gate WEBHOOK notification type behind EEVersion and add secret field"
```

---

## Task 14 (optional): Persist last delivery status

> Per spec §5.4, optional polish. Adds two columns so the UI can surface a misconfigured webhook.

**Files:**
- Modify: `server/libs/platform/platform-notification/platform-notification-api/src/main/java/com/bytechef/platform/notification/domain/Notification.java`
- Create: `server/libs/platform/platform-notification/platform-notification-service/src/main/resources/config/liquibase/changelog/platform/notification/1769000000_add_notification_delivery_status.xml`
- Modify: the aggregating changelog that includes the notification changelogs
- Modify: `NotificationService` (api) + `NotificationServiceImpl` (service) — add `updateDeliveryStatus`
- Modify: `WebhookNotificationSender` (EE) — fill `recordSuccess`/`recordFailure`

- [ ] **Step 1: Find the aggregating changelog**

Run: `grep -rln "platform_notification_init\|platform/notification" server/libs --include="*.xml" | grep -v /build/ | grep -i master`
Expected: the master changelog path; note where the notification includes are.

- [ ] **Step 2: Create the migration**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                   http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">
    <changeSet id="1769000000" author="Ivica Cardic">
        <addColumn tableName="notification">
            <column name="last_delivery_date" type="TIMESTAMP"/>
            <column name="last_error" type="TEXT"/>
        </addColumn>
    </changeSet>
</databaseChangeLog>
```

- [ ] **Step 3: Add fields/getters/setters to `Notification`** (after `version`):

```java
    @Column("last_delivery_date")
    private Instant lastDeliveryDate;

    @Column("last_error")
    private String lastError;
```

```java
    public Instant getLastDeliveryDate() {
        return lastDeliveryDate;
    }

    public void setLastDeliveryDate(Instant lastDeliveryDate) {
        this.lastDeliveryDate = lastDeliveryDate;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
```

- [ ] **Step 4: Add `updateDeliveryStatus`**

`NotificationService`:

```java
    void updateDeliveryStatus(long notificationId, @org.jspecify.annotations.Nullable String error);
```

`NotificationServiceImpl`:

```java
    @Override
    public void updateDeliveryStatus(long notificationId, @org.jspecify.annotations.Nullable String error) {
        notificationRepository.findById(notificationId)
            .ifPresent(notification -> {
                notification.setLastDeliveryDate(java.time.Instant.now());
                notification.setLastError(error);

                notificationRepository.save(notification);
            });
    }
```

- [ ] **Step 5: Fill the EE sender's record methods**

In `WebhookNotificationSender`:

```java
    private void recordSuccess(long notificationId) {
        notificationService.updateDeliveryStatus(notificationId, null);
    }

    private void recordFailure(long notificationId, @Nullable String message) {
        notificationService.updateDeliveryStatus(notificationId, message);
    }
```

- [ ] **Step 6: Run the touched module tests**

Run: `./gradlew :server:libs:platform:platform-notification:platform-notification-service:test :server:ee:libs:platform:platform-notification:platform-notification-webhook:test`
Expected: PASS (delete stale `build/resources/test` Liquibase copies if it complains about duplicate changesets).

- [ ] **Step 7: Commit**

```bash
git add -A server/libs/platform/platform-notification server/ee/libs/platform/platform-notification
git commit -m "732 Persist webhook last delivery status on notification"
```

---

## Final verification

- [ ] **Format + checks for the touched modules**

```bash
./gradlew spotlessApply
./gradlew :server:libs:platform:platform-notification:platform-notification-api:check \
          :server:libs:platform:platform-notification:platform-notification-service:check \
          :server:libs:platform:platform-coordinator:check \
          :server:ee:libs:platform:platform-notification:platform-notification-webhook:check
```
Expected: BUILD SUCCESSFUL. Fix Checkstyle/PMD/SpotBugs findings per CLAUDE.md (blank lines before control statements, camelCase test names, no empty blocks, EE license header + `@version ee`).

- [ ] **Client check**

```bash
cd client && npm run check
```
Expected: PASS.

- [ ] **Manual smoke (EE build, running stack):** with `bytechef.edition=ee`, create a WEBHOOK
  notification (the option is visible only in EE) pointing at a request-bin, subscribe to JOB_FAILED, run
  a failing workflow, confirm a signed `application/json` POST with the versioned envelope arrives. On a
  CE build, confirm the WEBHOOK option is absent and a pre-existing WEBHOOK notification is skipped (no
  error).

---

## Out of scope (Phase 2 / follow-up)

- **Task-level events** (`TASK_*`) — separate plan (`...-phase-2.md`); the task webhook handler also lands
  in this EE module.
- **Additional destination types** (Slack/Syslog/Sentry) — documented extension pattern only (Phase 3).
- **Audit/security event source** — separate EE spec.
- **JOB_STOPPED** selectability — currently unseeded; seed only if product wants it.
