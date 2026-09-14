import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.dsl.ApplicationExtension
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import org.json.JSONObject

fun runGit(vararg args: String): String {
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    val gitExecutables = if (isWindows) {
        listOf(
            "git",
            "git.exe",
            "C:\\Program Files\\Git\\cmd\\git.exe",
            "C:\\Program Files\\Git\\bin\\git.exe",
            "C:\\Program Files (x86)\\Git\\cmd\\git.exe",
            "${System.getenv("LOCALAPPDATA")}\\Programs\\Git\\cmd\\git.exe"
        )
    } else {
        listOf("git", "/usr/bin/git", "/usr/local/bin/git")
    }

    for (gitPath in gitExecutables) {
        try {
            val process = ProcessBuilder(listOf(gitPath) + args.toList())
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.isNotEmpty()) {
                return output
            }
        } catch (_: Exception) {}
    }
    return ""
}

fun getGitVersionName(): String {
    val descRaw = runGit("describe", "--tags", "--always")
    if (descRaw.isNotEmpty()) {
        val desc = descRaw.removePrefix("v").removePrefix("V")
        val regex = Regex("""^(\d+(\.\d+)*)-(\d+)-g[0-9a-fA-F]+.*$""")
        val match = regex.find(desc)
        if (match != null) {
            val base = match.groupValues[1]
            val ahead = match.groupValues[3]
            return "$base.$ahead"
        }
        val clean = desc.substringBefore("-")
        if (clean.matches(Regex("""^\d+(\.\d+)*$"""))) {
            return clean
        }
    }

    val latestTag = runGit("describe", "--tags", "--abbrev=0").removePrefix("v").removePrefix("V")
    if (latestTag.isNotEmpty() && latestTag.matches(Regex("""^\d+(\.\d+)*$"""))) {
        val revCount = runGit("rev-list", "--count", "HEAD")
        return if (revCount.isNotEmpty()) "$latestTag.$revCount" else latestTag
    }

    val revCount = runGit("rev-list", "--count", "HEAD")
    return if (revCount.isNotEmpty()) "1.0.$revCount" else "1.0.0"
}

fun getGitVersionCode(): Int {
    val revCount = runGit("rev-list", "--count", "HEAD")
    return revCount.toIntOrNull() ?: 1
}

fun isVersionGreater(v1: String, v2: String): Boolean {
    val p1 = v1.split("-")[0].split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
    val p2 = v2.split("-")[0].split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
    val length = maxOf(p1.size, p2.size)
    for (i in 0 until length) {
        val n1 = p1.getOrElse(i) { 0 }
        val n2 = p2.getOrElse(i) { 0 }
        if (n1 > n2) return true
        if (n1 < n2) return false
    }
    return false
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.chaquopy)
    alias(libs.plugins.kotlin.compose.compiler)
}

