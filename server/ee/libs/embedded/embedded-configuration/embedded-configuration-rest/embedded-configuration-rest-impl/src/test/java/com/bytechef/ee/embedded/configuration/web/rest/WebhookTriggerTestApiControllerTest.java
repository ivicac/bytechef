/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.embedded.configuration.facade.WebhookTriggerTestAdminFacade;
import com.bytechef.platform.security.web.authentication.AbstractApiKeyAuthenticationToken;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class WebhookTriggerTestApiControllerTest {

    private static final long DEVELOPMENT_ORDINAL = 0L;
    private static final long PRODUCTION_ORDINAL = 2L;

    private WebhookTriggerTestApiController controller;
    private WebhookTriggerTestAdminFacade webhookTriggerTestAdminFacade;

    @BeforeEach
    void setUp() {
        webhookTriggerTestAdminFacade = mock(WebhookTriggerTestAdminFacade.class);
        controller = new WebhookTriggerTestApiController(webhookTriggerTestAdminFacade);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testStartWebhookTriggerTestUsesConfinedPrincipalEnvironmentAtExecution() {
        authenticate(new TestApiKeyAuthenticationToken(PRODUCTION_ORDINAL, user()));

        when(webhookTriggerTestAdminFacade.startWebhookTriggerTest(anyString(), isNull(), anyLong()))
            .thenReturn("https://example.org/webhook");

        controller.startWebhookTriggerTest("workflow-1", DEVELOPMENT_ORDINAL, null);

        ArgumentCaptor<Long> environmentIdCaptor = ArgumentCaptor.forClass(Long.class);

        verify(webhookTriggerTestAdminFacade).startWebhookTriggerTest(
            eq("workflow-1"), isNull(), environmentIdCaptor.capture());

        assertThat(environmentIdCaptor.getValue()).isEqualTo(PRODUCTION_ORDINAL);
    }

    @Test
    void testStartWebhookTriggerTestHonoursSessionPrincipalRequestedEnvironment() {
        authenticate(new UsernamePasswordAuthenticationToken("admin@localhost.com", "n/a", List.of()));

        when(webhookTriggerTestAdminFacade.startWebhookTriggerTest(anyString(), isNull(), anyLong()))
            .thenReturn("https://example.org/webhook");

        controller.startWebhookTriggerTest("workflow-1", DEVELOPMENT_ORDINAL, null);

        ArgumentCaptor<Long> environmentIdCaptor = ArgumentCaptor.forClass(Long.class);

        verify(webhookTriggerTestAdminFacade).startWebhookTriggerTest(
            eq("workflow-1"), isNull(), environmentIdCaptor.capture());

        assertThat(environmentIdCaptor.getValue()).isEqualTo(DEVELOPMENT_ORDINAL);
    }

    @Test
    void testStopWebhookTriggerTestUsesConfinedPrincipalEnvironmentAtExecution() {
        authenticate(new TestApiKeyAuthenticationToken(PRODUCTION_ORDINAL, user()));

        controller.stopWebhookTriggerTest("workflow-1", DEVELOPMENT_ORDINAL, null);

        ArgumentCaptor<Long> environmentIdCaptor = ArgumentCaptor.forClass(Long.class);

        verify(webhookTriggerTestAdminFacade).stopWebhookTriggerTest(
            eq("workflow-1"), isNull(), environmentIdCaptor.capture());

        assertThat(environmentIdCaptor.getValue()).isEqualTo(PRODUCTION_ORDINAL);
    }

    @Test
    void testStopWebhookTriggerTestHonoursSessionPrincipalRequestedEnvironment() {
        authenticate(new UsernamePasswordAuthenticationToken("admin@localhost.com", "n/a", List.of()));

        controller.stopWebhookTriggerTest("workflow-1", DEVELOPMENT_ORDINAL, null);

        ArgumentCaptor<Long> environmentIdCaptor = ArgumentCaptor.forClass(Long.class);

        verify(webhookTriggerTestAdminFacade).stopWebhookTriggerTest(
            eq("workflow-1"), isNull(), environmentIdCaptor.capture());

        assertThat(environmentIdCaptor.getValue()).isEqualTo(DEVELOPMENT_ORDINAL);
    }

    private static void authenticate(Authentication authentication) {
        SecurityContextHolder.getContext()
            .setAuthentication(authentication);
    }

    private static User user() {
        return new User("connected-user-1", "", List.of());
    }

    private static final class TestApiKeyAuthenticationToken extends AbstractApiKeyAuthenticationToken {

        private TestApiKeyAuthenticationToken(long environmentId, User user) {
            super(environmentId, user);
        }
    }
}
