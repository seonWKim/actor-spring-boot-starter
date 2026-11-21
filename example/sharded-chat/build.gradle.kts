plugins {
	id("org.springframework.boot")
	id("io.spring.dependency-management")
}

apply(plugin = "org.springframework.boot")
apply(plugin = "io.spring.dependency-management")

repositories {
	mavenCentral()
}

// One chat node. The control app (control/) starts the nodes of the blue and the green cluster and
// serves the page; a node serves the chat API and the actuator endpoints only.
dependencies {
	implementation(project(":actor-spring-boot-starter-cluster"))
	// The blue-green generation record lives in the actor_generation table (spring.actor.generation.store=jdbc).
	implementation(project(":actor-jdbc"))

	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	runtimeOnly("com.h2database:h2")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.awaitility:awaitility")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
