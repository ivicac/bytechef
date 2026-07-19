# AI Gateway hardening: config parity, routing cache, streaming failover, remote-client analysis

Date: 2026-07-19
Status: Landed (config, cache, streaming) + Decision (remote-client)

## Context

A review of the EE AI Gateway (`ai-gateway-app` + `platform-ai-gateway-*` +
`automation-ai-gateway-*`) surfaced several real gaps. This records what was fixed and the
one deliberate non-change (the distributed remote-client).

## Fixed

### 1. Config-server wiring (the gateway was disabled in a microservices topology)

- The config-server file was `llm-gateway-app.yml`, but the app's `spring.application.name` is
  `ai-gateway-app`, so Spring Cloud Config never served it. Renamed to `ai-gateway-app.yml`.
- Inside it the enable flag was `bytechef.ai.ai-gateway.enabled`, while every
  `@ConditionalOnProperty` in code checks `bytechef.ai.gateway.enabled`. Corrected the nesting.
- Every sibling EE app ships `-dev.yml` (server port + livereload) and `-prod.yml`; `ai-gateway-app`
  had only the base file. Added `ai-gateway-app-dev.yml` (port 7999, livereload 35736) and an empty
  `ai-gateway-app-prod.yml` for parity.

### 2. Response cache on the routing path

The response cache is keyed on request content (model-agnostic), but only `chatCompletionDirect`
consulted it; requests carrying a routing policy always bypassed the cache. `chatCompletionWithRouting`
now performs the same lookup at entry and caches after a successful routed call.

### 3. Streaming pre-first-token cross-deployment failover

`AiGatewayRetryHandler.executeStreamWithRetry` existed but had **zero callers** because it was unsafe:
it retried and failed over unconditionally, so a failure after some tokens had already been streamed
would re-subscribe and replay already-flushed SSE tokens.

- `tryDeploymentStream` now tracks whether any element has been emitted (`AtomicBoolean`) and gates
  BOTH same-deployment retry and cross-deployment failover on it. Once the first token is out, errors
  propagate terminally. (Unit-tested: fails over before first token; does NOT fail over or replay
  after; propagates when all deployments fail before emitting.)
- `chatCompletionStreamInternal` now, when a routing policy is set, selects the ordered deployments
  (`selectRoutedDeployments`) and streams through `executeStreamWithRetry`, resolving each deployment's
  model/provider/prompt per attempt. The request log finalizes for whichever deployment actually
  streamed a token (captured on first emit), falling back to the routed primary for a pre-token
  terminal error. (Verified with a StepVerifier facade test that drives the real per-deployment
  builder through a mocked `executeStreamWithRetry`.)

Streaming still does not cache (a live token stream isn't a cacheable single response) — unchanged and
intentional.

## Deliberate non-change: distributed `ai-gateway-remote-client`

The review noted "no `ai-gateway-remote-client` for distributed deploy (monolith-only)." Investigation:

- `AiGatewayFacade` has exactly **one** in-process cross-module caller: `AiEvalExperimentExecutor`
  (`automation-ai-eval-experiment-service`), and it calls **only** `chatCompletion(request, headers, null)`.
- Both `automation-ai-gateway-service` (the impl) and `automation-ai-eval-experiment-service` are
  included **only in `ai-gateway-app`** — they are co-located. No cross-app boundary exists today, so
  there is no app that has the eval caller but lacks the `AiGatewayFacade` bean.

Therefore a remote-client is **not needed in the current topology**, and building one now would be dead,
untestable speculative infrastructure. It becomes necessary only if `automation-ai-eval-experiment-service`
is split into an app that does **not** also run `automation-ai-gateway-service`.

**Design for when that split happens** (follow the EE remote-client pattern):

- New module `automation-ai-gateway-remote-client` with a `@Component @ConditionalOnEEVersion`
  `AiGatewayFacade` implementation, gated `@ConditionalOnMissingBean(AiGatewayFacadeImpl.class)` (or a
  property) so it never conflicts with the real impl in `ai-gateway-app`.
- Implement `chatCompletion(...)` (the only method eval uses) as a `RestClient`/`WebClient` call to the
  gateway app's OpenAI-compatible endpoint `POST /api/ai-gateway/v1/chat/completions` (via
  `lb://ai-gateway-app`), mapping `AiGatewayChatCompletionRequest` ⇄ the OpenAI-format DTOs and passing
  the caller's API key. The streaming/embedding/score methods throw `UnsupportedOperationException`
  until a caller needs them (matching the established stub convention).
- Add the remote-client module to whatever app runs the split-out eval service.

This is left unbuilt on purpose: it cannot be verified without the split existing, and the co-located
topology does not exercise it.
