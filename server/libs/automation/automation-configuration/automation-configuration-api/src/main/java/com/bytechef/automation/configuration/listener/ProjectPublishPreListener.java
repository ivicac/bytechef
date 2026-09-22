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

/**
 * Callback invoked at the very start of {@code ProjectService#publishProject}, before the draft version is stamped as
 * published and before its workflows are duplicated into the next draft.
 * <p>
 * A feature that generates workflows inside a project implements this to bring those drafts up to date, so that the
 * published snapshot is never older than the configuration it was generated from. An implementation runs inside the
 * caller's transaction and under the caller's security context, and throws to abort the publish.
 *
 * @author Ivica Cardic
 */
public interface ProjectPublishPreListener {

    void onBeforePublishProject(long projectId);
}
