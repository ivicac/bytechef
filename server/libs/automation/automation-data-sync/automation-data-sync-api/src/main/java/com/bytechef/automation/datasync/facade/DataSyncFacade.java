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

package com.bytechef.automation.datasync.facade;

import com.bytechef.automation.datasync.domain.DataSync.TriggerType;
import com.bytechef.automation.datasync.domain.DataSyncElement;
import com.bytechef.automation.datasync.domain.DataSyncElement.Kind;
import com.bytechef.automation.datasync.dto.DataSyncDTO;
import com.bytechef.automation.datasync.dto.DataSyncDeploymentDTO;
import com.bytechef.automation.datasync.dto.DataSyncVersionDTO;
import com.bytechef.platform.tag.domain.Tag;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The HTTP surface of Data Sync, and the owner of its authorization: every method carries a {@code @PreAuthorize} on
 * the implementation. Every mutation regenerates the hidden project's draft workflow before returning.
 *
 * @author Ivica Cardic
 */
public interface DataSyncFacade {

    DataSyncDTO createDataSync(String title, @Nullable String description, long workspaceId);

    DataSyncDTO updateDataSync(long id, @Nullable String title, @Nullable String description);

    void deleteDataSync(long id);

    DataSyncDTO getDataSync(long id);

    List<DataSyncDTO> getDataSyncs(long workspaceId);

    void updateDataSyncTrigger(long id, TriggerType triggerType, @Nullable Map<String, ?> triggerParameters);

    /**
     * Upserts the element of {@code kind}. Replacing the {@code SOURCE} or {@code DESTINATION} component also deletes
     * the {@code PROCESSOR} row, because a field mapping over a different side is meaningless.
     */
    DataSyncElement setDataSyncElement(
        long id, Kind kind, String componentName, int componentVersion, String operationName,
        @Nullable Map<String, ?> parameters, @Nullable Long connectionId);

    /** Whole-map replace of the element's parameters and connection; the per-field autosave target. */
    void updateDataSyncElement(long elementId, @Nullable Map<String, ?> parameters, @Nullable Long connectionId);

    int publishDataSync(long id, @Nullable String description);

    List<DataSyncVersionDTO> getDataSyncVersions(long id);

    List<DataSyncDeploymentDTO> getDataSyncDeployments(long workspaceId);

    /** Starts one run of the deployed workflow, for either trigger type. Returns the job id. */
    long runDataSyncDeployment(long id, long projectDeploymentId);

    List<Tag> getDataSyncTags(long workspaceId);

    void updateDataSyncTags(long id, List<Tag> tags);

    List<Tag> getDataSyncDeploymentTags(long workspaceId);

    void updateDataSyncDeploymentTags(long id, long projectDeploymentId, List<Tag> tags);
}
