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

package com.bytechef.platform.workflow.coordinator.trigger.completion;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.configuration.domain.WorkflowTrigger;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.platform.workflow.coordinator.trigger.jobparameter.TriggerJobParameterContributor;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link TriggerCompletionHandler#assembleJobParameters}. The wider {@code handle()} path has too many
 * side-effects (event publishing, job-facade calls, trigger-state persistence) to mock cleanly just to exercise the
 * metadata merge. The merge logic was deliberately extracted into a package-private static helper so it can be
 * unit-tested as a pure function of its inputs.
 *
 * <p>
 * Five scenarios matter, each covered below:
 * <ol>
 * <li>No trigger jobParameters block, no contributors → empty result (pre-17b path).</li>
 * <li>Static jobParameters block only → block becomes the result verbatim.</li>
 * <li>Contributor only, no static block → contributor's entries become the result.</li>
 * <li>Both → contributor wins on key collisions (later layer wins).</li>
 * <li>Misbehaving contributor (throws) → skipped without blocking the dispatch.</li>
 * </ol>
 *
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
class TriggerCompletionHandlerTest {

    private static final String TRIGGER_NAME = "scheduledIncrementalSync";

    @Test
    void testAssembleJobParametersReturnsEmptyWhenNoSourceContributes() {
        Map<String, Object> result = TriggerCompletionHandler.assembleJobParameters(
            triggerWithoutJobParameters(), Map.of(), null, List.of());

        // Pre-17b shape: trigger has no jobParameters block, no contributors registered. Empty result tells the
        // caller to leave the principal metadataMap untouched — the spawned job carries no __jobParameters
        // entry and the dataStream reader falls back to its baked task parameters.
        assertThat(result).isEmpty();
    }

    @Test
    void testAssembleJobParametersFromTriggerStaticBlock() {
        Map<String, Object> result = TriggerCompletionHandler.assembleJobParameters(
            triggerWithJobParameters(Map.of("datastream.mode", "PARTIAL")), Map.of(), null, List.of());

        // The trigger JSON carries jobParameters.datastream.mode = PARTIAL (set by the dual-trigger workflow
        // generators from commit 4). Without any contributors, the static block is the entire result.
        assertThat(result).hasSize(1);
        assertThat(result).containsEntry("datastream.mode", "PARTIAL");
    }

    @Test
    void testAssembleJobParametersFromContributorOnly() {
        TriggerJobParameterContributor contributor = (metadata, executionId) -> Map.of(
            "datastream.since", 1700000000000L);

        Map<String, Object> result = TriggerCompletionHandler.assembleJobParameters(
            triggerWithoutJobParameters(), Map.of(), null, List.of(contributor));

        assertThat(result).hasSize(1);
        assertThat(result).containsEntry("datastream.since", 1700000000000L);
    }

    @Test
    void testAssembleJobParametersMergesTriggerBlockAndContributors() {
        // Contributor adds datastream.since to the trigger's static datastream.mode entry. Disjoint keys —
        // both end up in the final map.
        TriggerJobParameterContributor contributor = (metadata, executionId) -> Map.of(
            "datastream.since", 1700000000000L);

        Map<String, Object> result = TriggerCompletionHandler.assembleJobParameters(
            triggerWithJobParameters(Map.of("datastream.mode", "PARTIAL")), Map.of(), null, List.of(contributor));

        assertThat(result).hasSize(2);
        assertThat(result).containsEntry("datastream.mode", "PARTIAL");
        assertThat(result).containsEntry("datastream.since", 1700000000000L);
    }

    @Test
    void testAssembleJobParametersContributorWinsOnKeyCollision() {
        // Adversarial contributor overrides the trigger's PARTIAL with FULL_REPLACE. Contributors layer on top
        // of the static block (later wins) — documented in JobMetadataKeys / TriggerJobParameterContributor
        // Javadoc. In practice contributors should pick disjoint key prefixes; this test pins the merge order.
        TriggerJobParameterContributor contributor = (metadata, executionId) -> Map.of(
            "datastream.mode", "FULL_REPLACE");

        Map<String, Object> result = TriggerCompletionHandler.assembleJobParameters(
            triggerWithJobParameters(Map.of("datastream.mode", "PARTIAL")), Map.of(), null, List.of(contributor));

        assertThat(result).hasSize(1);
        assertThat(result).containsEntry("datastream.mode", "FULL_REPLACE");
    }

