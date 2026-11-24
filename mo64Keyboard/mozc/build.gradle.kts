import com.google.protobuf.gradle.id
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
}
android {
    namespace = "jp.example.mykeyboard.mozc"
    compileSdk = 35
    defaultConfig { minSdk = 24; consumerProguardFiles("consumer-rules.pro") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main") {
        jniLibs.setSrcDirs(emptyList<String>())
        assets.setSrcDirs(emptyList<String>())
    }
}
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.28.3" }
    generateProtoTasks {
        all().configureEach {
            builtins { id("java") { option("lite") } }
        }
    }
}
dependencies { api("com.google.protobuf:protobuf-javalite:4.28.3") }
