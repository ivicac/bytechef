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
 * Carries a failed MCP tool call's message after redaction, so the failure still reaches the calling agent as a tool
 * error but no longer as payload-derived text.
 *
 * <p>
 * The MCP layer turns an exception out of a tool callback into tool-error content built from its {@link #getMessage()}
 * alone, and the messages thrown on this surface routinely quote the payload: a provider's raw HTTP response body, or a
 * task error rethrown verbatim. {@link RedactingToolCallback} therefore replaces the message with its redacted form and
 * rethrows this, keeping the original as the cause for ByteChef's own logs -- which the calling agent never sees.
 * </p>
 *
 * @author Ivica Cardic
 */
public class RedactedToolExecutionException extends RuntimeException {

    public RedactedToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
