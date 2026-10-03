buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24")
        classpath("org.jetbrains.kotlin:kotlin-serialization:1.9.24")
        classpath("com.google.dagger:hilt-android-gradle-plugin:2.51")
    }
}

tasks.register("clean") {
    delete(layout.buildDirectory)
}