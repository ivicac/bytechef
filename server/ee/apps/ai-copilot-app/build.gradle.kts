group = "com.bytechef.ai.copilot"
description = ""

springBoot {
    mainClass.set("com.bytechef.ai.copilot.CopilotApplication")
}

dependencies {
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
    implementation("org.springframework.boot:spring-boot-starter-aspectj")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.cloud:spring-cloud-starter-loadbalancer")
    implementation(project(":server:libs:config:app-config"))
    // cache-config registers the named cache that backs WebhookResumeRegistry (Caffeine for single-instance dev,
    // Redis for the EE microservice topology). Without this dep the bridge bean would fail at startup with the
    // explicit IllegalStateException in WebhookResumeRegistry.getCache.
    implementation(project(":server:libs:config:cache-config"))
    implementation(project(":server:libs:config:jdbc-config"))
    implementation(project(":server:libs:platform:platform-ai:platform-ai-auto-memory:platform-ai-auto-memory-graphql"))

    implementation(project(":server:ee:libs:ai:ai-copilot:ai-copilot-rest"))
    implementation(project(":server:ee:libs:ai:ai-copilot:ai-copilot-service"))
    implementation(project(":server:ee:libs:automation:automation-ai-hub:automation-ai-hub-api"))
    implementation(project(":server:ee:libs:automation:automation-ai-hub:automation-ai-hub-graphql"))
    implementation(project(":server:ee:libs:automation:automation-ai-hub:automation-ai-hub-rest"))
    implementation(project(":server:ee:libs:automation:automation-ai-hub:automation-ai-hub-service"))
    implementation(project(":server:ee:libs:platform:platform-ai-hub:platform-ai-hub-api"))
    implementation(project(":server:ee:libs:platform:platform-ai-hub:platform-ai-hub-service"))
    implementation(project(":server:ee:libs:config:observability-config"))

    runtimeOnly("com.zaxxer:HikariCP")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":server:libs:test:test-int-support"))
}
