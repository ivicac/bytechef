version="1.0"

dependencies {
    implementation(project(":server:libs:platform:platform-component:platform-component-api"))

    runtimeOnly("com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre11")

    testImplementation(project(":server:libs:platform:platform-component:platform-component-service"))
}
