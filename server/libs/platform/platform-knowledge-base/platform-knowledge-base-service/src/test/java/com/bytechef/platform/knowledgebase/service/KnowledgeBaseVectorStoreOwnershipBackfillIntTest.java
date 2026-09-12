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

package com.bytechef.platform.knowledgebase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.bytechef.platform.constant.OwnerType;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The backfill against a real Postgres, because every risk it carries is a SQL one.
 *
 * <p>
 * The vector table's {@code metadata} column is {@code json}, not {@code jsonb}, so the concatenation and the key
 * checks both hinge on casts that a mocked {@link JdbcTemplate} would accept and Postgres might not. The table is
 * created here exactly as the store creates it in production, for the same reason.
 *
 * <p>
 * The case that matters most is the one that is not a rewrite: a chunk already carrying an {@code owner_id} must come
 * out untouched. A sweep that stamped {@code shared} on it would hand one account's chunk to every other, which is
 * worse than the invisibility it exists to prevent.
 *
 * @author Ivica Cardic
 */
@Testcontainers
class KnowledgeBaseVectorStoreOwnershipBackfillIntTest {

    private static final String TABLE_NAME = "kb_vector_store";

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres"));

    private HikariDataSource hikariDataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        hikariDataSource = new HikariDataSource();

        hikariDataSource.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        hikariDataSource.setUsername(postgreSQLContainer.getUsername());
        hikariDataSource.setPassword(postgreSQLContainer.getPassword());

