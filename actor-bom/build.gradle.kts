// io.github.seonwkim:actor-bom: every published module at the project version, so users set the
// version once. A module is published when gradle.properties has artifactId.<module>; the root build
// applies java-platform and the publishing setup. scripts/release/check-poms.py fails when a
// published module is missing from this BOM. It also manages the Pekko Management modules that
// discovery needs and the application adds itself, at the version the starter is built with.
val publishedModules = rootProject.subprojects.filter { findProperty("artifactId.${it.name}") != null }
val pekkoManagementVersion: String by project

dependencies {
    constraints {
        publishedModules.forEach { "api"(it) }
        listOf(
            "pekko-management_3",
            "pekko-management-cluster-bootstrap_3",
            "pekko-management-cluster-http_3",
            "pekko-discovery-kubernetes-api_3",
        ).forEach { "api"("org.apache.pekko:$it:$pekkoManagementVersion") }
    }
}
