plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.nathanxie.applepods"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.nathanxie.applepods"
        minSdk = 35
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-alpha01"
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = false
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(23)
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    testImplementation("junit:junit:4.13.2")
}