extensions.configure<ApplicationExtension> {
    namespace = "com.poobi.tvbrowser"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.poobi.tvbrowser"
        minSdk = 24
        targetSdk = 37
        versionCode = getGitVersionCode()
        versionName = getGitVersionName()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "abi"
    productFlavors {
        create("armv7") {
            dimension = "abi"
            isDefault = true
            ndk { abiFilters.add("armeabi-v7a") }
        }
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters.add("arm64-v8a") }
        }
        create("x86") {
            dimension = "abi"
            ndk { abiFilters.add("x86") }
        }
        create("x86_64") {
            dimension = "abi"
            ndk { abiFilters.add("x86_64") }
        }
    }

    signingConfigs {
        create("release") {
            val keystoreFile = project.findProperty("POOBI_KEYSTORE_FILE") as? String
            if (keystoreFile != null && File(keystoreFile).exists()) {
                storeFile = File(keystoreFile)
                storePassword = project.findProperty("POOBI_KEYSTORE_PASSWORD") as? String
                keyAlias = project.findProperty("POOBI_KEY_ALIAS") as? String
                keyPassword = project.findProperty("POOBI_KEY_PASSWORD") as? String
            } else {
                initWith(getByName("debug"))
            }
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
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/ASL2.0"
            excludes += "/META-INF/*.kotlin_module"
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
}

chaquopy {
    defaultConfig {
        buildPython("C:/Users/Chris/AppData/Local/Python/pythoncore-3.11-64/python.exe")
        version = "3.11"
        extractPackages("sources", "modules", "resolveurl", "subtitles", "curl_cffi", "_cffi_backend")
        pip {
            options("--find-links", "wheels")
            install("requests")
            install("beautifulsoup4")
            install("six")
            install("simplejson")
            install("trakt")
            install("curl_cffi")
            install("cffi")
            install("pywasm")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.constraintlayout)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")

    implementation("org.nanohttpd:nanohttpd:2.3.1")
    val jlibtorrentVersion = "2.0.12.9"
    implementation("com.frostwire:jlibtorrent:$jlibtorrentVersion")
    implementation("com.frostwire:jlibtorrent-android-arm:$jlibtorrentVersion")
    implementation("com.frostwire:jlibtorrent-android-arm64:$jlibtorrentVersion")
    implementation("com.frostwire:jlibtorrent-android-x86:$jlibtorrentVersion")
    implementation("com.frostwire:jlibtorrent-android-x86_64:$jlibtorrentVersion")

    implementation(libs.play.services.auth)
    implementation(libs.google.api.client.android)
    implementation(libs.google.http.client.android)
    implementation(libs.google.api.services.drive) {
        exclude(group = "org.apache.httpcomponents")
    }
}

tasks.register("publishGithubRelease") {
    group = "publishing"
    description = "Builds all signed release APKs, creates a GitHub release with the latest Git tag, and uploads the APKs."
    dependsOn("assembleRelease")

    doLast {
        val repoOwner = "Tophness"
        val repoName = "Poobi"

        val token = project.findProperty("GITHUB_TOKEN") as? String
            ?: System.getenv("GITHUB_TOKEN")
            ?: throw GradleException("GitHub token not found! Please add GITHUB_TOKEN=ghp_... to C:\\Users\\Chris\\.gradle\\gradle.properties")

        try {
            project.providers.exec {
                commandLine("git", "fetch", "--tags")
            }.result.get()
        } catch (_: Exception) {}

        var latestRemoteTag = ""
        try {
            val getReleasesUrl = URL("https://api.github.com/repos/$repoOwner/$repoName/releases/latest")
            val getConn = (getReleasesUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Poobi-Gradle-Publisher")
            }
            if (getConn.responseCode == 200) {
                val json = JSONObject(getConn.inputStream.bufferedReader().use { it.readText() })
                latestRemoteTag = json.optString("tag_name", "").removePrefix("v").removePrefix("V")
            }
        } catch (e: Exception) {
            println("Note: Could not check latest remote release: ${e.message}")
        }

        var version = getGitVersionName()
        if (latestRemoteTag.isNotEmpty() && !isVersionGreater(version, latestRemoteTag)) {
            val parts = latestRemoteTag.split(".").map { it.toIntOrNull() ?: 0 }.toMutableList()
            if (parts.size >= 2) {
                parts[parts.lastIndex] = parts.last() + 1
                version = parts.joinToString(".")
            } else {
                version = "$latestRemoteTag.1"
            }
        }

        val tagName = if (version.startsWith("v", ignoreCase = true)) {
            "V" + version.substring(1)
        } else {
            "V$version"
        }
        println("Publishing GitHub Release: $tagName for $repoOwner/$repoName (Latest remote was: V$latestRemoteTag)")

        try {
            project.providers.exec {
                commandLine("git", "tag", "-a", tagName, "-m", "Release $tagName")
            }.result.get()
        } catch (_: Exception) {}

        try {
            project.providers.exec {
                commandLine("git", "push", "origin", tagName)
            }.result.get()
            println("Pushed git tag $tagName to GitHub.")
        } catch (e: Exception) {
            println("Note: Git tag push skipped or already exists: ${e.message}")
        }

        val createReleaseUrl = URL("https://api.github.com/repos/$repoOwner/$repoName/releases")
        val createConn = (createReleaseUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Poobi-Gradle-Publisher")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }

        val requestBody = JSONObject().apply {
            put("tag_name", tagName)
            put("name", "Poobi $tagName")
            put("body", "Automated release for version $tagName.")
            put("draft", false)
            put("prerelease", false)
        }.toString()

        createConn.outputStream.use { it.write(requestBody.toByteArray()) }

        val responseCode = createConn.responseCode
        val responseBody = (if (responseCode in 200..299) createConn.inputStream else createConn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""

        val releaseJson: JSONObject
        if (responseCode == 201) {
            releaseJson = JSONObject(responseBody)
            println("Created new release on GitHub.")
        } else if (responseCode == 422) {
            println("Release already exists. Fetching existing release info...")
            val getUrl = URL("https://api.github.com/repos/$repoOwner/$repoName/releases/tags/$tagName")
            val getConn = (getUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Poobi-Gradle-Publisher")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            releaseJson = JSONObject(getConn.inputStream.bufferedReader().use { it.readText() })
        } else {
            throw GradleException("Failed to create GitHub release (Code: $responseCode): $responseBody")
        }

        val uploadUrlTemplate = releaseJson.getString("upload_url")
        val baseUploadUrl = uploadUrlTemplate.substringBefore("{")

        val apks = listOf(
            File(layout.buildDirectory.asFile.get(), "outputs/apk/arm64/release/app-arm64-release.apk"),
            File(layout.buildDirectory.asFile.get(), "outputs/apk/armv7/release/app-armv7-release.apk"),
            File(layout.buildDirectory.asFile.get(), "outputs/apk/x86/release/app-x86-release.apk"),
            File(layout.buildDirectory.asFile.get(), "outputs/apk/x86_64/release/app-x86_64-release.apk")
        )

        for (apk in apks) {
            if (!apk.exists()) {
                println("Warning: APK not found at ${apk.absolutePath}, skipping.")
                continue
            }

            val assetName = "Poobi-${tagName}-${apk.name}"
            val uploadUrl = URL("$baseUploadUrl?name=$assetName")
            println("Uploading $assetName (${apk.length() / (1024 * 1024)} MB)...")

            val uploadConn = (uploadUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Poobi-Gradle-Publisher")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("Content-Type", "application/vnd.android.package-archive")
                doOutput = true
                setFixedLengthStreamingMode(apk.length())
            }

            apk.inputStream().use { input ->
                uploadConn.outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            if (uploadConn.responseCode in 200..299) {
                println("Successfully uploaded $assetName")
            } else {
                val err = uploadConn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                println("Failed uploading $assetName (Code: ${uploadConn.responseCode}): $err")
            }
        }

        println("Release $tagName completed successfully!")
    }
}