    @Test
    void testAssembleJobParametersSkipsMisbehavingContributor() {
        TriggerJobParameterContributor throwingContributor = (metadata, executionId) -> {
            throw new RuntimeException("contributor exploded");
        };

        TriggerJobParameterContributor sensibleContributor = (metadata, executionId) -> Map.of(
            "datastream.since", 1700000000000L);

        Map<String, Object> result = TriggerCompletionHandler.assembleJobParameters(
            triggerWithJobParameters(Map.of("datastream.mode", "PARTIAL")), Map.of(), null,
            List.of(throwingContributor, sensibleContributor));

        // The throwing contributor is skipped (its exception is logged but swallowed); the sensible one still
        // contributes; the trigger's static block is preserved. The SPI contract says contributors MUST NOT
        // throw, but defending against the bad case keeps one misbehaving impl from breaking the platform.
        assertThat(result).hasSize(2);
        assertThat(result).containsEntry("datastream.mode", "PARTIAL");
        assertThat(result).containsEntry("datastream.since", 1700000000000L);
    }

    @Test
    void testAssembleJobParametersHandlesNullTrigger() {
        // The handler's enrichMetadata path passes null when WorkflowTrigger.of(triggerName, workflow) returns
        // null (no matching trigger by name). Helper must not NPE — only contributors run in that case.
        TriggerJobParameterContributor contributor = (metadata, executionId) -> Map.of(
            "datastream.since", 1700000000000L);

        Map<String, Object> result = TriggerCompletionHandler.assembleJobParameters(
            null, Map.of(), null, List.of(contributor));

        assertThat(result).hasSize(1);
        assertThat(result).containsEntry("datastream.since", 1700000000000L);
    }

    @Test
    void testAssembleJobParametersPassesWorkflowMetadataToContributors() {
        // The contributor receives the workflow's static metadata block — that's what carries
        // knowledgeBaseSourceId / contextStoreSourceId, the discriminator keys the real contributors look at.
        Map<String, Object> workflowMetadata = Map.of("knowledgeBaseSourceId", 42L);

        TriggerJobParameterContributor recording = new TriggerJobParameterContributor() {

            @Override
            public Map<String, ?> contribute(Map<String, ?> metadata, WorkflowExecutionId workflowExecutionId) {
                // Echo the metadata back so the test can verify it was threaded through.
                return Map.of("seen-id", metadata.get("knowledgeBaseSourceId"));
            }
        };

        Map<String, Object> result = TriggerCompletionHandler.assembleJobParameters(
            triggerWithoutJobParameters(), workflowMetadata, null, List.of(recording));

        assertThat(result).containsEntry("seen-id", 42L);
    }

    /**
     * Constructs a {@link WorkflowTrigger} via its {@code Map}-source ctor — bypasses the workflow mapper that the
     * {@code Workflow} domain ctor depends on, which would otherwise require the platform's reserved-word registration
     * and full Spring boot-up.
     */
    private static WorkflowTrigger triggerWithJobParameters(Map<String, Object> jobParameters) {
        return new WorkflowTrigger(Map.of(
            "name", TRIGGER_NAME,
            "type", "schedule/v1/cron",
            "jobParameters", jobParameters));
    }

    private static WorkflowTrigger triggerWithoutJobParameters() {
        return new WorkflowTrigger(Map.of(
            "name", TRIGGER_NAME,
            "type", "schedule/v1/cron"));
    }

    @Disabled("Wider handle() path covered by E2E IntTest queued for Phase 17b sub-task 6.")
    @Test
    public void testHandle() {
        // The full handle() flow runs trigger-status persistence, principal-job-facade dispatch, event
        // publishing, etc. — covered by the end-to-end paired-cadence IntTest that lands once the dataStream
        // action consumes __jobParameters (sub-tasks 5d + 5e). The targeted unit tests above cover the
        // Phase 17b merge logic in isolation.
    }
}
