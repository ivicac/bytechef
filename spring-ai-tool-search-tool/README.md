# spring-ai-tool-search-tool (vendored fork)

This directory is a **temporary fork** of
[`spring-ai-community/spring-ai-tool-search-tool`](https://github.com/spring-ai-community/spring-ai-tool-search-tool).

## Why is this vendored?

The upstream `2.1.0` release builds against `spring-ai 2.0.0-M4`. ByteChef tracks
`spring-ai 2.0.0-M8`, which widened the protected constructor on
`org.springframework.ai.chat.client.advisor.ToolCallAdvisor` (in M7, unchanged in
M8) from

```java
ToolCallAdvisor(ToolCallingManager toolCallingManager, int advisorOrder)
```

to

```java
ToolCallAdvisor(ToolCallingManager toolCallingManager, int advisorOrder,
                boolean conversationHistoryEnabled, boolean streamToolCallResponses)
```

That binary-incompatible change causes upstream's `ToolSearchToolCallAdvisor` to
fail at runtime with `NoSuchMethodError`. Rather than pin the entire project to
an older Spring AI milestone, we vendor the ~7 source files and apply a one-line
patch to the `super(...)` call.

## Source provenance

- **Upstream URL**: https://github.com/spring-ai-community/spring-ai-tool-search-tool
- **Forked from commit**: `9888e5e56cd33d103202143bdb907f6bd4a53386`
- **Upstream version**: `2.2.0-SNAPSHOT` (HEAD at fork time) /
  last published release `2.1.0`
- **Upstream license**: Apache License 2.0 (see `LICENSE.txt`)

## Modules

| Local Gradle path | Upstream Maven artifact |
|---|---|
| `:spring-ai-tool-search-tool:tool-search` | `org.springaicommunity:tool-search-tool` |
| `:spring-ai-tool-search-tool:tool-searcher-vectorstore` | `org.springaicommunity:tool-searcher-vectorstore` |

The `tool-searcher-lucene` and `tool-searcher-regex` searchers from upstream are
intentionally **not** vendored — ByteChef only consumes the vector-store
implementation.

## Removal plan

Drop this directory and restore the upstream Maven dependencies once **either**
of these is true:

1. The upstream project publishes a release that compiles against
   `spring-ai 2.0.0-M8` (or whatever Spring AI version ByteChef is on at that
   time).
2. ByteChef downgrades to a Spring AI release line that's still binary-
   compatible with upstream — unlikely.

To restore the upstream artifacts:

1. Re-add the two `org-springaicommunity-tool-*` entries to
   `gradle/libs.versions.toml`.
2. In the EE consumers
   (`server/ee/libs/automation/automation-ai-hub/automation-ai-hub-service`,
   `server/ee/libs/platform/platform-ai-hub/platform-ai-hub-service`), swap
   `implementation(project(":spring-ai-tool-search-tool:..."))` back to
   `implementation(libs.org.springaicommunity.tool.*)`.
3. Remove `include(...)` entries for this directory from
   `settings.gradle.kts`.
4. `rm -rf spring-ai-tool-search-tool/`.

## Local modifications

Any change made to the vendored sources is marked with the comment prefix
`// ByteChef temp fork:` so they can be easily located when re-syncing from
upstream or removing the fork. As of the initial vendor:

- `tool-search/src/main/java/org/springaicommunity/tool/search/ToolSearchToolCallAdvisor.java` —
  `super(...)` call widened from 2 args to 4 args for M7's new
  `ToolCallAdvisor` constructor signature (constructor unchanged in M8).
