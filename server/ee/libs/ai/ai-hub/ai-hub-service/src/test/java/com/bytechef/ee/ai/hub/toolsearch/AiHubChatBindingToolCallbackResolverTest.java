/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.toolsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.ai.hub.chat.AiHubChat;
import com.bytechef.ee.ai.hub.chat.AiHubChatService;
import com.bytechef.ee.ai.hub.chat.AiHubChatToolBinding;
import com.bytechef.ee.ai.hub.chat.AiHubChatToolFacade;
import com.bytechef.ee.ai.hub.mcpserver.AiHubMcpToolCallbackProvider;
import com.bytechef.ee.ai.hub.skill.AiHubSkillsToolProvider;
import com.bytechef.ee.ai.hub.tool.AiHubToolInvocationContext;
import com.bytechef.platform.component.domain.ClusterElementDefinition;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.connection.service.ConnectionService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubChatBindingToolCallbackResolverTest {

    @Test
    void testResolveReturnsEmptyWhenNoThreadId() {
        AiHubChatBindingToolCallbackResolver resolver = newResolver(
            mock(AiHubChatService.class), mock(AiHubChatToolFacade.class),
            mock(ClusterElementDefinitionService.class), mock(ConnectionService.class));

        // Missing thread id (anonymous request, broken context propagation) — must NOT throw and must NOT
        // try to look up a chat. Defensive: this is the most common "context-less" path during
        // initial agent boot or test wiring.
        AiHubToolInvocationContext bareContext = new AiHubToolInvocationContext(
            1L, 10L, (short) 0, "x", 0L, null);

        assertThat(resolver.resolve(bareContext)).isEmpty();
    }

    @Test
    void testResolveReturnsEmptyWhenChatNotFound() {
        AiHubChatService chatService = mock(AiHubChatService.class);
        when(chatService.findByThreadId("missing-thread")).thenReturn(Optional.empty());

        AiHubChatBindingToolCallbackResolver resolver = newResolver(
            chatService, mock(AiHubChatToolFacade.class),
            mock(ClusterElementDefinitionService.class), mock(ConnectionService.class));

        AiHubToolInvocationContext context = new AiHubToolInvocationContext(
            1L, 10L, (short) 0, "x", 0L, "missing-thread");

        // Race condition: thread id is in the agent context but the chat row was deleted in between.
        // Pre-v1 of this class would NPE on chat.get().getId(); regression-pin the empty-list return.
        assertThat(resolver.resolve(context)).isEmpty();
    }

    @Test
    void testResolveBuildsCallbackPerBindingWithPinnedConnectionAndParameters() {
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatToolFacade chatToolFacade = mock(AiHubChatToolFacade.class);
        ClusterElementDefinitionService clusterElementDefinitionService = mock(ClusterElementDefinitionService.class);
        ConnectionService connectionService = mock(ConnectionService.class);

        AiHubChat chat = mock(AiHubChat.class);
        when(chat.getId()).thenReturn(7L);
        when(chatService.findByThreadId("thread-1")).thenReturn(Optional.of(chat));

        AiHubChatToolBinding sendBinding = new AiHubChatToolBinding(
            11L, 99L, 7L, "slack", 1, "sendMessage", 42L, 0,
            Map.of("channel", "#engineering"), false);
        AiHubChatToolBinding postBinding = new AiHubChatToolBinding(
            12L, 99L, 7L, "github", 1, "createIssue", 50L, 0,
            Map.of("repo", "acme/site"), false);

        when(chatToolFacade.listChatTools(7L)).thenReturn(List.of(sendBinding, postBinding));

        ClusterElementDefinition slackDef = mock(ClusterElementDefinition.class);
        when(slackDef.getDescription()).thenReturn("Send a message to a Slack channel");
        when(slackDef.getTitle()).thenReturn("Slack: Send Message");
        when(slackDef.getProperties()).thenReturn(List.of());

        ClusterElementDefinition githubDef = mock(ClusterElementDefinition.class);
        when(githubDef.getDescription()).thenReturn("Open a new GitHub issue");
        when(githubDef.getTitle()).thenReturn("GitHub: Create Issue");
        when(githubDef.getProperties()).thenReturn(List.of());

        when(clusterElementDefinitionService.getClusterElementDefinition(eq("slack"), eq(1), eq("sendMessage")))
            .thenReturn(slackDef);
        when(clusterElementDefinitionService.getClusterElementDefinition(eq("github"), eq(1), eq("createIssue")))
            .thenReturn(githubDef);

        AiHubChatBindingToolCallbackResolver resolver = newResolver(
            chatService, chatToolFacade, clusterElementDefinitionService, connectionService);

        AiHubToolInvocationContext context = new AiHubToolInvocationContext(
            1L, 10L, (short) 0, "x", 0L, "thread-1");

        List<ToolCallback> callbacks = resolver.resolve(context);

        // Both bindings must produce a callback with the LLM-visible name shape that the search-discovery
        // path also uses — keeping the chat agent's tool-name model consistent across discovery vs attach.
        assertThat(callbacks).hasSize(2);
        assertThat(callbacks.get(0)
            .getToolDefinition()
            .name()).isEqualTo("slack_sendMessage");
        assertThat(callbacks.get(1)
            .getToolDefinition()
            .name()).isEqualTo("github_createIssue");

        // Description format mirrors the search-discovery formatter ("title: description") so chat traces
        // look consistent regardless of which path attached the tool.
        assertThat(callbacks.get(0)
            .getToolDefinition()
            .description()).isEqualTo("Slack: Send Message: Send a message to a Slack channel");
    }

    @Test
    void testResolveSkipsBindingsWhoseClusterElementVanishedFromCatalog() {
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatToolFacade chatToolFacade = mock(AiHubChatToolFacade.class);
        ClusterElementDefinitionService clusterElementDefinitionService = mock(ClusterElementDefinitionService.class);
        ConnectionService connectionService = mock(ConnectionService.class);

        AiHubChat chat = mock(AiHubChat.class);
        when(chat.getId()).thenReturn(7L);
        when(chatService.findByThreadId("thread-1")).thenReturn(Optional.of(chat));

        // One healthy binding + one whose cluster element was removed from the catalog (e.g. component author
        // dropped the action between attach time and now). Resolver must register the surviving one and skip
        // the dead one — failing the whole turn on a single dead binding would break the entire chat.
        AiHubChatToolBinding aliveBinding = new AiHubChatToolBinding(
            11L, 99L, 7L, "slack", 1, "sendMessage", 42L, 0, Map.of(), false);
        AiHubChatToolBinding deadBinding = new AiHubChatToolBinding(
            12L, 100L, 7L, "deprecated-component", 1, "removed-action", 50L, 0, Map.of(), false);

        when(chatToolFacade.listChatTools(7L)).thenReturn(List.of(aliveBinding, deadBinding));

        ClusterElementDefinition aliveDef = mock(ClusterElementDefinition.class);
        when(aliveDef.getDescription()).thenReturn("Send a message");
        when(aliveDef.getTitle()).thenReturn("Slack");
        when(aliveDef.getProperties()).thenReturn(List.of());

        when(clusterElementDefinitionService.getClusterElementDefinition(eq("slack"), eq(1), eq("sendMessage")))
            .thenReturn(aliveDef);
        when(clusterElementDefinitionService.getClusterElementDefinition(
            eq("deprecated-component"), eq(1), eq("removed-action")))
                .thenThrow(new RuntimeException("Cluster element not found"));

        AiHubChatBindingToolCallbackResolver resolver = newResolver(
            chatService, chatToolFacade, clusterElementDefinitionService, connectionService);

        AiHubToolInvocationContext context = new AiHubToolInvocationContext(
            1L, 10L, (short) 0, "x", 0L, "thread-1");

        List<ToolCallback> callbacks = resolver.resolve(context);

        assertThat(callbacks).hasSize(1);
        assertThat(callbacks.getFirst()
            .getToolDefinition()
            .name()).isEqualTo("slack_sendMessage");
    }

    /**
     * A connector the user turned off IN THIS CHAT must contribute no tools, while the rest of their globally-added
     * connectors keep working.
     *
     * <p>
     * The subtraction has to be explicit in the resolver, which is what this pins. The chat-scoped row and the
     * user-global row are independent rows on {@code ai_hub_chat_component}: disabling the chat-scoped one removes it
     * from {@code listChatTools} only, and the user-global tools would otherwise still stream in through
     * {@code listUserTools} — the toggle would look like it worked and change nothing the agent sees.
     * </p>
     *
     * <p>
     * The invocation context's sender matches the chat's owner (both id 1), so the owner-vs-participant gate passes and
     * this test exercises the disabled-connector subtraction alone;
     * {@link #testResolveOmitsUserConnectorsForANonOwnerSender} covers the gate itself.
     * </p>
     */
    @Test
    void testResolveDropsUserConnectorsTheChatSwitchedOff() {
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatToolFacade chatToolFacade = mock(AiHubChatToolFacade.class);
        ClusterElementDefinitionService clusterElementDefinitionService = mock(ClusterElementDefinitionService.class);
        ConnectionService connectionService = mock(ConnectionService.class);

        AiHubChat chat = mock(AiHubChat.class);
        when(chat.getId()).thenReturn(7L);
        // The resolver takes the user and workspace from the CHAT ROW, not from the invocation context — the
        // context's ids are the caller's, and a chat is resolved by thread id alone.
        when(chat.getUserId()).thenReturn(1L);
        when(chatService.findByThreadId("thread-1")).thenReturn(Optional.of(chat));
        when(chatService.getWorkspaceId(7L)).thenReturn(10L);

        // Chat-scoped bindings: none. Everything the agent would see here comes from the user's connectors.
        when(chatToolFacade.listChatTools(7L)).thenReturn(List.of());

        AiHubChatToolBinding slackBinding = new AiHubChatToolBinding(
            11L, 99L, 0L, "slack", 1, "sendMessage", 42L, 0, Map.of(), false);
        AiHubChatToolBinding githubBinding = new AiHubChatToolBinding(
            12L, 100L, 0L, "github", 1, "createIssue", 50L, 0, Map.of(), false);

        when(chatToolFacade.listChatDisabledConnectors(7L)).thenReturn(Set.of("slack"));

        ClusterElementDefinition githubDef = mock(ClusterElementDefinition.class);
        when(githubDef.getDescription()).thenReturn("Open a new GitHub issue");
        when(githubDef.getTitle()).thenReturn("GitHub: Create Issue");
        when(githubDef.getProperties()).thenReturn(List.of());

        when(clusterElementDefinitionService.getClusterElementDefinition(eq("github"), eq(1), eq("createIssue")))
            .thenReturn(githubDef);

        // Slack resolves perfectly well in the catalog. Stubbing it is what makes this test meaningful: leaving it
        // unstubbed would drop the binding as unresolvable, and the assertion below would pass without the
        // participation filter ever running.
        ClusterElementDefinition slackDef = mock(ClusterElementDefinition.class);
        lenient()
            .when(slackDef.getDescription())
            .thenReturn("Send a message to a Slack channel");
        lenient()
            .when(slackDef.getTitle())
            .thenReturn("Slack: Send Message");
        lenient()
            .when(slackDef.getProperties())
            .thenReturn(List.of());

        lenient()
            .when(clusterElementDefinitionService.getClusterElementDefinition(eq("slack"), eq(1), eq("sendMessage")))
            .thenReturn(slackDef);

        AiHubChatBindingToolCallbackResolver resolver = newResolver(
            chatService, chatToolFacade, clusterElementDefinitionService, connectionService);

        // After newResolver, which blanket-stubs listUserTools(anyLong(), anyLong()) to an empty list for the
        // chat-binding tests — stubbing above would be silently overwritten and this test would pass vacuously.
        when(chatToolFacade.listUserTools(1L, 10L)).thenReturn(List.of(slackBinding, githubBinding));

        AiHubToolInvocationContext context = new AiHubToolInvocationContext(
            1L, 1L, (short) 0, "x", 0L, "thread-1", 1L);

        List<ToolCallback> callbacks = resolver.resolve(context);

        assertThat(callbacks).hasSize(1);
        assertThat(callbacks.getFirst()
            .getToolDefinition()
            .name()).isEqualTo("github_createIssue");
    }

    /**
     * A participant's turn (sender != chat owner) must not receive the owner's user-global connectors, even though the
     * owner has one available and the chat itself has none of its own. Companion to
     * {@link #testResolveDropsUserConnectorsTheChatSwitchedOff}, which pins the same union path for the owner's own
     * turn.
     */
    @Test
    void testResolveOmitsUserConnectorsForANonOwnerSender() {
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatToolFacade chatToolFacade = mock(AiHubChatToolFacade.class);
        ClusterElementDefinitionService clusterElementDefinitionService = mock(ClusterElementDefinitionService.class);
        ConnectionService connectionService = mock(ConnectionService.class);

        AiHubChat chat = mock(AiHubChat.class);
        when(chat.getId()).thenReturn(7L);
        when(chat.getUserId()).thenReturn(1L);
        when(chatService.findByThreadId("thread-1")).thenReturn(Optional.of(chat));
        when(chatService.getWorkspaceId(7L)).thenReturn(10L);

        when(chatToolFacade.listChatTools(7L)).thenReturn(List.of());

        AiHubChatBindingToolCallbackResolver resolver = newResolver(
            chatService, chatToolFacade, clusterElementDefinitionService, connectionService);

        AiHubToolInvocationContext context = new AiHubToolInvocationContext(
            1L, 2L, (short) 0, "x", 0L, "thread-1", 1L);

        List<ToolCallback> callbacks = resolver.resolve(context);

        assertThat(callbacks).isEmpty();
        verify(chatToolFacade, never()).listUserTools(anyLong(), anyLong());
    }

    /**
     * Extends the owner-vs-participant gate beyond connectors: an external MCP server row is scoped to (userId,
     * workspaceId) ({@code AiHubMcpServerFacadeImpl#listMcpServers} queries {@code findAllByUserIdAndWorkspaceId}) and
     * an AI skill is scoped to its creator's login ({@link AiHubSkillsToolProvider#resolve}) — both exactly as personal
     * as a connector, so a participant's turn must not reach either. Constructs the resolver directly (not via
     * {@link #newResolver}) so the MCP and skills mocks can be verified.
     */
    @Test
    void testResolveOmitsMcpAndSkillToolsForANonOwnerSender() {
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatToolFacade chatToolFacade = mock(AiHubChatToolFacade.class);
        ClusterElementDefinitionService clusterElementDefinitionService = mock(ClusterElementDefinitionService.class);
        ConnectionService connectionService = mock(ConnectionService.class);
        AiHubMcpToolCallbackProvider mcpToolCallbackProvider = mock(AiHubMcpToolCallbackProvider.class);
        AiHubSkillsToolProvider skillsToolCallbackProvider = mock(AiHubSkillsToolProvider.class);

        AiHubChat chat = mock(AiHubChat.class);
        when(chat.getId()).thenReturn(7L);
        when(chat.getUserId()).thenReturn(1L);
        when(chatService.findByThreadId("thread-1")).thenReturn(Optional.of(chat));
        when(chatService.getWorkspaceId(7L)).thenReturn(10L);
        when(chatToolFacade.listChatTools(7L)).thenReturn(List.of());

        AiHubChatBindingToolCallbackResolver resolver = new AiHubChatBindingToolCallbackResolver(
            chatService, chatToolFacade, clusterElementDefinitionService, connectionService, mcpToolCallbackProvider,
            skillsToolCallbackProvider);

        AiHubToolInvocationContext context = new AiHubToolInvocationContext(
            1L, 2L, (short) 0, "x", 0L, "thread-1", 1L);

        List<ToolCallback> callbacks = resolver.resolve(context);

        assertThat(callbacks).isEmpty();
        verify(chatToolFacade, never()).listUserTools(anyLong(), anyLong());
        verify(mcpToolCallbackProvider, never()).resolve(anyLong(), anyLong());
        verify(skillsToolCallbackProvider, never()).resolve(anyLong());
    }

    /**
     * Counterpart to {@link #testResolveOmitsMcpAndSkillToolsForANonOwnerSender}: when the owner is the one sending the
     * turn, both user-scoped sources join the chat-scoped tools as before.
     */
    @Test
    void testResolveIncludesMcpAndSkillToolsForTheOwnersOwnTurn() {
        AiHubChatService chatService = mock(AiHubChatService.class);
        AiHubChatToolFacade chatToolFacade = mock(AiHubChatToolFacade.class);
        ClusterElementDefinitionService clusterElementDefinitionService = mock(ClusterElementDefinitionService.class);
        ConnectionService connectionService = mock(ConnectionService.class);
        AiHubMcpToolCallbackProvider mcpToolCallbackProvider = mock(AiHubMcpToolCallbackProvider.class);
        AiHubSkillsToolProvider skillsToolCallbackProvider = mock(AiHubSkillsToolProvider.class);

        AiHubChat chat = mock(AiHubChat.class);
        when(chat.getId()).thenReturn(7L);
        when(chat.getUserId()).thenReturn(1L);
        when(chatService.findByThreadId("thread-1")).thenReturn(Optional.of(chat));
        when(chatService.getWorkspaceId(7L)).thenReturn(10L);
        when(chatToolFacade.listChatTools(7L)).thenReturn(List.of());
        when(chatToolFacade.listUserTools(1L, 10L)).thenReturn(List.of());

        ToolCallback mcpCallback = mock(ToolCallback.class);

        when(mcpToolCallbackProvider.resolve(1L, 10L)).thenReturn(List.of(mcpCallback));

        ToolCallback skillCallback = mock(ToolCallback.class);

        when(skillsToolCallbackProvider.resolve(1L)).thenReturn(List.of(skillCallback));

        AiHubChatBindingToolCallbackResolver resolver = new AiHubChatBindingToolCallbackResolver(
            chatService, chatToolFacade, clusterElementDefinitionService, connectionService, mcpToolCallbackProvider,
            skillsToolCallbackProvider);

        AiHubToolInvocationContext context = new AiHubToolInvocationContext(
            1L, 1L, (short) 0, "x", 0L, "thread-1", 1L);

        List<ToolCallback> callbacks = resolver.resolve(context);

        assertThat(callbacks).containsExactlyInAnyOrder(mcpCallback, skillCallback);
    }

    private static AiHubChatBindingToolCallbackResolver newResolver(
        AiHubChatService chatService,
        AiHubChatToolFacade chatToolFacade,
        ClusterElementDefinitionService clusterElementDefinitionService, ConnectionService connectionService) {

        // No user-global "added connectors" in these chat-binding tests — exercised separately.
        lenient()
            .when(chatToolFacade.listUserTools(anyLong(), anyLong()))
            .thenReturn(List.of());

        // No external MCP server tools in these chat-binding tests — exercised separately.
        AiHubMcpToolCallbackProvider mcpToolCallbackProvider = mock(AiHubMcpToolCallbackProvider.class);

        lenient()
            .when(mcpToolCallbackProvider.resolve(anyLong(), anyLong()))
            .thenReturn(List.of());

        // No skill tools in these chat-binding tests — exercised separately.
        AiHubSkillsToolProvider skillsToolCallbackProvider = mock(AiHubSkillsToolProvider.class);

        lenient()
            .when(skillsToolCallbackProvider.resolve(anyLong()))
            .thenReturn(List.of());

        return new AiHubChatBindingToolCallbackResolver(
            chatService, chatToolFacade, clusterElementDefinitionService, connectionService, mcpToolCallbackProvider,
            skillsToolCallbackProvider);
    }
}
