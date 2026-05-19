dependencies {
    api(project(":server:libs:platform:platform-ai:platform-ai-stt-api"))

    implementation(project(":server:libs:config:app-config"))
    implementation("org.springframework:spring-context")
    implementation("org.springframework.boot:spring-boot")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core")
}
