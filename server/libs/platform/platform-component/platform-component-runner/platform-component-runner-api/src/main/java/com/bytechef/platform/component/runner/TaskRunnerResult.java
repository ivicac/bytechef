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

package com.bytechef.platform.component.runner;

import com.bytechef.component.definition.FileEntry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Everything one execution produced.
 *
 * @param output      the value the script returned, or the structured variables an external execution wrote
 * @param exitCode    the process exit code, or null for an in-process runner that has none
 * @param stdout      captured standard output, empty for an in-process runner
 * @param stderr      captured standard error, empty for an in-process runner
 * @param outputFiles files the execution produced, keyed by file name
 * @author Ivica Cardic
 */
@SuppressFBWarnings("EI")
public record TaskRunnerResult(
    @Nullable Object output, @Nullable Integer exitCode, String stdout, String stderr,
    Map<String, FileEntry> outputFiles) {

    public static TaskRunnerResult ofOutput(@Nullable Object output) {
        return new TaskRunnerResult(output, null, "", "", Map.of());
    }
}
