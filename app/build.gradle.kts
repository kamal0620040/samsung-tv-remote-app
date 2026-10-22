import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version numbers live in version.properties at the repo root (single source
// of truth; the no-Gradle build path reads the same values into the manifest).
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val appVersionCode = (versionProps.getProperty("versionCode") ?: "13").toInt()
val appVersionName = versionProps.getProperty("versionName") ?: "1.12"

// Optional release signing: create keystore.properties next to this file
// (storeFile, storePassword, keyAlias, keyPassword) and it is picked up.
val keystoreProps = Properties().apply {
    val f = file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasSigning = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.kamal.tvremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kamal.tvremote"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Keep rules are in proguard-rules.pro. Minify stays off until it
            // has been verified on a device; enable it deliberately.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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

dependencies {
    implementation(project(":tvcore"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

// The tests are JUnit 5 (Jupiter); without this, Gradle's default JUnit 4
// runner silently discovers zero tests and reports a false green.
tasks.withType<Test> {
    useJUnitPlatform()
}
