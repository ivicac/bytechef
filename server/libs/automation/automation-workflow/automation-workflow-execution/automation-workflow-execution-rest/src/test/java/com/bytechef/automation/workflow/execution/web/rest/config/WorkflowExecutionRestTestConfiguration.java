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

package com.bytechef.automation.workflow.execution.web.rest.config;

import com.bytechef.jackson.config.JacksonConfiguration;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.ConversionService;

/**
 * Context anchor for this module's {@code @WebMvcTest} slices.
 *
 * <p>
 * Deliberately a plain {@code @Configuration} named explicitly by {@code @ContextConfiguration} rather than a
 * {@code @SpringBootConfiguration} discovered by package search: a discoverable configuration in a package other suites
 * component-scan gets pulled into their contexts too, which is how one nested test configuration once broke most of a
 * module's integration tests at context startup. The controller's own collaborators are supplied per test as
 * {@code @MockitoBean}, so nothing needs scanning here.
 *
 * @author Ivica Cardic
 */
@Configuration
@Import(JacksonConfiguration.class)
public class WorkflowExecutionRestTestConfiguration {

    /**
     * The controller's own {@link ConversionService}, kept separate from Spring MVC's {@code mvcConversionService}
     * rather than replacing it. MVC's must stay a real {@code FormattingConversionService} — it is what converts the
     * {@code {id}} path variables these tests send into the {@code Long}s the controller receives, so mocking it would
     * hand every test a null id and make the routing assertions vacuous.
     */
    @Bean
    @Primary
    ConversionService restConversionService() {
        return Mockito.mock(ConversionService.class);
    }
}
