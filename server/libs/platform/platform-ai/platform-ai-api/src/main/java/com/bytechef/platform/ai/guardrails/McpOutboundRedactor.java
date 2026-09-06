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

/**
 * Redacts an MCP tool's already-serialized result before it is returned to the calling agent. Resolved for one
 * workspace and one surface by {@link McpOutboundRedactorProvider}, so implementations carry their policy rather than
 * taking it per call.
 *
 * @author Ivica Cardic
 */
@FunctionalInterface
public interface McpOutboundRedactor {

    /**
     * @param serializedResult the tool result as {@code ToolCallback#call} produced it
     * @return the redacted result
     */
    String redact(String serializedResult);
}
