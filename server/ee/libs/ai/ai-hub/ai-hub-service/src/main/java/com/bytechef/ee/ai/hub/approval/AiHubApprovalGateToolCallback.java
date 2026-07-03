/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.approval;

import com.bytechef.ai.copilot.tool.context.AgentToolInvocationContext;
import com.bytechef.commons.util.JsonUtils;
import com.bytechef.component.ai.llm.tool.DelegatingToolCallback;
import com.bytechef.ee.ai.hub.chat.AiHubChat;
import com.bytechef.ee.ai.hub.chat.AiHubChatService;
import com.bytechef.ee.ai.hub.metric.AiHubToolApprovalMetrics;
import com.bytechef.ee.ai.hub.toolsearch.ClusterElementToolCallback;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Decorator that intercepts a gated tool call before it executes: instead of running {@code delegate}, it records a
 * {@code PENDING} {@link AiHubToolApproval} row and returns a structured envelope so the chat surface can render an
 * approval card. Whether a given tool call is gated at all is decided per-call by {@link AiHubToolApprovalPolicy}, so
 * the same wrapper instance can pass some calls straight through and gate others.
 *
 * <p>
 * A call with no resolvable {@link AgentToolInvocationContext} (missing thread id, workspace id, or user id) or no
 * matching {@link AiHubChat} passes straight through to {@code delegate} — the gate only ever applies to a call it can
 * attribute to a chat.
 * </p>
 *
 * <p>
 * Persisting the {@code PENDING} row and building the response envelope are two separate failure domains: if the row is
 * persisted but the envelope then fails to build, the row is immediately marked {@code SUPERSEDED} rather than left
 * {@code PENDING} — a row nobody ever saw an approval card for must not block every later gated call in the chat with
 * {@code DEFERRED_RESULT} for the rest of its 24-hour expiry.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class AiHubApprovalGateToolCallback implements DelegatingToolCallback {

    public static final String ENVELOPE_KIND = "tool-approval-request";
    public static final String TOOL_CONTEXT_MODE_KEY = "bytechef.aiHub.mode";

    private static final Logger log = LoggerFactory.getLogger(AiHubApprovalGateToolCallback.class);

    private static final String DEFERRED_RESULT =
        "{\"deferred\":true,\"reason\":\"Another tool call is awaiting approval. Retry after it is resolved.\"}";
    private static final String REFUSED_RESULT =
        "{\"error\":\"approval could not be recorded; the tool was not executed\"}";

    private final ToolCallback delegate;
    private final AiHubToolApprovalPolicy policy;
    private final AiHubToolApprovalService approvalService;
    private final AiHubChatService chatService;
    private final AiHubToolApprovalMetrics metrics;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public AiHubApprovalGateToolCallback(
        ToolCallback delegate, AiHubToolApprovalPolicy policy, AiHubToolApprovalService approvalService,
        AiHubChatService chatService, AiHubToolApprovalMetrics metrics) {

        this.delegate = delegate;
        this.policy = policy;
        this.approvalService = approvalService;
        this.chatService = chatService;
        this.metrics = metrics;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolCallback getDelegate() {
        return delegate;
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        AgentToolInvocationContext invocationContext = AgentToolInvocationContext.fromToolContext(toolContext);

        if (invocationContext == null) {
            return delegate.call(toolInput, toolContext);
        }

        String threadId = invocationContext.conversationId();
        Long workspaceId = invocationContext.workspaceId();
        Long userId = invocationContext.userId();

        if (threadId == null || workspaceId == null || userId == null) {
            return delegate.call(toolInput, toolContext);
        }

        Optional<AiHubChat> chatOptional = chatService.findByThreadId(threadId);

        if (chatOptional.isEmpty()) {
            return delegate.call(toolInput, toolContext);
        }

        AiHubChat chat = chatOptional.get();
        String toolName = getToolDefinition().name();
        AiHubToolApprovalPolicy.Decision decision = policy.decide(workspaceId, userId, chat.getId());

        if (!decision.isGated(toolName)) {
            return delegate.call(toolInput, toolContext);
        }

        if (approvalService.findPending(chat.getId())
            .isPresent()) {

            metrics.record("deferred");

            return DEFERRED_RESULT;
        }

        AiHubToolApproval approval;

        try {
            approval = approvalService.createPending(
                toApproval(chat, userId, toolName, toolInput, invocationContext, toolContext));
        } catch (RuntimeException exception) {
            log.warn("Could not record a tool approval for tool={} chat={}", toolName, chat.getId(), exception);

            metrics.record("refused");

            return REFUSED_RESULT;
        }

        try {
            String envelope = JsonUtils.write(toEnvelope(approval));

            metrics.record("requested");

            return envelope;
        } catch (RuntimeException exception) {
            log.warn(
                "Tool approval id={} for tool={} chat={} was persisted but its response envelope could not be "
                    + "built; marking it SUPERSEDED so an unseen row does not block the chat for its full expiry",
                approval.getId(), toolName, chat.getId(), exception);

            approval.setStatus(AiHubToolApproval.Status.SUPERSEDED);

            try {
                approvalService.save(approval);
            } catch (RuntimeException saveException) {
                log.warn(
                    "Tool approval id={} for tool={} chat={} could not be marked SUPERSEDED; it may remain PENDING",
                    approval.getId(), toolName, chat.getId(), saveException);
            }

            metrics.record("refused");

            return REFUSED_RESULT;
        }
    }

    private AiHubToolApproval toApproval(
        AiHubChat chat, long userId, String toolName, String toolInput, AgentToolInvocationContext invocationContext,
        @Nullable ToolContext toolContext) {

        ToolCallback unwrapped = DelegatingToolCallback.unwrap(delegate);
        AiHubToolApproval approval = new AiHubToolApproval();

        approval.setChatId(chat.getId());
        approval.setThreadId(chat.getThreadId());
        approval.setRequestedByUserId(userId);
        approval.setToolName(toolName);
        approval.setArguments(toolInput == null ? "{}" : toolInput);
        approval.setMode(readMode(toolContext));
        approval.setEnvironment(chat.getEnvironment()
            .ordinal());
        approval.setLlmProvider(invocationContext.llmProvider());
        approval.setLlmModel(invocationContext.llmModel());

        if (unwrapped instanceof ClusterElementToolCallback clusterElementToolCallback) {
            approval.setToolKind(AiHubToolApproval.ToolKind.COMPONENT);
            approval.setComponentName(clusterElementToolCallback.getComponentName());
            approval.setComponentVersion(clusterElementToolCallback.getComponentVersion());
            approval.setConnectionId(clusterElementToolCallback.getPinnedConnectionId());
        } else {
            approval.setToolKind(AiHubToolApproval.ToolKind.CATALOG);
        }

        return approval;
    }

    private static String readMode(@Nullable ToolContext toolContext) {
        if (toolContext == null) {
            return "ASK";
        }

        Object mode = toolContext.getContext()
            .get(TOOL_CONTEXT_MODE_KEY);

        return mode instanceof String stringMode && !stringMode.isBlank() ? stringMode : "ASK";
    }

    private static Map<String, Object> toEnvelope(AiHubToolApproval approval) {
        Map<String, Object> envelope = new LinkedHashMap<>();

        envelope.put("kind", ENVELOPE_KIND);
        envelope.put("approvalId", approval.getId());
        envelope.put("toolName", approval.getToolName());
        envelope.put("componentName", approval.getComponentName());
        envelope.put("arguments", JsonUtils.read(approval.getArguments(), Map.class));
        envelope.put("expiresAt", approval.getExpiresAt() == null ? null : approval.getExpiresAt()
            .toString());
        envelope.put("awaitingApproval", true);

        return envelope;
    }
}
