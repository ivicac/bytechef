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

package com.bytechef.embedded.configuration.service;

import com.bytechef.embedded.configuration.domain.UnifiedIntegration;
import com.bytechef.embedded.configuration.repository.UnifiedIntegrationRepository;
import java.util.List;
import org.apache.commons.lang3.Validate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author Ivica Cardic
 */
@Service
@Transactional
public class UnifiedIntegrationServiceImpl implements UnifiedIntegrationService {

    private final UnifiedIntegrationRepository unifiedIntegrationRepository;

    public UnifiedIntegrationServiceImpl(UnifiedIntegrationRepository unifiedIntegrationRepository) {
        this.unifiedIntegrationRepository = unifiedIntegrationRepository;
    }

    @Override
    public UnifiedIntegration create(@NonNull UnifiedIntegration unifiedIntegration) {
        Validate.notNull(unifiedIntegration.getCategoryName(), "'categoryName' must not be null");
        Validate.notNull(unifiedIntegration.getComponentName(), "'componentName' must not be null");

        return unifiedIntegrationRepository.save(unifiedIntegration);
    }

    @Override
    public void delete(long id) {
        unifiedIntegrationRepository.deleteById(id);
    }

    @Override
    public void delete(String componentName) {
        unifiedIntegrationRepository.delete(unifiedIntegrationRepository.findByComponentName(componentName));
    }

    @Override
    public List<UnifiedIntegration> getUnifiedComponents() {
        return unifiedIntegrationRepository.findAll();
    }
}
