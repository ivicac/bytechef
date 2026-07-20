/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.gateway.guardrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.ee.automation.ai.gateway.service.AiGatewayWorkspaceSettingsService;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayWorkspaceSettings;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatMessage;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatRole;
import com.bytechef.ee.platform.ai.gateway.exception.AiGatewayGuardrailException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiGatewayGuardrailsTest {

    private final AiGatewayWorkspaceSettingsService settingsService = mock(AiGatewayWorkspaceSettingsService.class);

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
    void testApplyRedactsMessageContentWhenGloballyEnabled() {
        AiGatewayGuardrails guardrails = new AiGatewayGuardrails(settingsService, null, true, "", false);

        AiGatewayChatCompletionRequest result = guardrails.apply(requestOf("Contact bob@acme.io"), null);

        assertThat(result.messages()
            .getFirst()
            .content()).isEqualTo("Contact [REDACTED_EMAIL]");
    }

    @Test
    void testApplyRedactsWhenWorkspaceSettingEnablesIt() {
        AiGatewayGuardrails guardrails = new AiGatewayGuardrails(settingsService, null, false, "", false);

        when(settingsService.findByWorkspaceId(7L)).thenReturn(Optional.of(settings(true, null, null)));

        AiGatewayChatCompletionRequest result = guardrails.apply(requestOf("Contact bob@acme.io"), 7L);

        assertThat(result.messages()
            .getFirst()
            .content()).isEqualTo("Contact [REDACTED_EMAIL]");
    }

    @Test
    void testApplyRejectsGlobalBlockedTerm() {
        AiGatewayGuardrails guardrails =
            new AiGatewayGuardrails(settingsService, null, false, "forbidden, secret-project", false);

        assertThatExceptionOfType(AiGatewayGuardrailException.class).isThrownBy(
            () -> guardrails.apply(requestOf("Tell me about the Secret-Project roadmap"), null));
    }

    @Test
    void testApplyRejectsWorkspaceBlockedTerm() {
        AiGatewayGuardrails guardrails = new AiGatewayGuardrails(settingsService, null, false, "", false);

        when(settingsService.findByWorkspaceId(7L)).thenReturn(Optional.of(settings(null, "classified", null)));

        assertThatExceptionOfType(AiGatewayGuardrailException.class).isThrownBy(
            () -> guardrails.apply(requestOf("Summarize the CLASSIFIED memo"), 7L));
    }

    @Test
    void testApplyRejectsContentFlaggedByModeration() {
        AiGatewayGuardrails guardrails = new AiGatewayGuardrails(settingsService, content -> true, false, "", false);

        when(settingsService.findByWorkspaceId(7L)).thenReturn(Optional.of(settings(null, null, true)));

        assertThatExceptionOfType(AiGatewayGuardrailException.class).isThrownBy(
            () -> guardrails.apply(requestOf("anything"), 7L));
    }

    @Test
    void testApplySkipsModerationWithoutClassifier() {
        AiGatewayGuardrails guardrails = new AiGatewayGuardrails(settingsService, null, false, "", true);

        AiGatewayChatCompletionRequest request = requestOf("anything");

        assertThat(guardrails.apply(request, null)).isSameAs(request);
    }

    @Test
    void testApplyReturnsSameRequestWhenAllGuardrailsDisabled() {
        AiGatewayGuardrails guardrails = new AiGatewayGuardrails(settingsService, null, false, "", false);

        AiGatewayChatCompletionRequest request = requestOf("Contact bob@acme.io");

        assertThat(guardrails.apply(request, null)).isSameAs(request);
    }

    private static AiGatewayWorkspaceSettings settings(
        Boolean redactPii, String blockedTerms, Boolean moderationEnabled) {

        return new AiGatewayWorkspaceSettings(
            7L, null, null, null, null, null, null, null, redactPii, blockedTerms, moderationEnabled);
    }

    private static AiGatewayChatCompletionRequest requestOf(String content) {
        return new AiGatewayChatCompletionRequest(
            "gpt-4o", List.of(new AiGatewayChatMessage(AiGatewayChatRole.USER, content)), null, null, null, false,
            null, null);
    }
}
