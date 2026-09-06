/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.agent;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agui.core.agent.AgentSubscriber;
import com.agui.core.agent.RunAgentParameters;
import com.agui.core.exception.AGUIException;
import com.agui.core.message.BaseMessage;
import com.bytechef.automation.assetfile.domain.AssetFile;
import com.bytechef.automation.assetfile.service.AssetFileSystemFacade;
import com.bytechef.ee.ai.hub.chat.AiHubChat;
import com.bytechef.ee.ai.hub.chat.AiHubChatKind;
import com.bytechef.ee.ai.hub.chat.AiHubChatService;
import com.bytechef.file.storage.domain.FileEntry;
import com.bytechef.platform.configuration.domain.Environment;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link AiHubRoutingAgent}. The router's only behaviour worth pinning is "the right kind goes to the
 * right agent" — but the regression cost of getting that wrong is huge (every chat routes to the wrong agent and unit
 * tests of the leaf agents wouldn't catch it). The tests below pin every branch of the dispatch decision tree,
 * including the fallback paths that fire when the bridge bean is absent or the chat lookup fails.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubRoutingAgentTest {

    private static final String AGENT_ID = "ai_hub_ask";
    private static final String THREAD_ID = "thread-1";

    private AiHubSpringAIAgent llmAgent;
    private WebhookBridgeAgent webhookBridgeAgent;
    private AiHubChatService chatService;
    private AgentSubscriber subscriber;
    private AssetFileSystemFacade assetFileSystemFacade;

    @BeforeEach
    void setUp() {
        llmAgent = mock(AiHubSpringAIAgent.class);
        webhookBridgeAgent = mock(WebhookBridgeAgent.class);
        chatService = mock(AiHubChatService.class);
        subscriber = mock(AgentSubscriber.class);
        assetFileSystemFacade = mock(AssetFileSystemFacade.class);

        when(llmAgent.runAgent(any(RunAgentParameters.class), any(AgentSubscriber.class)))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(webhookBridgeAgent.runAgent(any(RunAgentParameters.class), any(AgentSubscriber.class)))
            .thenReturn(CompletableFuture.completedFuture(null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testWorkflowChatChatDispatchesToBridgeAgent() throws AGUIException {
        AiHubChat chat = mock(AiHubChat.class);

        when(chat.getKind()).thenReturn(AiHubChatKind.WORKFLOW_CHAT);
        when(chatService.findByThreadId(THREAD_ID)).thenReturn(Optional.of(chat));

        AiHubRoutingAgent agent =
            new AiHubRoutingAgent(AGENT_ID, llmAgent, webhookBridgeAgent, chatService, assetFileSystemFacade);

        agent.runAgent(parametersOf(THREAD_ID), subscriber)
            .join();

        verify(webhookBridgeAgent).runAgent(any(RunAgentParameters.class), any(AgentSubscriber.class));
        verify(llmAgent, never()).runAgent(any(), any());
    }

    @Test
    void testAgentChatDispatchesToBridgeAgent() throws AGUIException {
        // An agent chat is webhook-bridged exactly like a workflow chat — it binds to the workflow inside the agent's
        // hidden __AI_AGENT__ project. If this ever routed to the LLM agent instead, the user would get a plausible
        // AI Hub answer in place of their agent's actual run, with nothing in the logs to say so.
        AiHubChat chat = mock(AiHubChat.class);

        when(chat.getKind()).thenReturn(AiHubChatKind.AGENT_CHAT);
        when(chatService.findByThreadId(THREAD_ID)).thenReturn(Optional.of(chat));

        AiHubRoutingAgent agent =
            new AiHubRoutingAgent(AGENT_ID, llmAgent, webhookBridgeAgent, chatService, assetFileSystemFacade);

        agent.runAgent(parametersOf(THREAD_ID), subscriber)
            .join();

        verify(webhookBridgeAgent).runAgent(any(RunAgentParameters.class), any(AgentSubscriber.class));
        verify(llmAgent, never()).runAgent(any(), any());
    }

    @Test
    void testStandardChatDispatchesToLlmAgent() throws AGUIException {
        AiHubChat chat = mock(AiHubChat.class);

        when(chat.getKind()).thenReturn(AiHubChatKind.STANDARD);
        when(chatService.findByThreadId(THREAD_ID)).thenReturn(Optional.of(chat));

        AiHubRoutingAgent agent =
            new AiHubRoutingAgent(AGENT_ID, llmAgent, webhookBridgeAgent, chatService, assetFileSystemFacade);

        agent.runAgent(parametersOf(THREAD_ID), subscriber)
            .join();

        verify(llmAgent).runAgent(any(RunAgentParameters.class), any(AgentSubscriber.class));
        verify(webhookBridgeAgent, never()).runAgent(any(), any());
    }

    /**
     * The highest-value test in this class: {@code uploadAttachmentsBestEffort} runs on the same
     * {@code ForkJoinPool.commonPool()} worker as the rest of {@code runAgent}, where {@code AiHubAgentTenantBinder}
     * binds the tenant but establishes no {@code Authentication}. A membership check on that thread denies — the
     * guarded facade resolves the user through {@code fetchCurrentUser}, so a missing principal is a non-member rather
     * than an exception — and the surrounding try/catch in {@code uploadAttachmentsBestEffort} swallows that denial as
     * a WARN, so the regression this guards against is silent: the chat turn still completes, but the attachment
     * quietly never becomes a workspace asset file. Deliberately does NOT stub a current user — stubbing one would hide
     * exactly this defect.
     */
    @Test
    void testAttachmentUploadSucceedsWithNoSecurityContextEstablished() throws AGUIException {
        SecurityContextHolder.clearContext();

        AiHubChat chat = mock(AiHubChat.class);

        when(chat.getId()).thenReturn(11L);
        when(chat.getKind()).thenReturn(AiHubChatKind.STANDARD);
        when(chat.getEnvironment()).thenReturn(Environment.PRODUCTION);
        when(chatService.findByThreadId(THREAD_ID)).thenReturn(Optional.of(chat));
        when(chatService.getWorkspaceId(11L)).thenReturn(42L);

        AssetFile assetFile = new AssetFile();

        assetFile.setName("no-auth.pdf");
        assetFile.setMimeType("application/pdf");
        assetFile.setFile(new FileEntry("no-auth.pdf", "file:///workspace/no-auth.pdf"));

        when(
            assetFileSystemFacade.createFromUpload(
                anyLong(), anyInt(), anyString(), anyString(), any(InputStream.class)))
                    .thenReturn(assetFile);

        Map<String, Object> attachment = Map.of(
            "name", "no-auth.pdf",
            "contentType", "application/pdf",
            "base64", "JVBERi0=");
        Map<String, Object> forwardedProps = Map.of("attachments", List.of(attachment));

        AiHubRoutingAgent agent =
            new AiHubRoutingAgent(AGENT_ID, llmAgent, webhookBridgeAgent, chatService, assetFileSystemFacade);

        assertThatNoException()
            .as("this runs on a commonPool worker where only the tenant is bound; requiring a principal here "
                + "breaks every best-effort attachment upload for standard chats")
            .isThrownBy(() -> agent.runAgent(parametersOf(THREAD_ID, forwardedProps), subscriber)
                .join());

        verify(assetFileSystemFacade).createFromUpload(
            eq(42L), anyInt(), eq("no-auth.pdf"), eq("application/pdf"), any(InputStream.class));
        verify(llmAgent).runAgent(any(RunAgentParameters.class), any(AgentSubscriber.class));
    }

    @Test
    void testMissingChatFallsBackToLlmAgent() throws AGUIException {
        // When the threadId doesn't map to a chat row (race with delete, unknown threadId), the router
        // falls through to the LLM agent rather than throwing — the LLM agent's own error handling will
        // surface the missing-chat case if it matters. Pin: bridge is NOT invoked.
        when(chatService.findByThreadId(THREAD_ID)).thenReturn(Optional.empty());

        AiHubRoutingAgent agent =
            new AiHubRoutingAgent(AGENT_ID, llmAgent, webhookBridgeAgent, chatService, assetFileSystemFacade);

        agent.runAgent(parametersOf(THREAD_ID), subscriber)
            .join();

        verify(llmAgent).runAgent(any(RunAgentParameters.class), any(AgentSubscriber.class));
        verify(webhookBridgeAgent, never()).runAgent(any(), any());
    }

    @Test
    void testWorkflowChatFallsBackToLlmAgentWhenBridgeBeanAbsent() throws AGUIException {
        // Deployments without the webhook coordinator don't register a WebhookBridgeAgent bean. Without the
        // fallback the user would stare at a hung stream forever. Falling back to the LLM agent surfaces
        // SOMETHING — sub-optimal but recoverable. Pin: WORKFLOW_CHAT + null bridge → LLM agent.
        AiHubChat chat = mock(AiHubChat.class);

        when(chat.getKind()).thenReturn(AiHubChatKind.WORKFLOW_CHAT);
        when(chatService.findByThreadId(THREAD_ID)).thenReturn(Optional.of(chat));

        AiHubRoutingAgent agent =
            new AiHubRoutingAgent(AGENT_ID, llmAgent, null, chatService, assetFileSystemFacade);

        agent.runAgent(parametersOf(THREAD_ID), subscriber)
            .join();

        verify(llmAgent).runAgent(any(RunAgentParameters.class), any(AgentSubscriber.class));
    }

    @Test
    void testNullThreadIdRoutesToLlmAgentWithoutLookup() throws AGUIException {
        // Defensive: a malformed RunAgentParameters with no threadId should NOT trigger a database lookup —
        // findByThreadId(null) would explode. The router's resolveKind short-circuits on blank/null threadId
        // and returns STANDARD (the safe default). Pin: chatService is NEVER called.
        AiHubRoutingAgent agent =
            new AiHubRoutingAgent(AGENT_ID, llmAgent, webhookBridgeAgent, chatService, assetFileSystemFacade);

        agent.runAgent(parametersOf(null), subscriber)
            .join();

        verify(chatService, never()).findByThreadId(any());
        verify(llmAgent).runAgent(any(RunAgentParameters.class), any(AgentSubscriber.class));
    }

    private static RunAgentParameters parametersOf(String threadId) {
        return parametersOf(threadId, null);
    }

    private static RunAgentParameters parametersOf(String threadId, Object forwardedProps) {
        RunAgentParameters.Builder builder = RunAgentParameters.builder()
            .runId("run-1")
            .messages(List.<BaseMessage>of())
            .forwardedProps(forwardedProps);

        if (threadId != null) {
            builder.threadId(threadId);
        }

        return builder.build();
    }
}
