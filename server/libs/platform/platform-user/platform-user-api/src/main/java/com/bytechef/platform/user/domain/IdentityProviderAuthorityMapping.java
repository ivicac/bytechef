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

package com.bytechef.platform.user.domain;

import java.util.Objects;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Maps an external IdP group (a value of the token's authorities claim) to a ByteChef authority, so a tenant's MCP tool
 * authorization can gate on ByteChef authorities without the IdP having to emit ByteChef-specific authority strings.
 *
 * @author Ivica Cardic
 */
@Table("identity_provider_authority_mapping")
public final class IdentityProviderAuthorityMapping {

    @Column("external_group")
    private String externalGroup;

    @Column
    private String authority;

    private IdentityProviderAuthorityMapping() {
    }

    public IdentityProviderAuthorityMapping(String externalGroup, String authority) {
        this.externalGroup = externalGroup;
        this.authority = authority;
    }

    public String getExternalGroup() {
        return externalGroup;
    }

    public String getAuthority() {
        return authority;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        IdentityProviderAuthorityMapping that = (IdentityProviderAuthorityMapping) o;

        return Objects.equals(externalGroup, that.externalGroup);
    }

    @Override
    public int hashCode() {
        return Objects.hash(externalGroup);
    }

    @Override
    public String toString() {
        return "IdentityProviderAuthorityMapping{" +
            "externalGroup='" + externalGroup + '\'' +
            ", authority='" + authority + '\'' +
            '}';
    }
}
