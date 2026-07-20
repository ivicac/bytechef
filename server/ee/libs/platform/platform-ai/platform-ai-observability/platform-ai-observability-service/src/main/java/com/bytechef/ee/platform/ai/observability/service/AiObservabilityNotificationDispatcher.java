/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.observability.service;

import com.bytechef.commons.util.JsonUtils;
import com.bytechef.ee.platform.ai.observability.domain.AiObservabilityAlertCondition;
import com.bytechef.ee.platform.ai.observability.domain.AiObservabilityAlertEvent;
import com.bytechef.ee.platform.ai.observability.domain.AiObservabilityAlertEventStatus;
import com.bytechef.ee.platform.ai.observability.domain.AiObservabilityAlertMetric;
import com.bytechef.ee.platform.ai.observability.domain.AiObservabilityAlertRule;
import com.bytechef.ee.platform.ai.observability.domain.AiObservabilityAlertRuleChannel;
import com.bytechef.ee.platform.ai.observability.domain.AiObservabilityNotificationChannel;
import com.bytechef.ee.platform.ai.observability.repository.AiObservabilityNotificationChannelRepository;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.notification.delivery.SlackNotificationClient;
import com.bytechef.platform.notification.delivery.WebhookDeliveryRequest;
import com.bytechef.platform.notification.delivery.WebhookNotificationClient;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * @version ee
 */
