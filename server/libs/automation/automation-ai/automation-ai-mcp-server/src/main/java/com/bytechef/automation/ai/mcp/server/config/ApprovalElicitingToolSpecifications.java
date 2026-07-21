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

package com.bytechef.automation.ai.mcp.server.config;

import com.bytechef.automation.ai.mcp.server.facade.AutomationMcpToolFacade;
import com.bytechef.commons.util.JsonUtils;
import com.bytechef.tenant.TenantContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Decorates a workflow-backed MCP tool specification with URL-mode elicitation for pending approvals. When the tool's
 * synchronous run pauses on a human approval (the facade returns an {@code approval_required} descriptor carrying
 * {@code formUrl} + {@code jobId}), and the connected client advertises the URL elicitation capability, the server
 * sends an {@code elicitation/create} request pointing the user at the hosted approval form instead of just returning
 * the descriptor text. When the client reports the URL interaction as accepted (the user resolved the approval), the
 * tool call re-awaits the resumed run and returns its real output — so the MCP caller sees the completed result in the
 * original {@code tools/call} response. Declined/cancelled elicitations, clients without the capability, and any
 * elicitation transport failure all fall back to the plain descriptor result.
 *
 * @author Ivica Cardic
 */
public final class ApprovalElicitingToolSpecifications {

    private static final Logger log = LoggerFactory.getLogger(ApprovalElicitingToolSpecifications.class);

    private static final String STATUS_APPROVAL_REQUIRED = "approval_required";

    private ApprovalElicitingToolSpecifications() {
    }

    public static McpServerFeatures.AsyncToolSpecification decorate(
        McpServerFeatures.AsyncToolSpecification toolSpecification, AutomationMcpToolFacade mcpToolFacade) {

        return new McpServerFeatures.AsyncToolSpecification(
            toolSpecification.tool(),
            (exchange, request) -> {
                // Captured on the invoking thread so the post-elicitation continuation (which lands on a reactor
                // thread) re-awaits the run under the same tenant the tool call started with.
                String tenantId = TenantContext.getCurrentTenantId();

                return toolSpecification.callHandler()
                    .apply(exchange, request)
                    .flatMap(result -> elicitApprovalIfPending(exchange, result, mcpToolFacade, tenantId));
            });
    }

    private static Mono<McpSchema.CallToolResult> elicitApprovalIfPending(
        McpAsyncServerExchange exchange, McpSchema.CallToolResult result, AutomationMcpToolFacade mcpToolFacade,
        String tenantId) {

        Map<String, ?> pendingApproval = parsePendingApproval(result);

        if (pendingApproval == null || !supportsUrlElicitation(exchange)) {
            return Mono.just(result);
        }

        if (!(pendingApproval.get("formUrl") instanceof String formUrl) ||
            !(pendingApproval.get("jobId") instanceof Number jobId)) {

            return Mono.just(result);
        }

        Object pendingMessage = pendingApproval.get("message");

        McpSchema.ElicitRequest elicitRequest = McpSchema.ElicitUrlRequest
            .builder(
                pendingMessage == null
                    ? "Approval required — resolve the pending approval to continue."
                    : String.valueOf(pendingMessage),
                formUrl, "approval-" + jobId.longValue())
            .build();

        return exchange.createElicitation(elicitRequest)
            .flatMap(elicitResult -> elicitResult.action() == McpSchema.ElicitResult.Action.ACCEPT
                ? awaitResolvedRun(mcpToolFacade, tenantId, jobId.longValue())
                : Mono.just(result))
            .onErrorResume(exception -> {
                log.warn("Approval elicitation failed; returning the pending descriptor: {}", exception.getMessage());

                return Mono.just(result);
            });
    }

    private static Mono<McpSchema.CallToolResult> awaitResolvedRun(
        AutomationMcpToolFacade mcpToolFacade, String tenantId, long jobId) {

        return Mono
            .fromCallable(
                () -> TenantContext.callWithTenantId(
                    tenantId, () -> mcpToolFacade.awaitApprovedWorkflowRun(jobId)))
            .subscribeOn(Schedulers.boundedElastic())
            .map(output -> McpSchema.CallToolResult.builder()
                .addTextContent(output == null ? "" : JsonUtils.write(output))
                .isError(false)
                .build());
    }

    /**
     * Returns the parsed {@code approval_required} descriptor when the result carries one as its first text content,
     * otherwise {@code null}. Non-JSON and non-descriptor payloads (the overwhelmingly common case) return null cheaply
     * — the text must at least mention the status marker before a parse is attempted.
     */
    private static @Nullable Map<String, ?> parsePendingApproval(McpSchema.CallToolResult result) {
        if (Boolean.TRUE.equals(result.isError()) || result.content() == null || result.content()
            .isEmpty()) {

            return null;
        }

        if (!(result.content()
            .getFirst() instanceof McpSchema.TextContent textContent)) {

            return null;
        }

        String text = textContent.text();

        if (text == null || !text.contains(STATUS_APPROVAL_REQUIRED)) {
            return null;
        }

        try {
            Map<String, ?> map = JsonUtils.readMap(text);

            return STATUS_APPROVAL_REQUIRED.equals(map.get("status")) ? map : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private static boolean supportsUrlElicitation(McpAsyncServerExchange exchange) {
        McpSchema.ClientCapabilities clientCapabilities = exchange.getClientCapabilities();

        if (clientCapabilities == null || clientCapabilities.elicitation() == null) {
            return false;
        }

        McpSchema.ClientCapabilities.Elicitation elicitation = clientCapabilities.elicitation();

        return elicitation.url() != null;
    }
}
