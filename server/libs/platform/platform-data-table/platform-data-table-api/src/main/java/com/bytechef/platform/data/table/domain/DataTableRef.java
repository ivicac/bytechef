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
 * known by, the owner whose copy it is, and the owner the run acts for.
 *
 * <p>
 * This is the only way to name a physical table. Every DDL and DML statement takes a ref rather than a base name, so
 * the owner that goes into the physical name is necessarily the owner that resolution settled on -- a caller cannot
 * supply a base name and let the callee guess an owner, which is exactly how a run ends up reading or writing another
 * account's table.
 *
 * <p>
 * Two owners, on two independent axes:
 * <ul>
 * <li>{@code resourceOwner} -- whose the TABLE is. It comes off the registry row resolution chose and is what
 * {@link PhysicalTableNaming#buildPhysicalName} spells into the physical name. Null means the vendor's shared table,
 * which every account in the pool reads.</li>
 * <li>{@code runOwner} -- whom the RUN acts for. It selects rows inside whichever table was resolved. Null means a run
 * with no owner: the vendor, which sees unowned rows only.</li>
 * </ul>
 *
 * <p>
 * One field cannot express both. An account reading the shared table has no resource owner and a run owner; an account
 * reading its own table has both; a vendor has neither. Collapsing them would leave the shared-table case unable to say
 * who is asking, which is precisely the cross-account read the pair exists to prevent.
 *
 * <p>
 * Two producers, and the difference between them is the whole point:
 * <ul>
 * <li>{@code DataTableService.fetchDataTableResolution} -- resolution. The resource owner comes out of the same lookup
 * that chose the registry row, so the two cannot disagree. Everything reachable from an EMBEDDED run must come through
 * here.</li>
 * <li>{@link #shared(String, long, PlatformType)} -- an explicit claim that the table has no owner and that no run
 * owner scopes it. Correct only where the caller knows that for certain: the AUTOMATION pool, whose tables never carry
 * an owner, and the creation of a shared table. Using it on an EMBEDDED path would silently address the vendor's table
 * instead of the account's.</li>
 * </ul>
 *
 * <p>
 * The base name is validated here rather than at each statement, so a ref is well formed by construction and the
 * identifier allowlist that keeps the generated SQL injection-free holds for every physical name built from one.
 *
 * @author Ivica Cardic
 */
public record DataTableRef(
    String baseName, long environmentId, PlatformType platformType, @Nullable Owner resourceOwner,
    @Nullable Owner runOwner) {

    /**
     * Postgres truncates an identifier past {@code NAMEDATALEN - 1} bytes rather than refusing it, and two physical
     * names that truncate to the same 63 bytes are the same table. Checked here, where every name is built, because the
     * owner the name carries is what pushes a name over: a shared name is the length it always was.
     */
    private static final int MAX_IDENTIFIER_BYTES = 63;

    /**
     * The invariant is {@code resourceOwner == null || resourceOwner.equals(runOwner)}: a ref addressing an account's
     * own physical table must be held by a run acting for that account.
     *
     * <p>
     * Resolution already guarantees it -- a run with no owner resolves only shared tables, and an owned row is only
     * ever matched against the run's own owner -- so this should never fire in production. Its job is to make a
     * mis-resolved ref unconstructable rather than merely absent, which turns a whole class of cross-account bug from
     * something to test for into something that cannot be written.
     *
     * <p>
     * DDL that names a table FOR an owner -- creating one, renaming one, moving one to a new owner -- passes that owner
     * in both positions. Such a statement acts for the owner it names, and the invariant admits no other value.
     */
    public DataTableRef {
        Assert.hasText(baseName, "baseName must not be empty");
        Assert.notNull(platformType, "platformType must not be null");
        Assert.isTrue(
            resourceOwner == null || resourceOwner.equals(runOwner),
            "A ref addressing an owned table must be held by a run acting for that owner");

        baseName = baseName.toLowerCase(Locale.ROOT);

        Assert.isTrue(!baseName.startsWith("dt_"), "baseName must not start with 'dt_'");
        Assert.isTrue(baseName.matches("[a-z_][a-z0-9_]*"), "Invalid base name: " + baseName);

        String physicalName =
            PhysicalTableNaming.buildPhysicalName(platformType, environmentId, resourceOwner, baseName);
        byte[] bytes = physicalName.getBytes(StandardCharsets.UTF_8);

        Assert.isTrue(
            bytes.length <= MAX_IDENTIFIER_BYTES,
            "Physical table name '" + physicalName + "' exceeds " + MAX_IDENTIFIER_BYTES + " bytes");
    }

    /**
     * A table with no owner, addressed by a run with no owner: the vendor's, shared with every account, scoped to the
     * rows that belong to nobody.
     *
     * <p>
     * Read the class javadoc before reaching for this. It is a claim about ownership, not a shortcut around resolution.
     */
    public static DataTableRef shared(String baseName, long environmentId, PlatformType platformType) {
        return new DataTableRef(baseName, environmentId, platformType, null, null);
    }

    /**
     * The id of the owner whose physical table this is, or null where the table is shared. A convenience for the
     * callers that only need the id -- the ref itself holds the pair, because that is what an owner is.
     */
    public @Nullable Long resourceOwnerId() {
        return resourceOwner == null ? null : resourceOwner.id();
    }

    /**
     * The id of the owner the run acts for, or null where the run has none. Deliberately a second method rather than
     * one whose meaning a caller has to guess: the two ids answer different questions and are unequal exactly where it
     * matters -- an account reading the shared table.
     */
    public @Nullable Long runOwnerId() {
        return runOwner == null ? null : runOwner.id();
    }

    /**
     * The Postgres table this ref addresses. Shared tables keep the unowned form {@code <pool>_<envId>_<baseName>} --
     * they are released data and must never be renamed -- and an owned table adds its owner between the two.
     */
    public String physicalName() {
        return PhysicalTableNaming.buildPhysicalName(platformType, environmentId, resourceOwner, baseName);
    }
}
