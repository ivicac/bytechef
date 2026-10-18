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

package com.bytechef.platform.workflow.execution.token;

import java.time.Instant;

/**
 * Mints short ids standing for approval resume tokens, for approval channels whose button payloads cannot hold a whole
 * token (Telegram {@code callback_data}, Discord {@code custom_id}).
 *
 * @author Ivica Cardic
 */
public interface ApprovalShortResumeIds {

    /**
     * @param resumeToken the possibly signed resume token the short id stands for
     * @param expiresAt   when the approval expires; the short id stays resolvable until then plus
     *                    {@link ApprovalFormUrls#APPROVAL_TOKEN_TTL_MARGIN}
     * @return the short id
     */
    String mint(String resumeToken, Instant expiresAt);
}
