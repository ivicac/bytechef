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

package com.bytechef.component.daytona.action;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class DaytonaExecuteCodeActionTest {

    @Test
    void testToResultReadsResultAsStdout() {
        Map<String, Object> result = DaytonaExecuteCodeAction.toResult(
            Map.of("exitCode", 0, "result", "hello world"));

        assertThat(result)
            .containsEntry("exitCode", 0)
            .containsEntry("stdout", "hello world")
            .containsEntry("success", true);
    }

    @Test
    void testToResultFallsBackToArtifactsStdout() {
        Map<String, Object> result = DaytonaExecuteCodeAction.toResult(
            Map.of("exitCode", 0, "artifacts", Map.of("stdout", "from artifacts")));

        assertThat(result).containsEntry("stdout", "from artifacts");
    }

    @Test
    void testToResultMarksNonZeroExitAsFailure() {
        Map<String, Object> result = DaytonaExecuteCodeAction.toResult(
            Map.of("exitCode", 1, "result", "boom"));

        assertThat(result)
            .containsEntry("exitCode", 1)
            .containsEntry("success", false);
    }

    @Test
    void testToResultDefaultsMissingFields() {
        Map<String, Object> result = DaytonaExecuteCodeAction.toResult(Map.of());

        assertThat(result)
            .containsEntry("exitCode", 0)
            .containsEntry("stdout", "")
            .containsEntry("success", true);
    }
}
