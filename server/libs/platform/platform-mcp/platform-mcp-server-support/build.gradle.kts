// Bundles the MCP App widgets (mcp-apps/<name>) into this module's resources as mcp-apps/<name>.html (served by
// McpAppWorkflowEditor / McpAppViewer). Building the widgets requires Node.js, so it is NOT part of the regular build:
// run the build<Name> task (npm run build) first; processResources picks up each artifact only when it exists.
val mcpAppWidgets =
    mapOf(
        "workflow-editor" to "WorkflowEditor",
        "data-table-viewer" to "DataTableViewer",
        "code-workflow-viewer" to "CodeWorkflowViewer",
        "custom-component-viewer" to "CustomComponentViewer",
        "file-viewer" to "FileViewer",
    )

mcpAppWidgets.forEach { (dirName, taskSuffix) ->
    val widgetDirectory = rootProject.layout.projectDirectory.dir("mcp-apps/$dirName")

    tasks.register<Exec>("build$taskSuffix") {
        description = "Builds the MCP App $dirName widget (requires Node.js and npm install in mcp-apps/$dirName)."
        group = "build"

        workingDir = widgetDirectory.asFile

        commandLine("npm", "run", "build")
    }

    tasks.processResources {
        from(widgetDirectory.file("dist/index.html")) {
            into("mcp-apps")
            rename { "$dirName.html" }
        }
    }
}

dependencies {
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("io.modelcontextprotocol.sdk:mcp:${libs.versions.io.modelcontextprotocol.sdk.get()}")
    implementation("org.springframework.ai:mcp-spring-webmvc")
    implementation("org.springframework:spring-webmvc")
    implementation("org.slf4j:slf4j-api")
    implementation("io.projectreactor:reactor-core")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
}
