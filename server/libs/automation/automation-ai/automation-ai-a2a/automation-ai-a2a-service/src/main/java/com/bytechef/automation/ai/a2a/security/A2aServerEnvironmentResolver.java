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

package com.bytechef.automation.ai.a2a.security;

import com.bytechef.automation.ai.a2a.domain.A2aServer;
import com.bytechef.automation.ai.a2a.service.A2aServerService;
import com.bytechef.automation.configuration.security.ResourceEnvironmentResolver;
import com.bytechef.platform.configuration.domain.Environment;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.Serializable;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * @author Ivica Cardic
 */
@Component
public class A2aServerEnvironmentResolver implements ResourceEnvironmentResolver {

    private final A2aServerService a2aServerService;

    @SuppressFBWarnings("EI")
    public A2aServerEnvironmentResolver(A2aServerService a2aServerService) {
        this.a2aServerService = a2aServerService;
    }

    @Override
    public String resourceType() {
        return "A2aServer";
    }

    @Override
    public Optional<Environment> fetchEnvironment(Serializable id) {
        if (!(id instanceof Number number)) {
            return Optional.empty();
        }

        try {
            A2aServer a2aServer = a2aServerService.getA2aServer(number.longValue());

            return Optional.of(a2aServer.getEnvironment());
        } catch (RuntimeException runtimeException) {
            return Optional.empty();
        }
    }
}
