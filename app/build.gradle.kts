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

// ---------------------------------------------------------------------------
// Native dependencies from thingino-dfu
//
// This repo has no C toolchain. Everything native arrives prebuilt in one
// tarball: libtdfu_jni.so per ABI (the JNI bridge, libtdfu, and a statically
// linked libusb) plus the tpl.bin/uboot.bin bootstrap blobs. Produced there by
// scripts/build-android-deps.sh.
//
// Set -PtdfuDepsFile to build against a local tarball, which is how you test an
// unreleased libtdfu without cutting a tag. Otherwise it downloads the pinned
// release.
// ---------------------------------------------------------------------------
val tdfuDepsVersion = providers.gradleProperty("tdfuDepsVersion").getOrElse("1.5.35")
// Releases are published from upstream, not the gtxaspec dev fork.
val tdfuDepsRepo = providers.gradleProperty("tdfuDepsRepo").getOrElse("wltechblog/thingino-dfu")
val tdfuDepsFile = providers.gradleProperty("tdfuDepsFile").orNull?.takeIf { it.isNotBlank() }

val depsRoot = layout.buildDirectory.dir("tdfu-deps")

val fetchTdfuDeps = tasks.register("fetchTdfuDeps") {
    description = "Unpacks the prebuilt libtdfu_jni.so and bootstrap blobs."
    val out = depsRoot.get().asFile
    val localTarball = tdfuDepsFile?.let { rootProject.file(it) }
    val cached = File(out, "thingino-dfu-android-deps-$tdfuDepsVersion.tar.gz")

    // A local tarball is a real input, so a rebuilt libtdfu re-triggers this.
    if (localTarball != null) inputs.file(localTarball).withPathSensitivity(PathSensitivity.NONE)
    inputs.property("version", tdfuDepsVersion)
    outputs.dir(out)

    doLast {
        val tarball = when {
            localTarball != null && localTarball.isFile -> localTarball
            localTarball != null -> throw GradleException(
                "tdfuDepsFile points at $localTarball, which does not exist. Run " +
                    "scripts/build-android-deps.sh in thingino-dfu, or unset the property " +
                    "to download the pinned release instead."
            )
            else -> {
                val url = "https://github.com/$tdfuDepsRepo/releases/download/" +
                    "v$tdfuDepsVersion/thingino-dfu-android-deps-$tdfuDepsVersion.tar.gz"
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
            throw GradleException("tdfu deps look wrong: ${abis.size} ABIs, $blobs blobs")
        }
        logger.lifecycle("tdfu deps $tdfuDepsVersion: ${abis.size} ABIs, $blobs blobs")
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
        versionCode = 1
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

tasks.named("preBuild").configure { dependsOn(fetchTdfuDeps) }

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
