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

package com.bytechef.platform.data.table.internal;

import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.owner.Owner;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Builds the physical table name for a data table.
 *
 * <p>
 * The pool lives in the physical name as well as in the {@code data_table.platform_type} column, so a name may repeat
 * across pools and the separation survives a query that forgets to filter. The two must never disagree.
 *
 * <p>
 * Reached through {@link com.bytechef.platform.data.table.domain.DataTableRef} rather than called directly:
 * {@code DataTableRef} is the only thing that names a physical table, and it can only be built with an owner in hand. A
 * call site that reaches this class straight would be choosing an owner for itself, which is the mistake the ref exists
 * to prevent.
 *
 * @author Ivica Cardic
 */
public final class PhysicalTableNaming {

    private PhysicalTableNaming() {
    }

    public static String buildPhysicalName(
        PlatformType platformType, long environmentId, @Nullable Owner owner, String baseName) {

        return prefix(platformType, environmentId, owner) + baseName.toLowerCase(Locale.ROOT);
    }

    /**
     * The owner sits between the environment and the base name, and it is the whole owner: the id and then the type,
     * because an owner IS the pair. The registry key says the same thing -- {@code owner_type} is in the unique index
     * beside {@code owner_id} -- and a name built from the id alone would give two owners of different types sharing an
     * id two registry rows and one physical table.
     *
     * <p>
     * A base name cannot start with a digit ({@code [a-z_][a-z0-9_]*}), so an owned name can never be spelled as a
     * shared one -- that validation is what keeps the two forms apart, and loosening it would make them collide. The id
     * therefore stays first, ahead of the type token, so that the leading digit remains the marker.
     */
    public static String prefix(PlatformType platformType, long environmentId, @Nullable Owner owner) {
        String environmentPrefix = poolToken(platformType) + "_" + environmentId + "_";

        return owner == null ? environmentPrefix : environmentPrefix + owner.id() + "_" + ownerTypeToken(owner.type())
            + "_";
    }

    /**
     * The owner type as it is spelled in a physical name: the enum constant, lowercased, with its underscores removed.
     *
     * <p>
     * Deliberately not the ordinal, which is what {@code owner_type} persists. A column holding a stale ordinal can be
     * UPDATEd; a physical table carrying one has to be found and renamed, and until it is, a reordered enum has every
     * name silently addressing another owner's table. The constant name fails the other way: rename one and no name
     * built from it resolves -- {@link #ownerType(String)} returns empty and the table a run addresses does not exist.
     * Loud beats compact, and the length that costs is bounded by
     * {@link com.bytechef.platform.data.table.domain.DataTableRef}'s identifier-length check.
     *
     * <p>
     * The underscores go because the token sits between two fields that are themselves underscore-delimited. Keeping
     * them would make {@code 5_connected_user_orders} parse two ways -- type {@code connected} with base name
     * {@code user_orders}, or type {@code connected_user} with base name {@code orders} -- and the ambiguity would have
     * to be resolved by consulting the enum, which is not something a regex over {@code information_schema} should have
     * to do. Without them the token is the run up to the first underscore, and there is exactly one parse.
     *
     * <p>
     * Two constants whose names differ only in underscore placement would collide here. {@code PhysicalTableNamingTest}
     * refuses that.
     */
    public static String ownerTypeToken(OwnerType ownerType) {
        String name = ownerType.name();

        return name.toLowerCase(Locale.ROOT)
            .replace("_", "");
    }

    /**
     * The owner type a token names, or empty when no type answers to it.
     *
     * <p>
     * Empty is a real answer, not a failure to report: a physical table left behind by a build that knew a type this
     * one does not must be skipped, the way {@code listTables} skips an unregistered table, rather than guessed at.
     */
    public static Optional<OwnerType> ownerType(String ownerTypeToken) {
        for (OwnerType ownerType : OwnerType.values()) {
            if (ownerTypeToken(ownerType).equals(ownerTypeToken)) {
                return Optional.of(ownerType);
            }
        }

        return Optional.empty();
    }

    public static String poolToken(PlatformType platformType) {
        return platformType == PlatformType.EMBEDDED ? "edt" : "dt";
    }
}
