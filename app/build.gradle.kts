@file:Suppress("UnstableApiUsage")

import java.security.MessageDigest
import java.util.Locale

val pinnedVentoyVersion = "1.1.16"
val ventoyPayloadArchive = providers.gradleProperty("ventoyPayloadArchive")
    .orElse(providers.environmentVariable("VENTOY_PAYLOAD_ARCHIVE"))
    .orElse(
        rootProject.layout.projectDirectory
            .file(".vendroid-cache/ventoy-$pinnedVentoyVersion-linux.tar.gz")
            .asFile
            .absolutePath
    )
val generatedVentoyPayloadAssets = layout.buildDirectory.dir("generated/ventoyPayload/assets")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.junit5)
    alias(libs.plugins.robolectric.junit5)
}

android {
    val sdkMin = 23
    val sdkTarget = 36

    namespace = "io.github.xntso.vendroid"
    compileSdk = sdkTarget

    defaultConfig {
        applicationId = "io.github.xntso.vendroid"
        minSdk = sdkMin
        targetSdk = sdkTarget
        versionCode = 26
        versionName = "2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isPseudoLocalesEnabled = true
        }
        create("optimized") {
            // Optimized like release (minify + shrinkResources + proguard) but debug-signed
            // and non-debuggable, so it installs in CI and runs fast on the slow e2e VM.
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }
    flavorDimensions += "store"
    productFlavors {
        create("foss") {
            isDefault = true
            dimension = "store"
        }
    }
    packaging {
        resources {
            excludes += "META-INF/AL2.0"
            excludes += "META-INF/LGPL2.1"
            excludes += "META-INF/licenses/ASM"
            excludes += "META-INF/libaums_release.kotlin_module"
            excludes += "win32-x86/attach_hotspot_windows.dll"
            excludes += "win32-x86-64/attach_hotspot_windows.dll"
        }
    }
    compileOptions {
        encoding = "UTF-8"
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("main") {
            assets.srcDir(generatedVentoyPayloadAssets.get().asFile)
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
        unitTests.all {
            it.useJUnitPlatform()
            it.maxHeapSize = "4g"
            it.systemProperty("robolectric.dependency.proxy.host", project.findProperty("systemProp.https.proxyHost") ?: System.getenv("ROBOLECTRIC_PROXY_HOST"))
            it.systemProperty("robolectric.dependency.proxy.port", project.findProperty("systemProp.https.proxyPort") ?: System.getenv("ROBOLECTRIC_PROXY_PORT"))
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.activity.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.constraintlayout.compose)
    implementation(libs.core.ktx)
    implementation(libs.documentfile)
    implementation(libs.kotlinx.coroutines.debug)
    implementation(libs.libaums.core)
    // TODO: re-enable once released
    // implementation(libs.libaums.libusbcommunication)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.service)
    implementation(libs.localbroadcastmanager)
    implementation(libs.lottie.compose)
    implementation(libs.material)
    implementation(libs.material.icons.extended)
    implementation(libs.material3)
    implementation(libs.material3.adaptive)
    implementation(libs.xz.java)
    implementation(platform(libs.compose.bom))

    debugImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.compose.ui.tooling)

    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)
}

fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
}

val prepareVentoyPayload = tasks.register("prepareVentoyPayload") {
    group = "vendroid"
    description = "Imports and verifies the pinned Ventoy release payload into generated app assets."
    notCompatibleWithConfigurationCache("Uses Gradle archive and copy APIs to import a local release archive.")

    val archivePath = ventoyPayloadArchive.get()
    val outputAssetsDir = generatedVentoyPayloadAssets.get().asFile
    inputs.property("pinnedVentoyVersion", pinnedVentoyVersion)
    inputs.property("ventoyPayloadArchive", archivePath)
    outputs.dir(outputAssetsDir)

    doLast {
        val archive = file(archivePath)
        check(archive.isFile) {
            "Missing Ventoy payload archive: ${archive.absolutePath}. " +
                "Download the official Linux release package or set -PventoyPayloadArchive=/path/to/ventoy-$pinnedVentoyVersion-linux.tar.gz"
        }

        val requiredPaths = listOf(
            "boot/boot.img",
            "boot/core.img.xz",
            "ventoy/ventoy.disk.img.xz",
            "ventoy/version",
        )

        val payloadRoot = outputAssetsDir.resolve("ventoy_payload")
        delete(payloadRoot)
        payloadRoot.mkdirs()

        val archiveTree = tarTree(resources.gzip(archive))
        requiredPaths.forEach { payloadPath ->
            val match = archiveTree.files.singleOrNull { candidate ->
                val normalized = candidate.path.replace(File.separatorChar, '/')
                normalized.endsWith("/$payloadPath") || normalized == payloadPath
            }
            check(match != null) { "Ventoy archive ${archive.absolutePath} is missing $payloadPath" }

            copy {
                from(match)
                into(payloadRoot.resolve(payloadPath).parentFile)
            }
        }

        val version = payloadRoot.resolve("ventoy/version").readText().trim()
        check(version == pinnedVentoyVersion) {
            "Ventoy payload version mismatch: expected $pinnedVentoyVersion, found $version"
        }

        val manifest = buildString {
            appendLine("version=$version")
            requiredPaths.forEach { payloadPath ->
                val file = payloadRoot.resolve(payloadPath)
                appendLine("file=$payloadPath|${file.length()}|${file.sha256()}")
            }
        }
        payloadRoot.resolve("payload.manifest").writeText(manifest)
    }
}

tasks.named("preBuild") {
    dependsOn(prepareVentoyPayload)
}
