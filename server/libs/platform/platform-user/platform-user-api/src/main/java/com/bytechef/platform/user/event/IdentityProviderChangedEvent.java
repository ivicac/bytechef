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

package com.bytechef.platform.user.event;

/**
 * Published when an identity provider is created, updated, or deleted, so caches keyed on a tenant's identity providers
 * (e.g. the MCP per-tenant issuer cache) can invalidate promptly rather than waiting for their TTL.
 *
 * @author Ivica Cardic
 */
public record IdentityProviderChangedEvent() {
}
