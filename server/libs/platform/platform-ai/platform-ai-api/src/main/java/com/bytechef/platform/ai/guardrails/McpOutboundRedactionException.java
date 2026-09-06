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
 * Thrown when redacting an MCP tool's outbound result fails. {@link RedactingToolCallback} fails closed on it rather
 * than returning the unredacted payload; the MCP layer converts the throw into a tool error the calling agent sees, the
 * same channel the MCP facades already use to refuse a disabled server or tool.
 *
 * @author Ivica Cardic
 */
public class McpOutboundRedactionException extends RuntimeException {

    public McpOutboundRedactionException(String message, Throwable cause) {
        super(message, cause);
    }
}
