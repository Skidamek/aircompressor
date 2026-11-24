plugins {
	`java-library`
	`maven-publish`
}

group = providers.gradleProperty("maven_group").get()
version = providers.gradleProperty("lib_version").get()

repositories {
	mavenCentral()
}

dependencies {
	testImplementation(platform("org.junit:junit-bom:5.11.4"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testImplementation("com.github.luben:zstd-jni:1.5.7-28")
}

java {
	withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
	options.encoding = "UTF-8"
	options.release.set(17)
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
	if (JavaVersion.current().majorVersion.toInt() >= 24) {
		jvmArgs("--sun-misc-unsafe-memory-access=deny")
	}
}

val licenseFiles = copySpec {
	into("META-INF/licenses/aircompressor") {
		from("license.txt") { rename { "LICENSE.txt" } }
		from("notice.md") { rename { "NOTICE.txt" } }
		from("UPSTREAM.md")
	}
}

tasks.named<Jar>("jar") {
	with(licenseFiles)
	manifest {
		attributes(
			"Implementation-Title" to "aircompressor (zstd only, pure Java)",
			"Implementation-Version" to project.version.toString(),
			"Implementation-Vendor" to "Skidamek fork of airlift/aircompressor 2.0.3",
			"X-License" to "Apache-2.0",
		)
	}
}

tasks.named<Jar>("sourcesJar") {
	with(licenseFiles)
}

publishing {
	publications {
		create<MavenPublication>("mavenJava") {
			artifactId = rootProject.name
			from(components["java"])
			pom {
				name.set(artifactId)
				description.set("Pure-Java, Unsafe-free zstd-only fork of airlift/aircompressor 2.0.3 with raw-content prefix support.")
				url.set("https://github.com/Skidamek/aircompressor")
				licenses {
					license {
						name.set("Apache-2.0")
						url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
					}
				}
				scm {
					url.set("https://github.com/Skidamek/aircompressor")
					connection.set("scm:git:https://github.com/Skidamek/aircompressor.git")
					developerConnection.set("scm:git:ssh://git@github.com:Skidamek/aircompressor.git")
				}
			}
		}
	}
	repositories {
		maven {
			name = "mavenRepo"
			url =
				providers
					.gradleProperty("mavenRepoDir")
					.map { rootProject.file(it).toURI() }
					.orElse(
						rootProject.layout.buildDirectory
							.dir("maven-repo")
							.map { it.asFile.toURI() },
					).get()
		}
	}
}
