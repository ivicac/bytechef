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

import com.bytechef.platform.notification.delivery.EmailNotificationClient;
import com.bytechef.platform.notification.domain.Notification;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Delivers EMAIL-channel notifications through the shared {@link EmailNotificationClient} — the same transport the EE
 * AI-observability alert channels use, keeping {@code MailService} exclusively for user-account mail (activation,
 * invitation, password reset). Plain-text only. Settings: {@code email} (recipient address, required).
 *
 * <p>
 * Delivery is {@code @Async} fire-and-forget (matching the previous MailService behavior) so SMTP latency never blocks
 * the coordinator's event consumer thread; failures are logged with the notification id.
 * </p>
 *
 * @author Matija Petanjek
 * @author Ivica Cardic
 */
@Component
public class EmailNotificationSender implements NotificationSender<EmailNotificationHandler> {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationSender.class);

    private final EmailNotificationClient emailNotificationClient;

    public EmailNotificationSender(EmailNotificationClient emailNotificationClient) {
        this.emailNotificationClient = emailNotificationClient;
    }

    @Override
    public Notification.Type getType() {
        return Notification.Type.EMAIL;
    }

    @Async
    @Override
    public void send(
        Notification notification, EmailNotificationHandler emailNotificationHandler,
        NotificationHandlerContext notificationHandlerContext) {

        Map<String, Object> settings = notification.getSettings();

        String email = (String) settings.get("email");

        if (email == null || email.isBlank()) {
            log.warn("Notification {} has no email address configured; skipping delivery", notification.getId());

            return;
        }

        try {
            emailNotificationClient.send(
                List.of(email), emailNotificationHandler.getSubject(notificationHandlerContext),
                emailNotificationHandler.getContent(notificationHandlerContext));
        } catch (RuntimeException exception) {
            log.error("Failed to deliver email notification {}", notification.getId(), exception);
        }
    }
}
