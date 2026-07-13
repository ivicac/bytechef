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

package com.bytechef.component.ldap.connection;

import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.ldap.constant.LdapConstants.BIND_DN;
import static com.bytechef.component.ldap.constant.LdapConstants.PASSWORD;
import static com.bytechef.component.ldap.constant.LdapConstants.URL;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;

/**
 * @author Ivica Cardic
 */
public class LdapConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .authorizations(
            authorization(AuthorizationType.CUSTOM)
                .title("Simple Bind")
                .properties(
                    string(URL)
                        .label("URL")
                        .description("The URL of the LDAP server (e.g. ldap://ldap.example.com:389).")
                        .required(true),
                    string(BIND_DN)
                        .label("Bind DN")
                        .description("The distinguished name used to bind (e.g. cn=admin,dc=example,dc=com).")
                        .required(true),
                    string(PASSWORD)
                        .label("Password")
                        .description("The password used to bind.")
                        .required(true)))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/ldap_v1#connection-setup");

    private LdapConnection() {
    }
}
