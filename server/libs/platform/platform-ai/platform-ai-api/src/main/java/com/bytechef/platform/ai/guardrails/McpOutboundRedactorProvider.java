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

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * CE seam so the MCP server modules can redact outbound tool results without depending on the EE guardrails module, the
 * same CE-SPI/EE-impl idiom as {@code AiGuardrailsAdvisorProvider} and {@code ToolExecutionRecorder}.
 *
 * <p>
 * Hands back a fully resolved {@link McpOutboundRedactor} rather than a policy: returning a policy would leak
 * {@code SensitiveKind}, confidence thresholds and metrics tagging into two MCP modules that have no business knowing
 * them.
 * </p>
 *
 * @author Ivica Cardic
 */
public interface McpOutboundRedactorProvider {

    /**
     * @param workspaceId the MCP server's workspace, or {@code null} for the tenant default (embedded MCP servers are
     *                    {@code Scope.EMBEDDED}, not workspace-scoped, and always pass {@code null})
     * @param surface     {@code "mcp_automation"} or {@code "mcp_embedded"}, for metrics tagging
     * @return a resolved redactor, or empty when outbound redaction is off for this workspace
     */
    Optional<McpOutboundRedactor> fetchRedactor(@Nullable Long workspaceId, String surface);
}
