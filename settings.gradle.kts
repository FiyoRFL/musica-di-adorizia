// m2local: copia local opcional (solo si existe en la compu que compila)
val local = file(System.getProperty("user.home") + "/m2local")
pluginManagement {
    repositories {
        val l = file(System.getProperty("user.home") + "/m2local")
        if (l.exists()) maven(l.toURI())
        google(); mavenCentral(); maven("https://repo1.maven.org/maven2"); gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        if (local.exists()) maven(local.toURI())
        google(); mavenCentral(); maven("https://repo1.maven.org/maven2")
    }
}
rootProject.name = "MammaMusica"
include(":app")
