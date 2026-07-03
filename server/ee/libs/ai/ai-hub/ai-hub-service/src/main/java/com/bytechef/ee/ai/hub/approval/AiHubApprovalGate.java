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
 * Gated on {@code bytechef.ai.hub.enabled} alone — the module switch, and the only one. Every deployment running the
 * hub therefore gates the built-in tool names ({@link AiHubToolApprovalDefaults#DEFAULT_TOOL_NAMES}) plus four
 * destructive-verb prefixes: a destructive tool call waits for a person by default rather than by opt-in. The escape
 * hatch travels with it — the Tool Approvals settings page, which writes an {@code EXEMPT} rule to lift a default, is
 * likewise reachable wherever the hub runs in EE, so a workspace can always widen what its agents may do unattended.
 * See {@code .agents/ai-hub.md}'s tool approval gate section.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
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
