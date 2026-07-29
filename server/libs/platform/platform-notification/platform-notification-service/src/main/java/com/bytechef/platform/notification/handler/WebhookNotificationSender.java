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

import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.notification.delivery.WebhookDelivery;
import com.bytechef.platform.notification.delivery.WebhookDeliveryService;
import com.bytechef.platform.notification.delivery.WebhookRetry;
import com.bytechef.platform.notification.delivery.WebhookTestResult;
import com.bytechef.platform.notification.domain.Notification;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Delivers WEBHOOK-channel notifications through the shared {@link WebhookNotificationClient} — the same transport the
 * EE AI-observability alert channels use. Settings: {@code webhook} (destination URL, required) and
 * {@code webhookSecret} (optional HMAC secret; when set, deliveries carry an {@code X-ByteChef-Signature} header).
 *
 * @author Matija Petanjek
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class WebhookNotificationSender implements NotificationSender<WebhookNotificationHandler> {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotificationSender.class);

    private final WebhookNotificationClient webhookNotificationClient;

    @SuppressFBWarnings("EI2")
    public WebhookNotificationSender(WebhookDeliveryService webhookDeliveryService) {
        this.webhookDeliveryService = webhookDeliveryService;
    }

    @Override
    public Notification.Type getType() {
        return Notification.Type.WEBHOOK;
    }

    @Async
    @Override
    public CompletableFuture<Void> send(
        Notification notification, WebhookNotificationHandler webhookNotificationHandler,
        NotificationHandlerContext notificationHandlerContext) {

        String url = (String) notification.getSetting("webhook");

        if (url == null || url.isBlank()) {
            log.warn("Notification {} has no webhook URL configured; skipping delivery", notification.getId());

            return;
        }

        WebhookDelivery webhookDelivery = buildDelivery(
            notification, webhookNotificationHandler, notificationHandlerContext, url);

        return webhookDeliveryService.submit(webhookDelivery)
            .thenAccept(NotificationDeliveryOutcomes::requireAnySent);
    }

    @Override
    public NotificationTestResult sendTest(
        Notification notification, WebhookNotificationHandler webhookNotificationHandler,
        NotificationHandlerContext notificationHandlerContext) {

        String url = (String) notification.getSetting("webhook");

        if (url == null || url.isBlank()) {
            return NotificationTestResult.failed("Webhook URL is required");
        }

        WebhookDelivery webhookDelivery = buildDelivery(
            notification, webhookNotificationHandler, notificationHandlerContext, url);

        WebhookTestResult webhookTestResult = webhookDeliveryService.sendTest(webhookDelivery);

        String secret = webhookDelivery.secret();

        List<String> secretValues = new ArrayList<>(webhookDelivery.headers()
            .values());

        secretValues.add(secret);

        TestSendRedaction testSendRedaction = TestSendRedaction.of(secretValues);

        return new NotificationTestResult(
            webhookTestResult.success(), webhookTestResult.statusCode(),
            testSendRedaction.redact(webhookTestResult.responseBody()),
            testSendRedaction.redact(webhookTestResult.error()), secret != null && !secret.isBlank());
    }

    private static WebhookDelivery buildDelivery(
        Notification notification, WebhookNotificationHandler webhookNotificationHandler,
        NotificationHandlerContext notificationHandlerContext, String url) {

        NotificationHandlerContext deliveryContext = notificationHandlerContext.withDeliveryId(
            String.valueOf(UUID.randomUUID()));

        Object template = notification.getSetting("webhookBodyTemplate");

        String body = template instanceof String templateString && !templateString.isBlank()
            ? WebhookBodyTemplate.render(templateString, deliveryContext.toMap())
            : webhookNotificationHandler.getPayload(deliveryContext);

        return WebhookDelivery.builder("notification", url)
            .headers(getHeaders(notification))
            .description("notification " + notification.getId() + " '" + notification.getName() + "'")
            .eventType("job.status")
            .secret((String) notification.getSetting("webhookSecret"))
            .validateUrl(true)
            .payload(String.valueOf(deliveryContext.getJobId()), body)
            .retry(WebhookRetry.none())
            .deliveryId(deliveryContext.getDeliveryId())
            .timestamp(notificationHandlerContext.getTimestamp())
            .build();
    }

        try {
            webhookNotificationClient.deliver(
                new WebhookDeliveryRequest(
                    url, "job.status", webhookNotificationHandler.getPayload(notificationHandlerContext), Map.of(),
                    secret));
        } catch (RuntimeException exception) {
            log.error("Failed to deliver webhook notification {}", notification.getId(), exception);
        }
    }
}
