plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version from the latest tag, same derivation thingino-dfu uses. stderr is
// deliberately not captured, so git's "fatal: no names found" on a tagless or
// shallow checkout cannot become the version.
val computedVersionName: String = run {
    try {
        val p = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
            .directory(rootProject.projectDir)
            .start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() == 0 && out.matches(Regex("^v?[0-9].*"))) out.removePrefix("v") else "0.1.0"
    } catch (e: Exception) {
        "0.1.0"
    }
}

// versionCode from the same tag, since updaters compare that number and not
// versionName: two decimal digits per field plus a pre-release slot, so
// v0.4.0 -> 40099. A final release takes 99 in that slot and pre-releases
// fill 0..98 in alpha < beta < rc order, so v1.0.0-rc.2 -> 1000062 sorts
// below v1.0.0 -> 1000099 and above v1.0.0-alpha.1 -> 1000001. Releases up
// to v0.4.0 all shipped as 1 and looked identical to updaters.
val computedVersionCode: Int = run {
    val m = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-([A-Za-z]*)\.?(\d*))?""").find(computedVersionName)
        ?: throw GradleException("versionName '$computedVersionName' is not MAJOR.MINOR.PATCH")
    val (major, minor, patch) = m.groupValues.subList(1, 4).map { it.toInt() }
    if (minor > 99 || patch > 99) {
        throw GradleException("versionName '$computedVersionName': MINOR and PATCH must stay below 100")
    }
    val n = m.groups[5]?.value?.toIntOrNull() ?: 0
    val pre = when (m.groups[4]?.value?.lowercase()) {
        null -> 99                       // no suffix: final release
        "rc" -> 60 + n.coerceAtMost(38)
        "beta" -> 30 + n.coerceAtMost(29)
        else -> n.coerceAtMost(29)       // alpha, or an unknown word
    }
    ((major * 100 + minor) * 100 + patch) * 100 + pre
}

// ---------------------------------------------------------------------------
// Native dependencies from thingino-dfu-rs
//
// This repo has no native toolchain. Everything native arrives prebuilt in one
// tarball: libtdfu_jni.so per ABI (the JNI bridge and the Rust core, with only
// Android's own system libraries external) plus the stage-1/U-Boot bootstrap
// blobs. Produced there by `cargo xtask package --target android`.
//
// Set -PlibtdfuFile to build against a local tarball, which is how you test an
// unreleased libtdfu without cutting a tag. Otherwise it downloads the pinned
// release.
// ---------------------------------------------------------------------------
val libtdfuVersion = providers.gradleProperty("libtdfuVersion").getOrElse("2.0.0")
val libtdfuRepo = providers.gradleProperty("libtdfuRepo").getOrElse("thingino/thingino-dfu-rs")
val libtdfuFile = providers.gradleProperty("libtdfuFile").orNull?.takeIf { it.isNotBlank() }

val depsRoot = layout.buildDirectory.dir("libtdfu")

val fetchLibtdfu = tasks.register("fetchLibtdfu") {
    description = "Unpacks libtdfu-android: libtdfu_jni.so per ABI plus the bootstrap blobs."
    val out = depsRoot.get().asFile
    val localTarball = libtdfuFile?.let { rootProject.file(it) }
    val cached = File(out, "libtdfu-android-$libtdfuVersion.tar.gz")

    // A local tarball is a real input, so a rebuilt libtdfu re-triggers this.
    if (localTarball != null) inputs.file(localTarball).withPathSensitivity(PathSensitivity.NONE)
    inputs.property("version", libtdfuVersion)
    outputs.dir(out)

    doLast {
        val tarball = when {
            localTarball != null && localTarball.isFile -> localTarball
            localTarball != null -> throw GradleException(
                "libtdfuFile points at $localTarball, which does not exist. Run " +
                    "`cargo xtask package --target android` in thingino-dfu-rs, or unset the property " +
                    "to download the pinned release instead."
            )
            else -> {
                val url = "https://github.com/$libtdfuRepo/releases/download/" +
                    "v$libtdfuVersion/libtdfu-android-$libtdfuVersion.tar.gz"
                if (!cached.isFile) {
                    cached.parentFile.mkdirs()
                    logger.lifecycle("Downloading $url")
                    uri(url).toURL().openStream().use { input ->
                        cached.outputStream().use { input.copyTo(it) }
                    }
                }
                cached
            }
        }

        // Split into the two layouts the Android plugin wants. The tarball keeps
        // them in one tree; jniLibs and assets need separate roots or the .so
        // files would ship as assets and the blobs as native libraries.
        val raw = File(out, "raw")
        sync {
            from(tarTree(resources.gzip(tarball)))
            into(raw)
        }
        sync {
            from(File(raw, "jniLibs"))
            into(File(out, "jniLibs"))
        }
        sync {
            from(File(raw, "firmware"))
            into(File(out, "assets/firmware"))
        }

        val abis = File(out, "jniLibs").listFiles()?.filter { it.isDirectory } ?: emptyList()
        val blobs = File(out, "assets/firmware").walkTopDown().count { it.extension == "bin" }
        if (abis.isEmpty() || blobs == 0) {
            throw GradleException("libtdfu tarball looks wrong: ${abis.size} ABIs, $blobs blobs")
        }
        logger.lifecycle("libtdfu $libtdfuVersion: ${abis.size} ABIs, $blobs blobs")
    }
}

android {
    namespace = "com.thingino.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.thingino.app"
        // 26, matching what the flashing path has always supported. Provisioning
        // needs WifiNetworkSpecifier and so is gated on 29 at runtime rather than
        // dragging the whole app up and dropping Android 8 users of a tool that
        // works fine for them.
        minSdk = 26
        targetSdk = 34
        versionCode = computedVersionCode
        versionName = computedVersionName

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../thingino-release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "thingino-dfu"
            keyAlias = "thingino"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "thingino-dfu"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            keepDebugSymbols += listOf("**/*.so")
        }
    }

    androidResources {
        noCompress += listOf("bin")
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(depsRoot.map { it.dir("jniLibs") })
            assets.srcDirs("src/main/assets", depsRoot.map { it.dir("assets") })
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

tasks.named("preBuild").configure { dependsOn(fetchLibtdfu) }

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Saved Wi-Fi passphrases are encrypted at rest under a Keystore master key.
    implementation("androidx.security:security-crypto:1.0.0")
    // SHA-512 crypt for the root password. Not in the platform, and hand
    // rolling SHA-crypt's final permutation step is a good way to lock
    // yourself out of a camera.
    implementation("commons-codec:commons-codec:1.16.0")
    // WebViewAssetLoader: serves the preview page from an https origin.
    // RTCPeerConnection requires a secure context, which a file:// page is not
    // reliably treated as inside WebView.
    implementation("androidx.webkit:webkit:1.10.0")

    testImplementation("junit:junit:4.13.2")
}
