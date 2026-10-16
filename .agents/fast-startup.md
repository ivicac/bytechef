# Fast IntelliJ Dev Startup (`fastStartup`)
- `server-app` startup from IntelliJ is slow because IntelliJ puts every module dependency on the
  classpath as an exploded output directory (hundreds of dirs → slow ServiceLoader/DevTools scans).
- Set `fastStartup=true` in `gradle.properties` (alias: the deprecated `useComponentJars`) to present
  **all** bytechef library + component modules to IntelliJ as pre-built JARs instead. Everything then
  loads in a single base classloader — fast startup, and no Spring DevTools base/restart classloader
  split (the split is what made the old component-only jar swap fail with `NoClassDefFoundError`).
- Mechanism (`server/apps/server-app/build.gradle.kts`): a resolvable `fastStartupRuntime` configuration
  forces the runtime-JAR variant and carries each module's **full transitive closure**, then feeds it
  back onto `implementation` as one `files(...)` entry. Plain `files(jarTask)` would drop transitives —
  that was the old breakage.
- Workflow: `fastStartup=true` → `./gradlew -PfastStartup=true :server:apps:server-app:buildModuleJars --parallel`
  → refresh Gradle in IntelliJ → run. Trade-off: DevTools still hot-reloads server-app's own code, but
  library modules (now JARs) don't hot-reload — edit a lib, re-run `buildModuleJars` + restart (or use
  IntelliJ HotSwap for method bodies). Zero-prebuild alternative: IntelliJ "Build and run using: Gradle"
  (bootRun's `runtimeClasspath` already yields JARs). `fastStartup=false` (default) keeps full library
  hot-reload and is byte-for-byte the previous behavior.
