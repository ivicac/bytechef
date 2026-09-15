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

package com.bytechef.platform.webhook.web.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.bytechef.platform.security.web.authentication.AbstractApiKeyAuthenticationToken;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

/**
 * Pins the voice token mint to the same gate the editor's own workflow test run uses
 * ({@code WorkflowTestApiController#startWorkflowTest}): the caller must hold {@code WORKFLOW_EDIT} on the workflow in
 * the environment the token will be bound to. The SpEL function's semantics are pinned beside its implementation, in
 * {@code AutomationMethodSecurityExpressionRootTest}; this test pins the wiring.
 *
 * @author Ivica Cardic
 */
class WorkflowTestVoiceSessionTokenControllerTest {

    private static final long DEVELOPMENT_ORDINAL = 0L;
    private static final long PRODUCTION_ORDINAL = 2L;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * The gate authorizes the principal's effective environment, so the token must be bound to that same environment: a
     * principal confined to PRODUCTION asking for DEVELOPMENT is authorized for PRODUCTION and gets a PRODUCTION token.
     */
    @Test
    void testConfinedPrincipalGetsATokenBoundToItsOwnEnvironment() {
        WorkflowTestVoiceSessionTokenService tokenService = mock(WorkflowTestVoiceSessionTokenService.class);

        SecurityContextHolder.getContext()
            .setAuthentication(
                new ConfinedApiKeyAuthenticationToken(PRODUCTION_ORDINAL, new User("connected-user-1", "", List.of())));

        new WorkflowTestVoiceSessionTokenController(tokenService).issueToken("wf-1", DEVELOPMENT_ORDINAL);

        verify(tokenService).issue("wf-1", PRODUCTION_ORDINAL);
    }

    @Test
    void testSessionPrincipalGetsATokenBoundToTheRequestedEnvironment() {
        WorkflowTestVoiceSessionTokenService tokenService = mock(WorkflowTestVoiceSessionTokenService.class);

        new WorkflowTestVoiceSessionTokenController(tokenService).issueToken("wf-1", DEVELOPMENT_ORDINAL);

        verify(tokenService).issue("wf-1", DEVELOPMENT_ORDINAL);
    }

    @Test
    void testMintRequiresWorkflowEditInTheRequestedEnvironment() throws NoSuchMethodException {
        Method issueToken = WorkflowTestVoiceSessionTokenController.class.getMethod(
            "issueToken", String.class, Long.class);

        PreAuthorize preAuthorize = issueToken.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize)
            .as("@PreAuthorize on issueToken")
            .isNotNull();
        assertThat(preAuthorize.value())
            .isEqualTo("hasWorkflowScopeInEnvironment(#workflowId, 'WORKFLOW_EDIT', #environmentId)");
    }

    private static final class ConfinedApiKeyAuthenticationToken extends AbstractApiKeyAuthenticationToken {

        private ConfinedApiKeyAuthenticationToken(long environmentId, User user) {
            super(environmentId, user);
        }
    }
}
