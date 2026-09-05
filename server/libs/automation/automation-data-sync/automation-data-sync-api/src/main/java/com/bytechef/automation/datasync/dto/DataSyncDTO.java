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

import com.bytechef.automation.datasync.domain.DataSync;
import com.bytechef.automation.datasync.domain.DataSyncElement;
import com.bytechef.platform.security.domain.ResourceVisibility;
import com.bytechef.platform.tag.domain.Tag;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Read-model returned by {@link com.bytechef.automation.datasync.facade.DataSyncFacade} — the {@link DataSync} row plus
 * its {@link DataSyncElement} rows, and derived fields describing the draft &harr; published relationship of the Data
 * Sync's hidden backing project.
 *
 * @param dataSync             the Data Sync row
 * @param elements             the Data Sync's SOURCE/DESTINATION/PROCESSOR rows
 * @param unpublishedChanges   {@code true} when the backing project has never been published, or when its current draft
 *                             workflow was modified after the most recently published {@code ProjectVersion}
 * @param lastPublishedVersion the backing project's most recently published version number, or {@code 0} if it has
 *                             never been published
 * @param publishedDate        when the backing project's most recently published version was published, or {@code null}
 *                             if it has never been published
 * @param tags                 the Data Sync's tags, resolved from its {@code data_sync_tag} rows
 * @param visibility           who may see the Data Sync, read from the backing project rather than stored on
 *                             {@code data_sync}
 * @param draftWorkflowId      the id of the hidden project's current draft workflow
 *
 * @author Ivica Cardic
 */
@SuppressFBWarnings({
    "EI_EXPOSE_REP", "EI_EXPOSE_REP2"
})
public record DataSyncDTO(
    DataSync dataSync, List<DataSyncElement> elements, boolean unpublishedChanges, int lastPublishedVersion,
    @Nullable Instant publishedDate, List<Tag> tags, ResourceVisibility visibility, String draftWorkflowId) {
}
