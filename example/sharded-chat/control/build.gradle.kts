plugins {
	id("org.springframework.boot")
	id("io.spring.dependency-management")
}

apply(plugin = "org.springframework.boot")
apply(plugin = "io.spring.dependency-management")

repositories {
	mavenCentral()
}

// The control app plays the platform for the sharded chat: it starts and stops the chat nodes (the
// JVMs of the blue and the green cluster), hosts the store their generation record lives in, routes
// the page's requests to the active color, merges both colors' events, and runs the blue-green
// pipeline. It serves the page. It runs no actors: the chat nodes are the starter's users.
dependencies {
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	// The H2 TCP server that holds the nodes' actor_generation table.
	implementation("com.h2database:h2")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The page (../web) is built into src/main/resources/static/ by `npm run build`, which this build runs
// before processResources when npm is on PATH and web/node_modules exists. Without them the jar serves
// the page from the last build, or the API alone.
val webDir = layout.projectDirectory.dir("../web")
// npm by its full path: a Gradle daemon started elsewhere may look commands up on another PATH.
val npm = System.getenv("PATH").orEmpty().split(File.pathSeparator)
	.map { File(it, "npm") }
	.firstOrNull { it.canExecute() }
val buildWeb by tasks.registering(Exec::class) {
	description = "Builds the page into src/main/resources/static/"
	workingDir = webDir.asFile
	commandLine(npm?.absolutePath ?: "npm", "run", "build")
	// npm runs node from the same folder.
	environment("PATH", listOfNotNull(npm?.parent, System.getenv("PATH")).joinToString(File.pathSeparator))
	inputs.dir(webDir.dir("src"))
	inputs.files(webDir.file("package.json"), webDir.file("index.html"), webDir.file("vite.config.ts"))
	outputs.dir(layout.projectDirectory.dir("src/main/resources/static"))
	onlyIf("web/node_modules exists and npm is on PATH") { webDir.dir("node_modules").asFile.exists() && npm != null }
}
tasks.named("processResources") { dependsOn(buildWeb) }
