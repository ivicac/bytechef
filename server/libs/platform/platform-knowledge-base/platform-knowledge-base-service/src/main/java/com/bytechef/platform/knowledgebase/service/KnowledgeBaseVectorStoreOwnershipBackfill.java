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

import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_OWNER_ID;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_OWNER_TYPE;
import static com.bytechef.platform.knowledgebase.constant.KnowledgeBaseConstants.METADATA_SHARED;

import com.bytechef.platform.constant.OwnerType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Marks every chunk written before chunk-level ownership existed as {@code shared: true}.
 *
 * <p>
 * Not optional, and not deferrable to a later release. A chunk carrying neither {@code owner_id} nor {@code shared}
 * satisfies no read filter the wrapper builds, so without this sweep every document already in the store becomes
 * invisible the moment the filter ships. It therefore lands in the same commit as the filter.
 *
 * <p>
 * Not a Liquibase changeset either: the vector store lives in its own datasource ({@code pgVectorDataSource}), which
 * the application's changelogs never reach, and the table itself is created by Spring AI rather than by a changeset.
 * The sweep runs at startup instead, is idempotent -- its own predicate excludes everything it has already written --
 * and skips silently when the table does not exist yet, which is the fresh-install case where there is nothing to
 * backfill.
 *
 * <p>
 * It runs on EVERY startup and is made cheap rather than skipped. A flag saying "already done" would have to live
 * somewhere, and every somewhere is wrong: in the application database it goes out of step with a vector store restored
 * from a dump or brought up as a fresh replica, and in memory it is not a flag at all. So the sweep keeps asking the
 * question and a partial index makes the question cost nothing -- its predicate is the sweep's own, so after the first
 * pass the index is empty, the planner answers from it, and the repeat is an index probe instead of a scan of every
 * chunk. The index travels with the table it indexes, which is exactly the property the flag lacked.
 *
 * <p>
 * Two sweeps, not one, because there are two half-written shapes and both are invisible under the read filter: a chunk
 * carrying neither ownership key, and a chunk carrying an {@code owner_id} with no {@code owner_type}. The second
 * exists because the type joined the encoding after the id did.
 *
 * @author Ivica Cardic
 */
