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

package com.bytechef.platform.notification.delivery;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Plain-text notification email transport over the (optional) {@link JavaMailSender}. Exists so notification surfaces
 * that run in apps without the full {@code platform-mail} stack (e.g. the EE ai-gateway-app) still send through one
 * shared component; in the monolith the same {@link JavaMailSender} instance also underlies {@code MailService}, which
 * remains the async, templated path for user-account mail.
 *
 * <p>
 * Delivery is synchronous and failures throw, so alerting callers can record the error against the channel and surface
 * it to admins instead of losing it on an async executor.
 * </p>
 *
 * @author Ivica Cardic
 */
@Component
public class EmailNotificationClient {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationClient.class);

    private final @Nullable JavaMailSender javaMailSender;
    private final String mailFrom;

    @SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
    public EmailNotificationClient(
        ObjectProvider<JavaMailSender> javaMailSenderProvider,
        @Value("${spring.mail.username:no-reply@bytechef.io}") String mailFrom) {

        this.javaMailSender = javaMailSenderProvider.getIfAvailable();
        this.mailFrom = mailFrom;
    }

    /**
     * Sends a plain-text email to the recipients. Returns {@code false} (with a warning) when no mail sender is
     * configured or the recipient list is empty; throws {@link IllegalStateException} on SMTP failure.
     */
    public boolean send(List<String> recipients, String subject, String text) {
        if (javaMailSender == null) {
            log.warn("JavaMailSender is not configured; skipping notification email '{}'", subject);

            return false;
        }

        if (recipients.isEmpty()) {
            log.warn("No recipients configured for notification email '{}'", subject);

            return false;
        }

        SimpleMailMessage message = new SimpleMailMessage();

        message.setFrom(mailFrom);
        message.setTo(recipients.toArray(new String[0]));
        message.setSubject(subject);
        message.setText(text);

        try {
            javaMailSender.send(message);

            return true;
        } catch (MailException mailException) {
            throw new IllegalStateException(
                "Failed to send notification email to " + recipients, mailException);
        }
    }
}
