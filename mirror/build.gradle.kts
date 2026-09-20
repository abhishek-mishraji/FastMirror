plugins {
    application
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // AR-8: NO third-party runtime dependencies. JDK only.
    // Nothing may ever be added to `implementation` or `runtimeOnly`.
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "com.handsoffretail.fastmirror.Application"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

// Default test run: everything except the heavy scenarios (IT-13/14/15/16).
tasks.test {
    useJUnitPlatform {
        excludeTags("slow")
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

// The heavy scenarios, run explicitly: ./gradlew slowTest
tasks.register<Test>("slowTest") {
    group = "verification"
    description = "Runs the @Tag(\"slow\") scenarios: throughput, overflow, atomic visibility, soak."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("slow")
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

// Single runnable jar, wrapped by Launch4j into FastMirror.exe (SRS 14.1).
tasks.register<Jar>("fatJar") {
    group = "distribution"
    description = "Builds the self-contained jar that Launch4j wraps as FastMirror.exe."

    archiveBaseName = "FastMirror"
    archiveClassifier = ""
    archiveVersion = ""
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "com.handsoffretail.fastmirror.Application"
        attributes["Implementation-Title"] = "FastMirror"
    }

    from(sourceSets.main.get().output)

    // There are no runtime dependencies (AR-8); this is here so the task stays
    // correct if that ever changes, and fails loudly in review if it does.
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
}
