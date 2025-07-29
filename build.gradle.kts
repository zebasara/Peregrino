buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.11.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
        classpath("androidx.navigation:navigation-safe-args-gradle-plugin:2.9.2")
    }
}

plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    id("androidx.navigation.safeargs.kotlin") version "2.9.2" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}