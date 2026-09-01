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

import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_KNOWLEDGE_BASE_ID;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_OWNER_ID;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_OWNER_TYPE;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_SHARED;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_TAG_NAMES;

import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.owner.Owner;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Updates tag metadata in the vector store without triggering re-embedding.
 *
 * @author Marko Kriskovic
 */
public final class KnowledgeBaseVectorStoreMetadataService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseVectorStoreMetadataService.class);

    private static final Pattern SAFE_TABLE_NAME_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_.]*$");

    private final Supplier<String> fullTableNameSupplier;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate pgVectorJdbcTemplate;

    @SuppressFBWarnings("EI2")
    public KnowledgeBaseVectorStoreMetadataService(
        JdbcTemplate pgVectorJdbcTemplate, ObjectMapper objectMapper, Supplier<String> fullTableNameSupplier) {

        this.objectMapper = objectMapper;
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.fullTableNameSupplier = fullTableNameSupplier;
    }

    /**
     * Updates tag metadata in the vector store for the given entry. Preserves all other existing metadata fields (e.g.,
     * {@code source}, {@code charset}) and avoids re-embedding the content. Stores both {@code tag_names: [list]}
     * (human-readable) and {@code tag_names_NAME: true} boolean flags (used for filtering).
     *
     * @param vectorStoreId the vector store document ID
     * @param tagNames      the new tag names to set; an empty list removes all tag fields
     */
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    public void updateTagNames(String vectorStoreId, List<String> tagNames) {
        String fullTableName = resolveFullTableName();

        List<String> rows = pgVectorJdbcTemplate.queryForList(
            "SELECT metadata::text FROM " + fullTableName + " WHERE id = ?::uuid",
            String.class, vectorStoreId);

        if (rows.isEmpty()) {
            log.warn("Vector store entry not found for id={}, skipping tag metadata update", vectorStoreId);

            return;
        }

        Map<String, Object> metadata = objectMapper.readValue(rows.get(0), new TypeReference<>() {});

        // preserve insertion order so tag_names ends up at the tail
        Map<String, Object> updatedMetadata = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String key = entry.getKey();

            // drop existing tag entries so they can be rebuilt fresh
            if (!key.startsWith(METADATA_TAG_NAMES + "_") && !key.equals(METADATA_TAG_NAMES)) {
                updatedMetadata.put(key, entry.getValue());
            }
        }

        if (!tagNames.isEmpty()) {
            updatedMetadata.put(METADATA_TAG_NAMES, tagNames);

            for (String tagName : tagNames) {
                updatedMetadata.put(METADATA_TAG_NAMES + "_" + tagName, true);
            }
        }

        pgVectorJdbcTemplate.update(
            "UPDATE " + fullTableName + " SET metadata = ?::jsonb WHERE id = ?::uuid",
            objectMapper.writeValueAsString(updatedMetadata), vectorStoreId);
    }

    /**
     * Moves every chunk of the given knowledge base onto {@code owner}, or back to nobody when {@code owner} is null,
     * in the encoding {@code KnowledgeBaseVectorStoreWrapper#add} writes: an owned chunk carries {@code owner_id} and
     * {@code owner_type} and no {@code shared}, an unowned one carries {@code shared: true} and neither of the other
     * two.
     *
     * <p>
     * A document's chunks carry the account independently of the document row -- the chunker runs off a message long
     * after the request is gone, which is why the pair was written into the chunk metadata at all -- so re-stamping the
     * documents on assignment without re-stamping their chunks would leave a document whose owner disagrees with its
     * own chunks. That is the half-owner shape in a new costume: the account could edit the document and still not see
     * a word of it in a search.
     *
     * <p>
     * Scoped by {@code knowledge_base_id} rather than by the document ids just re-stamped, deliberately. The caller
     * refuses the assignment unless every document in the knowledge base is unowned or already the target's, so every
     * chunk under that id is one this owner is entitled to; going by knowledge base also reaches a chunk whose document
     * row is gone, which going by document id could never do and which would otherwise stay stranded on an owner that
     * no longer has anything there.
     *
     * @param knowledgeBaseId the knowledge base whose chunks move
     * @param owner           the new owner, or null to return the chunks to the vendor
     * @return the number of chunks re-stamped
     */
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    public int updateOwner(long knowledgeBaseId, @Nullable Owner owner) {
        String fullTableName = resolveFullTableName();

        String knowledgeBaseIdValue = String.valueOf(knowledgeBaseId);

        if (owner == null) {
            return pgVectorJdbcTemplate.update(
                "UPDATE " + fullTableName + " SET metadata = (metadata::jsonb - '" + METADATA_OWNER_ID + "' - '" +
                    METADATA_OWNER_TYPE + "') || '{\"" + METADATA_SHARED + "\": true}'::jsonb" +
                    " WHERE metadata::jsonb ->> '" + METADATA_KNOWLEDGE_BASE_ID + "' = ?",
                knowledgeBaseIdValue);
        }

        OwnerType ownerType = owner.type();

        Map<String, Object> ownerMetadata = new LinkedHashMap<>();

        ownerMetadata.put(METADATA_OWNER_ID, owner.id());
        ownerMetadata.put(METADATA_OWNER_TYPE, ownerType.ordinal());

        return pgVectorJdbcTemplate.update(
            "UPDATE " + fullTableName + " SET metadata = (metadata::jsonb - '" + METADATA_SHARED + "') || ?::jsonb" +
                " WHERE metadata::jsonb ->> '" + METADATA_KNOWLEDGE_BASE_ID + "' = ?",
            objectMapper.writeValueAsString(ownerMetadata), knowledgeBaseIdValue);
    }

    private String resolveFullTableName() {
        String fullTableName = fullTableNameSupplier.get();

        Matcher matcher = SAFE_TABLE_NAME_PATTERN.matcher(fullTableName);

        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid table name: " + fullTableName);
        }

        return fullTableName;
    }
}
