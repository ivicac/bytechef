dependencies {
    api(project(":server:libs:automation:automation-task:automation-task-api"))

    implementation("org.springframework:spring-context")
    implementation("org.springframework.graphql:spring-graphql")
    implementation("org.springframework.security:spring-security-core")
    implementation(project(":server:libs:atlas:atlas-coordinator:atlas-coordinator-api"))
    implementation(project(":server:libs:core:graphql:graphql-api"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-graphql-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-config")
    testImplementation("org.springframework:spring-webflux")
    testImplementation(project(":server:libs:core:graphql:graphql-impl"))
    testImplementation(project(":server:libs:test:test-int-support"))
}
