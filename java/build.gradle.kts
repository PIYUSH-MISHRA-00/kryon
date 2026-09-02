import java.util.Base64

plugins {
    `java-library`
    `maven-publish`
    signing
}

// The Maven groupId is `io.github.piyush-mishra-00`, which is the namespace
// verifiable through GitHub account ownership. The *Java package* drops the
// hyphens -- `io.github.piyushmishra00.kryon` -- because a Java package
// identifier cannot contain them. The two are allowed to differ, and this is
// the standard way to reconcile a hyphenated GitHub username with the language.

java {
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    // Zero runtime dependencies. Kryon's job is to orchestrate the JDK's own
    // process facilities correctly; adding a dependency to do that would be
    // adding supply-chain risk to a library that already runs arbitrary
    // programs.
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Test-only. The published artifact still has no dependencies; a JSON parser
    // written by hand here would be more code than the runner it serves.
    testImplementation("com.google.code.gson:gson:2.11.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // `release` rather than a toolchain: it targets Java 17 bytecode and the Java 17
    // API from whatever modern JDK is present, so a contributor with only a newer JDK
    // installed can still build without Gradle downloading a second one.
    options.release = 17
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("Xdoclint:none", "-quiet")
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showStandardStreams = false
    }
    // The conformance corpus lives at the repository root and is shared with
    // every other SDK. It is never forked into a language directory.
    systemProperty("kryon.corpus", rootProject.file("../tests/conformance/cases.json").absolutePath)
    systemProperty("kryon.helper.classes", layout.buildDirectory.dir("classes/java/test").get().asFile.absolutePath)

    // `execute.env.inherited` needs this in the *runner's* own environment.
    // The JVM cannot set its own environment at runtime, so CI provides it and
    // the runner skips the case with a reason when it is absent.
    environment("KRYON_CONFORMANCE_INHERITED", System.getenv("KRYON_CONFORMANCE_INHERITED") ?: "")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "kryon"
            from(components["java"])

            pom {
                name = "Kryon"
                description =
                    "Powerful terminal execution, everywhere. Command execution, process " +
                        "control and streaming, with one conceptual API across languages."
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
        // Staged locally, then uploaded as a bundle to the Central Portal. No
        // credentials live in this file, and none are needed to build.
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

    // Two ways to sign, because they suit two very different situations.
    //
    // -PuseGpgCmd delegates to the local gpg, which prompts for the passphrase through
    // its own agent. Nothing is exported, nothing is stored, and the private key never
    // leaves the keyring. This is the right way to sign from your own machine.
    //
    // SIGNING_KEY/SIGNING_PASSWORD is for CI, where there is no agent and no human to
    // answer a prompt.
    val useGpgCmd = providers.gradleProperty("useGpgCmd").isPresent

    isRequired = useGpgCmd || key != null
    when {
        useGpgCmd -> {
            useGpgCmd()
            sign(publishing.publications["maven"])
        }
        key != null -> {
            useInMemoryPgpKeys(key, password)
            sign(publishing.publications["maven"])
        }
    }
}
