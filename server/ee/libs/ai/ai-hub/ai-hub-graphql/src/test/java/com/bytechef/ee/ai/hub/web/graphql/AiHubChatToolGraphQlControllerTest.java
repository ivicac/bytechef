/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.web.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.domain.Workspace;
import com.bytechef.automation.configuration.facade.WorkspaceFacade;
import com.bytechef.ee.ai.hub.chat.AiHubChat;
import com.bytechef.ee.ai.hub.chat.AiHubChatComponent;
import com.bytechef.ee.ai.hub.chat.AiHubChatService;
import com.bytechef.ee.ai.hub.chat.AiHubChatToolBinding;
import com.bytechef.ee.ai.hub.chat.AiHubChatToolFacade;
import com.bytechef.ee.ai.hub.exception.ForbiddenException;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import com.bytechef.platform.user.domain.User;
import com.bytechef.platform.user.service.UserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubChatToolGraphQlControllerTest {

    @Test
    void testSetAiHubChatToolRequiresApprovalUpdatesFlagAndReturnsRereadBinding() {
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatToolFacade chatToolFacade = mock(AiHubChatToolFacade.class);
        ClusterElementDefinitionService clusterElementDefinitionService = mock(ClusterElementDefinitionService.class);
        ComponentDefinitionService componentDefinitionService = mock(ComponentDefinitionService.class);
        UserService userService = mock(UserService.class);
        WorkspaceFacade workspaceFacade = mock(WorkspaceFacade.class);

        User user = mock(User.class);

        when(user.getId()).thenReturn(10L);
        when(userService.getCurrentUser()).thenReturn(user);

        Workspace workspace7 = buildWorkspace(7L);

        when(workspaceFacade.getUserWorkspaces(10L)).thenReturn(List.of(workspace7));

        AiHubChat chat = mock(AiHubChat.class);

        when(chat.getId()).thenReturn(42L);
        when(chatService.list(7L, 10L, 0, null)).thenReturn(List.of(chat));
        when(chatService.getById(42L, 7L, 10L)).thenReturn(chat);

        AiHubChatToolBinding beforeFlip = new AiHubChatToolBinding(
            5L, 99L, 42L, "slack", 1, "sendMessage", 42L, 0, Map.of(), false);
        AiHubChatToolBinding afterFlip = new AiHubChatToolBinding(
            5L, 99L, 42L, "slack", 1, "sendMessage", 42L, 0, Map.of(), true);

        when(chatToolFacade.listChatTools(42L)).thenReturn(List.of(beforeFlip), List.of(afterFlip));

        AiHubChatToolGraphQlController controller = new AiHubChatToolGraphQlController(
            chatService, chatToolFacade, clusterElementDefinitionService, componentDefinitionService, userService,
            workspaceFacade);

        AiHubChatToolBinding result = controller.setAiHubChatToolRequiresApproval(7L, 5L, true);

        verify(chatToolFacade).setToolRequiresApproval(5L, true);
        assertThat(result.requiresApproval()).isTrue();
    }

    @Test
    void testSetAiHubChatToolRequiresApprovalRejectsCallerOutsideWorkspace() {
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatToolFacade chatToolFacade = mock(AiHubChatToolFacade.class);
        ClusterElementDefinitionService clusterElementDefinitionService = mock(ClusterElementDefinitionService.class);
        ComponentDefinitionService componentDefinitionService = mock(ComponentDefinitionService.class);
        UserService userService = mock(UserService.class);
        WorkspaceFacade workspaceFacade = mock(WorkspaceFacade.class);

        User user = mock(User.class);

        when(user.getId()).thenReturn(10L);
        when(userService.getCurrentUser()).thenReturn(user);

        Workspace foreignWorkspace = buildWorkspace(99L);

        when(workspaceFacade.getUserWorkspaces(10L)).thenReturn(List.of(foreignWorkspace));

        AiHubChatToolGraphQlController controller = new AiHubChatToolGraphQlController(
            chatService, chatToolFacade, clusterElementDefinitionService, componentDefinitionService, userService,
            workspaceFacade);

        assertThatThrownBy(() -> controller.setAiHubChatToolRequiresApproval(7L, 5L, true))
            .isInstanceOf(ForbiddenException.class);

        verify(chatToolFacade, never()).setToolRequiresApproval(5L, true);
    }

    @Test
    void testSetAiHubUserConnectorToolRequiresApprovalUpdatesOwnedConnectorTool() {
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatToolFacade chatToolFacade = mock(AiHubChatToolFacade.class);
        ClusterElementDefinitionService clusterElementDefinitionService = mock(ClusterElementDefinitionService.class);
        ComponentDefinitionService componentDefinitionService = mock(ComponentDefinitionService.class);
        UserService userService = mock(UserService.class);
        WorkspaceFacade workspaceFacade = mock(WorkspaceFacade.class);

        User user = mock(User.class);

        when(user.getId()).thenReturn(10L);
        when(userService.getCurrentUser()).thenReturn(user);

        Workspace workspace7 = buildWorkspace(7L);

        when(workspaceFacade.getUserWorkspaces(10L)).thenReturn(List.of(workspace7));

        AiHubChatComponent ownedConnector = new AiHubChatComponent();

        ownedConnector.setId(55L);

        when(chatToolFacade.listUserComponents(10L, 7L)).thenReturn(List.of(ownedConnector));

        AiHubChatToolGraphQlController controller = new AiHubChatToolGraphQlController(
            chatService, chatToolFacade, clusterElementDefinitionService, componentDefinitionService, userService,
            workspaceFacade);

        boolean result = controller.setAiHubUserConnectorToolRequiresApproval(7L, 55L, "sendMessage", true);

        assertThat(result).isTrue();
        verify(chatToolFacade).setToolRequiresApproval(55L, "sendMessage", true);
    }

    @Test
    void testSetAiHubUserConnectorToolRequiresApprovalReturnsFalseForUnownedConnector() {
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatToolFacade chatToolFacade = mock(AiHubChatToolFacade.class);
        ClusterElementDefinitionService clusterElementDefinitionService = mock(ClusterElementDefinitionService.class);
        ComponentDefinitionService componentDefinitionService = mock(ComponentDefinitionService.class);
        UserService userService = mock(UserService.class);
        WorkspaceFacade workspaceFacade = mock(WorkspaceFacade.class);

        User user = mock(User.class);

        when(user.getId()).thenReturn(10L);
        when(userService.getCurrentUser()).thenReturn(user);

        Workspace workspace7 = buildWorkspace(7L);

        when(workspaceFacade.getUserWorkspaces(10L)).thenReturn(List.of(workspace7));
        when(chatToolFacade.listUserComponents(10L, 7L)).thenReturn(List.of());

        AiHubChatToolGraphQlController controller = new AiHubChatToolGraphQlController(
            chatService, chatToolFacade, clusterElementDefinitionService, componentDefinitionService, userService,
            workspaceFacade);

        boolean result = controller.setAiHubUserConnectorToolRequiresApproval(7L, 55L, "sendMessage", true);

        assertThat(result).isFalse();
        verify(chatToolFacade, never()).setToolRequiresApproval(55L, "sendMessage", true);
    }

    private static Workspace buildWorkspace(long id) {
        Workspace workspace = mock(Workspace.class);

        when(workspace.getId()).thenReturn(id);

        return workspace;
    }
}
