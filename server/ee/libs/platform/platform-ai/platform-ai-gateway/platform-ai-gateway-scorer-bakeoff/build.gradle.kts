dependencies {
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("tools.jackson.core:jackson-databind")
    testImplementation(project(":server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-api"))
    testImplementation(project(":server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-service"))
}

tasks.test {
    exclude("**/PromptComplexityScorerBakeoff*")
}

tasks.register<Test>("bakeoff") {
    description = "Execute the prompt complexity scorer bake-off and write its report."
    group = "verification"

    useJUnitPlatform()

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath

    dependsOn(tasks.testClasses)

    include("**/PromptComplexityScorerBakeoff*")

    jvmArgs("-Xmx2g")

    testLogging {
        events("standardOut", "failed")
        showExceptions = true
    }
}
