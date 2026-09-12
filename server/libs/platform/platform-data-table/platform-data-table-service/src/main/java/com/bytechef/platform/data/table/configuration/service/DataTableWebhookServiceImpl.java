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

package com.bytechef.platform.data.table.configuration.service;

import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.data.table.configuration.domain.DataTable;
import com.bytechef.platform.data.table.configuration.domain.DataTableWebhook;
import com.bytechef.platform.data.table.configuration.domain.DataTableWebhookType;
import com.bytechef.platform.data.table.configuration.repository.DataTableWebhookRepository;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.owner.Owner;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Implementation of the {@link DataTableWebhookService} that provides functionality for managing webhooks associated
 * with data tables. This service interacts with the data table and webhook repositories to facilitate the addition,
 * retrieval, and removal of webhooks.
 *
 * <p>
 * Registration and delivery go through the same table resolution, which is what makes them meet: whatever table a ref
 * addresses at registration time is the table an event on that same ref is delivered to. They also read the same field
 * of the ref for the owner -- {@code runOwner}, stamped at registration and compared at delivery -- so the two sides
 * cannot drift apart into disagreeing about who a registration belongs to.
 *
 * @author Ivica Cardic
 */
@Service
public class DataTableWebhookServiceImpl implements DataTableWebhookService {

    private final DataTableService dataTableService;
    private final DataTableWebhookRepository webhookRepository;

    @SuppressFBWarnings("EI")
    public DataTableWebhookServiceImpl(
        DataTableService dataTableService, DataTableWebhookRepository webhookRepository) {

        this.dataTableService = dataTableService;
        this.webhookRepository = webhookRepository;
    }

    @Override
    public long addWebhook(DataTableRef dataTableRef, String url, DataTableWebhookType type) {
        DataTable dataTable = resolveDataTable(dataTableRef);

        DataTableWebhook dataTableWebhook = new DataTableWebhook();

        dataTableWebhook.setDataTableId(dataTable.getId());
        dataTableWebhook.setType(type);
        dataTableWebhook.setUrl(url);
        dataTableWebhook.setEnvironment(Environment.values()[(int) dataTableRef.environmentId()]);

        // The ref's run owner is the whole of it: every account addresses the same table, so the registry row cannot
        // tell two accounts' registrations apart and this column is what does. setOwner moves both columns together.
        dataTableWebhook.setOwner(dataTableRef.runOwner());

        DataTableWebhook savedDataTableHook = webhookRepository.save(dataTableWebhook);

        return savedDataTableHook.getId();
    }

    @Override
    public List<Webhook> listWebhooks(DataTableRef dataTableRef) {
        Optional<DataTable> dataTableOptional = dataTableService.fetchDataTable(dataTableRef);

        if (dataTableOptional.isEmpty()) {
            return List.of();
        }

        DataTable dataTable = dataTableOptional.get();

        Owner runOwner = dataTableRef.runOwner();

        // Not a filter laid over the management listing but the whole of what this method returns: a caller holding a
        // ref is asking on behalf of one run, and the registrations of other accounts on the same shared table are not
        // its answer. The management listing stays whole and is reached by its own method.
        return findWebhooks(dataTable.getId(), dataTableRef.environmentId())
            .stream()
            .filter(webhook -> webhook.receivesRowsWrittenBy(runOwner))
            .toList();
    }

    @Override
    public List<Webhook> listWebhooks(long dataTableId, long environmentId) {
        return findWebhooks(dataTableId, environmentId);
    }

    @Override
    public void removeWebhook(long id) {
        webhookRepository.deleteById(id);
    }

    private List<Webhook> findWebhooks(long dataTableId, long environmentId) {
        Environment environment = Environment.values()[(int) environmentId];

        List<DataTableWebhook> dataTableWebhooks =
            webhookRepository.findByDataTableIdAndEnvironment(dataTableId, environment.ordinal());

        List<Webhook> webhooks = new ArrayList<>(dataTableWebhooks.size());

        for (DataTableWebhook dataTableWebhook : dataTableWebhooks) {
            webhooks.add(new Webhook(
                dataTableWebhook.getId(), dataTableWebhook.getDataTableId(), dataTableWebhook.getUrl(),
                dataTableWebhook.getType(), dataTableWebhook.getEnvironment()
                    .ordinal(),
                dataTableWebhook.getOwner()));
        }

        return webhooks;
    }

    private DataTable resolveDataTable(DataTableRef dataTableRef) {
        Optional<DataTable> dataTableOptional = dataTableService.fetchDataTable(dataTableRef);

        return dataTableOptional.orElseThrow(
            () -> new IllegalArgumentException("Table not found: " + dataTableRef.baseName()));
    }
}