@Service
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
@SuppressFBWarnings("EI")
public class AiObservabilityNotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AiObservabilityNotificationDispatcher.class);

    private final AiObservabilityNotificationChannelRepository aiObservabilityNotificationChannelRepository;
    private final JavaMailSender javaMailSender;
    private final String mailFrom;
    private final SlackNotificationClient slackNotificationClient;
    private final WebhookNotificationClient webhookNotificationClient;

    AiObservabilityNotificationDispatcher(
        AiObservabilityNotificationChannelRepository aiObservabilityNotificationChannelRepository,
        @Autowired(required = false) JavaMailSender javaMailSender,
        @Value("${spring.mail.username:no-reply@bytechef.io}") String mailFrom,
        SlackNotificationClient slackNotificationClient, WebhookNotificationClient webhookNotificationClient) {

        this.aiObservabilityNotificationChannelRepository = aiObservabilityNotificationChannelRepository;
        this.javaMailSender = javaMailSender;
        this.mailFrom = mailFrom;
        this.slackNotificationClient = slackNotificationClient;
        this.webhookNotificationClient = webhookNotificationClient;
    }

    void dispatchTest(AiObservabilityNotificationChannel notificationChannel) {
        AiObservabilityAlertRule testRule = new AiObservabilityAlertRule(
            "Test Notification",
            AiObservabilityAlertMetric.ERROR_RATE, AiObservabilityAlertCondition.GREATER_THAN,
            BigDecimal.ZERO, 5, 0);
        AiObservabilityAlertEvent testEvent = new AiObservabilityAlertEvent(
            -1L, BigDecimal.ZERO,
            "Test notification from ByteChef AI Gateway for channel '" + notificationChannel.getName() + "'");

        // Mirror dispatch()'s failure persistence so admins clicking "Send Test" on a misconfigured channel see a
        // concrete error surfaced in the UI rather than a generic 500.
        try {
            switch (notificationChannel.getType()) {
                case WEBHOOK -> sendWebhookNotification(notificationChannel, testRule, testEvent);
                case EMAIL -> sendEmailNotification(notificationChannel, testRule, testEvent);
                case SLACK -> sendSlackNotification(notificationChannel, testRule, testEvent);

                default -> throw new IllegalStateException(
                    "Unsupported notification channel type: " + notificationChannel.getType());
            }

            if (notificationChannel.getLastError() != null) {
                notificationChannel.setLastError(null, null);

                aiObservabilityNotificationChannelRepository.save(notificationChannel);
            }
        } catch (Exception exception) {
            String message = exception.getClass()
                .getSimpleName() + ": " +
                (exception.getMessage() != null ? exception.getMessage() : "<no message>");

            notificationChannel.setLastError(message, Instant.now());

            aiObservabilityNotificationChannelRepository.save(notificationChannel);

            throw exception instanceof RuntimeException runtimeException
                ? runtimeException
                : new IllegalStateException(message, exception);
        }
    }

    public void dispatch(AiObservabilityAlertRule alertRule, AiObservabilityAlertEvent alertEvent) {
        Set<AiObservabilityAlertRuleChannel> channels = alertRule.getChannels();

        for (AiObservabilityAlertRuleChannel ruleChannel : channels) {
            AiObservabilityNotificationChannel notificationChannel = null;

            try {
                notificationChannel =
                    aiObservabilityNotificationChannelRepository.findById(ruleChannel.notificationChannelId())
                        .orElseThrow(() -> new IllegalArgumentException(
                            "AiObservabilityNotificationChannel not found with id: " +
                                ruleChannel.notificationChannelId()));

                if (!notificationChannel.isEnabled()) {
                    continue;
                }

                switch (notificationChannel.getType()) {
                    case WEBHOOK -> sendWebhookNotification(notificationChannel, alertRule, alertEvent);
                    case EMAIL -> sendEmailNotification(notificationChannel, alertRule, alertEvent);
                    case SLACK -> sendSlackNotification(notificationChannel, alertRule, alertEvent);

                    default -> log.warn(
                        "Unsupported notification channel type: {}", notificationChannel.getType());
                }

                // Successful delivery clears any prior failure so the UI badge disappears.
                if (notificationChannel.getLastError() != null) {
                    notificationChannel.setLastError(null, null);

                    aiObservabilityNotificationChannelRepository.save(notificationChannel);
                }
            } catch (Exception exception) {
                log.error(
                    "Failed to dispatch notification to channel {} for alert rule {}",
                    ruleChannel.notificationChannelId(), alertRule.getId(), exception);

                if (notificationChannel != null) {
                    // Persist the failure so admins see broken channels in the UI instead of having to trawl logs.
                    try {
                        String message = exception.getClass()
                            .getSimpleName() + ": " +
                            (exception.getMessage() != null ? exception.getMessage() : "<no message>");

                        notificationChannel.setLastError(message, Instant.now());

                        aiObservabilityNotificationChannelRepository.save(notificationChannel);
                    } catch (Exception persistException) {
                        log.warn(
                            "Failed to persist lastError for notification channel {}",
                            notificationChannel.getId(), persistException);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void sendWebhookNotification(
        AiObservabilityNotificationChannel notificationChannel,
        AiObservabilityAlertRule alertRule, AiObservabilityAlertEvent alertEvent) {

        Map<String, Object> config = parseChannelConfig(notificationChannel);

        String url = (String) config.get("url");

        String payload = JsonUtils.write(Map.of(
            "alertRuleId", alertRule.getId(),
            "alertRuleName", alertRule.getName(),
            "metric", alertRule.getMetric()
                .name(),
            "threshold", alertRule.getThreshold(),
            "triggeredValue", alertEvent.getTriggeredValue(),
            "message", alertEvent.getMessage(),
            "status", alertEvent.getStatus()
                .name()));

        Map<String, String> headers = (Map<String, String>) config.get("headers");

        // The shared client owns SSRF validation, standard headers, optional HMAC signing, and non-2xx -> exception
        // mapping; failures propagate so dispatch() records lastError.
        webhookNotificationClient.deliver(
            new WebhookDeliveryRequest(
                url, "ai-observability.alert", payload, headers == null ? Map.of() : headers,
                (String) config.get("secret")));
    }

    @SuppressWarnings("unchecked")
    @SuppressFBWarnings("VA_FORMAT_STRING_USES_NEWLINE")
    private void sendEmailNotification(
        AiObservabilityNotificationChannel notificationChannel,
        AiObservabilityAlertRule alertRule, AiObservabilityAlertEvent alertEvent) {

        if (javaMailSender == null) {
            log.warn(
                "JavaMailSender is not configured; skipping email notification for alert rule '{}'",
                alertRule.getName());

            return;
        }

        Map<String, Object> config = parseChannelConfig(notificationChannel);

        List<String> recipients = new ArrayList<>();
        Object recipientsValue = config.get("recipients");

        if (recipientsValue instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null) {
                    recipients.add(item.toString());
                }
            }
        } else if (recipientsValue instanceof String recipientsString && !recipientsString.isBlank()) {
            for (String part : recipientsString.split(",")) {
                String trimmed = part.trim();

                if (!trimmed.isEmpty()) {
                    recipients.add(trimmed);
                }
            }
        }

        Object toValue = config.get("to");

        if (toValue instanceof String toString && !toString.isBlank()) {
            for (String part : toString.split(",")) {
                String trimmed = part.trim();

                if (!trimmed.isEmpty()) {
                    recipients.add(trimmed);
                }
            }
        }

        if (recipients.isEmpty()) {
            log.warn(
                "No email recipients configured for notification channel {} (alert rule '{}')",
                notificationChannel.getId(), alertRule.getName());

            return;
        }

        boolean resolved = alertEvent.getStatus() == AiObservabilityAlertEventStatus.RESOLVED;
        String subjectPrefix = resolved ? "[ByteChef Alert RESOLVED]" : "[ByteChef Alert]";

        SimpleMailMessage message = new SimpleMailMessage();

        message.setFrom(mailFrom);
        message.setTo(recipients.toArray(new String[0]));
        message.setSubject(String.format("%s %s", subjectPrefix, alertRule.getName()));
        message.setText(String.format(
            "Alert: %s%nStatus: %s%n%nMessage: %s%nTriggered value: %s%nThreshold: %s%nMetric: %s%nTimestamp: %s%n",
            alertRule.getName(),
            alertEvent.getStatus()
                .name(),
            alertEvent.getMessage(),
            alertEvent.getTriggeredValue(),
            alertRule.getThreshold(),
            alertRule.getMetric()
                .name(),
            Instant.now()));

        // Propagate MailException so dispatch()'s catch records lastError — an SMTP rejection must not silently
        // drop the notification while the channel stays marked healthy.
        try {
            javaMailSender.send(message);
        } catch (org.springframework.mail.MailException mailException) {
            throw new IllegalStateException(
                "Failed to send email notification to " + recipients + " for alert rule '" + alertRule.getName() + "'",
                mailException);
        }
    }

    @SuppressWarnings("unchecked")
    @SuppressFBWarnings("VA_FORMAT_STRING_USES_NEWLINE")
    private void sendSlackNotification(
        AiObservabilityNotificationChannel notificationChannel,
        AiObservabilityAlertRule alertRule, AiObservabilityAlertEvent alertEvent) {

        Map<String, Object> config = parseChannelConfig(notificationChannel);

        String webhookUrl = (String) config.get("webhookUrl");

        boolean resolved = alertEvent.getStatus() == AiObservabilityAlertEventStatus.RESOLVED;
        String icon = resolved ? ":white_check_mark:" : ":rotating_light:";
        String statusLabel = resolved ? "RESOLVED" : "Alert";

        // The shared Slack client owns the payload shape, SSRF validation, and transport/non-2xx -> exception
        // mapping so dispatch() records lastError; only the alert-specific message text is composed here.
        slackNotificationClient.send(
            webhookUrl,
            String.format(
                "%s *%s: %s*\n%s\nTriggered value: %s",
                icon, statusLabel, alertRule.getName(), alertEvent.getMessage(),
                alertEvent.getTriggeredValue()));
    }

    /**
     * Parse a notification channel's JSON config with channel-id context in the error message so a malformed row
     * surfaces in lastError as a concrete diagnostic ("channel 42 has malformed config") instead of a generic Jackson
     * parse error.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseChannelConfig(AiObservabilityNotificationChannel notificationChannel) {
        try {
            return JsonUtils.read(notificationChannel.getConfig(), Map.class);
        } catch (Exception exception) {
            throw new IllegalStateException(
                "Notification channel " + notificationChannel.getId() + " has malformed config JSON", exception);
        }
    }
}
