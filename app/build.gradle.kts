plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.ioniq"
    compileSdk = 34

    // Load support config from local.properties (gitignored — not in source control)
    val localPropsFile = rootProject.file("local.properties")
    val supportEmail = if (localPropsFile.exists()) {
        localPropsFile.readText()
            .lines()
            .map { it.trim() }
            .find { it.startsWith("supportEmail=") }
            ?.substringAfter("=")?.trim()
            ?: ""
    } else ""

    defaultConfig {
        applicationId = "com.ioniq.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.0.0-beta.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Support email destination — read from local.properties at build time.
        // Falls back to empty string if not configured.
        buildConfigField("String", "SUPPORT_EMAIL", "\"${supportEmail.ifBlank { "" }}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose BOM (manages all Compose versions)
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // BLE (Nordic Semiconductor - industry standard for Android BLE)
    implementation("no.nordicsemi.android:ble:2.8.0")
    implementation("no.nordicsemi.android:ble-ktx:2.8.0")
    implementation("no.nordicsemi.android.support.v18:scanner:1.6.0")

    // OBD II / CAN bus (parsing implemented in ObdPids.kt, no external lib needed)

    // Permissions (Compose-native, coroutine-friendly)
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")

    // Room DB (local telemetry storage)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Networking (Home Assistant WebSocket)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("org.java-websocket:Java-WebSocket:1.5.6")

    // Security (encrypted credential storage)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // WorkManager (background tasks)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Location services (for trip logging)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Android Auto / MediaBrowserServiceCompat
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.core:core:1.13.1")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.3.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
