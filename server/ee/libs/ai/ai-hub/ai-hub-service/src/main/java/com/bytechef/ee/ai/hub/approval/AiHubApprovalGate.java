/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.approval;

import com.bytechef.ee.ai.hub.chat.AiHubChatService;
import com.bytechef.ee.ai.hub.metric.AiHubToolApprovalMetrics;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Spring bean facade over {@link AiHubApprovalGateToolCallback}: wraps a {@link ToolCallback} with the tool approval
 * gate. Registered as a bean (rather than constructing {@link AiHubApprovalGateToolCallback} directly at each call
 * site) so {@link com.bytechef.ee.ai.hub.agent.AiHubToolCallbackWrappers} and its callers can depend on one
 * collaborator that is simply absent when the AI Hub module isn't enabled.
 *
 * <p>
 * Also requires {@code bytechef.ai.hub.tool-approval.enabled} (default {@code false}), a second and independent switch
 * from {@code bytechef.ai.hub.enabled}: turning the hub on must not, by itself, start gating the built-in tool names
 * ({@link AiHubToolApprovalDefaults#DEFAULT_TOOL_NAMES}) plus four destructive-verb prefixes across every deployment
 * the moment this ships — an operator opts in deliberately. The client's own escape hatch (the Tool Approvals settings
 * page, which can write an {@code EXEMPT} rule to lift a default) sits behind the separate
 * {@code ff-ai-hub-tool-approvals} feature flag, off by default; the two must be turned on together, or gating begins
 * with no UI able to lift it. See {@code .agents/ai-hub.md}'s tool approval gate section.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnProperty(
    name = {
        "bytechef.ai.hub.enabled", "bytechef.ai.hub.tool-approval.enabled"
    }, havingValue = "true")
public class AiHubApprovalGate {

    private final AiHubToolApprovalPolicy policy;
    private final AiHubToolApprovalService approvalService;
    private final AiHubChatService chatService;
    private final AiHubToolApprovalMetrics metrics;

    @SuppressFBWarnings("EI")
    public AiHubApprovalGate(
        AiHubToolApprovalPolicy policy, AiHubToolApprovalService approvalService, AiHubChatService chatService,
        AiHubToolApprovalMetrics metrics) {

        this.policy = policy;
        this.approvalService = approvalService;
        this.chatService = chatService;
        this.metrics = metrics;
    }

    /**
     * Wraps {@code callback} with the tool approval gate. Returns {@code callback} unchanged when it is already wrapped
     * — mirrors {@link com.bytechef.ee.ai.hub.agent.NonEmptyToolCallback#wrap} so re-wrapping at a second registration
     * site doesn't nest the gate twice.
     */
    public ToolCallback wrap(ToolCallback callback) {
        if (callback instanceof AiHubApprovalGateToolCallback) {
            return callback;
        }

        return new AiHubApprovalGateToolCallback(callback, policy, approvalService, chatService, metrics);
    }
}
