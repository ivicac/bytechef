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

import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.owner.Owner;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/**
 * The chunk half of a knowledge base assignment, against a real Postgres, because every risk it carries is a SQL one.
 *
 * <p>
 * A chunk's owner lives in a {@code json} metadata column as either the {@code owner_id}/{@code owner_type} pair or the
 * {@code shared} flag, never both, and the statements that move it hinge on jsonb concatenation and key removal that a
 * mocked {@link JdbcTemplate} would accept and Postgres might not. The table is created here exactly as the store
 * creates it in production, for the same reason.
 *
 * <p>
 * The assertions are on the two encodings being mutually exclusive afterwards, not merely on the new one being present.
 * A chunk carrying {@code shared} beside an {@code owner_id} matches the read filter of every account in the knowledge
 * base, which is the failure an assignment must not introduce while fixing a smaller one.
 *
 * @author Ivica Cardic
 */
@Testcontainers
class KnowledgeBaseVectorStoreMetadataServiceIntTest {

    private static final Owner OWNER = Owner.connectedUser(42L);
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

    /**
     * The effect the assignment is for: a chunk written before its knowledge base was assigned carries
     * {@code shared: true}, and afterwards carries the account, so the account's own search filter reaches it.
     */
    @Test
    void testAssigningMovesASharedChunkOntoTheAccount() {
        insertChunk("11111111-1111-1111-1111-111111111111", "{\"knowledge_base_id\": 7, \"shared\": true}");

        int updatedCount = updateOwner(7L, OWNER);

        String metadata = metadata("11111111-1111-1111-1111-111111111111");

        assertThat(updatedCount).isEqualTo(1);
        assertThat(metadata).contains("\"owner_id\": 42");
        assertThat(metadata).contains("\"owner_type\": " + OwnerType.CONNECTED_USER.ordinal());
        assertThat(metadata)
            .as("shared must go, or the chunk stays readable by every other account in the knowledge base")
            .doesNotContain("shared");
    }

    /**
     * The rest of a chunk's metadata is not the assignment's business. Losing {@code tag_names_*} would silently narrow
     * every tag-filtered search over the knowledge base that was just assigned.
     */
    @Test
    void testTheRestOfAChunksMetadataSurvivesTheMove() {
        insertChunk(
            "11111111-1111-1111-1111-111111111111",
            "{\"knowledge_base_id\": 7, \"shared\": true, \"tag_names_mine\": true, \"source\": \"a.pdf\"}");

        updateOwner(7L, OWNER);

        String metadata = metadata("11111111-1111-1111-1111-111111111111");

        assertThat(metadata).contains("\"knowledge_base_id\": 7");
        assertThat(metadata).contains("\"tag_names_mine\": true");
        assertThat(metadata).contains("\"source\": \"a.pdf\"");
    }

    /**
     * Unassignment inverted: the chunks of a knowledge base handed back to the vendor become the vendor's, which is
     * {@code shared: true} and neither owner key. Leaving the pair behind would make the returned knowledge base
     * unsearchable by the vendor, whose filter asks for {@code shared}.
     */
    @Test
    void testUnassigningReturnsChunksToTheVendor() {
        insertChunk(
            "22222222-2222-2222-2222-222222222222",
            "{\"knowledge_base_id\": 7, \"owner_id\": 42, \"owner_type\": 0}");

        int updatedCount = updateOwner(7L, null);

        String metadata = metadata("22222222-2222-2222-2222-222222222222");

        assertThat(updatedCount).isEqualTo(1);
        assertThat(metadata).contains("\"shared\": true");
        assertThat(metadata).doesNotContain("owner_id");
        assertThat(metadata).doesNotContain("owner_type");
    }

    /**
     * Only the assigned knowledge base's chunks move. The scope is {@code knowledge_base_id}, and a build that dropped
     * it would hand every chunk in the tenant to the account that was assigned one knowledge base.
     */
    @Test
    void testChunksOfAnotherKnowledgeBaseAreLeftAlone() {
        insertChunk("11111111-1111-1111-1111-111111111111", "{\"knowledge_base_id\": 7, \"shared\": true}");
        insertChunk("33333333-3333-3333-3333-333333333333", "{\"knowledge_base_id\": 8, \"shared\": true}");

        int updatedCount = updateOwner(7L, OWNER);

        assertThat(updatedCount).isEqualTo(1);
        assertThat(metadata("33333333-3333-3333-3333-333333333333")).doesNotContain("owner_id");
        assertThat(metadata("33333333-3333-3333-3333-333333333333")).contains("\"shared\": true");
    }

    /**
     * A chunk carrying an {@code owner_id} with no {@code owner_type} belongs to nobody and is invisible to everyone.
     * The assignment is entitled to move it, and must leave a whole owner behind rather than the id it found.
     */
    @Test
    void testAHalfWrittenOwnerIsCompletedByTheMove() {
        insertChunk("44444444-4444-4444-4444-444444444444", "{\"knowledge_base_id\": 7, \"owner_id\": 99}");

        updateOwner(7L, OWNER);

        String metadata = metadata("44444444-4444-4444-4444-444444444444");

        assertThat(metadata).contains("\"owner_id\": 42");
        assertThat(metadata).contains("\"owner_type\": " + OwnerType.CONNECTED_USER.ordinal());
    }

    private int updateOwner(long knowledgeBaseId, @Nullable Owner owner) {
        KnowledgeBaseVectorStoreMetadataService knowledgeBaseVectorStoreMetadataService =
            new KnowledgeBaseVectorStoreMetadataService(
                jdbcTemplate, JsonMapper.builder()
                    .build(),
                () -> TABLE_NAME);

        return knowledgeBaseVectorStoreMetadataService.updateOwner(knowledgeBaseId, owner);
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
