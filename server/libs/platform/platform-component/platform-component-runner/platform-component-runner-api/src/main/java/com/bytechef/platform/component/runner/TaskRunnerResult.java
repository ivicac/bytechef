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
import java.util.HashMap;
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

    /**
     * Returns the {@code {exitCode, stdout, stderr, vars, outputFiles}} map an external runner's action hands back to
     * the workflow.
     *
     * <p>
     * The keys were pulled into {@link TaskRunnerConstants} so {@code script} and {@code commands} could not drift
     * apart on their spelling, but each module still assembled the map itself - so a sixth key added to one would
     * simply never appear in the other. The assembly lives here for the same reason the keys do.
     *
     * <p>
     * A {@link java.util.HashMap} rather than {@code Map.of(...)}: {@link #exitCode()} is null for an in-process runner
     * that has none, and {@code Map.of} throws on a null value.
     */
    public Map<String, Object> toExternalMap() {
        Map<String, Object> map = new HashMap<>();

        map.put(TaskRunnerConstants.RESULT_EXIT_CODE, exitCode);
        map.put(TaskRunnerConstants.RESULT_STDOUT, stdout);
        map.put(TaskRunnerConstants.RESULT_STDERR, stderr);
        map.put(TaskRunnerConstants.RESULT_VARS, output);
        map.put(TaskRunnerConstants.OUTPUT_FILES, outputFiles);

        return map;
    }
}
