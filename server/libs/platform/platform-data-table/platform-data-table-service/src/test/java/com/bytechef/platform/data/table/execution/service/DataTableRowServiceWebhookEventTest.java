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

package com.bytechef.platform.data.table.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.configuration.domain.DataTableWebhookType;
import com.bytechef.platform.data.table.domain.ColumnSpec;
import com.bytechef.platform.data.table.domain.ColumnType;
import com.bytechef.platform.data.table.domain.DataTableRef;
import com.bytechef.platform.data.table.domain.ReservedColumns;
import com.bytechef.platform.data.table.execution.domain.DataTableRow;
import com.bytechef.platform.data.table.execution.event.DataTableWebhookEvent;
import com.bytechef.platform.owner.Owner;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

/**
 * The row event is what the webhook listener keys on, so the table the statement actually touched has to travel with
 * it.
 *
 * <p>
 * The owner used to be read off the row filter, which broke the moment owned tables became physical tables of their
 * own: the plain write path filters nothing, so an account's row announced itself as unowned and was delivered to the
 * vendor's registration. The ref carries the owner as resolution settled it, and cannot say otherwise.
 *
 * <p>
 * All three publish sites are pinned, not just the delete. They are three separate literals in three methods, so a
 * revert of any one of them to {@code DataTableRef.unowned(...)} would send that account's records to the vendor's URL
 * while the other two stayed correct.
 *
 * @author Ivica Cardic
 */
class DataTableRowServiceWebhookEventTest {

    private static final long ACCOUNT_A_ID = 4201L;
    private static final long ENVIRONMENT_ID = 0;

