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

package com.bytechef.platform.ai.guardrails;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The single fail-closed redaction rule shared by every place an MCP server hands a result to the calling agent:
 * {@link RedactingToolCallback} for a tool's own synchronous result, and the approval-resume path (the
 * {@code runOnBoundedElastic} funnel in {@code ApprovalElicitingToolSpecifications} and its embedded counterpart) for
 * the output of a workflow that paused for a human approval and resumed outside the wrapped {@code ToolCallback}.
 *
 * <p>
 * Both call sites must fail closed identically: no redactor resolved returns the result unchanged, a redactor that
 * throws must never let the raw payload through. Keeping that rule in one place means it can only stop failing closed
 * once, not once per call site.
 * </p>
 *
 * <p>
 * A failure to <em>resolve</em> a redactor fails closed too, and that distinction is load-bearing: "this workspace has
 * outbound redaction off" and "the settings lookup threw" both used to arrive here as a missing redactor, so a
 * transient database error during a {@code tools/call} would have shipped the raw payload with {@code isError} false.
 * Only a provider that genuinely reports no redaction for this workspace returns the result unchanged.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class McpOutboundRedaction {

    private static final Logger log = LoggerFactory.getLogger(McpOutboundRedaction.class);

    private McpOutboundRedaction() {
    }

    /**
     * @param serializedResult                    the result as it would otherwise reach the calling agent
     * @param mcpOutboundRedactorProviderProvider resolves the EE provider, absent in CE builds
     * @param workspaceId                         the MCP server's workspace, or {@code null} for the tenant default
     * @param surface                             {@link McpOutboundRedactorProvider#SURFACE_AUTOMATION} or
     *                                            {@link McpOutboundRedactorProvider#SURFACE_EMBEDDED}
     * @return the redacted result, or {@code serializedResult} unchanged when no redactor resolves
     * @throws McpOutboundRedactionException when resolving a redactor fails, or when a resolved redactor throws; the
     *                                       caller must not fall back to {@code serializedResult} on this exception
     */
    public static String redact(
        String serializedResult, ObjectProvider<McpOutboundRedactorProvider> mcpOutboundRedactorProviderProvider,
        @Nullable Long workspaceId, String surface) {

        McpOutboundRedactor redactor;

        try {
            redactor = fetchRedactor(mcpOutboundRedactorProviderProvider, workspaceId, surface);
        } catch (RuntimeException resolutionException) {
            log.warn(
                "Failed to resolve an outbound MCP redactor on surface {}; refusing the call rather than returning the "
                    + "result unredacted",
                surface, resolutionException);

            throw new McpOutboundRedactionException(
                "Failed to resolve the outbound redaction policy", resolutionException);
        }

        if (redactor == null) {
            return serializedResult;
        }

        try {
            return redactor.redact(serializedResult);
        } catch (RuntimeException redactionException) {
            log.warn(
                "Failed to redact an outbound MCP result on surface {}; refusing the call rather than returning it "
                    + "unredacted",
                surface, redactionException);

            throw new McpOutboundRedactionException("Failed to redact the tool result", redactionException);
        }
    }

    /**
     * Redacts the message of a failure that would otherwise be handed to the calling agent as tool-error text. Failure
     * messages on this surface are payload-derived as often as results are -- {@code OpenApiClientUtils} throws the
     * provider's raw HTTP response body as the message, and a task error is rethrown verbatim -- so an error path that
     * skips redaction leaks exactly what the result path protects.
     *
     * <p>
     * Returns {@code null} when there is no message to hand back safely: either the failure carried none, or redacting
     * it failed. A caller that cannot throw (a reactive funnel already building an error result) must then emit its
     * generic wording and never the raw message.
     * </p>
     *
     * @param message                             the failure's message, as it would otherwise reach the calling agent
     * @param mcpOutboundRedactorProviderProvider resolves the EE provider, absent in CE builds
     * @param workspaceId                         the MCP server's workspace, or {@code null} for the tenant default
     * @param surface                             {@link McpOutboundRedactorProvider#SURFACE_AUTOMATION} or
     *                                            {@link McpOutboundRedactorProvider#SURFACE_EMBEDDED}
     * @return the redacted message, or {@code null} when none can be handed back
     */
    public static @Nullable String redactFailureMessage(
        @Nullable String message, ObjectProvider<McpOutboundRedactorProvider> mcpOutboundRedactorProviderProvider,
        @Nullable Long workspaceId, String surface) {

        if (message == null || message.isBlank()) {
            return null;
        }

        try {
            return redact(message, mcpOutboundRedactorProviderProvider, workspaceId, surface);
        } catch (McpOutboundRedactionException mcpOutboundRedactionException) {
            return null;
        }
    }

    private static @Nullable McpOutboundRedactor fetchRedactor(
        ObjectProvider<McpOutboundRedactorProvider> mcpOutboundRedactorProviderProvider, @Nullable Long workspaceId,
        String surface) {

        McpOutboundRedactorProvider mcpOutboundRedactorProvider = mcpOutboundRedactorProviderProvider.getIfAvailable();

        if (mcpOutboundRedactorProvider == null) {
            return null;
        }

        return mcpOutboundRedactorProvider.fetchRedactor(workspaceId, surface)
            .orElse(null);
    }
}
