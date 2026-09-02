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

package com.bytechef.platform.owner;

import com.bytechef.platform.constant.OwnerType;

/**
 * The principal a resource belongs to.
 *
 * <p>
 * Two levels, for data tables and knowledge bases, and the same {@code Owner} says both. On the resource it means the
 * table or knowledge base is that account's alone. On a row or a chunk it means that record is theirs inside a resource
 * shared with everyone -- a read reaches their records plus the unowned ones, a write reaches theirs alone.
 *
 * <p>
 * Which level is meant is never a property of this record; it is a property of what holds it. {@code DataTableRef}
 * carries one of each and names them apart for that reason.
 *
 * <p>
 * Never the job principal: for {@code PlatformType.EMBEDDED} that is the integration-instance id and for the automation
 * bridge the project-deployment id, both of which differ per integration and per project for the same account. Storing
 * one of those would scatter a single account's resources across its own integrations.
 *
 * @author Ivica Cardic
 */
public record Owner(OwnerType type, long id) {

    public static Owner connectedUser(long id) {
        return new Owner(OwnerType.CONNECTED_USER, id);
    }
}
