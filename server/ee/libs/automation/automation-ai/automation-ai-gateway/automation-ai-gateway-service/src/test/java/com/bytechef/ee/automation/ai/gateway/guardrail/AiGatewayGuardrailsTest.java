/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.gateway.guardrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatMessage;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatRole;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiGatewayGuardrailsTest {

    @Test
    void testRedactPiiReplacesCommonPatterns() {
        String redacted = AiGatewayGuardrails.redactPii(
            "Email me at jane.doe@example.com or call 415-555-0132. SSN 123-45-6789, card 4111 1111 1111 1111, " +
                "host 192.168.1.20.");

        assertThat(redacted).contains("[REDACTED_EMAIL]");
        assertThat(redacted).contains("[REDACTED_SSN]");
        assertThat(redacted).contains("[REDACTED_CC]");
        assertThat(redacted).contains("[REDACTED_PHONE]");
        assertThat(redacted).contains("[REDACTED_IP]");
        assertThat(redacted).doesNotContain("jane.doe@example.com");
        assertThat(redacted).doesNotContain("123-45-6789");
    }

    @Test
    void testRedactPiiLeavesCleanTextUnchanged() {
        String content = "Summarize the quarterly revenue report.";

        assertThat(AiGatewayGuardrails.redactPii(content)).isEqualTo(content);
    }

    @Test
    void testApplyRedactsMessageContentWhenEnabled() {
        AiGatewayGuardrails guardrails = new AiGatewayGuardrails(true, "");

        AiGatewayChatCompletionRequest request = requestOf("Contact bob@acme.io");

        AiGatewayChatCompletionRequest result = guardrails.apply(request);

        assertThat(result.messages()
            .getFirst()
            .content()).isEqualTo("Contact [REDACTED_EMAIL]");
    }

    @Test
    void testApplyRejectsBlockedTerm() {
        AiGatewayGuardrails guardrails = new AiGatewayGuardrails(false, "forbidden, secret-project");

        AiGatewayChatCompletionRequest request = requestOf("Tell me about the Secret-Project roadmap");

        assertThatExceptionOfType(AiGatewayGuardrailException.class).isThrownBy(() -> guardrails.apply(request));
    }

    @Test
    void testApplyReturnsSameRequestWhenAllGuardrailsDisabled() {
        AiGatewayGuardrails guardrails = new AiGatewayGuardrails(false, "");

        AiGatewayChatCompletionRequest request = requestOf("Contact bob@acme.io");

        assertThat(guardrails.apply(request)).isSameAs(request);
    }

    private static AiGatewayChatCompletionRequest requestOf(String content) {
        return new AiGatewayChatCompletionRequest(
            "gpt-4o", List.of(new AiGatewayChatMessage(AiGatewayChatRole.USER, content)), null, null, null, false,
            null, null);
    }
}
