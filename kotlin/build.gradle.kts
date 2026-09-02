import java.util.Base64

plugins {
    kotlin("jvm") version "2.0.21"
    `java-library`
    `maven-publish`
    signing
}

// Published as `io.github.piyush-mishra-00:kryon-kotlin`, in the package
// `io.github.piyushmishra00.kryon.coroutines`. The package differs from the Java
// SDK's on purpose: two jars sharing a package would collide on the classpath of
// any project that happened to use both.

repositories {
    mavenCentral()
}

dependencies {
    // The one dependency in the whole project, and it earns its place: coroutines
    // are how asynchronous Kotlin is written, and reimplementing structured
    // concurrency to avoid the dependency would be strictly worse than using it.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Test-only. The published artifact carries only coroutines.
    testImplementation("com.google.code.gson:gson:2.11.0")
}

kotlin {
    compilerOptions {
        // Java 17 bytecode from whatever modern JDK is present, so a contributor with
        // only a newer JDK installed can still build without downloading a second one.
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        freeCompilerArgs.add("-Xjvm-default=all")
        allWarningsAsErrors = true
    }
    explicitApi()
}

java {
    withSourcesJar()
    // Kotlin's documentation tool is Dokka, which would be another plugin to keep
    // current. An empty javadoc jar satisfies Maven Central's requirement honestly:
    // the real API documentation is the KDoc in the sources jar.
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed") }
    systemProperty("kryon.corpus", rootProject.file("../tests/conformance/cases.json").absolutePath)
    systemProperty(
        "kryon.helper.classes",
        layout.buildDirectory.dir("classes/kotlin/test").get().asFile.absolutePath,
    )
    systemProperty(
        "kryon.helper.classpath",
        sourceSets.test.get().runtimeClasspath.asPath,
    )

    // `execute.env.inherited` needs this in the *runner's* own environment. The JVM
    // cannot set its own environment at runtime, so CI provides it and the runner
    // skips the case with a reason when it is absent.
    environment("KRYON_CONFORMANCE_INHERITED", System.getenv("KRYON_CONFORMANCE_INHERITED") ?: "")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "kryon-kotlin"
            from(components["java"])

            pom {
                name = "Kryon for Kotlin"
                description =
                    "Powerful terminal execution, everywhere. Command execution, process " +
                        "control and streaming with coroutines, with one conceptual API " +
                        "across languages."
                url = "https://github.com/PIYUSH-MISHRA-00/kryon"
                inceptionYear = "2026"

                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        distribution = "repo"
                    }
                }
                developers {
                    developer {
                        id = "PIYUSH-MISHRA-00"
                        name = "PIYUSH-MISHRA-00"
                        email = "piyushmishra.professional@gmail.com"
                        url = "https://github.com/PIYUSH-MISHRA-00"
                    }
                }
                scm {
                    url = "https://github.com/PIYUSH-MISHRA-00/kryon"
                    connection = "scm:git:https://github.com/PIYUSH-MISHRA-00/kryon.git"
                    developerConnection = "scm:git:ssh://git@github.com/PIYUSH-MISHRA-00/kryon.git"
                }
                issueManagement {
                    system = "GitHub Issues"
                    url = "https://github.com/PIYUSH-MISHRA-00/kryon/issues"
                }
            }
        }
    }

    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

signing {
    // Signing is required by Maven Central and irrelevant everywhere else, so it
    // activates only when a key is actually supplied. A build that silently produced
    // unsigned artifacts and called them releasable would be worse than one that fails.
    //
    // `isNotBlank`, not `!= null`: GitHub Actions sets an undefined secret to the empty
    // string rather than leaving the variable unset, so a null check treats "no signing
    // key configured" as "signing key configured, and it is empty" -- which fails with
    // "Could not read PGP secret key" instead of skipping.
    // Accepts the signing key in either form: the raw ASCII-armored block, or that block
    // base64-encoded. Base64 is what most CI guides tell you to paste, because it survives
    // copy-paste and shell quoting -- but Gradle's useInMemoryPgpKeys wants the armor
    // itself, so decode when the value does not already look like one.
    val raw = providers.environmentVariable("SIGNING_KEY").orNull?.takeIf { it.isNotBlank() }
    val key = raw?.let { value ->
        if (value.contains("BEGIN PGP")) value
        else String(Base64.getMimeDecoder().decode(value.trim()))
    }
    val password = providers.environmentVariable("SIGNING_PASSWORD").orNull
    isRequired = key != null
    if (key != null) {
        useInMemoryPgpKeys(key, password)
        sign(publishing.publications["maven"])
    }
}
