/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.gateway.guardrail;

import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatMessage;
import com.bytechef.ee.platform.ai.gateway.exception.AiGatewayGuardrailException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Applies inline content guardrails to an AI Gateway request before it is routed upstream:
 *
 * <ul>
 * <li><b>PII redaction</b> — when {@code bytechef.ai.gateway.guardrails.pii-redaction-enabled} is set, message content
 * is scanned for common personally identifiable information (email, US SSN, credit-card number, phone number, IPv4
 * address) and each match is replaced with a {@code [REDACTED_*]} placeholder before the prompt leaves ByteChef.</li>
 * <li><b>Blocked terms</b> — when {@code bytechef.ai.gateway.guardrails.blocked-terms} lists one or more terms, any
 * request whose message content contains one (case-insensitive) is rejected with an
 * {@link AiGatewayGuardrailException}.</li>
 * </ul>
 *
 * <p>
 * Both are off by default. Redaction runs before the blocked-term check so a term is matched against the redacted text.
 * The redactor is deterministic and side-effect-free, so it is safe to run on every message of every request.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
public class AiGatewayGuardrails {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayGuardrails.class);

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern SSN_PATTERN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile("\\b(?:\\d{4}[ -]?){3}\\d{4}\\b");
    // Linear, backtracking-safe: a 3-3-4 grouping with a single required separator between groups (no nested optional
    // quantifiers, so no catastrophic backtracking / ReDoS).
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\b\\d{3}[-.\\s]\\d{3}[-.\\s]\\d{4}\\b");
    private static final Pattern IPV4_PATTERN = Pattern.compile(
        "\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b");

    private final List<String> blockedTerms;
    private final boolean piiRedactionEnabled;

    public AiGatewayGuardrails(
        @Value("${bytechef.ai.gateway.guardrails.pii-redaction-enabled:false}") boolean piiRedactionEnabled,
        @Value("${bytechef.ai.gateway.guardrails.blocked-terms:}") String blockedTerms) {

        this.blockedTerms = parseBlockedTerms(blockedTerms);
        this.piiRedactionEnabled = piiRedactionEnabled;
    }

    /**
     * Returns the request with guardrails applied: PII redacted (when enabled) and a blocked-term violation raised
     * (when configured). Returns the request unchanged when no guardrail is active.
     *
     * @param request the inbound chat-completion request
     * @return the guardrailed request
     * @throws AiGatewayGuardrailException if a message contains a blocked term
     */
    public AiGatewayChatCompletionRequest apply(AiGatewayChatCompletionRequest request) {
        if (!piiRedactionEnabled && blockedTerms.isEmpty()) {
            return request;
        }

        List<AiGatewayChatMessage> guardrailedMessages = new ArrayList<>();

        for (AiGatewayChatMessage message : request.messages()) {
            String content = message.content();

            if (content != null) {
                if (piiRedactionEnabled) {
                    content = redactPii(content);
                }

                checkBlockedTerms(content);
            }

            guardrailedMessages.add(
                new AiGatewayChatMessage(
                    message.role(), content, message.contentBlocks(), message.toolCalls(), message.toolCallId()));
        }

        return new AiGatewayChatCompletionRequest(
            request.model(), guardrailedMessages, request.temperature(), request.maxTokens(), request.topP(),
            request.stream(), request.routingPolicy(), request.cache(), request.toolChoice(), request.tools(),
            request.tags());
    }

    /**
     * Replaces common PII patterns in {@code content} with {@code [REDACTED_*]} placeholders.
     */
    public static String redactPii(@Nullable String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        String redacted = EMAIL_PATTERN.matcher(content)
            .replaceAll("[REDACTED_EMAIL]");

        redacted = SSN_PATTERN.matcher(redacted)
            .replaceAll("[REDACTED_SSN]");
        redacted = CREDIT_CARD_PATTERN.matcher(redacted)
            .replaceAll("[REDACTED_CC]");
        redacted = PHONE_PATTERN.matcher(redacted)
            .replaceAll("[REDACTED_PHONE]");
        redacted = IPV4_PATTERN.matcher(redacted)
            .replaceAll("[REDACTED_IP]");

        return redacted;
    }

    private void checkBlockedTerms(String content) {
        String lowerContent = content.toLowerCase(Locale.ROOT);

        for (String blockedTerm : blockedTerms) {
            if (lowerContent.contains(blockedTerm)) {
                log.warn("AI Gateway request rejected by content guardrail (blocked term matched)");

                throw new AiGatewayGuardrailException(
                    "Request rejected by content guardrail: matched a blocked term");
            }
        }
    }

    private static List<String> parseBlockedTerms(String blockedTerms) {
        if (StringUtils.isBlank(blockedTerms)) {
            return List.of();
        }

        List<String> terms = new ArrayList<>();

        for (String term : blockedTerms.split(",")) {
            String trimmed = term.strip();

            if (!trimmed.isEmpty()) {
                terms.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }

        return terms;
    }
}
