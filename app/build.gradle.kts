import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Las claves del CFS de Creality NUNCA se versionan ni van en un release: solo
// las lee el build de depuracion desde local.properties (fuera de git).
val propiedadesLocales = Properties().apply {
    val fichero = rootProject.file("local.properties")
    if (fichero.exists()) FileInputStream(fichero).use { load(it) }
}

fun claveLocal(nombre: String): String = propiedadesLocales.getProperty(nombre, "").trim()

// Firma de release: los secretos salen de keystore.properties (gitignored) o de las variables
// RODET_*. Sin ninguna se firma con el debug.keystore, que vale para probar pero no para
// publicar (firma distinta = no se puede actualizar encima).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

fun datoFirma(clave: String, entorno: String): String? =
    (keystoreProps[clave] as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(entorno)?.takeIf { it.isNotBlank() }

val storeFileRelease = datoFirma("storeFile", "RODET_STORE_FILE")

android {
    namespace = "com.marcmayol.rodet"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.marcmayol.rodet"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = providers.gradleProperty("appVersionCode").get().toInt()
        versionName = providers.gradleProperty("appVersionName").get()
        // ML Kit trae librerias nativas: moviles de 64 y 32 bits, y el emulador (x86_64). Sin x86 de 32 bits.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        buildConfigField("String", "CREALITY_DERIVATION_KEY", "\"\"")
        buildConfigField("String", "CREALITY_PAYLOAD_KEY", "\"\"")
    }

    signingConfigs {
        create("release") {
            if (storeFileRelease != null) {
                storeFile = file(storeFileRelease)
                storePassword = datoFirma("storePassword", "RODET_STORE_PASSWORD")
                keyAlias = datoFirma("keyAlias", "RODET_KEY_ALIAS")
                keyPassword = datoFirma("keyPassword", "RODET_KEY_PASSWORD")
            } else {
                storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "CREALITY_DERIVATION_KEY", "\"${claveLocal("creality.derivationKey")}\"")
            buildConfigField("String", "CREALITY_PAYLOAD_KEY", "\"${claveLocal("creality.payloadKey")}\"")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core:tags"))
    implementation(project(":core:extract"))
    implementation(project(":core:model"))
    implementation(project(":core:inventory"))
    implementation(project(":actualizador"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons)

    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.text)
    debugImplementation(libs.compose.ui.tooling)
}
