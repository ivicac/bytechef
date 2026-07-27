plugins {
    id("com.bytechef.java-library-conventions")
    alias(libs.plugins.org.openapi.generator)
}

val generateClient by tasks.registering(org.openapitools.generator.gradle.plugin.tasks.GenerateTask::class) {
    generatorName.set("java")
    library.set("native")
    inputSpec.set("${rootDir}/server/ee/libs/embedded/embedded-configuration/embedded-configuration-rest/embedded-configuration-rest-impl/openapi.yaml")
    outputDir.set("$projectDir/generated")
    apiPackage.set("com.bytechef.cli.client.embeddedconfigurationinternal.api")
    modelPackage.set("com.bytechef.cli.client.embeddedconfigurationinternal.model")
    invokerPackage.set("com.bytechef.cli.client.embeddedconfigurationinternal")
    modelNameSuffix.set("Model")
    configOptions.set(
        mapOf(
            "useJakartaEe" to "true",
            "useTags" to "true",
            "hideGenerationTimestamp" to "true",
            "openApiNullable" to "false"
        )
    )
    // The full internal spec pulls in schemas (e.g. Workflow) tagged with x-implements referencing
    // server-only interfaces (com.bytechef.platform.configuration.web.rest.model.WorkflowModelAware)
    // that are not on this lightweight client's classpath. Restrict generation to the one API tag
    // this module exists for; unrelated tags/schemas are skipped instead of failing the build.
    globalProperties.set(
        mapOf(
            "apis" to "AutomationProjectCodeWorkflow",
            "models" to "AutomationProjectCodeWorkflowDeployResult",
            "supportingFiles" to ""
        )
    )
}

sourceSets.main.get().java.srcDir("$projectDir/generated/src/main/java")

// Generated client sources are committed; regenerate manually with the `generateClient` task
// when openapi.yaml changes (mirrors cli/clients/embedded-configuration).

listOf("checkstyleMain", "checkstyleTest", "spotbugsMain", "spotbugsTest").forEach { taskName ->
    tasks.matching { it.name == taskName }
        .configureEach { enabled = false }
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("jakarta.annotation:jakarta.annotation-api")

    implementation("org.apache.httpcomponents:httpmime:4.5.14")
}
