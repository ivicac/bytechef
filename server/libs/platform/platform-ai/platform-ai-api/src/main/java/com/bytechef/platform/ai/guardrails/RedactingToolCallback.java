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
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Decorates a {@link ToolCallback} registered on an automation or embedded MCP server so its result is redacted before
 * it leaves the process.
 *
 * <p>
 * MCP is the only ByteChef surface reaching a model the tenant did not choose: an external agent calls a tool, a
 * workflow runs, and the result lands in that agent's context permanently. There is no return path, so this surface
 * redacts irreversibly rather than tokenizing -- a token nothing ever restores is a broken value in someone else's
 * context, not a protected one.
 * </p>
 *
 * <p>
 * {@link ToolCallback#call} returns the result already serialized, so a single redaction pass covers every nested
 * field. Walking the result object instead would have to handle {@code Map}, {@code List}, arrays, records and nulls,
 * and would leak silently on any node type it missed.
 * </p>
 *
 * <p>
 * A delegate that throws is redacted too. The MCP layer builds its tool-error content from the exception's message, and
 * the messages thrown here quote the payload -- {@code OpenApiClientUtils} throws the provider's raw HTTP response
 * body, a task error is rethrown verbatim -- so the message is redacted and rethrown as a
 * {@link RedactedToolExecutionException}, keeping the failure a tool error while denying it the unredacted text. When
 * redacting that message itself fails, the raw message is not a fallback: the redaction failure propagates instead.
 * </p>
 *
 * <p>
 * The redactor is resolved per call, never at registration: MCP clients cache {@code tools/list} for a long time, so
 * binding it when the tool list was assembled would mean flipping the workspace setting did nothing until the server
 * restarted.
 * </p>
 *
 * @author Ivica Cardic
 */
public class RedactingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ObjectProvider<McpOutboundRedactorProvider> mcpOutboundRedactorProviderProvider;
    private final @Nullable Long workspaceId;
    private final String surface;

    private RedactingToolCallback(
        ToolCallback delegate, ObjectProvider<McpOutboundRedactorProvider> mcpOutboundRedactorProviderProvider,
        @Nullable Long workspaceId, String surface) {

        this.delegate = delegate;
        this.mcpOutboundRedactorProviderProvider = mcpOutboundRedactorProviderProvider;
        this.workspaceId = workspaceId;
        this.surface = surface;
    }

    /**
     * @param delegate                            the callback to decorate
     * @param mcpOutboundRedactorProviderProvider resolves the EE provider, absent in CE builds
     * @param workspaceId                         the MCP server's workspace, or {@code null} for the tenant default
     * @param surface                             {@link McpOutboundRedactorProvider#SURFACE_AUTOMATION} or
     *                                            {@link McpOutboundRedactorProvider#SURFACE_EMBEDDED}; linked rather
     *                                            than spelled out so the two cannot drift apart
     * @return a {@link ToolCallback} that redacts {@code delegate}'s result
     */
    public static ToolCallback wrap(
        ToolCallback delegate, ObjectProvider<McpOutboundRedactorProvider> mcpOutboundRedactorProviderProvider,
        @Nullable Long workspaceId, String surface) {

        return new RedactingToolCallback(delegate, mcpOutboundRedactorProviderProvider, workspaceId, surface);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        String result;

        try {
            result = delegate.call(toolInput);
        } catch (RuntimeException runtimeException) {
            throw redactFailure(runtimeException);
        }

        return redact(result);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        String result;

        try {
            result = delegate.call(toolInput, toolContext);
        } catch (RuntimeException runtimeException) {
            throw redactFailure(runtimeException);
        }

        return redact(result);
    }

    private String redact(String result) {
        return McpOutboundRedaction.redact(result, mcpOutboundRedactorProviderProvider, workspaceId, surface);
    }

    private RuntimeException redactFailure(RuntimeException runtimeException) {
        String message = runtimeException.getMessage();

        if (message == null) {
            return runtimeException;
        }

        return new RedactedToolExecutionException(redact(message), runtimeException);
    }
}
