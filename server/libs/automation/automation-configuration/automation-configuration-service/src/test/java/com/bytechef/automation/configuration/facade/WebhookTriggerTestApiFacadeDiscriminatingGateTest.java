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

package com.bytechef.automation.configuration.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.security.AutomationMethodSecurityConfiguration;
import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.facade.WebhookTriggerTestFacade;
import com.bytechef.platform.constant.PlatformType;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Discriminating pair for D2's substituting expression, {@code hasWorkflowScopeInEnvironment(...)}, pinned on
 * {@link WebhookTriggerTestApiFacadeImpl#enableTrigger}. A single member fixture holding {@code WORKFLOW_EDIT} in
 * DEVELOPMENT only is permitted when the call names DEVELOPMENT and denied when it names PRODUCTION, without resetting
 * the fixture between the two calls -- proving the per-environment branch is real, not merely reachable, and that it is
 * the NAMED environment being checked rather than a constant the gate would pass either way.
 * <p>
 * This module carries the real {@code AutomationMethodSecurityExpressionRoot} (via
 * {@link AutomationMethodSecurityConfiguration}), so this test exercises it directly with a mocked
 * {@link PermissionService} rather than standing in with a fake root the way the platform-module tests must --
 * {@code enableTrigger}'s own body calls {@code PrincipalEnvironment.resolveEffectiveEnvironmentId(environmentId)}, the
 * same resolution {@code hasWorkflowScopeInEnvironment(...)} performs for a confined principal, so gate and body never
 * diverge; here the authenticated principal is an ordinary session member, for whom that resolution is a no-op and the
 * requested environment is honoured directly.
 *
 * @author Ivica Cardic
 */
@SpringBootTest(classes = WebhookTriggerTestApiFacadeDiscriminatingGateTest.Config.class)
class WebhookTriggerTestApiFacadeDiscriminatingGateTest {

    private static final long DEVELOPMENT_ORDINAL = 0L;
    private static final long PRODUCTION_ORDINAL = 2L;
    private static final String WORKFLOW_ID = "workflow-1";

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private WebhookTriggerTestApiFacade webhookTriggerTestApiFacade;

    @Autowired
    private WebhookTriggerTestFacade webhookTriggerTestFacade;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(
                "user@localhost.com", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testEnableTriggerPermitsDevelopmentAndDeniesProductionForSameMember() {
        when(permissionService.hasWorkflowScope(WORKFLOW_ID, "WORKFLOW_EDIT", Environment.DEVELOPMENT))
            .thenReturn(true);
        when(permissionService.hasWorkflowScope(WORKFLOW_ID, "WORKFLOW_EDIT", Environment.PRODUCTION))
            .thenReturn(false);
        when(webhookTriggerTestFacade.enableTrigger(WORKFLOW_ID, DEVELOPMENT_ORDINAL, PlatformType.AUTOMATION))
            .thenReturn("https://example.org/webhook");

        String url = webhookTriggerTestApiFacade.enableTrigger(WORKFLOW_ID, DEVELOPMENT_ORDINAL);

        assertThat(url).isEqualTo("https://example.org/webhook");

        assertThatThrownBy(() -> webhookTriggerTestApiFacade.enableTrigger(WORKFLOW_ID, PRODUCTION_ORDINAL))
            .isInstanceOf(AccessDeniedException.class);
    }

    @SpringBootConfiguration
    @EnableMethodSecurity
    @ImportAutoConfiguration(AutomationMethodSecurityConfiguration.class)
    @Import(WebhookTriggerTestApiFacadeImpl.class)
    static class Config {

        @Bean("permissionService")
        PermissionService permissionService() {
            return mock(PermissionService.class);
        }

        @Bean
        WebhookTriggerTestFacade webhookTriggerTestFacade() {
            return mock(WebhookTriggerTestFacade.class);
        }
    }
}
