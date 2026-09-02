dependencies {
    api(project(":server:libs:platform:platform-component:platform-component-runner:platform-component-runner-api"))

    implementation("org.slf4j:slf4j-api")
    implementation("org.springframework:spring-context")
    implementation(project(":server:libs:config:app-config"))
    implementation(project(":server:libs:platform:platform-component:platform-component-polyglot"))

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core")
}
