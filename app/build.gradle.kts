import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "xtr.keymapper"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "xtr.keymapper"
        minSdk = 25
        targetSdk = 34
        versionCode = 25
        versionName = "2.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        androidResources {
            ignoreAssetsPattern = ""
        }
    }

    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
            storeFile = file(keystoreProperties["storeFile"] as String)
            storePassword = keystoreProperties["storePassword"] as String
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "XtMapper(Debug)")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        viewBinding = true
        aidl = true
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    ndkVersion = "30.0.16248370"

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.material3.window.size.class1)
    implementation(libs.constraintlayout)
    implementation(libs.coordinatorlayout)
    implementation(libs.material)
    implementation(libs.androidx.webkit)
    implementation(libs.libsu.core)
    implementation(libs.service)
    implementation(libs.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)

    implementation(platform(libs.androidx.compose.bom))

    // Optional - Integration with activities
    implementation(libs.androidx.activity.compose)

    // Material Design 3
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended.android)

    // Android Studio Preview support
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    compileOnly(project(path = ":app:hidden-api"))
    testImplementation(libs.junit)
}


abstract class GenerateInputLabels : Exec() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

val inputNdk = androidComponents.sdkComponents.ndkDirectory
val inputToolchain = inputNdk.map { ndk ->
    File(ndk.asFile, "toolchains/llvm/prebuilt").listFiles()!!.first { it.isDirectory }
}
val generatedInput = layout.buildDirectory.dir("generated/source/inputLabels")

val generateInputLabels = tasks.register<GenerateInputLabels>("generateInputLabels") {
    description = "Generate input header labels"
    inputs.file("src/main/cpp/generate-input.h-labels.py")
    inputs.files(inputToolchain.map { toolchain ->
        listOf(
            File(toolchain, "sysroot/usr/include/linux/input.h"),
            File(toolchain, "sysroot/usr/include/linux/input-event-codes.h"),
        )
    })
    outputDirectory.set(generatedInput)

    doFirst {
        val toolchain = inputToolchain.get()
        val python = File(toolchain, "python3/bin/python3").takeIf { it.exists() }
            ?: File(toolchain, "python3/python.exe")
        commandLine(
            python,
            file("src/main/cpp/generate-input.h-labels.py"),
            File(toolchain, "sysroot/usr/include/linux/input.h"),
            File(toolchain, "sysroot/usr/include/linux/input-event-codes.h"),
            "--kotlin",
            outputDirectory.get().asFile,
        )
    }
}

androidComponents.onVariants { variant ->
    variant.sources.kotlin!!.addGeneratedSourceDirectory(
        generateInputLabels,
        GenerateInputLabels::outputDirectory,
    )
}