plugins {
    kotlin("jvm") version "2.3.0"
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    dependsOn(tasks.named("compileJava"))

    classes = fileTree(layout.buildDirectory.dir("classes/java/main"))
}

dependencies {
    implementation("com.embabel.agent:embabel-agent-api:1.0.0")
    implementation("com.embabel.agent:embabel-agent-starter-platform:1.0.0")
    // Registers Embabel LlmService beans from OPENAI_API_KEY so the platform can boot with a
    // default model. Its AgentOpenAiAutoConfiguration hard-fails without a key, so apps exclude
    // it by default and un-exclude it together with AgentPlatformAutoConfiguration in the
    // opt-in 'agentic' profile.
    implementation("com.embabel.agent:embabel-agent-starter-openai:1.0.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.springframework:spring-context")
    implementation(project(":server:libs:core:commons:commons-util"))
    implementation(project(":server:libs:core:evaluator:evaluator-api"))

    implementation(project(":server:libs:modules:components:ai:llm"))
    implementation(project(":server:libs:platform:platform-ai:platform-ai-api"))
}
