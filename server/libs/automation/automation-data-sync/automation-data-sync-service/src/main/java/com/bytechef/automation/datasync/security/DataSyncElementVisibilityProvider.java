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

package com.bytechef.automation.datasync.security;

import com.bytechef.automation.configuration.security.ProjectVisibilityFilter;
import com.bytechef.automation.configuration.security.ResourceVisibilityProvider;
import com.bytechef.automation.configuration.service.ResourceVisibilityResolver.VisibilityRecord;
import com.bytechef.automation.datasync.domain.DataSyncElement;
import com.bytechef.automation.datasync.repository.DataSyncElementRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The visibility half of {@link DataSyncElementOwnershipResolver}, and registered for the same reason: an element id is
 * the whole argument list of {@code updateDataSyncElement}, so without a provider here that gate would be the one way
 * into a withheld Data Sync that skipped the visibility precondition every {@code 'DataSync'}-keyed gate has.
 *
 * <p>
 * The tail is delegated to {@link DataSyncVisibilityProvider} rather than repeated, so the element path and the Data
 * Sync path cannot disagree about which project a Data Sync inherits from — the same pairing the ownership resolvers
 * already have.
 *
 * @author Ivica Cardic
 */
@Component
public class DataSyncElementVisibilityProvider implements ResourceVisibilityProvider {

    private final DataSyncElementRepository dataSyncElementRepository;
    private final DataSyncVisibilityProvider dataSyncVisibilityProvider;

    @SuppressFBWarnings("EI")
    public DataSyncElementVisibilityProvider(
        DataSyncElementRepository dataSyncElementRepository, DataSyncVisibilityProvider dataSyncVisibilityProvider) {

        this.dataSyncElementRepository = dataSyncElementRepository;
        this.dataSyncVisibilityProvider = dataSyncVisibilityProvider;
    }

    @Override
    public String resourceType() {
        return "DataSyncElement";
    }

    @Override
    public String visibilityResourceType() {
        return ProjectVisibilityFilter.PROJECT;
    }

    @Override
    public Optional<VisibilityRecord> fetchVisibility(long id) {
        return dataSyncElementRepository.findById(id)
            .map(DataSyncElement::getDataSyncId)
            .flatMap(dataSyncVisibilityProvider::fetchVisibility);
    }
}
