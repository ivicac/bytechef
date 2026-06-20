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

package com.bytechef.automation.task.facade;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.domain.TaskExecution;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.atlas.execution.service.TaskExecutionService;
import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.task.audit.ApprovalTaskAuditEvents;
import com.bytechef.automation.task.domain.ApprovalTask;
import com.bytechef.automation.task.domain.PendingApproval;
import com.bytechef.automation.task.service.ApprovalTaskService;
import com.bytechef.commons.util.MapUtils;
import com.bytechef.platform.audit.AuditData;
import com.bytechef.platform.audit.Audited;
import com.bytechef.platform.component.constant.MetadataConstants;
import com.bytechef.platform.component.definition.SuspendUtils;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.workflow.execution.JobResumeId;
import com.bytechef.platform.workflow.execution.JobSuspends;
import com.bytechef.platform.workflow.execution.service.PrincipalJobService;
import com.bytechef.platform.workflow.execution.token.ApprovalTokens;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * @author Ivica Cardic
 */
@Service
@Transactional
public class ApprovalTaskFacadeImpl implements ApprovalTaskFacade {

    private static final Logger log = LoggerFactory.getLogger(ApprovalTaskFacadeImpl.class);

    private final ApprovalTaskService approvalTaskService;
    private final ApprovalTokens approvalTokens;
    private final PrincipalJobService principalJobService;
    private final ProjectDeploymentService projectDeploymentService;
    private final @Nullable String publicUrl;
    private final TaskExecutionService taskExecutionService;
    private final WorkflowService workflowService;

    @SuppressFBWarnings("EI")
    public ApprovalTaskFacadeImpl(
        ApprovalTaskService approvalTaskService, ApprovalTokens approvalTokens, PrincipalJobService principalJobService,
        ProjectDeploymentService projectDeploymentService) {

        this.approvalTaskService = approvalTaskService;
        this.approvalTokens = approvalTokens;
        this.principalJobService = principalJobService;
        this.projectDeploymentService = projectDeploymentService;
        this.publicUrl = publicUrl;
        this.taskExecutionService = taskExecutionService;
        this.workflowService = workflowService;
    }

    @Override
    public ApprovalTask createApprovalTask(ApprovalTask approvalTask) {
        String innerToken = resolveInnerToken(approvalTask.getJobResumeId());

        approvalTask.setJobResumeId(innerToken);

        approvalTask.setEnvironment(getEnvironment(innerToken));

        return approvalTaskService.create(approvalTask);
    }

    private String resolveInnerToken(String jobResumeIdString) {
        Assert.notNull(jobResumeIdString, "'jobResumeId' must not be null");

        return approvalTokens.resolveInnerToken(jobResumeIdString)
            .orElseThrow(() -> new IllegalArgumentException("Invalid or expired approval token"));
    }

    private Environment getEnvironment(String jobResumeIdString) {
        JobResumeId jobResumeId = JobResumeId.parse(jobResumeIdString);

        if (projectDeploymentIdOptional.isEmpty()) {
            return Environment.DEVELOPMENT;
        }

        ProjectDeployment projectDeployment = projectDeploymentService.getProjectDeployment(
            projectDeploymentIdOptional.get());

        return projectDeployment.getEnvironment();
    }
}