        jdbcTemplate = new JdbcTemplate(hikariDataSource);

        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector SCHEMA public");
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\" SCHEMA public");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + TABLE_NAME);
        jdbcTemplate.execute(
            "CREATE TABLE " + TABLE_NAME +
                " (id uuid PRIMARY KEY, content text, metadata json, embedding public.vector(3))");
    }

    @AfterEach
    void tearDown() {
        if (hikariDataSource != null) {
            hikariDataSource.close();
        }
    }

    @Test
    void testAChunkCarryingNeitherKeyBecomesShared() {
        insertChunk("11111111-1111-1111-1111-111111111111", "{\"knowledge_base_id\": 7}");

        backfill();

        assertThat(metadata("11111111-1111-1111-1111-111111111111"))
            .contains("\"shared\": true");
    }

    @Test
    void testTheRestOfAChunksMetadataSurvivesTheSweep() {
        insertChunk(
            "11111111-1111-1111-1111-111111111111",
            "{\"knowledge_base_id\": 7, \"tag_names_mine\": true, \"source\": \"a.pdf\"}");

        backfill();

        String metadata = metadata("11111111-1111-1111-1111-111111111111");

        assertThat(metadata).contains("\"knowledge_base_id\": 7");
        assertThat(metadata).contains("\"tag_names_mine\": true");
        assertThat(metadata).contains("\"source\": \"a.pdf\"");
    }

    /**
     * The negative that matters. An owned chunk must not be marked shared -- doing so would publish one account's chunk
     * to every other account in the knowledge base.
     */
    @Test
    void testAChunkAlreadyOwnedIsLeftUntouched() {
        insertChunk(
            "22222222-2222-2222-2222-222222222222", "{\"knowledge_base_id\": 7, \"owner_id\": 42}");

        backfill();

        String metadata = metadata("22222222-2222-2222-2222-222222222222");

        assertThat(metadata).contains("\"owner_id\": 42");
        assertThat(metadata).doesNotContain("shared");
    }

    /**
     * The second half-written shape. A chunk stamped with an owner id before the owner type joined the encoding matches
     * neither half of the read predicate, so it is invisible to the very account that owns it, and completing it is the
     * only way it comes back.
     *
     * <p>
     * Safe because {@code OwnerType} has one constant: an id written by any version of this code can only have meant
     * that one kind.
     */
    @Test
    void testAChunkStampedWithAnOwnerIdButNoOwnerTypeHasItsTypeCompleted() {
        insertChunk(
            "55555555-5555-5555-5555-555555555555", "{\"knowledge_base_id\": 7, \"owner_id\": 42}");

        backfill();

        String metadata = metadata("55555555-5555-5555-5555-555555555555");

        assertThat(metadata).contains("\"owner_id\": 42");
        assertThat(metadata).contains("\"owner_type\": " + OwnerType.CONNECTED_USER.ordinal());
        assertThat(metadata).doesNotContain("shared");
    }

    /**
     * Completing an owner must not publish one. A chunk that already carries a type keeps it, and an unowned chunk
     * gains no owner from this sweep.
     */
    @Test
    void testCompletingTheOwnerTypeTouchesNothingElse() {
        insertChunk(
            "66666666-6666-6666-6666-666666666666",
            "{\"knowledge_base_id\": 7, \"owner_id\": 42, \"owner_type\": 0}");
        insertChunk("77777777-7777-7777-7777-777777777777", "{\"knowledge_base_id\": 7}");

        backfill();

        assertThat(metadata("66666666-6666-6666-6666-666666666666")).contains("\"owner_type\": 0");
        assertThat(metadata("77777777-7777-7777-7777-777777777777")).doesNotContain("owner_id");
        assertThat(metadata("77777777-7777-7777-7777-777777777777")).doesNotContain("owner_type");
    }

    /**
     * Idempotent, because it runs on every startup rather than once. The second pass must both leave the flag alone and
     * update nothing.
     */
    @Test
    void testASecondSweepRewritesNothing() {
        insertChunk("33333333-3333-3333-3333-333333333333", "{\"knowledge_base_id\": 7}");

        backfill();

        String afterFirstSweep = metadata("33333333-3333-3333-3333-333333333333");

        backfill();

        assertThat(metadata("33333333-3333-3333-3333-333333333333")).isEqualTo(afterFirstSweep);
    }

    /**
     * The fresh-install case: the store creates its own table, and on a database where it has not run yet there is
     * nothing to sweep. Failing here would take the whole context down at startup.
     */
    @Test
    void testAMissingTableIsSkippedRatherThanFailing() {
        KnowledgeBaseVectorStoreOwnershipBackfill backfill = new KnowledgeBaseVectorStoreOwnershipBackfill(
            jdbcTemplate, () -> "kb_vector_store_that_does_not_exist");

        assertThatCode(backfill::backfill).doesNotThrowAnyException();
    }

    /**
     * The sweep runs on every startup, so it has to be cheap on the startups where it finds nothing, which is all of
     * them after the first. It is made cheap rather than skipped: a partial index carrying the sweep's own predicate is
     * empty once the sweep has run, and the planner answers from it instead of reading every chunk.
     *
     * <p>
     * Asserted against the plan rather than a timing, because on a table small enough to fit a test Postgres will
     * choose a sequential scan on cost alone and be right to; {@code enable_seqscan = off} asks the narrower question
     * this cares about, which is whether the index is usable for this predicate at all. It is not obviously so: the
     * predicate casts a {@code json} column to {@code jsonb}, and an index predicate may only contain immutable
     * expressions.
     */
    @Test
    void testTheSweepCanBeAnsweredFromTheIndexRatherThanAScan() {
        insertChunk("11111111-1111-1111-1111-111111111111", "{\"knowledge_base_id\": 7}");

        backfill();

        jdbcTemplate.execute("SET enable_seqscan = off");

        List<String> plan = jdbcTemplate.queryForList(
            "EXPLAIN UPDATE " + TABLE_NAME +
                " SET metadata = metadata::jsonb || '{\"shared\": true}'::jsonb" +
                " WHERE NOT jsonb_exists(metadata::jsonb, 'owner_id')" +
                " AND NOT jsonb_exists(metadata::jsonb, 'shared')",
            String.class);

        assertThat(String.join("\n", plan)).contains("idx_" + TABLE_NAME + "_unowned_chunk");
    }

    /**
     * The index is not a "already done" flag. A chunk that arrives carrying neither key after the first sweep, from a
     * restored snapshot or a replica built off an older one, is still swept on the next startup. That is the property a
     * run-once marker would have lost.
     */
    @Test
    void testAChunkArrivingAfterTheFirstSweepIsStillSwept() {
        insertChunk("11111111-1111-1111-1111-111111111111", "{\"knowledge_base_id\": 7}");

        backfill();

        insertChunk("44444444-4444-4444-4444-444444444444", "{\"knowledge_base_id\": 7}");

        backfill();

        assertThat(metadata("44444444-4444-4444-4444-444444444444"))
            .contains("\"shared\": true");
    }

    private void backfill() {
        KnowledgeBaseVectorStoreOwnershipBackfill backfill = new KnowledgeBaseVectorStoreOwnershipBackfill(
            jdbcTemplate, () -> TABLE_NAME);

        backfill.backfill();
    }

    private void insertChunk(String id, String metadata) {
        jdbcTemplate.update(
            "INSERT INTO " + TABLE_NAME + " (id, content, metadata, embedding) VALUES (?::uuid, ?, ?::json, ?::vector)",
            id, "text", metadata, "[1,2,3]");
    }

    private String metadata(String id) {
        List<String> rows = jdbcTemplate.queryForList(
            "SELECT metadata::text FROM " + TABLE_NAME + " WHERE id = ?::uuid", String.class, id);

        assertThat(rows).hasSize(1);

        return rows.getFirst();
    }
}
