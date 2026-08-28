import org.gradle.api.tasks.testing.Test
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.room)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.m57.hermescontrol"

    lint {
        disable += "MissingTranslation"
    }
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "com.m57.hermescontrol"
        minSdk = 26
        targetSdk = 37
        // Version overrides passed from CI via -PversionName / -PversionCode
        // Falls back to defaults for local development.
        versionCode = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as? String) ?: "1.0-dev"

        // Embed git commit SHA for the About card in Settings
        val gitSha =
            providers.exec {
                commandLine("git", "rev-parse", "--short", "HEAD")
            }.standardOutput.asText.get().trim()
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }

    signingConfigs {
        create("release") {
            val isReleaseBuild =
                gradle.startParameter.taskNames.any { name ->
                    name.contains("Release", ignoreCase = true) || name == "build"
                }

            if (isReleaseBuild) {
                val storePath = System.getenv("KEYSTORE_PATH")
                val storePass = System.getenv("KEYSTORE_PASSWORD")
                val alias = System.getenv("KEY_ALIAS")
                val keyPass = System.getenv("KEY_PASSWORD")

                if (storePath != null && storePass != null && alias != null && keyPass != null) {
                    storeFile = file(storePath)
                    storePassword = storePass
                    keyAlias = alias
                    keyPassword = keyPass
                } else {
                    // Env vars missing — signing deferred to release workflow.
                    // Don't hard-require(): that would break compileReleaseKotlin
                    // in CI where keystore secrets aren't set.
                    logger.warn("Release signing config missing env vars — signing deferred to release workflow")
                    storeFile = file("dummy.keystore")
                    storePassword = "dummy"
                    keyAlias = "dummy"
                    keyPassword = "dummy"
                }
            } else {
                // Dummy values for evaluation configuration during non-release builds
                storeFile = file("dummy.keystore")
                storePassword = "dummy"
                keyAlias = "dummy"
                keyPassword = "dummy"
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "true")
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs["release"]
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "true")
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        aidl = false
        buildConfig = true
        shaders = false
    }

    dependenciesInfo {
        includeInApk = false
    }

    packaging {
        resources {
            pickFirsts += "META-INF/AL2.0"
            pickFirsts += "META-INF/LGPL2.1"
            pickFirsts += "META-INF/LICENSE"
            pickFirsts += "META-INF/LICENSE.md"
            pickFirsts += "META-INF/LICENSE-notice.md"
            pickFirsts += "META-INF/NOTICE"
            pickFirsts += "META-INF/NOTICE.md"
        }
    }
}

// Overwrite version-control-info with empty content for reproducible builds
// F-Droid CI uses detached HEAD (branches: []) while GitHub CI uses main
// (branches: ["main"]) — this field in the generated textproto causes a
// byte-level APK difference. Emptying the file makes both CI environments
// produce identical artifacts.
tasks.matching { it.name.endsWith("VersionControlInfo") }.configureEach {
    doLast {
        outputs.files.filter { it.exists() }.forEach { it.writeText("") }
    }
}

kotlin {
    jvmToolchain(21)
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)

    // Arch Components
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    // Tooling
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.leakcanary.android)
    // Instrumented tests
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    // Native Compose LaTeX rendering
    implementation(libs.latex.base)
    implementation(libs.latex.parser)
    implementation(libs.latex.renderer)

    // Image loading
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    // Encrypted storage
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore)
    implementation("androidx.startup:startup-runtime:1.1.1")

    // Local database (Room) — message persistence
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.sqlcipher)
    ksp(libs.androidx.room.compiler)

    // Local tests: jUnit, coroutines, Android runner
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)

    // Instrumented tests: jUnit rules and runners
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.mockk.android)

    // Navigation
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}

tasks.register<Exec>("ktlintCheck") {
    group = "verification"
    description = "Runs ktlint check."
    workingDir = rootProject.projectDir
    commandLine("sh", "-c", "ktlint \"app/src/**/*.kt\"")
}
tasks.register<Exec>("ktlintFormat") {
    group = "formatting"
    description = "Runs ktlint format."
    workingDir = rootProject.projectDir
    commandLine("sh", "-c", "ktlint -F \"app/src/**/*.kt\"")
}

// ── Hardcoded Color Guard (issue #622) ────────────────────────────────────
//
// Fails the build if a hardcoded Color literal (Color(0x...) or named
// Color.White/Black/Red/Green/Gray/etc.) is introduced outside the theme
// module, *Preview.kt files, and _test sources. Pairing/Auth screens are
// exempt via the per-path overlay list below.
//
// Acceptable replacements (see docs/color-hardcoded-audit.md):
//   - MaterialTheme.colorScheme.<token>
//   - LocalHermesStatusColors.current.<semantic>
//   - Color.Transparent / Color.Unspecified (intentional)

// Resolve paths at configuration time (config-cache compatible).
val colorGuardSrcDir = layout.projectDirectory
    .dir("src/main/java/com/m57/hermescontrol")
