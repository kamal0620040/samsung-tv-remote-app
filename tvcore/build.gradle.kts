plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kamal.tvcore"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

// The tests are JUnit 5 (Jupiter); without this, Gradle's default JUnit 4
// runner silently discovers zero tests and reports a false green.
tasks.withType<Test> {
    useJUnitPlatform()
}
