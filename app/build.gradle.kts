import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.nio.charset.StandardCharsets
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.materialthemebuilder)
    alias(libs.plugins.kotlinAndroid)
}

fun getGitHashCommit(): String {
    return try {
        val processBuilder = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        val process = processBuilder.start()
        process.inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        "unknown"
    }
}

val gitHash: String = getGitHashCommit().uppercase(Locale.getDefault())

android {
    namespace = "com.wmods.wppenhacer"
    compileSdk = 36
    ndkVersion = "27.0.11902837" // Cleaned up version name

    flavorDimensions += "version"
    productFlavors {
        create("whatsapp") {
            dimension = "version"
            applicationIdSuffix = ""
        }
        create("business") {
            dimension = "version"
            applicationIdSuffix = ".w4b"
            resValue("string", "app_name", "Wa Enhancer Business")
        }
    }

    defaultConfig {
        applicationId = "com.wmods.wppenhacer"
        minSdk = 28
        targetSdk = 34
        versionCode = 152
        versionName = "1.5.2-DEV ($gitHash)"
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        signingConfigs.create("config") {
            val androidStoreFile = properties["androidStoreFile"] as? String
            if (!androidStoreFile.isNullOrEmpty()) {
                storeFile = rootProject.file(androidStoreFile)
                storePassword = properties["androidStorePassword"] as String
                keyAlias = properties["androidKeyAlias"] as String
                keyPassword = properties["androidKeyPassword"] as String
            }
        }

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86_64", "x86"))
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/**", "okhttp3/**", "kotlin/**", "org/**",
                "**.properties", "**.bin"
            )
            // Critical CSS parser files
            pickFirsts.addAll(listOf(
                "META-INF/services/cz.vutbr.web.css.RuleFactory",
                "META-INF/services/cz.vutbr.web.css.SupportedCSS",
                "META-INF/services/cz.vutbr.web.css.TermFactory"
            ))
        }
    }

    buildTypes {
        all {
            signingConfig =
                if (signingConfigs["config"].storeFile != null) signingConfigs["config"] else signingConfigs["debug"]
            if (project.hasProperty("minify") && project.properties["minify"].toString()
                    .toBoolean()
            ) {
                isMinifyEnabled = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android.txt"),
                    "proguard-rules.pro"
                )
            }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
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
        viewBinding = true
        buildConfig = true
        aidl = true
    }

    lint {
        disable += "SelectedPhotoAccess"
    }

    materialThemeBuilder {
        themes {
            create("MaterialGreen") {
                lightThemeFormat = "ThemeOverlay.Light.%s"
                darkThemeFormat = "ThemeOverlay.Dark.%s"
                primaryColor = "#4FAF50"
            }
        }
        generatePalette = true
    }
}


// Task that patches generated AIDL .java files (escapes backslashes in header comment lines).
val fixAidlGeneratedJava = tasks.register("fixAidlGeneratedJava") {
    group = "build"
    description = "Patch AIDL-generated .java files on Windows to escape backslashes in header comments."

    doLast {
        val genDir = layout.buildDirectory.dir("generated/aidl_source_output_dir").get().asFile
        if (!genDir.exists()) {
            println("fixAidlGeneratedJava: no generated AIDL dir at ${genDir.absolutePath}")
            return@doLast
        }

        var patchedCount = 0
        genDir.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { f ->
            val text = f.readText(StandardCharsets.UTF_8)
            var newText = text

            // 1) Escape backslashes on lines that contain "Using:" (aidl header) or "aidl.exe"
            newText = newText.replace(Regex("(?m)^(\\s*\\*.*Using:.*)$")) { m ->
                m.value.replace("\\", "\\\\")
            }
            newText = newText.replace(Regex("(?m)^(\\s*\\*.*aidl\\.exe.*)$")) { m ->
                m.value.replace("\\", "\\\\")
            }

            // 2) Fallback: if file still contains suspicious "\u" sequences inside comments
            if (newText.contains("\\u")) {
                newText = newText.replace(Regex("(?m)^(\\s*\\*.*)$")) { m ->
                    m.value.replace("\\", "\\\\")
                }
            }

            if (newText != text) {
                f.writeText(newText, StandardCharsets.UTF_8)
                println("fixAidlGeneratedJava: patched ${f.absolutePath}")
                patchedCount++
            }
        }
        println("fixAidlGeneratedJava: patched $patchedCount file(s).")
    }
}

// Ensure fix runs before Java compilation reads sources:
tasks.withType(JavaCompile::class.java).configureEach {
    dependsOn(fixAidlGeneratedJava)
    options.encoding = "UTF-8"
}

dependencies {
    implementation(libs.colorpicker)
    implementation(libs.dexkit)
    compileOnly(libs.libxposed.legacy)

    implementation(libs.androidx.activity)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.rikkax.appcompat)
    implementation(libs.rikkax.core)
    implementation(libs.material)
    implementation(libs.rikkax.material)
    implementation(libs.rikkax.material.preference)
    implementation(libs.rikkax.widget.borderview)
    implementation(libs.jstyleparser)
    implementation(libs.okhttp)
    implementation(libs.filepicker)
    implementation(libs.betterypermissionhelper)
    implementation(libs.bcpkix.jdk18on)
    implementation(libs.arscblamer)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
}

configurations.all {
    exclude("org.jetbrains", "annotations")
    exclude("androidx.appcompat", "appcompat")
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk7")
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk8")
}

interface InjectedExecOps {
    @get:Inject val execOps: ExecOperations
}


afterEvaluate {
    listOf("installWhatsappDebug", "installBusinessDebug").forEach { taskName ->
        tasks.findByName(taskName)?.doLast {
            runCatching {
                val injected  = project.objects.newInstance<InjectedExecOps>()
                runBlocking {
                    injected.execOps.exec {
                        commandLine(
                            "adb",
                            "shell",
                            "am",
                            "force-stop",
                            project.properties["debug_package_name"]?.toString()
                        )
                    }
                    delay(500)
                    injected.execOps.exec {
                        commandLine(
                            "adb",
                            "shell",
                            "monkey",
                            "-p",
                            project.properties["debug_package_name"].toString(),
                            "1"
                        )
                    }
                }
            }
        }
    }
}