plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Valores públicos por ambiente vêm de gradle.properties / ~/.gradle/gradle.properties / ORG_GRADLE_PROJECT_*.
// Nada aqui é segredo: a chave publicável do Supabase e a chave de tiles são públicas por definição.
fun prop(name: String): String = (findProperty(name) as String?)?.trim().orEmpty()
fun quoted(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "app.donodavez"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.donodavez"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "env"
    productFlavors {
        create("local") {
            dimension = "env"
            applicationIdSuffix = ".local"
            versionNameSuffix = "-local"
            val host = prop("dvLocalHost").ifEmpty { "10.0.2.2" }
            buildConfigField("String", "DV_ENVIRONMENT", quoted("local"))
            buildConfigField("String", "SUPABASE_URL", quoted("http://$host:54321"))
            buildConfigField("String", "PUBLISHABLE_KEY", quoted(prop("dvLocalPublishableKey")))
            buildConfigField("String", "MAP_STYLE_URL", quoted(prop("dvLocalMapStyleUrl").ifEmpty { "https://tiles.openfreemap.org/styles/bright" }))
            buildConfigField("String", "MAP_PUBLIC_KEY", quoted(prop("dvLocalMapPublicKey")))
            buildConfigField("boolean", "ALLOW_MOCK_LOCATION", "true")
            firebase(this, "Local")
            resValue("string", "app_name", "Dono da Vez (local)")
        }
        create("staging") {
            dimension = "env"
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            buildConfigField("String", "DV_ENVIRONMENT", quoted("staging"))
            buildConfigField("String", "SUPABASE_URL", quoted(prop("dvStagingSupabaseUrl")))
            buildConfigField("String", "PUBLISHABLE_KEY", quoted(prop("dvStagingPublishableKey")))
            buildConfigField("String", "MAP_STYLE_URL", quoted(prop("dvStagingMapStyleUrl")))
            buildConfigField("String", "MAP_PUBLIC_KEY", quoted(prop("dvStagingMapPublicKey")))
            buildConfigField("boolean", "ALLOW_MOCK_LOCATION", "true")
            firebase(this, "Staging")
            resValue("string", "app_name", "Dono da Vez (homolog.)")
        }
        create("prod") {
            dimension = "env"
            buildConfigField("String", "DV_ENVIRONMENT", quoted("production"))
            buildConfigField("String", "SUPABASE_URL", quoted(prop("dvProdSupabaseUrl")))
            buildConfigField("String", "PUBLISHABLE_KEY", quoted(prop("dvProdPublishableKey")))
            buildConfigField("String", "MAP_STYLE_URL", quoted(prop("dvProdMapStyleUrl")))
            buildConfigField("String", "MAP_PUBLIC_KEY", quoted(prop("dvProdMapPublicKey")))
            // GPS simulado nunca é aceito em produção (o servidor também recusa).
            buildConfigField("boolean", "ALLOW_MOCK_LOCATION", "false")
            firebase(this, "Prod")
            resValue("string", "app_name", "Dono da Vez")
        }
    }

    // Assinatura de release: keystore fora do repositório (CI decodifica de um secret; ver docs/setup.md).
    val keystore = System.getenv("DV_UPLOAD_KEYSTORE").orEmpty()
    signingConfigs {
        if (keystore.isNotEmpty()) create("upload") {
            storeFile = file(keystore)
            storePassword = System.getenv("DV_UPLOAD_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("DV_UPLOAD_KEY_ALIAS")
            keyPassword = System.getenv("DV_UPLOAD_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystore.isNotEmpty()) signingConfig = signingConfigs.getByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

fun firebase(flavor: com.android.build.api.dsl.ProductFlavor, env: String) {
    flavor.buildConfigField("String", "FIREBASE_PROJECT_ID", quoted(prop("dv${env}FirebaseProjectId")))
    flavor.buildConfigField("String", "FIREBASE_APP_ID", quoted(prop("dv${env}FirebaseAppId")))
    flavor.buildConfigField("String", "FIREBASE_API_KEY", quoted(prop("dv${env}FirebaseApiKey")))
    flavor.buildConfigField("String", "FIREBASE_SENDER_ID", quoted(prop("dv${env}FirebaseSenderId")))
}

// Builds de homologação/produção sem configuração falham cedo, em vez de gerar um app que aponta para o nada.
listOf("Staging", "Prod").forEach { env ->
    val missing = listOf("SupabaseUrl", "PublishableKey", "MapStyleUrl").map { "dv$env$it" }.filter { prop(it).isEmpty() }
    val check = tasks.register("check${env}Config") {
        doLast {
            if (missing.isNotEmpty()) throw GradleException("Configuração ausente para ${env.lowercase()}: ${missing.joinToString()} (ver docs/setup.md)")
        }
    }
    tasks.matching { it.name == "pre${env}ReleaseBuild" }.configureEach { dependsOn(check) }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":auth"))
    implementation(project(":map"))
    implementation(project(":property"))
    implementation(project(":wallet"))
    implementation(project(":portfolio"))
    implementation(project(":proposals"))
    implementation(project(":profile"))

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
}
