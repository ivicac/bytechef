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

package com.bytechef.component.ai.llm.tool;

/**
 * Thrown by {@link ToolApprovalRequests#raise} when an approval cannot be raised because the execution has no resume
 * URL — there is no job to suspend, as in a voice session. A subclass of {@link IllegalStateException} so every
 * existing {@code catch (IllegalStateException ...)} around a tool call keeps working unchanged.
 *
 * @author Ivica Cardic
 */
public class ApprovalUnavailableException extends IllegalStateException {

    public ApprovalUnavailableException(String message) {
        super(message);
    }
}
