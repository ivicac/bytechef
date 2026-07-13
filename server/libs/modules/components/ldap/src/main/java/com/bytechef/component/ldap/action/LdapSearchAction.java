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

package com.bytechef.component.ldap.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.ldap.constant.LdapConstants.BASE_DN;
import static com.bytechef.component.ldap.constant.LdapConstants.BIND_DN;
import static com.bytechef.component.ldap.constant.LdapConstants.FILTER;
import static com.bytechef.component.ldap.constant.LdapConstants.PASSWORD;
import static com.bytechef.component.ldap.constant.LdapConstants.URL;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Parameters;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;

/**
 * @author Ivica Cardic
 */
public class LdapSearchAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("search")
        .title("Search")
        .description("Searches entries in the directory using an LDAP filter.")
        .properties(
            string(BASE_DN)
                .label("Base DN")
                .description("The distinguished name of the entry to start the search from.")
                .required(true),
            string(FILTER)
                .label("Filter")
                .description("The LDAP search filter (e.g. (objectClass=person)).")
                .defaultValue("(objectClass=*)")
                .required(false))
        .output(
            outputSchema(
                array()
                    .description("The entries matching the filter.")
                    .items(
                        object()
                            .properties(
                                string("dn")
                                    .description("The distinguished name of the entry."),
                                object("attributes")
                                    .description("The attributes of the entry.")))))
        .help("", "https://docs.bytechef.io/reference/components/ldap_v1#search")
        .perform(LdapSearchAction::perform);

    private LdapSearchAction() {
    }

    @SuppressFBWarnings(value = "LDAP_INJECTION", justification = "the search filter is intentionally user-defined")
    public static List<Map<String, Object>> perform(
        Parameters inputParameters, Parameters connectionParameters, com.bytechef.component.definition.Context context)
        throws NamingException {

        Hashtable<String, String> environment = new Hashtable<>();

        environment.put(javax.naming.Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        environment.put(javax.naming.Context.PROVIDER_URL, connectionParameters.getRequiredString(URL));
        environment.put(javax.naming.Context.SECURITY_AUTHENTICATION, "simple");
        environment.put(javax.naming.Context.SECURITY_PRINCIPAL, connectionParameters.getRequiredString(BIND_DN));
        environment.put(javax.naming.Context.SECURITY_CREDENTIALS, connectionParameters.getRequiredString(PASSWORD));

        DirContext dirContext = new InitialDirContext(environment);

        try {
            SearchControls searchControls = new SearchControls();

            searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);

            NamingEnumeration<SearchResult> searchResults = dirContext.search(
                inputParameters.getRequiredString(BASE_DN), inputParameters.getString(FILTER, "(objectClass=*)"),
                searchControls);

            List<Map<String, Object>> entries = new ArrayList<>();

            while (searchResults.hasMore()) {
                SearchResult searchResult = searchResults.next();

                Map<String, Object> attributes = new HashMap<>();

                NamingEnumeration<? extends Attribute> attributeEnumeration = searchResult.getAttributes()
                    .getAll();

                while (attributeEnumeration.hasMore()) {
                    Attribute attribute = attributeEnumeration.next();

                    List<Object> values = new ArrayList<>();

                    NamingEnumeration<?> valueEnumeration = attribute.getAll();

                    while (valueEnumeration.hasMore()) {
                        values.add(valueEnumeration.next());
                    }

                    attributes.put(attribute.getID(), values.size() == 1 ? values.getFirst() : values);
                }

                Map<String, Object> entry = new HashMap<>();

                entry.put("dn", searchResult.getNameInNamespace());
                entry.put("attributes", attributes);

                entries.add(entry);
            }

            return entries;
        } finally {
            dirContext.close();
        }
    }
}
