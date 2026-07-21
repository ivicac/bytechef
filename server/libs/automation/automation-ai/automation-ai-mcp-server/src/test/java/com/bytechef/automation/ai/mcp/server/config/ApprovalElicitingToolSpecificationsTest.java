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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.automation.ai.mcp.server.facade.AutomationMcpToolFacade;
import com.bytechef.commons.util.JsonUtils;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import reactor.core.publisher.Mono;

/**
 * Pins the URL-elicitation contract of {@link ApprovalElicitingToolSpecifications}: a pending-approval tool result
 * triggers an {@code elicitation/create} pointing at the hosted form only when the client advertises the URL
 * elicitation capability; an accepted elicitation re-awaits the resumed run and returns its real output; declined
 * elicitations, capability-less clients, and ordinary results all pass the original result through untouched.
 *
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
class ApprovalElicitingToolSpecificationsTest {

    private static final McpSchema.Tool TOOL = McpSchema.Tool.builder()
        .name("run_workflow")
        .description("Runs the workflow")
        .inputSchema(Map.of("type", "object"))
        .build();

    private final AutomationMcpToolFacade mcpToolFacade = mock(AutomationMcpToolFacade.class);
    private final McpAsyncServerExchange exchange = mock(McpAsyncServerExchange.class);

    @Test
    void testOrdinaryResultPassesThroughWithoutElicitation() {
        McpSchema.CallToolResult innerResult = textResult("{\"answer\": 42}");

        McpSchema.CallToolResult result = call(innerResult);

        assertThat(result).isSameAs(innerResult);

        verify(exchange, never()).createElicitation(any());
    }

    @Test
    void testPendingApprovalWithoutUrlCapabilityPassesThrough() {
        when(exchange.getClientCapabilities()).thenReturn(
            McpSchema.ClientCapabilities.builder()
                .build());

        McpSchema.CallToolResult innerResult = pendingApprovalResult();

        McpSchema.CallToolResult result = call(innerResult);

        assertThat(result).isSameAs(innerResult);

        verify(exchange, never()).createElicitation(any());
    }

    @Test
    void testAcceptedElicitationReturnsTheResumedRunOutput() {
        stubUrlCapability();

        when(exchange.createElicitation(any())).thenReturn(
            Mono.just(
                McpSchema.ElicitResult.builder(McpSchema.ElicitResult.Action.ACCEPT)
                    .build()));
        when(mcpToolFacade.awaitApprovedWorkflowRun(42L)).thenReturn(Map.of("message", "done"));

        McpSchema.CallToolResult result = call(pendingApprovalResult());

        verify(mcpToolFacade).awaitApprovedWorkflowRun(42L);

        assertThat(firstText(result)).contains("done");
        assertThat(firstText(result)).doesNotContain("approval_required");
    }

    @Test
    void testDeclinedElicitationReturnsThePendingDescriptor() {
        stubUrlCapability();

        when(exchange.createElicitation(any())).thenReturn(
            Mono.just(
                McpSchema.ElicitResult.builder(McpSchema.ElicitResult.Action.DECLINE)
                    .build()));

        McpSchema.CallToolResult innerResult = pendingApprovalResult();

        McpSchema.CallToolResult result = call(innerResult);

        assertThat(result).isSameAs(innerResult);

        verify(mcpToolFacade, never()).awaitApprovedWorkflowRun(42L);
    }

    @Test
    void testElicitationFailureFallsBackToThePendingDescriptor() {
        stubUrlCapability();

        when(exchange.createElicitation(any())).thenReturn(Mono.error(new RuntimeException("transport gone")));

        McpSchema.CallToolResult innerResult = pendingApprovalResult();

        McpSchema.CallToolResult result = call(innerResult);

        assertThat(result).isSameAs(innerResult);
    }

    private McpSchema.CallToolResult call(McpSchema.CallToolResult innerResult) {
        McpServerFeatures.AsyncToolSpecification innerSpecification = new McpServerFeatures.AsyncToolSpecification(
            TOOL, (currentExchange, request) -> Mono.just(innerResult));

        McpServerFeatures.AsyncToolSpecification decorated = ApprovalElicitingToolSpecifications.decorate(
            innerSpecification, mcpToolFacade);

        return decorated.callHandler()
            .apply(exchange, new McpSchema.CallToolRequest("run_workflow", Map.of()))
            .block();
    }

    private void stubUrlCapability() {
        when(exchange.getClientCapabilities()).thenReturn(
            McpSchema.ClientCapabilities.builder()
                .elicitation(
                    new McpSchema.ClientCapabilities.Elicitation(
                        null, new McpSchema.ClientCapabilities.Elicitation.Url()))
                .build());
    }

    private static McpSchema.CallToolResult pendingApprovalResult() {
        return textResult(
            JsonUtils.write(
                Map.of(
                    "status", "approval_required",
                    "message", "Approval required — resolve it at: https://example.com/resume/tok",
                    "formUrl", "https://example.com/resume/tok",
                    "jobId", 42L)));
    }

    private static McpSchema.CallToolResult textResult(String text) {
        return McpSchema.CallToolResult.builder()
            .addTextContent(text)
            .isError(false)
            .build();
    }

    private static String firstText(McpSchema.CallToolResult result) {
        McpSchema.TextContent textContent = (McpSchema.TextContent) result.content()
            .getFirst();

        return textContent.text();
    }
}
