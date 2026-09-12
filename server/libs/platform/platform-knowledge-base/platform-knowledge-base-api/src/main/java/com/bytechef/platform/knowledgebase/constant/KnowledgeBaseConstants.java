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

package com.bytechef.platform.knowledgebase.constant;

/**
 * @author Ivica Cardic
 */
public class KnowledgeBaseConstants {

    public static final String METADATA_ENVIRONMENT_ID = "environment_id";
    public static final String METADATA_KNOWLEDGE_BASE_ID = "knowledge_base_id";
    public static final String METADATA_KNOWLEDGE_BASE_DOCUMENT_ID = "knowledge_base_document_id";
    public static final String METADATA_KNOWLEDGE_BASE_DOCUMENT_CHUNK_ID = "knowledge_base_document_chunk_id";
    public static final String METADATA_TAG_NAMES = "tag_names";

    /**
     * The account a chunk belongs to, present only on an owned chunk.
     */
    public static final String METADATA_OWNER_ID = "owner_id";

    /**
     * The kind of principal {@link #METADATA_OWNER_ID} names, as an {@code OwnerType} ordinal, present only on an owned
     * chunk and always beside it.
     *
     * <p>
     * The id alone does not identify an owner. {@code OwnerType} has one constant today, so two principals of different
     * kinds cannot yet share an id and the type is unexercisable -- which is exactly why it goes in now: a chunk is
     * written once and read forever, and a second constant would make every chunk written without a type ambiguous with
     * no way to tell afterwards which kind it meant. The data table columns already carry both for the same reason.
     */
    public static final String METADATA_OWNER_TYPE = "owner_type";

    /**
     * Marks a chunk as belonging to no account: every account may read it and none may write it.
     *
     * <p>
     * A boolean flag rather than the absence of {@link #METADATA_OWNER_ID}, because "key is absent" is not expressible
     * in a {@code Filter.Expression} portably across vector stores. The per-tag flags ({@code tag_names_NAME: true})
     * already answer the same problem the same way; this mirrors them rather than inventing a second convention.
     */
    public static final String METADATA_SHARED = "shared";
}
