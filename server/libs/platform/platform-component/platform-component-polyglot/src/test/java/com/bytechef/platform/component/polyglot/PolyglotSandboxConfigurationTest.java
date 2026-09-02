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

package com.bytechef.platform.component.polyglot;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.config.ApplicationProperties;
import org.junit.jupiter.api.Test;

/**
 * Pins the two copies of the sandbox defaults to each other.
 *
 * <p>
 * The ceilings are declared twice - as field initialisers on {@code ApplicationProperties.Script.Sandbox}, which Spring
 * binds {@code bytechef.script.sandbox.*} onto, and as the {@code DEFAULT_*} constants
 * {@link PolyglotSandboxSettings#defaults()} builds from, which every caller reaching {@link PolyglotSandbox} without a
 * container uses. The duplication is structural: {@code app-config} cannot depend on this module, because the
 * dependency runs the other way. So it is pinned by assertion instead - without this, editing one default would leave a
 * configured deployment and an unconfigured one silently metering guest code differently.
 *
 * @author Ivica Cardic
 */
public class PolyglotSandboxConfigurationTest {

    @Test
    public void testDefaultApplicationPropertiesYieldTheDefaultSettings() {
        PolyglotSandboxConfiguration polyglotSandboxConfiguration = new PolyglotSandboxConfiguration(
            new ApplicationProperties());

        assertThat(polyglotSandboxConfiguration.getPolyglotSandboxSettings())
            .isEqualTo(PolyglotSandboxSettings.defaults());
    }
}
