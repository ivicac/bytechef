dependencies {
    annotationProcessor(rootProject.libs.com.google.auto.service.auto.service)

    implementation("org.apache.commons:commons-lang3")
    implementation("org.slf4j:slf4j-api")
    implementation(rootProject.libs.com.google.auto.service.auto.service.annotations)
    implementation("org.springframework:spring-context")
    implementation("org.springframework.ai:spring-ai-client-chat")
    implementation("org.springframework.ai:spring-ai-model")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("tools.jackson.core:jackson-databind")
    implementation(project(":server:libs:ai:ai-api"))
    implementation(project(":server:libs:ai:ai-copilot:ai-copilot-tool"))
    implementation(project(":server:libs:ai:ai-mcp:ai-mcp-server-api"))
    implementation(project(":server:libs:atlas:atlas-configuration:atlas-configuration-api"))
    implementation(project(":server:libs:atlas:atlas-execution:atlas-execution-api"))
    implementation(project(":server:libs:automation:automation-ai:automation-ai-agent:automation-ai-agent-api"))
    implementation(project(":server:libs:automation:automation-ai:automation-ai-mcp:automation-ai-mcp-api"))
    implementation(project(":server:libs:automation:automation-asset-file:automation-asset-file-api"))
    implementation(project(":server:libs:automation:automation-configuration:automation-configuration-api"))
    implementation(project(":server:libs:automation:automation-data-table:automation-data-table-api"))
    implementation(project(":server:libs:automation:automation-knowledge-base:automation-knowledge-base-api"))
    implementation(project(":server:libs:automation:automation-workflow:automation-workflow-execution:automation-workflow-execution-api"))
    implementation(project(":server:libs:core:commons:commons-util"))
    implementation(project(":server:libs:core:error:error-api"))
    implementation(project(":server:libs:core:exception:exception-api"))
    implementation(project(":server:libs:platform:platform-ai:platform-ai-api"))
    implementation(project(":server:libs:platform:platform-ai:platform-ai-skill:platform-ai-skill-api"))
    implementation(project(":server:libs:platform:platform-api"))
    implementation(project(":server:libs:platform:platform-component:platform-component-api"))
    implementation(project(":server:libs:platform:platform-configuration:platform-configuration-api"))
    implementation(project(":server:libs:platform:platform-data-table:platform-data-table-api"))
    implementation(project(":server:libs:platform:platform-knowledge-base:platform-knowledge-base-api"))
    implementation(project(":server:libs:platform:platform-mcp:platform-mcp-api"))
    implementation(project(":server:libs:platform:platform-user:platform-user-api"))
    implementation(project(":server:libs:platform:platform-workflow:platform-workflow-execution:platform-workflow-execution-api"))

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.mockito:mockito-junit-jupiter")
    testImplementation(project(":server:libs:test:test-support"))
}

// ToolContextWorkspaceVerificationTest reads production Java sources across server/ (including other modules, e.g.
// ai-copilot-service, ai-hub-service) directly off disk at run time via Files.readString, which is invisible to
// Gradle's task-input graph. Without help, a change to one of those other modules' files leaves this module's own
// test task fingerprint unchanged, and both the up-to-date check and the remote build-cache key (this build enables
// org.gradle.caching) would report the task as still current - the scan would silently not re-execute.
//
// Declaring the scanned tree as an input of `test` was the first attempt at that. It made every spotless task a
// producer of this task's declared inputs, so `check` - and any invocation pairing spotlessApply with this module's
// tests, which is the documented pre-commit sequence - failed validation before running a single test: "uses this
// output of task :...:spotlessJava without declaring an explicit or implicit dependency". A dedicated
// never-up-to-date task keeps the scan honest without claiming server/ as an input at all. It walks every
// production source under server/ and takes a few seconds, so it carries its own generous @Timeout rather than
// the 30s unit-test default it would otherwise inherit from test-support.
// failOnNoDiscoveredTests is deliberately left at its default of true, unlike the convention's `test` and
// `testIntegration`: a scan task that silently discovers nothing is the exact failure this task exists to prevent.
val toolContextWorkspaceScan = tasks.register<Test>("toolContextWorkspaceScan") {
    useJUnitPlatform()

    description = "Runs the cross-module tool-context workspace-id source scan. Never cached, never up to date."
    group = "verification"

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath

    dependsOn(tasks.testClasses)
    include("**/ToolContextWorkspaceVerificationTest*")

    outputs.upToDateWhen { false }
}

tasks.test {
    exclude("**/ToolContextWorkspaceVerificationTest*")
}

tasks.check {
    dependsOn(toolContextWorkspaceScan)
}
