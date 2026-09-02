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

package com.bytechef.component.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.file.storage.TaskFileStorage;
import com.bytechef.component.script.task.handler.ScriptJavaScriptTaskHandler;
import com.bytechef.platform.component.test.ComponentJobTestExecutor;
import com.bytechef.platform.component.test.config.ComponentTestIntConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * The branch's headline capability, end to end: a real workflow, the real registry, the real {@code GraalVmTaskRunner},
 * and a real trusted polyglot context.
 *
 * <p>
 * The two tests run the SAME script under the two modes, which is what makes either of them mean anything. Reaching a
 * host class is the one thing a trusted context can do and a strict one cannot, so the trusted run succeeding is
 * evidence the mode reached the context rather than evidence that the script was harmless; and the strict run failing
 * on the same source is evidence the strict path was not quietly widened along the way.
 *
 * @author Ivica Cardic
 */
@SpringBootTest(
    classes = {
        ComponentTestIntConfiguration.class,
        ScriptComponentHandlerTrustedModeIntTest.ScriptComponentHandlerTrustedModeIntTestConfiguration.class
    },
    properties = {
        "bytechef.workflow.repository.classpath.enabled=true",
        "bytechef.script.runners.graalvm.enabled=true",
        // Trusted mode is a deliberate operator act, and GraalVmTaskRunner.validate rejects a workflow asking for it
        // without this flag - so the trusted test below cannot run without it.
        "bytechef.script.runners.graalvm.properties.trusted-enabled=true"
    })
public class ScriptComponentHandlerTrustedModeIntTest {

    private static final Base64.Encoder ENCODER = Base64.getEncoder();

    @Autowired
    private ComponentJobTestExecutor componentJobTestExecutor;

    @Autowired
    private ScriptJavaScriptTaskHandler scriptJavaScriptTaskHandler;

    @Autowired
    private TaskFileStorage taskFileStorage;

    @Test
    @Timeout(120)
    public void testPerformJavaScriptInTrustedModeReachesHostClasses() {
        Job job = componentJobTestExecutor.execute(
            ENCODER.encodeToString("script_v1_javascript_trusted".getBytes(StandardCharsets.UTF_8)),
            Map.of(), Map.of("script/v1/javascript", scriptJavaScriptTaskHandler));

        assertThat(job.getStatus()).isEqualTo(Job.Status.COMPLETED);

        Map<String, ?> outputs = taskFileStorage.readJobOutputs(job.getOutputs());

        assertThat((String) outputs.get("result")).isNotBlank();
    }

    @Test
    @Timeout(120)
    public void testPerformJavaScriptInStrictModeDeniesHostClasses() {
        assertThatThrownBy(
            () -> componentJobTestExecutor.execute(
                ENCODER.encodeToString("script_v1_javascript_strict_host_access".getBytes(StandardCharsets.UTF_8)),
                Map.of(), Map.of("script/v1/javascript", scriptJavaScriptTaskHandler)))
                    .hasMessageContaining("Access to host class java.lang.System is not allowed");
    }

    @ComponentScan(basePackages = "com.bytechef.component.script")
    @TestConfiguration
    public static class ScriptComponentHandlerTrustedModeIntTestConfiguration {
    }
}
