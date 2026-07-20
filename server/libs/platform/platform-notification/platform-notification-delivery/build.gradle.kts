dependencies {
    implementation("org.slf4j:slf4j-api")
    implementation("org.springframework:spring-beans")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-context-support")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation(project(":server:libs:core:commons:commons-util"))

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.mockito:mockito-core")
    testImplementation(project(":server:libs:test:test-support"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}
