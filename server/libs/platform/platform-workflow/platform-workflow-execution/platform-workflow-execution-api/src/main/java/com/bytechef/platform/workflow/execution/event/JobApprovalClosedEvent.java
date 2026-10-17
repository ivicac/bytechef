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

package com.bytechef.platform.workflow.execution.event;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Published when a run waiting on an approval ends without a decision, because the approval expired or the run was
 * stopped.
 *
 * @param jobId       the run whose approval closed
 * @param jobResumeId the encoded resume id of the closed approval, when known
 * @param reason      how the approval closed
 *
 * @author Ivica Cardic
 */
public record JobApprovalClosedEvent(long jobId, @Nullable String jobResumeId, Reason reason) {

    public JobApprovalClosedEvent {
        Objects.requireNonNull(reason, "reason");
    }

    public enum Reason {
        CANCELLED, EXPIRED
    }
}
