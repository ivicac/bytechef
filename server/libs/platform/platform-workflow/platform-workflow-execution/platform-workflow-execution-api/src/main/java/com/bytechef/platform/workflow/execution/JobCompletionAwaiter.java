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

package com.bytechef.platform.workflow.execution;

import com.bytechef.atlas.execution.domain.Job;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Awaits the terminal status of a job run by the real (possibly distributed) coordinator. The completion signal is the
 * broker-published {@code SSE_STREAM_EVENTS} job-status event (the only completion signal that crosses process
 * boundaries).
 *
 * <p>
 * Use it for synchronous callers whose job must outlive the request: the MCP tool and A2A surfaces, where a run can
 * suspend for an approval and be resumed later through {@code JobResumeFacade} — only a persisted, coordinator-driven
 * job can be resumed. Request-scoped runs that always finish within the call (synchronous webhooks and the API
 * Platform, the editor's Test button, simulation) use the in-process {@code JobSyncExecutor} instead.
 *
 * @author Ivica Cardic
 */
public interface JobCompletionAwaiter {

    /**
     * Default maximum wait for a synchronous job before it times out. Shared by every synchronous caller — the MCP and
     * A2A surfaces awaiting here, and the webhook sync path waiting on {@code JobSyncExecutor} — so the bound cannot
     * drift between paths. A tenant plan's {@code syncRunTimeout} may tighten it, never extend it.
     */
    Duration DEFAULT_SYNC_TIMEOUT = Duration.ofSeconds(300);

    /**
     * Returns a future that completes with the job once it reaches a terminal status ({@code COMPLETED},
     * {@code FAILED}, or {@code STOPPED}; suspend maps to {@code STOPPED}). Completes exceptionally with
     * {@link java.util.concurrent.TimeoutException} if {@code timeout} elapses first.
     *
     * @param jobId   the id of the job to await
     * @param timeout the maximum time to wait for the job to reach a terminal status
     * @return a future resolving with the completed job
     */
    CompletableFuture<Job> await(long jobId, Duration timeout);
}
