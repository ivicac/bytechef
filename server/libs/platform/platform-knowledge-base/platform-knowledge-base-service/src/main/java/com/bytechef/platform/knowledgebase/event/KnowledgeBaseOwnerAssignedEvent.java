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

package com.bytechef.platform.knowledgebase.event;

import com.bytechef.platform.owner.Owner;
import org.jspecify.annotations.Nullable;

/**
 * Published by {@code KnowledgeBaseService#assignOwner} once the registry row and the documents have moved, and
 * consumed by {@link KnowledgeBaseOwnerAssignedListener} after that transaction commits.
 *
 * <p>
 * It exists so the two halves of an assignment cannot disagree in the direction that matters. The registry row and the
 * documents live in the application database; the chunks' owner lives in the vector store, a separate datasource with
 * no transaction spanning the two. Writing the vector store inline writes it on a transaction that may still roll back,
 * leaving chunks owned by an account that owns no document there -- a cross-account read, which is the state the row
 * axis exists to prevent. Deferring it to after the commit inverts the surviving inconsistency: chunks lagging BEHIND
 * their documents, which discloses nothing and which re-running the assignment repairs.
 *
 * @param knowledgeBaseId the knowledge base whose chunks are to follow their documents
 * @param owner           the new owner, or null when the knowledge base was handed back to the vendor
 *
 * @author Ivica Cardic
 */
public record KnowledgeBaseOwnerAssignedEvent(long knowledgeBaseId, @Nullable Owner owner) {
}
