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

package com.bytechef.platform.component.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ClusterElementContext;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.file.storage.FileStorageServiceRegistry;
import com.bytechef.file.storage.service.FileStorageService;
import com.bytechef.platform.component.definition.ClusterElementContextAware;
import com.bytechef.platform.component.log.LogFileStorage;
import com.bytechef.platform.component.owner.OwnerResolution;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.storage.DataStorage;
import com.bytechef.platform.file.storage.TempFileStorage;
import com.bytechef.platform.owner.Owner;
import com.bytechef.platform.owner.OwnerResolver;
import io.micrometer.tracing.Tracer;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;

/**
 * A widened factory signature does nothing until every frame between the value's source and its use forwards it, and
 * each frame can drop it silently -- the factory can ignore the new arguments, the context can not expose them, and the
 * resolution can not read them. Compilation catches none of the three.
 *
 * <p>
 * So this walks the whole way through real code rather than asserting the signature exists: the real
 * {@link ContextFactoryImpl} builds a real cluster-element context, and {@link OwnerResolution} is asked for the owner
 * of it. Only the {@link OwnerResolver} is stubbed, which is the Enterprise bean this Community module has no
 * implementation of by design.
 *
 * @author Ivica Cardic
 */
class ContextFactoryImplTest {

    private static final long JOB_PRINCIPAL_ID = 77L;
    private static final long CONNECTED_USER_ID = 1055L;

    @Test
    void testAClusterElementContextCreatedWithAJobPrincipalCarriesIt() {
        ClusterElementContext clusterElementContext = newContextFactory().createClusterElementContext(
            "knowledgeBase", 1, "writeAsDocument", JOB_PRINCIPAL_ID, null, PlatformType.EMBEDDED, false);

        ClusterElementContextAware clusterElementContextAware = (ClusterElementContextAware) clusterElementContext;

        assertThat(clusterElementContextAware.getJobPrincipalId()).isEqualTo(JOB_PRINCIPAL_ID);
        assertThat(clusterElementContextAware.getPlatformType()).isEqualTo(PlatformType.EMBEDDED);
    }

    /**
     * The claim the whole change rests on. An empty owner here would not be a weaker answer than a wrong one -- it
     * opens every pool -- so the assertion is on the Owner value rather than on the resolution having been attempted.
     */
    @Test
    void testSuchAContextResolvesToANonEmptyOwner() {
        ClusterElementContext clusterElementContext = newContextFactory().createClusterElementContext(
            "knowledgeBase", 1, "writeAsDocument", JOB_PRINCIPAL_ID, null, PlatformType.EMBEDDED, false);

        OwnerResolver ownerResolver = mock(OwnerResolver.class);

        when(ownerResolver.resolveJobPrincipal(JOB_PRINCIPAL_ID, PlatformType.EMBEDDED))
            .thenReturn(Optional.of(Owner.connectedUser(CONNECTED_USER_ID)));

        Optional<Owner> owner = OwnerResolution.resolve(clusterElementContext, providerOf(ownerResolver));

        assertThat(owner).contains(Owner.connectedUser(CONNECTED_USER_ID));
    }

    /**
     * The overload without a principal is unchanged: it still carries none, so it still falls through to the security
     * context rather than newly taking the job-principal branch.
     */
    @Test
    void testTheOverloadWithoutAPrincipalStillCarriesNone() {
        ClusterElementContext clusterElementContext = newContextFactory().createClusterElementContext(
            "knowledgeBase", 1, "writeAsDocument", null, false);

        ClusterElementContextAware clusterElementContextAware = (ClusterElementContextAware) clusterElementContext;

        assertThat(clusterElementContextAware.getJobPrincipalId()).isNull();
        assertThat(clusterElementContextAware.getPlatformType()).isNull();

        OwnerResolver ownerResolver = mock(OwnerResolver.class);

        when(ownerResolver.resolveCurrentPrincipal()).thenReturn(Optional.empty());

        assertThat(OwnerResolution.resolve(clusterElementContext, providerOf(ownerResolver))).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static ContextFactoryImpl newContextFactory() {
        FileStorageServiceRegistry fileStorageServiceRegistry = mock(FileStorageServiceRegistry.class);

        when(fileStorageServiceRegistry.getFileStorageService(anyString()))
            .thenReturn(mock(FileStorageService.class));

        return new ContextFactoryImpl(
            mock(ApplicationContext.class), new ApplicationProperties(), mock(CacheManager.class),
            mock(DataStorage.class), mock(ApplicationEventPublisher.class), fileStorageServiceRegistry,
            mock(ObjectProvider.class), mock(ObjectProvider.class), mock(LogFileStorage.class),
            mock(TempFileStorage.class), mock(Tracer.class));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<OwnerResolver> providerOf(OwnerResolver ownerResolver) {
        ObjectProvider<OwnerResolver> objectProvider = mock(ObjectProvider.class);

        when(objectProvider.getIfAvailable()).thenReturn(ownerResolver);

        return objectProvider;
    }
}
