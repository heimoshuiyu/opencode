buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.openapitools:openapi-generator-gradle-plugin:7.10.0")
    }
}

apply(plugin = "org.openapi.generator")

configure<org.openapitools.generator.gradle.plugin.extensions.OpenApiGeneratorGenerateExtension> {
    generatorName.set("kotlin")
    inputSpec.set("$projectDir/src/main/resources/opencode-filtered.json")
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.path)
    packageName.set("com.opencode.app.api")
    modelNameSuffix.set("DTO")
    configOptions.set(
        mapOf(
            "library" to "jvm-okhttp4",
            "serializationLibrary" to "kotlinx_serialization",
            "dateLibrary" to "string",
            "collectionType" to "array",
            "omitGradleWrapper" to "true",
            "useCoroutines" to "true",
        ),
    )
    globalProperties.set(
        mapOf(
            "apis" to "",
            "models" to "",
            "apiDocs" to "false",
            "modelDocs" to "false",
        ),
    )
}

// Expose generated sources as a consumable dependency
configurations.create("generated")
artifacts {
    add("generated", layout.buildDirectory.dir("generated/openapi/src/main/kotlin")) {
        builtBy(tasks.named("openApiGenerate"))
    }
}
