/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.aihub.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agui.core.state.State;
import com.bytechef.ee.automation.ai.gateway.service.WorkspaceAiGatewayProviderService;
import com.bytechef.ee.platform.ai.gateway.catalog.CatalogChatClientResolver;
import com.bytechef.ee.platform.ai.gateway.provider.AiGatewayChatModelFactory;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayProviderService;
import com.bytechef.ee.platform.aihub.util.AiHubStateKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Verifies that a user-selected catalog provider/model is resolved via {@link CatalogChatClientResolver} BEFORE the
 * existing AI-Gateway path.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class AiHubChatClientResolverCatalogTest {

    @Test
    void testCatalogSelectionResolvedBeforeGateway() {
        CatalogChatClientResolver catalogChatClientResolver = mock(CatalogChatClientResolver.class);
        ChatClient catalogChatClient = mock(ChatClient.class);

        when(catalogChatClientResolver.resolve(3, "ai.provider.openAi", "gpt-4o")).thenReturn(catalogChatClient);

        AiHubChatClientResolver resolver = new AiHubChatClientResolver(
            mock(WorkspaceAiGatewayProviderService.class), mock(AiGatewayProviderService.class),
            mock(AiGatewayChatModelFactory.class), catalogChatClientResolver);

        State state = new State();

        state.set(AiHubStateKeys.VERIFIED_WORKSPACE_ID, 10L);
        state.set(AiHubStateKeys.ENVIRONMENT_ID, 3);
        state.set(AiHubStateKeys.USER_SELECTED_LLM_PROVIDER_KEY, "ai.provider.openAi");
        state.set(AiHubStateKeys.USER_SELECTED_LLM_MODEL_KEY, "gpt-4o");

        assertThat(resolver.resolve(state)).isSameAs(catalogChatClient);
    }
}
