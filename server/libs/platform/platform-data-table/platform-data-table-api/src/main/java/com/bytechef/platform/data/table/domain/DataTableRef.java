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

package com.bytechef.platform.data.table.domain;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.internal.PhysicalTableNaming;
import com.bytechef.platform.owner.Owner;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;

/**
 * One physical data table, fully addressed: the pool that holds it, the environment it lives in, the base name it is
 * known by, and the owner the run acts for.
 *
 * <p>
 * A table belongs to nobody. There is one physical table per base name per environment per pool, and the only thing
 * separating two accounts inside it is the {@code owner_id} predicate the row statements apply. The ref is the one
 * place a row statement learns whose run it is: {@code runOwner} is null for a run with no named account, which sees
 * unowned rows only.
 *
 * <p>
 * This is also the only way to name a physical table. Every DDL and DML statement takes a ref rather than a base name,
 * so no call site can hand over a base name and leave the callee to work out the rest of the address.
 *
 * <p>
 * The base name is validated here rather than at each statement, so a ref is well formed by construction and the
 * identifier allowlist that keeps the generated SQL injection-free holds for every physical name built from one.
 *
 * @author Ivica Cardic
 */
public record DataTableRef(
    String baseName, long environmentId, PlatformType platformType, @Nullable Owner runOwner) {

    /**
     * Postgres truncates an identifier past {@code NAMEDATALEN - 1} bytes rather than refusing it, and two physical
     * names that truncate to the same 63 bytes are the same table. Checked here, where every name is built, because a
     * long enough base name is all it takes.
     */
    private static final int MAX_IDENTIFIER_BYTES = 63;

    public DataTableRef {
        Assert.hasText(baseName, "baseName must not be empty");
        Assert.notNull(platformType, "platformType must not be null");

        baseName = baseName.toLowerCase(Locale.ROOT);

        Assert.isTrue(!baseName.startsWith("dt_"), "baseName must not start with 'dt_'");
        Assert.isTrue(baseName.matches("[a-z_][a-z0-9_]*"), "Invalid base name: " + baseName);

        String physicalName = PhysicalTableNaming.buildPhysicalName(platformType, environmentId, baseName);
        byte[] bytes = physicalName.getBytes(StandardCharsets.UTF_8);

        Assert.isTrue(
            bytes.length <= MAX_IDENTIFIER_BYTES,
            "Physical table name '" + physicalName + "' exceeds " + MAX_IDENTIFIER_BYTES + " bytes");
    }

    /**
     * A ref held by a run with no named account: the vendor's, scoped to the rows that belong to nobody. The table it
     * addresses is the same table every account addresses -- only the rows differ.
     */
    public static DataTableRef unowned(String baseName, long environmentId, PlatformType platformType) {
        return new DataTableRef(baseName, environmentId, platformType, null);
    }

    /**
     * The id of the owner the run acts for, or null where the run has none. A convenience for the callers that only
     * need the id -- the ref itself holds the pair, because that is what an owner is.
     */
    public @Nullable Long runOwnerId() {
        return runOwner == null ? null : runOwner.id();
    }

    /**
     * The Postgres table this ref addresses: {@code <pool>_<envId>_<baseName>}, whoever the run acts for.
     */
    public String physicalName() {
        return PhysicalTableNaming.buildPhysicalName(platformType, environmentId, baseName);
    }
}
