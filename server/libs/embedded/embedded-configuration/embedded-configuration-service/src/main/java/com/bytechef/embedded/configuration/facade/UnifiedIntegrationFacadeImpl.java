/*
 * Copyright 2023-present ByteChef Inc.
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

package com.bytechef.embedded.configuration.facade;

import com.bytechef.embedded.configuration.domain.UnifiedIntegration;
import com.bytechef.embedded.configuration.service.UnifiedIntegrationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author Ivica Cardic
 */
@Service
@Transactional
public class UnifiedIntegrationFacadeImpl implements UnifiedIntegrationFacade {

    private final UnifiedIntegrationService unifiedIntegrationService;

    public UnifiedIntegrationFacadeImpl(UnifiedIntegrationService unifiedIntegrationService) {
        this.unifiedIntegrationService = unifiedIntegrationService;
    }

    @Override
    public void enableUnifiedIntegration(String componentName, boolean enable) {
        if (enable) {
            // TODO fetch unified integration category name

            unifiedIntegrationService.create(new UnifiedIntegration("crm", componentName));
        } else {
            unifiedIntegrationService.delete(componentName);
        }
    }
}