public class KnowledgeBaseVectorStoreOwnershipBackfill implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseVectorStoreOwnershipBackfill.class);

    private static final Pattern SAFE_TABLE_NAME_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_.]*$");

    /**
     * The predicate, written once and used twice: as the sweep's {@code WHERE} and as the index's own predicate. They
     * have to be the same text, because Postgres will only answer the query from the index when the query's predicate
     * implies the index's, and identical is the only implication worth relying on here.
     */
    private static final String UNOWNED_PREDICATE =
        "NOT jsonb_exists(metadata::jsonb, '" + METADATA_OWNER_ID + "')" +
            " AND NOT jsonb_exists(metadata::jsonb, '" + METADATA_SHARED + "')";

    /**
     * The other half-written shape: an owner id with no owner type, which is what an owned chunk looked like before the
     * type joined the encoding. It satisfies neither half of the read predicate, so such a chunk is invisible to its
     * own account as surely as an unstamped one is invisible to everybody.
     */
    private static final String OWNER_ID_WITHOUT_TYPE_PREDICATE =
        "jsonb_exists(metadata::jsonb, '" + METADATA_OWNER_ID + "')" +
            " AND NOT jsonb_exists(metadata::jsonb, '" + METADATA_OWNER_TYPE + "')";

    private final Supplier<String> fullTableNameSupplier;
    private final JdbcTemplate pgVectorJdbcTemplate;

    @SuppressFBWarnings("EI2")
    public KnowledgeBaseVectorStoreOwnershipBackfill(
        JdbcTemplate pgVectorJdbcTemplate, Supplier<String> fullTableNameSupplier) {

        this.fullTableNameSupplier = fullTableNameSupplier;
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        backfill();
    }

    /**
     * The predicate is "carries neither key" rather than "does not carry {@code shared}", so a chunk already stamped
     * with an {@code owner_id} is never handed to every account by this sweep.
     *
     * <p>
     * {@code jsonb_exists(...)} rather than the {@code ?} operator it is spelled as in SQL: a literal {@code ?} in a
     * statement handed to {@link JdbcTemplate} is a bind placeholder, so the operator form would be rewritten into a
     * parameter and the statement would fail to prepare.
     */
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    public void backfill() {
        String fullTableName = resolveFullTableName();

        Boolean tableExists = pgVectorJdbcTemplate.queryForObject(
            "SELECT to_regclass(?) IS NOT NULL", Boolean.class, fullTableName);

        if (!Boolean.TRUE.equals(tableExists)) {
            if (log.isDebugEnabled()) {
                log.debug("Vector store table {} does not exist, skipping chunk ownership backfill", fullTableName);
            }

            return;
        }

        int updatedCount = pgVectorJdbcTemplate.update(
            "UPDATE " + fullTableName +
                " SET metadata = metadata::jsonb || '{\"" + METADATA_SHARED + "\": true}'::jsonb" +
                " WHERE " + UNOWNED_PREDICATE);

        if (updatedCount > 0) {
            log.info("Marked {} knowledge base chunks in {} as shared", updatedCount, fullTableName);
        }

        backfillOwnerType(fullTableName);

        createUnownedIndex(fullTableName);
        createOwnerIdWithoutTypeIndex(fullTableName);
    }

    /**
     * Completes the owner on chunks written before {@code owner_type} joined the encoding.
     *
     * <p>
     * Safe only while {@link OwnerType} has exactly one constant, and guarded on precisely that: with one kind of
     * principal in existence, an {@code owner_id} written by any version of this code can only ever have meant that
     * kind, so filling the type in is recovering a fact rather than guessing one. The day a second constant is appended
     * the guard stops this sweep, and any chunk still carrying a bare id stays invisible instead of being handed to
     * whichever kind happens to sort first -- invisible is recoverable, misattributed is not.
     */
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    private void backfillOwnerType(String fullTableName) {
        OwnerType[] ownerTypes = OwnerType.values();

        if (ownerTypes.length != 1) {
            return;
        }

        OwnerType ownerType = ownerTypes[0];

        int updatedCount = pgVectorJdbcTemplate.update(
            "UPDATE " + fullTableName +
                " SET metadata = metadata::jsonb || '{\"" + METADATA_OWNER_TYPE + "\": " + ownerType.ordinal()
                + "}'::jsonb" +
                " WHERE " + OWNER_ID_WITHOUT_TYPE_PREDICATE);

        if (updatedCount > 0) {
            log.info(
                "Completed the owner on {} knowledge base chunks in {} with owner type {}", updatedCount,
                fullTableName, ownerType);
        }
    }

    /**
     * Created after the sweep rather than before it, so the first pass builds the index over a table the sweep has
     * already emptied of matches instead of indexing every row only to dead-end them a statement later.
     *
     * <p>
     * {@code IF NOT EXISTS} makes this a no-op on every startup after the first. The index name is derived from the
     * table name so the two stay together per tenant in the multi-tenant deployment, where each schema carries its own
     * copy of the table.
     */
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    private void createUnownedIndex(String fullTableName) {
        String indexName = "idx_" + unqualified(fullTableName) + "_unowned_chunk";

        pgVectorJdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS " + indexName + " ON " + fullTableName + " (id) WHERE " + UNOWNED_PREDICATE);
    }

    /**
     * The same trick for the owner-type sweep, which is likewise a question asked on every startup and answered "none"
     * on all but the first.
     */
    @SuppressFBWarnings("SQL_INJECTION_SPRING_JDBC")
    private void createOwnerIdWithoutTypeIndex(String fullTableName) {
        String indexName = "idx_" + unqualified(fullTableName) + "_owner_id_without_type";

        pgVectorJdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS " + indexName + " ON " + fullTableName + " (id) WHERE "
                + OWNER_ID_WITHOUT_TYPE_PREDICATE);
    }

    /**
     * The index name is an identifier in its own right and may not carry the schema qualifier the table name does --
     * {@code CREATE INDEX schema.name ON schema.table} is a syntax error, the schema being taken from the table.
     */
    private static String unqualified(String fullTableName) {
        int separatorIndex = fullTableName.lastIndexOf('.');

        return separatorIndex < 0 ? fullTableName : fullTableName.substring(separatorIndex + 1);
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
