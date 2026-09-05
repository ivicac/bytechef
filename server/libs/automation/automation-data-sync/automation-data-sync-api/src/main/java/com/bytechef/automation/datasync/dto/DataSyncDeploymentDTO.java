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

package com.bytechef.automation.datasync.dto;

import com.bytechef.automation.datasync.domain.DataSync.TriggerType;
import com.bytechef.platform.tag.domain.Tag;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Row of the Data Sync deployments listing: one {@code project_deployment} of a Data Sync's hidden backing project,
 * flattened with the fields the listing needs from the owning {@link com.bytechef.automation.datasync.domain.DataSync}
 * so the client does not have to join them itself.
 *
 * @param id                the {@code project_deployment} id
 * @param name              the deployment's name
 * @param dataSyncId        the owning Data Sync's id
 * @param dataSyncTitle     the owning Data Sync's title
 * @param projectId         the hidden backing project's id
 * @param environmentId     the {@link com.bytechef.platform.configuration.domain.Environment} ordinal this deployment
 *                          targets
 * @param enabled           whether the deployment is enabled
 * @param projectVersion    the deployed project version
 * @param triggerType       the owning Data Sync's trigger type at the time of this read
 * @param workflowId        the deployed workflow's id
 * @param tags              the deployment's tags
 * @param lastExecutionDate when the deployed workflow last ran, or {@code null} if it never has
 *
 * @author Ivica Cardic
 */
@SuppressFBWarnings("EI_EXPOSE_REP")
public record DataSyncDeploymentDTO(
    long id, String name, long dataSyncId, String dataSyncTitle, long projectId, int environmentId, boolean enabled,
    int projectVersion, TriggerType triggerType, String workflowId, List<Tag> tags,
    @Nullable Instant lastExecutionDate) {
}
