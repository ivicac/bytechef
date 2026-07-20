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

package com.bytechef.platform.ratelimit.web;

import com.bytechef.platform.plan.domain.PlanLimits;
import com.bytechef.platform.plan.provider.PlanLimitsProvider;
import com.bytechef.platform.ratelimit.RateLimitPolicy;
import com.bytechef.platform.ratelimit.RateLimiter;
import com.bytechef.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Plan-limit HTTP rate limiting (Sim tiers): webhook trigger calls consume the sync tier, public API calls the API
 * tier, and unauthenticated {@code /api/**} traffic is throttled per client IP on the API tier (pre-auth flood control)
 * with a fixed tight budget on the login endpoint (brute-force control). A {@code null} limit — the SELF_HOSTED default
 * — means the class is unlimited and the request passes untouched, so existing deployments see zero behavior change
 * until a plan tier is configured. Rejections are HTTP 429 with {@code Retry-After: 60}.
 *
 * @author Ivica Cardic
 */
public class PlanRateLimitFilter extends OncePerRequestFilter {

    private static final RateLimitPolicy LOGIN_POLICY = new RateLimitPolicy(10, 1);

    private final PlanLimitsProvider planLimitsProvider;
    private final RateLimiter rateLimiter;

    public PlanRateLimitFilter(PlanLimitsProvider planLimitsProvider, RateLimiter rateLimiter) {
        this.planLimitsProvider = planLimitsProvider;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {

        String path = request.getRequestURI();

        // Login brute-force control is a fixed budget, independent of the plan tier.
        if ("/api/authentication".equals(path) && "POST".equalsIgnoreCase(request.getMethod())) {
            if (!rateLimiter.tryConsume("login:" + clientIp(request), LOGIN_POLICY)) {
                reject(response);

                return;
            }

            filterChain.doFilter(request, response);

            return;
        }

        PlanLimits planLimits = planLimitsProvider.getPlanLimits(TenantContext.getCurrentTenantId());

        Integer permitsPerMinute;
        String bucketKey;

        if (path.startsWith("/webhooks/")) {
            // Webhook trigger calls — Sim's sync request tier, budgeted per tenant.
            permitsPerMinute = planLimits.syncRequestsPerMinute();
            bucketKey = "sync:" + TenantContext.getCurrentTenantId();
        } else if (isPublicApiPath(path)) {
            // Public API — budgeted per tenant.
            permitsPerMinute = planLimits.apiRequestsPerMinute();
            bucketKey = "api:" + TenantContext.getCurrentTenantId();
        } else if (path.startsWith("/api/") && isAnonymous()) {
            // Pre-auth flood control: anything under /api/ without an authenticated principal is throttled per
            // client IP so unauthenticated scans can't grind authenticated tenants' budgets or the endpoints.
            permitsPerMinute = planLimits.apiRequestsPerMinute();
            bucketKey = "preauth:" + clientIp(request);
        } else {
            filterChain.doFilter(request, response);

            return;
        }

        if (permitsPerMinute == null ||
            rateLimiter.tryConsume(bucketKey, new RateLimitPolicy(permitsPerMinute, planLimits.burstMultiplier()))) {

            filterChain.doFilter(request, response);

            return;
        }

        reject(response);
    }

    private static boolean isPublicApiPath(String path) {
        return path.startsWith("/api/automation/v1/") || path.startsWith("/api/embedded/v1/");
    }

    private static boolean isAnonymous() {
        Authentication authentication = SecurityContextHolder.getContext()
            .getAuthentication();

        return authentication == null || authentication instanceof AnonymousAuthenticationToken ||
            !authentication.isAuthenticated();
    }

    private static String clientIp(HttpServletRequest request) {
        @Nullable
        String forwardedFor = request.getHeader("X-Forwarded-For");

        if (forwardedFor != null && !forwardedFor.isBlank()) {
            int commaIndex = forwardedFor.indexOf(',');

            return commaIndex > 0 ? forwardedFor.substring(0, commaIndex)
                .trim() : forwardedFor.trim();
        }

        return request.getRemoteAddr();
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", "60");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        response.getWriter()
            .write("{\"error\":\"rate_limit_exceeded\",\"message\":\"Too many requests; retry after 60 seconds.\"}");
    }
}
