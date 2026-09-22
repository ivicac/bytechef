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

package com.bytechef.automation.configuration.domain;

/**
 * What a {@code project_workflow} row holds. {@link #AI_AGENT} rows are generated from an AI Agent's configuration and
 * are edited through the agent, never through the workflow editor, so workflow listings leave them out.
 *
 * @author Ivica Cardic
 */
public enum ProjectWorkflowType {

    // Persisted as INT ordinal - append new values at the end only.
    WORKFLOW, AI_AGENT
}
