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

import com.bytechef.platform.constant.PlatformType;
import java.util.Locale;

/**
 * Builds the physical table name for a data table.
 *
 * <p>
 * The pool lives in the physical name as well as in the {@code data_table.platform_type} column, so a name may repeat
 * across pools and the separation survives a query that forgets to filter. The two must never disagree.
 *
 * <p>
 * Reached through {@link com.bytechef.platform.data.table.domain.DataTableRef} rather than called directly:
 * {@code DataTableRef} is the only thing that names a physical table, and it validates the base name that goes into
 * every name built here.
 *
 * @author Ivica Cardic
 */
public final class PhysicalTableNaming {

    private PhysicalTableNaming() {
    }

    public static String buildPhysicalName(PlatformType platformType, long environmentId, String baseName) {
        return prefix(platformType, environmentId) + baseName.toLowerCase(Locale.ROOT);
    }

    public static String prefix(PlatformType platformType, long environmentId) {
        return poolToken(platformType) + "_" + environmentId + "_";
    }

    public static String poolToken(PlatformType platformType) {
        return platformType == PlatformType.EMBEDDED ? "edt" : "dt";
    }
}
