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

package com.bytechef.automation.configuration.listener;

import java.util.Map;

/**
 * Carries a feature's project-owned content — content that lives in a project but is not an ordinary workflow, such as
 * AI agents — through the project operations that copy or move a project: duplicate, export/import and git sync.
 * <p>
 * Exported content is a set of files keyed by a relative path under {@link #getContentDirectory()}, e.g.
 * {@code agents/support-bot.json}; the same files go into a project export archive and into a project's git repository,
 * next to the workflow files. An implementation runs inside the caller's transaction and under the caller's security
 * context, throws rather than swallows, and must not depend on {@code ProjectFacade}.
 *
 * @author Ivica Cardic
 */
public interface ProjectContentContributor {

    /**
     * The directory, relative and ending with {@code /}, under which this contributor's files live. Files whose path
     * starts with it are handed to this contributor on import and git pull, and never read as workflows.
     */
    String getContentDirectory();

    /**
     * Copies the content of {@code sourceProjectId} into the freshly created {@code duplicateProjectId}, in the same
     * workspace.
     */
    void onProjectDuplicated(long sourceProjectId, long duplicateProjectId);

    /**
     * Returns the project's content as files, keyed by their path under {@link #getContentDirectory()}.
     */
    Map<String, byte[]> exportProjectContent(long projectId);

    /**
     * Recreates exported content in the newly imported {@code projectId}. {@code files} holds only the files under
     * {@link #getContentDirectory()}.
     */
    void importProjectContent(long projectId, long workspaceId, Map<String, byte[]> files);

    /**
     * Applies content pulled from a project's git repository to the existing {@code projectId}: content already in the
     * project is updated from its file and content new to the project is created. Content missing from {@code files} is
     * left alone, as workflows missing from the repository are.
     */
    void pullProjectContent(long projectId, Map<String, byte[]> files);
}
