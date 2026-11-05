plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "io.github.youjiaxing"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null) local(localIde)
        else goland(providers.gradleProperty("platformVersion").get())
        bundledPlugin("Git4Idea")
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

intellijPlatform {
    pluginConfiguration {
        name = "GoLand Diff Navigation"
        ideaVersion {
            sinceBuild = "262"
            untilBuild = "262.*"
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

val verifyNavigation by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "io.github.youjiaxing.diffnav.ReviewHistoryTest"
}

tasks.check { dependsOn(verifyNavigation) }
tasks.named("buildPlugin") { dependsOn(verifyNavigation) }
