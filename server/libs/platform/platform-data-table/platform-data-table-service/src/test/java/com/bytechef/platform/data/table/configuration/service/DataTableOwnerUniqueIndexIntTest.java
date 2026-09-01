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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.config.DataTableIntTestConfiguration;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The shape of the registry key, asserted against the database rather than against the changelog text.
 *
 * <p>
 * Rows are written with raw SQL on purpose. {@code OwnerType} has exactly one value today, so the second owner type
 * these tests need cannot be produced through the domain at all -- and the whole point of the key carrying
 * {@code owner_type} is what happens on the day a second value is appended. An ordinal the enum does not have yet is
 * the only way to write that day's row now.
 *
 * @author Ivica Cardic
 */
@SpringBootTest(classes = DataTableIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
class DataTableOwnerUniqueIndexIntTest {

    private static final int CONNECTED_USER = 0;
    private static final int SOME_FUTURE_OWNER_TYPE = 1;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * {@code isReadableBy} compares owner id AND type, so two owners of different types sharing an id are two different
     * owners. The key has to be able to say so, or the second one's table is refused as a duplicate of the first one's.
     */
    @Test
    void testTwoOwnersOfDifferentTypesMayEachOwnTheSameName() {
        insert("uqorders", PlatformType.EMBEDDED, 42L, CONNECTED_USER);

        assertThatCode(() -> insert("uqorders", PlatformType.EMBEDDED, 42L, SOME_FUTURE_OWNER_TYPE))
            .as("owner id 42 of one type and owner id 42 of another are not the same owner")
            .doesNotThrowAnyException();
    }

    @Test
    void testOneOwnerCannotHoldTheSameNameTwice() {
        insert("uqinvoices", PlatformType.EMBEDDED, 42L, CONNECTED_USER);

        assertThatThrownBy(() -> insert("uqinvoices", PlatformType.EMBEDDED, 42L, CONNECTED_USER))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * The partial index, which carries no owner_type: a stray type with no id is still an unowned row and must collide
     * with the plain shared row of the same name rather than sit beside it.
     */
    @Test
    void testTwoSharedRowsOfTheSameNameCollideEvenWithDifferingOwnerTypes() {
        insert("uqshared", PlatformType.EMBEDDED, null, null);

        assertThatThrownBy(() -> insert("uqshared", PlatformType.EMBEDDED, null, CONNECTED_USER))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void testTheSameOwnedNameMayExistInBothPools() {
        insert("uqpools", PlatformType.AUTOMATION, 42L, CONNECTED_USER);

        assertThatCode(() -> insert("uqpools", PlatformType.EMBEDDED, 42L, CONNECTED_USER))
            .doesNotThrowAnyException();
    }

    private void insert(String name, PlatformType platformType, Long ownerId, Integer ownerType) {
        jdbcTemplate.update(
            "INSERT INTO data_table (name, platform_type, owner_id, owner_type, created_date, created_by, " +
                "last_modified_date, last_modified_by, version) " +
                "VALUES (?, ?, ?, ?, now(), 'test', now(), 'test', 0)",
            name, platformType.ordinal(), ownerId, ownerType);
    }
}
