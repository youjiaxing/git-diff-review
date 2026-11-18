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
        name = "Git Diff Review"
        version = providers.gradleProperty("pluginVersion")
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
    classpath = files(sourceSets.test.get().output, sourceSets.main.get().output, sourceSets.test.get().compileClasspath)
    mainClass = "io.github.youjiaxing.diffnav.ReviewHistoryTest"
}

val verifySelectedLines by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = files(sourceSets.test.get().output, sourceSets.main.get().output, sourceSets.test.get().compileClasspath)
    mainClass = "io.github.youjiaxing.diffnav.SelectedLinesTest"
}
val verifySelectedReplacement by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = files(sourceSets.test.get().output, sourceSets.main.get().output, sourceSets.test.get().compileClasspath)
    mainClass = "io.github.youjiaxing.diffnav.SelectedReplacementTest"
}
val verifyGitIndex by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = files(sourceSets.test.get().output, sourceSets.main.get().output, sourceSets.test.get().compileClasspath)
    mainClass = "io.github.youjiaxing.diffnav.GitIndexTransactionTest"
}
val verificationTasks = listOf(verifyNavigation, verifySelectedLines, verifySelectedReplacement, verifyGitIndex)
// The regression sources are executable main classes rather than JUnit tests.
// Keep the standard entry point useful without making Gradle's JUnit scanner
// report a false failure for a source set with no discovered test methods.
tasks.named("test") {
    enabled = false
    dependsOn(verificationTasks)
}
tasks.check { dependsOn(verificationTasks) }
tasks.named("buildPlugin") { dependsOn(verificationTasks) }
