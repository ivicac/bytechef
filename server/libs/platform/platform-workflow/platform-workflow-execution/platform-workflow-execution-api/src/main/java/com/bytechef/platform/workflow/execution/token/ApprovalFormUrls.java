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

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Builds the public hosted approval-form URL for a run suspended on a pending approval. The stored job-resume id (the
 * unsigned inner token from the job's {@code jobResumeId} metadata) is wrapped in an HMAC-signed token when an
 * {@link ApprovalTokens} signer is configured — the same signed-vs-legacy fallback the action context applies when
 * minting resume URLs — and appended to the SPA's {@code /resume/} route.
 *
 * @author Ivica Cardic
 */
public final class ApprovalFormUrls {

    private ApprovalFormUrls() {
    }

    public static Optional<String> buildFormUrl(
        @Nullable String publicUrl, @Nullable String jobResumeId, @Nullable ApprovalTokens approvalTokens) {

        if (publicUrl == null || publicUrl.isBlank() || jobResumeId == null || jobResumeId.isBlank()) {
            return Optional.empty();
        }

        String token = approvalTokens == null
            ? jobResumeId
            : approvalTokens.toSignedTokenIfConfigured(jobResumeId)
                .orElse(jobResumeId);

        return Optional.of(publicUrl + "/resume/" + token);
    }
}
