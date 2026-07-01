import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.appupdater"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.unbeatable.lifetime"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
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
        }
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.register("generateAppIcon") {
    doLast {
        val base64Icon = "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXUpAAAABGdBTUEAALGPC/xhBQAAACBjSFJNAAB6JgAAgIQAAPoAAACA6AAAdTAAAOpgAAA6mAAAF3CculE8AAAACXBIWXMAAAsTAAALEwEAmpwYAAABiWlUWHRYTUw6Y29tLmFkb2JlLnhtcAAAAAAAPD94cGFja2V0IGJlZ2luPSLvu78iIGlkPSJXNU0wTXBDZWhpSHpyZVN6TlRjemtjOWQiPz4KPHg6eG1wbWV0YSB4bWxuczp4PSJhZG9iZTpuczptZXRhLyIgeDp4bXB0az0iQWRvYmUgWE1QIENvcmUgOC4wLWMwMDIgNzkuYTMzNGJhYSwgMjAyMi8wOC8xOC0xODozOToxNiAgICAgICAgIj4KIDxyZGY6UkRGIHhtbG5zOnJkZj0iaHR0cDovL3d3dy53My5vcmcvMTk5OS8wMi8yMi1yZGYtc3ludGF4LW5zIyI+CiAgPHJkZjpEZXNjcmlwdGlvbiByZGY6YWJvdXQ9IiIKICAgIHhtbG5zOnhtcD0iaHR0cDovL25zLmFkb2JlLmNvbS94YXAvMS4wLyI+CiAgIDx4bXA6Q3JlYXRvclRvb2w+Q2FuYmEgKExpdGUpPC94bXA6Q3JlYXRvclRvb2w+CiAgPC9yZGY6UkRGPgo8L3g6eG1wbWV0YT4KPD94cGFja2V0IGVuZD0iciI/PrzXhB8AAAJ0SURBVHgB7VvRcdowFP0m8zscgWmApsNAp4FmAsMEpIFAu0E6DXSAThNMp4FOg80EpIEoM/fDlzor9mSJLSlC88DvzZIsX92T7j0dyZKVZpE6aZZYIEnzJg0T2TqC82f+WbC9B9t98fBAsIUEayzBFrfE49VpE/m4Z4Z7XID8tZpAsP4eD68E24H3b5mK/HeCid9X9gI3gvefmQeIiz/I6oI8Z9zXp6kI578p3bYmK6A93FHeHwj3M+F+I9yfo0hE8O/eEPiF6qA68L0f6mXU8A/Vv5wM/zBv+EfCHxBOCPcCwZ8T7g2CPyHcK4T/m3DCPyj9HqCHxwh+kPDDhO8n/Aih5f+fU692/x9m+MvN8N+F3yKUD/YI+Vf8IerhZqgn6+HGrYfbnR6umvW8gGv8K8K/I/xDwrfQeRjh92h5m9Ceb4X/gfcI9hD1sOfVw76sh31fD/uZHvYgT6of0fN7gh683XU9XHXqYenVw8Kth4VPD3f2D6Zof4KeXkH6E4I9QPiQhPeUeI9v8XgG8YQgnxD8CYf/nC8Xg6b0/M2wDqE/5zvs1vO7YfcGek/Z0vMLpX/k6HklYt+S5X2xO6vnL2dK3x6V3kv8O0vYnyB+6g9r9bxdW6v0X0T6W459FRLtE8RP8S/qer9bX99f6uHmvofXb8d6Xof667feWw83+R6u8uYf5W2bZ60mEC+G6v/Xw7VbbyreS7vG11Xf3H9U7p2u1M/0q+6er97b2mO/9sh7e/D63T63Y+/V9mC6ff69qUfL/6r7v/SrrTda/vWrtu6/1UuorXup9dfV76XWb7ZfW6un/7T+bfeWb7D0X9bfv6p7W6sWfwNlZ6u8iN/EagAAAABJRU5ErkJggg=="
        val resDir = file("src/main/res/drawable")
        if (!resDir.exists()) {
            resDir.mkdirs()
        }
        val iconFile = file("src/main/res/drawable/app_icon.png")
        iconFile.writeBytes(Base64.getDecoder().decode(base64Icon))
        println("Generated app_icon.png successfully")
    }
}

tasks.named("preBuild") {
    dependsOn("generateAppIcon")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.apksig)
    implementation(libs.bcpkix)
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
