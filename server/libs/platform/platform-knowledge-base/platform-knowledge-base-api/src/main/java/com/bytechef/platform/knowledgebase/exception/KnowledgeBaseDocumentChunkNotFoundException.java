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

package com.bytechef.platform.knowledgebase.exception;

/**
 * Thrown when a knowledge base document chunk lookup fails because no row exists with the given id, and -- the reason
 * this is a type rather than the plain {@code RuntimeException} it replaces -- when a chunk exists but does not belong
 * to the knowledge base the caller was admitted to. The two cases are deliberately indistinguishable: a caller who
 * could tell "someone else's chunk" from "no such chunk" could enumerate the id space of every other account.
 *
 * <p>
 * A refusal has to be raised from a guard that has no chunk row of its own to describe, so the message it produces must
 * be identical to the service's own miss. Sharing a type is how that is kept true through a message rename, which
 * matching on the string was not.
 *
 * @author Ivica Cardic
 */
public class KnowledgeBaseDocumentChunkNotFoundException extends RuntimeException {

    private final long documentChunkId;

    public KnowledgeBaseDocumentChunkNotFoundException(long documentChunkId) {
        super("KnowledgeBase document chunk not found: " + documentChunkId);

        this.documentChunkId = documentChunkId;
    }

    public long getDocumentChunkId() {
        return documentChunkId;
    }
}
