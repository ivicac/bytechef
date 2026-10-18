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

package com.bytechef.automation.task.web.graphql;

import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.automation.task.domain.ApprovalTask;
import com.bytechef.automation.task.domain.ApprovalTask.Priority;
import com.bytechef.automation.task.domain.ApprovalTask.Status;
import com.bytechef.automation.task.domain.PendingApproval;
import com.bytechef.automation.task.facade.ApprovalTaskFacade;
import com.bytechef.automation.task.service.ApprovalTaskService;
import com.bytechef.graphql.error.GraphQlBadRequestException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import graphql.schema.DataFetchingEnvironment;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/**
 * @author Ivica Cardic
 */
@Controller
@ConditionalOnCoordinator
public class ApprovalTaskGraphQlController {

    private final ApprovalTaskFacade approvalTaskFacade;
    private final ApprovalTaskService approvalTaskService;

    @SuppressFBWarnings("EI")
    public ApprovalTaskGraphQlController(
        ApprovalTaskFacade approvalTaskFacade, ApprovalTaskService approvalTaskService) {

        this.approvalTaskFacade = approvalTaskFacade;
        this.approvalTaskService = approvalTaskService;
    }

    @MutationMapping
    @PreAuthorize("isTenantAdmin()")
    public ApprovalTask createApprovalTask(@Argument ApprovalTaskInput approvalTask) {
        return approvalTaskService.create(toApprovalTask(approvalTask));
    }

    @MutationMapping
    @PreAuthorize("isTenantAdmin()")
    public boolean deleteApprovalTask(@Argument long id) {
        approvalTaskService.delete(id);

        return true;
    }

    // The ApprovalTask reads expose the row's jobResumeId — the signed resume capability token that, via the hosted
    // form's one-click links, is sufficient to approve/reject the run. getApprovalTasks(environmentId) is NOT scoped to
    // an assignee, so leaving these open to any authenticated member lets a low-privileged user of one workspace
    // enumerate and hijack every pending approval in the tenant. Restricted to tenant admins, matching pendingApprovals
    // below. A future per-user inbox (returning tasks where assigneeId == the caller) would relax this for assignees;
    // that needs current-user-id resolution the assignee_id (a user id, not a login) matching requires.
    @QueryMapping
    @PreAuthorize("isTenantAdmin()")
    public ApprovalTask approvalTask(@Argument long id) {
        return approvalTaskService.getApprovalTask(id);
    }

    @QueryMapping
    @PreAuthorize("isTenantAdmin()")
    public List<ApprovalTask> approvalTasks(@Argument Integer environmentId) {
        return approvalTaskService.getApprovalTasks(environmentId);
    }

    @QueryMapping
    @PreAuthorize("isTenantAdmin()")
    public List<ApprovalTask> approvalTasksByIds(@Argument List<Long> ids) {
        return approvalTaskService.getApprovalTasks(ids);
    }

    /**
     * Lists every run in the tenant currently blocked on an approval. Each {@link PendingApproval} embeds the run's
     * hosted-form URL, which carries the signed resume capability token — approving/rejecting the run needs nothing
     * more than that URL. The listing is not scoped to an assignee or workspace, so it is restricted to tenant admins
     * to prevent a low-privileged member from enumerating and hijacking every pending approval in the tenant.
     */
    @QueryMapping
    @PreAuthorize("isTenantAdmin()")
    public List<PendingApproval> pendingApprovals(@Argument Integer environmentId) {
        return approvalTaskFacade.getPendingApprovals(environmentId);
    }

    /**
     * Patches the fields present in the input and keeps the stored value of every omitted one. A status change the
     * task's lifecycle rejects (reopening or resolving it) is reported as a bad request.
     */
    @MutationMapping
    @PreAuthorize("isTenantAdmin()")
    public ApprovalTask updateApprovalTask(
        @Argument ApprovalTaskInput approvalTask, DataFetchingEnvironment dataFetchingEnvironment) {

        Map<String, Object> approvalTaskArgument = dataFetchingEnvironment.getArgument("approvalTask");

        Set<String> providedFieldNames = approvalTaskArgument == null ? Set.of() : approvalTaskArgument.keySet();

        ApprovalTask currentApprovalTask = approvalTaskService.getApprovalTask(
            Objects.requireNonNull(approvalTask.id(), "'id' must not be null"));

        ApprovalTask patchedApprovalTask = ApprovalTask.builder()
            .id(approvalTask.id())
            .name(providedFieldNames.contains("name") ? approvalTask.name() : currentApprovalTask.getName())
            .description(
                providedFieldNames.contains("description")
                    ? approvalTask.description() : currentApprovalTask.getDescription())
            .status(providedFieldNames.contains("status") ? approvalTask.status() : currentApprovalTask.getStatus())
            .priority(
                providedFieldNames.contains("priority") ? approvalTask.priority() : currentApprovalTask.getPriority())
            .assigneeId(
                providedFieldNames.contains("assigneeId")
                    ? approvalTask.assigneeId() : currentApprovalTask.getAssigneeId())
            .dueDate(
                providedFieldNames.contains("dueDate")
                    ? toInstant(approvalTask.dueDate()) : currentApprovalTask.getDueDate())
            .version(
                providedFieldNames.contains("version") && approvalTask.version() != null
                    ? approvalTask.version() : currentApprovalTask.getVersion())
            .build();

        try {
            return approvalTaskService.update(patchedApprovalTask);
        } catch (IllegalStateException illegalStateException) {
            throw new GraphQlBadRequestException(illegalStateException.getMessage(), illegalStateException);
        }
    }

    private static @Nullable Instant toInstant(@Nullable String dueDate) {
        return dueDate == null ? null : Instant.parse(dueDate);
    }

    private ApprovalTask toApprovalTask(ApprovalTaskInput approvalTaskInput) {
        return ApprovalTask.builder()
            .id(approvalTaskInput.id())
            .name(approvalTaskInput.name())
            .description(approvalTaskInput.description())
            .status(approvalTaskInput.status())
            .priority(approvalTaskInput.priority())
            .assigneeId(approvalTaskInput.assigneeId())
            .dueDate(toInstant(approvalTaskInput.dueDate()))
            .version(approvalTaskInput.version() == null ? 0 : approvalTaskInput.version())
            .build();
    }

    public record ApprovalTaskInput(
        Long id, String name, String description, Status status, Priority priority, Long assigneeId, String dueDate,
        Integer version) {
    }
}
