plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.opencode.app"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.opencode.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java")
        }
    }
}

// OpenAPI code generation: generates Kotlin models and API interfaces from the filtered OpenAPI spec.
// Run with: ./gradlew generateOpenApiModels
// Output: app/build/generated/openapi/src/main/kotlin/com/opencode/app/api/
tasks.register<JavaExec>("generateOpenApiModels") {
    group = "openapi"
    description = "Generate Kotlin models and API interfaces from OpenAPI spec"
    classpath = files(configurations.detachedConfiguration(
        dependencies.create("org.openapitools:openapi-generator-cli:7.10.0")
    ))
    args = listOf(
        "generate",
        "-g", "kotlin",
        "-i", rootProject.file("api-gen/src/main/resources/opencode-filtered.json").absolutePath,
        "-o", layout.buildDirectory.dir("generated/openapi").get().asFile.path,
        "--package-name", "com.opencode.app.api",
        "--model-name-suffix", "DTO",
        "--additional-properties",
        "library=jvm-okhttp4,serializationLibrary=kotlinx_serialization,dateLibrary=string,collectionType=array,omitGradleWrapper=true,useCoroutines=true",
        "--global-property",
        "apis=,models=,apiDocs=false,modelDocs=false",
    )
    outputs.dir(layout.buildDirectory.dir("generated/openapi/src/main/kotlin"))
}

dependencies {
    // Compose
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.runtime)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.sse)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // DataStore
    implementation(libs.datastore.preferences)

    // Security
    implementation(libs.security.crypto)

    // Coil
    implementation(libs.coil.compose)

    // Testing
    testImplementation(libs.junit)
}

kapt {
    correctErrorTypes = true
}