val colorGuardExemptions = listOf(
    "/theme/",
    "PairingScreen.kt",
    "AuthLoginScreen.kt",
    "Preview.kt",
)
val colorGuardHexPattern = Regex("""Color\(\s*0x[0-9A-Fa-f]{6,8}\s*\)""")
val colorGuardNamedPattern = Regex("""Color\.(White|Black|Red|Green|Gray|LightGray|DarkGray|Yellow|Blue|Cyan|Magenta)\b""")

tasks.register("checkColorLiterals") {
    group = "verification"
    description = "Fails if hardcoded Color(...) literals appear outside theme/, *Preview.kt, and _test."

    val srcDir = colorGuardSrcDir
    val exemptions = colorGuardExemptions
    val hexPattern = colorGuardHexPattern
    val namedPattern = colorGuardNamedPattern

    doLast {
        val offenders = mutableListOf<Pair<String, Int>>()
        srcDir.asFile.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            if (exemptions.any { file.absolutePath.contains(it) }) return@forEach
            file.useLines { lines ->
                lines.forEachIndexed { idx, raw ->
                    val line = raw.trim()
                    if (line.startsWith("import ")) return@forEachIndexed
                    if (line.startsWith("//") || line.startsWith("*")) return@forEachIndexed
                    if (hexPattern.containsMatchIn(line) || namedPattern.containsMatchIn(line)) {
                        offenders.add(file.absolutePath to (idx + 1))
                    }
                }
            }
        }

        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (path, line) ->
                "  - $path:$line"
            }
            throw GradleException(
                "Hardcoded Color literals found outside theme/ + *Preview.kt + _test:\n$report\n\n" +
                    "Replace with MaterialTheme.colorScheme.<token>, " +
                    "LocalHermesStatusColors.current.<semantic>, or " +
                    "Color.Transparent / Color.Unspecified (intentional).\n" +
                    "See docs/color-hardcoded-audit.md.",
            )
        }
        logger.lifecycle("checkColorLiterals: no hardcoded Color literals found. ✅")
    }
}

tasks.named("check") {
    dependsOn("checkColorLiterals")
}

// Fixture parity gate: fails the build when the shared room-view fixtures
// drift from the pinned asgard-rooms oracle. The pin lives in
// app/src/test/resources/fixtures/fixture-set.sha256; regenerate it only from
// a reviewed asgard-rooms head (never by hand).
tasks.register("checkFixtureParity") {
    group = "verification"
    description = "Fails when test fixtures drift from the pinned asgard-rooms oracle."

    val fixturesDir = layout.projectDirectory.dir("src/test/resources/fixtures")

    doLast {
        val pinFile = File(fixturesDir.asFile, "fixture-set.sha256")
        if (!pinFile.isFile) {
            throw GradleException("fixture-set.sha256 missing at ${pinFile.absolutePath}")
        }

        data class Entry(val hash: String, val name: String)

        val pinned = mutableListOf<Entry>()
        pinFile.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) throw GradleException("unparseable pin line: $line")
            val (hash, rawName) = parts
            val name = rawName.trimStart('*')
            val hex = hash.lowercase()
            if (hex.length != 64 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) {
                throw GradleException("pin hash is not sha256 hex: $line")
            }
            pinned += Entry(hex, name)
        }
        if (pinned.isEmpty()) throw GradleException("fixture-set.sha256 pins no files")

        val missing = mutableListOf<String>()
        pinned.forEach { entry ->
            val file = File(fixturesDir.asFile, entry.name)
            if (!file.isFile) {
                missing += entry.name
                return@forEach
            }
            val actual = file.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")!!
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
                digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            }
            if (actual != entry.hash) {
                throw GradleException(
                    "fixture '${entry.name}' drifted from the pinned oracle.\n" +
                        "  pin:    ${entry.hash}\n" +
                        "  actual: $actual\n" +
                        "Sync all pinned fixtures from the reviewed asgard-rooms head and " +
                        "regenerate the pin; never edit fixtures to force a pass.",
                )
            }
        }
        if (missing.isNotEmpty()) throw GradleException("pinned fixtures missing: $missing")

        // A pin that silently stops covering the fixture set is a vacuous gate:
        // every fixture file must be pinned on purpose.
        val pinnedNames = pinned.map { it.name }.toSet()
        val unpinned =
            fixturesDir.asFile.walkTopDown()
                .filter { it.isFile && it != pinFile }
                .filterNot { it.relativeTo(fixturesDir.asFile).path in pinnedNames }
                .map { it.relativeTo(fixturesDir.asFile).path }
                .toList()
        if (unpinned.isNotEmpty()) throw GradleException("fixtures present but not pinned: $unpinned")

        logger.lifecycle("checkFixtureParity: ${pinned.size} files match the pinned oracle ✅")
    }
}

tasks.named("check") {
    dependsOn("checkFixtureParity")
}

tasks.withType<Test> {
    useJUnitPlatform()
}