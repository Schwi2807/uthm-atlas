plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// The Surveyor ships the same basemap, glyphs and schema the site uses (single source of truth in the repo).
val repoRoot = rootProject.projectDir.parentFile
val sharedAssets = layout.buildDirectory.dir("generated/atlasAssets")
val syncSharedAssets by tasks.registering(Sync::class) {
    from(repoRoot.resolve("site/data")) { include("campus.pmtiles"); into("map") }
    from(repoRoot.resolve("site/map")) { into("map") }
    from(repoRoot.resolve("schema")) { include("*.json"); into("schema") }
    into(sharedAssets)
}

android {
    namespace = "io.github.schwi2807.atlas"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.schwi2807.atlas"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // must match schema/*.json x-schema-version and engine/atlas/__init__.py
        buildConfigField("int", "SCHEMA_VERSION", "1")
        buildConfigField("String", "CAMPUS_ID", "\"parit-raja\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // signed with the debug key so a release APK can be side-loaded for field use; no store upload
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets["main"].assets.srcDir(sharedAssets.get().asFile) // AGP 9 wants a plain directory here
    testOptions {
        unitTests.all {
            it.systemProperty("atlas.schemaDir", repoRoot.resolve("schema").absolutePath)
        }
    }
    androidResources {
        noCompress += listOf("pmtiles", "pbf")
    }
    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", projectDir.resolve("schemas").path)
}

tasks.named("preBuild") { dependsOn(syncSharedAssets) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.maplibre)
    implementation(libs.exifinterface)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.json.schema.validator)
}
