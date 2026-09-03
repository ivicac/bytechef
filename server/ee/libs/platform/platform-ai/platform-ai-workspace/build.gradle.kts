dependencies {
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.springframework:spring-context")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation(project(":server:libs:platform:platform-api"))
    // Resolves a job principal id (project deployment id) to its workspace.
    implementation(project(":server:libs:automation:automation-configuration:automation-configuration-api"))

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.mockito:mockito-junit-jupiter")
    testImplementation(project(":server:libs:test:test-support"))
}
