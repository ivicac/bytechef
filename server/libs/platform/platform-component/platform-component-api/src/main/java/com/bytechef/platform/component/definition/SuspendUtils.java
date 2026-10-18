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

package com.bytechef.platform.component.definition;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.platform.component.constant.MetadataConstants;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Utility methods for finalizing a suspend request after a streaming action completes. Used by post-output processors
 * (Task 12) to replicate the resume-URL / {@code JobResumeId} enrichment that
 * {@code ActionDefinitionServiceImpl.checkSuspend} performs for non-streaming actions.
 *
 * <p>
 * Note: {@code BeforeSuspendConsumer} invocation is intentionally omitted here — it requires the
 * {@code ActionDefinition} and an error-type token from the service layer. AI agent actions (the only streaming suspend
 * callers) do not declare a {@code BeforeSuspendConsumer}, so this omission is acceptable for the streaming path.
 * </p>
 *
 * @author Ivica Cardic
 */
public class SuspendUtils {

    private SuspendUtils() {
    }

    /**
     * Reads {@code actionContextAware.getSuspend()} and, if non-null, enriches the {@code continueParameters} map with
     * the {@code jobResumeId} (when one was generated) and returns the final {@link ActionContext.Suspend}. Returns
     * {@code null} when no suspension was requested.
     *
     * @param actionContextAware the action context to inspect
     * @return the finalized {@link ActionContext.Suspend}, or {@code null} if the action did not suspend
     */
    public static ActionContext.@Nullable Suspend finalizeSuspend(ActionContextAware actionContextAware) {
        ActionContext.Suspend suspend = actionContextAware.getSuspend();

        if (suspend == null) {
            return null;
        }

        String jobResumeId = actionContextAware.getJobResumeId();

        if (jobResumeId != null) {
            Map<String, Object> continueParameters = new HashMap<>(suspend.continueParameters());

            continueParameters.put(MetadataConstants.JOB_RESUME_ID, jobResumeId);

            return new ActionContext.Suspend(continueParameters, suspend.expiresAt());
        }

        return suspend;
    }

    /**
     * Extracts the suspend expiry from a task execution's metadata (the {@code suspend.expiresAt} value the worker
     * persisted, or the {@link ActionContext.Suspend} recorded when the task suspended), or {@code null} when the
     * metadata carries no suspend or no expiry. Handles both the epoch-millis number form (JSON round-trip) and the
     * ISO-8601 string form.
     *
     * @param taskExecutionMetadata the suspended task execution's metadata map
     * @return the expiry instant, or {@code null} when absent
     */
    @SuppressWarnings("unchecked")
    public static @Nullable Instant extractSuspendExpiresAt(Map<String, ?> taskExecutionMetadata) {
        Object suspendValue = taskExecutionMetadata.get(MetadataConstants.SUSPEND);

        if (suspendValue instanceof ActionContext.Suspend suspend) {
            return suspend.expiresAt();
        }

        if (!(suspendValue instanceof Map)) {
            return null;
        }

        return toInstant(((Map<String, ?>) suspendValue).get("expiresAt"));
    }

    /**
     * Extracts when the pending approval was raised ({@link MetadataConstants#APPROVAL_RAISED_AT} in the suspend's
     * continue parameters) from a task execution's metadata, or {@code null} when the suspend does not record it.
     *
     * @param taskExecutionMetadata the suspended task execution's metadata map
     * @return the instant the approval was raised, or {@code null} when absent
     */
    @SuppressWarnings("unchecked")
    public static @Nullable Instant extractApprovalRaisedAt(Map<String, ?> taskExecutionMetadata) {
        Object suspendValue = taskExecutionMetadata.get(MetadataConstants.SUSPEND);

        Object continueParametersValue;

        if (suspendValue instanceof ActionContext.Suspend suspend) {
            continueParametersValue = suspend.continueParameters();
        } else if (suspendValue instanceof Map) {
            continueParametersValue = ((Map<String, ?>) suspendValue).get("continueParameters");
        } else {
            return null;
        }

        if (!(continueParametersValue instanceof Map)) {
            return null;
        }

        return toInstant(((Map<String, ?>) continueParametersValue).get(MetadataConstants.APPROVAL_RAISED_AT));
    }

    private static @Nullable Instant toInstant(@Nullable Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }

        if (value instanceof Number number) {
            return Instant.ofEpochMilli(number.longValue());
        }

        if (value instanceof String string) {
            try {
                return Instant.parse(string);
            } catch (DateTimeParseException dateTimeParseException) {
                return null;
            }
        }

        return null;
    }
}