    private final ApplicationEventPublisher applicationEventPublisher = mock(ApplicationEventPublisher.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final DataTableStorageService dataTableStorageService = mock(DataTableStorageService.class);

    private final DataTableRowServiceImpl dataTableRowService =
        new DataTableRowServiceImpl(applicationEventPublisher, jdbcTemplate, dataTableStorageService);

    /**
     * The regression: a plain embedded write carries no filter at all, so anything derived from the filter says
     * "unowned" and the event lands on the vendor's registration. The ref says otherwise.
     */
    @Test
    void testAPlainEmbeddedWriteCarriesTheOwnedTableItTouched() {
        givenARowIsAffected();

        DataTableRef dataTableRef = ownedDataTableRef();

        dataTableRowService.deleteRow(dataTableRef, 1L);

        assertThat(publishedEvent().getDataTableRef())
            .as("an account's row must not announce itself as the vendor's shared data")
            .isEqualTo(dataTableRef);
    }

    @Test
    void testADeletedRowCarriesTheTableItWasDeletedFrom() {
        givenARowIsAffected();

        DataTableRef dataTableRef = ownedDataTableRef();

        dataTableRowService.deleteRow(dataTableRef, 1L);

        DataTableWebhookEvent event = publishedEvent();

        assertThat(event.getDataTableRef()).isEqualTo(dataTableRef);
        assertThat(event.getType()).isEqualTo(DataTableWebhookType.RECORD_DELETED);
    }

    /**
     * {@code RECORD_CREATED} is the trigger an account's own workflow subscribes to, so an insert that announced the
     * shared table would deliver every new record of that account to the vendor.
     */
    @Test
    void testACreatedRowCarriesTheTableItWasInsertedInto() {
        givenARowIsWritten();

        DataTableRef dataTableRef = ownedDataTableRef();

        dataTableRowService.insertRow(dataTableRef, Map.of("title", "first"));

        DataTableWebhookEvent event = publishedEvent();

        assertThat(event.getDataTableRef())
            .as("a newly created row must not announce itself as the vendor's shared data")
            .isEqualTo(dataTableRef);
        assertThat(event.getType()).isEqualTo(DataTableWebhookType.RECORD_CREATED);
    }

    @Test
    void testAnUpdatedRowCarriesTheTableItWasUpdatedIn() {
        givenARowIsWritten();

        DataTableRef dataTableRef = ownedDataTableRef();

        dataTableRowService.updateRow(dataTableRef, 1L, Map.of("title", "second"));

        DataTableWebhookEvent event = publishedEvent();

        assertThat(event.getDataTableRef())
            .as("an updated row must not announce itself as the vendor's shared data")
            .isEqualTo(dataTableRef);
        assertThat(event.getType()).isEqualTo(DataTableWebhookType.RECORD_UPDATED);
    }

    @Test
    void testASharedTablesRowCarriesTheSharedTable() {
        givenARowIsAffected();

        DataTableRef dataTableRef = DataTableRef.unowned("invoices", ENVIRONMENT_ID, PlatformType.EMBEDDED);

        dataTableRowService.deleteRow(dataTableRef, 1L);

        DataTableWebhookEvent event = publishedEvent();

        assertThat(event.getDataTableRef()).isEqualTo(dataTableRef);
    }

    @Test
    void testAnAutomationRowCarriesItsOwnPool() {
        givenARowIsAffected();

        dataTableRowService.deleteRow(DataTableRef.unowned("invoices", ENVIRONMENT_ID, PlatformType.AUTOMATION), 1L);

        assertThat(publishedEvent().getDataTableRef()
            .platformType()).isEqualTo(PlatformType.AUTOMATION);
    }

    @Test
    void testTheEventCarriesTheAffectedRowId() {
        givenARowIsAffected();

        dataTableRowService.deleteRow(DataTableRef.unowned("invoices", ENVIRONMENT_ID, PlatformType.EMBEDDED), 7L);

        Map<String, Object> payload = publishedEvent().getPayload();

        assertThat(payload).containsEntry("id", 7L);
    }

    private static DataTableRef ownedDataTableRef() {
        Owner accountOwner = Owner.connectedUser(ACCOUNT_A_ID);

        return new DataTableRef("invoices", ENVIRONMENT_ID, PlatformType.EMBEDDED, accountOwner);
    }

    /**
     * Delete reads the table's columns before writing, to confirm it has an {@code id}. That used to be a query of its
     * own answering "yes", so this fixture could get away with a table whose listing had no id in it; the listing is
     * now the only source, and a fixture table has to actually have one.
     */
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    private void givenARowIsAffected() {
        when(
            jdbcTemplate.query(
                anyString(), any(PreparedStatementSetter.class), ArgumentMatchers.<RowMapper<Object>>any()))
                    .thenReturn(columnListing());
        when(jdbcTemplate.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);
    }

    // List<Object> rather than List<ColumnSpec>: the stub is on the RowMapper<Object> overload, which the row
    // service uses for every catalog read.
    private static List<Object> columnListing() {
        return List.of(
            new ColumnSpec(ReservedColumns.ID, ColumnType.INTEGER), new ColumnSpec("title", ColumnType.STRING));
    }

    /**
     * Insert and update read the table's columns before writing and hand back the written row, so they need the column
     * listing answered as well as the write itself. One listing now serves both the column names and the id check, so
     * there is no longer a second catalog query to tell apart by its SQL.
     */
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    private void givenARowIsWritten() {
        when(
            jdbcTemplate.query(
                anyString(), any(PreparedStatementSetter.class), ArgumentMatchers.<RowMapper<Object>>any()))
                    .thenReturn(columnListing());
        when(
            jdbcTemplate.query(
                anyString(), any(PreparedStatementSetter.class),
                ArgumentMatchers.<ResultSetExtractor<DataTableRow>>any()))
                    .thenReturn(new DataTableRow(1L, Map.of("title", "first")));
    }

    private DataTableWebhookEvent publishedEvent() {
        ArgumentCaptor<DataTableWebhookEvent> eventCaptor = ArgumentCaptor.forClass(DataTableWebhookEvent.class);

        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());

        return eventCaptor.getValue();
    }
}
