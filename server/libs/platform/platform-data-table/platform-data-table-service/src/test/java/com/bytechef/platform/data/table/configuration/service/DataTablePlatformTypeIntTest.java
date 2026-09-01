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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.config.DataTableIntTestConfiguration;
import com.bytechef.platform.data.table.configuration.domain.DataTable;
import com.bytechef.platform.data.table.configuration.repository.DataTableRepository;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;

/**
 * @author Ivica Cardic
 */
@SpringBootTest(classes = DataTableIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
class DataTablePlatformTypeIntTest {

    @Autowired
    private DataTableRepository dataTableRepository;

    @Test
    void testTheSameNameIsAllowedInBothPools() {
        dataTableRepository.save(dataTable("platform_type_shared", PlatformType.AUTOMATION));
        dataTableRepository.save(dataTable("platform_type_shared", PlatformType.EMBEDDED));

        assertThat(
            dataTableRepository.findByNameAndPlatformTypeAndOwnerIdIsNull(
                "platform_type_shared", PlatformType.AUTOMATION.ordinal()))
                    .isPresent();
        assertThat(
            dataTableRepository.findByNameAndPlatformTypeAndOwnerIdIsNull(
                "platform_type_shared", PlatformType.EMBEDDED.ordinal()))
                    .isPresent();
    }

    @Test
    void testTheSameNameTwiceInOnePoolIsRejected() {
        dataTableRepository.save(dataTable("dupe", PlatformType.AUTOMATION));

        assertThatThrownBy(() -> dataTableRepository.save(dataTable("dupe", PlatformType.AUTOMATION)))
            .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void testAnExistingRowDefaultsToAutomation() {
        DataTable dataTable = new DataTable();

        dataTable.setName("defaulted");

        DataTable saved = dataTableRepository.save(dataTable);

        assertThat(saved.getPlatformType()).isEqualTo(PlatformType.AUTOMATION);
    }

    private static DataTable dataTable(String name, PlatformType platformType) {
        DataTable dataTable = new DataTable();

        dataTable.setName(name);
        dataTable.setPlatformType(platformType);

        return dataTable;
    }
}
