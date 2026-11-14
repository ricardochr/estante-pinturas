plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.ricardochr.estantepinturas"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ricardochr.estantepinturas"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    // Llave fija dentro del repo: así cada APK nuevo se instala encima del anterior
    // sin borrar el inventario. Es una app personal; no se publica en Play Store.
    // Un APK por procesador: el de 64 bits (todos los teléfonos actuales) no carga las librerías de 32 bits
    splits {
        abi {
            isEnable = true
            reset()
            // x86_64 solo sirve para el emulador de las pruebas en CI; no se publica
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("estante") {
            storeFile = file("estante.jks")
            storePassword = "estante-pinturas"
            keyAlias = "estante"
            keyPassword = "estante-pinturas"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("estante")
        }
        getByName("release") {
            // R8 optimiza Compose: listas y búsquedas mucho más fluidas, APK más chico
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("estante")
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
    testOptions {
        // Las pruebas de cálculos no usan Android; si algo lo toca por accidente, devuelve valores neutros
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.testLogging { showStandardStreams = true; events("passed", "failed") } }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Recorte de la figura en "Luz y sombra" (el modelo lo descargan los servicios de Google)
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Modelo de profundidad para "Luz y sombra" con fotos (el modelo se descarga aparte)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    testImplementation("junit:junit:4.13.2")
    // Pruebas en emulador: abren las pantallas como lo haría una persona
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // org.json real para probar el respaldo fuera del teléfono (en Android viene incluido)
    testImplementation("org.json:json:20240303")
}
