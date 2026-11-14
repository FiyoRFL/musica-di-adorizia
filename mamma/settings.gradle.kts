pluginManagement { repositories { google(); maven("https://repo1.maven.org/maven2"); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); maven("https://repo1.maven.org/maven2") } }
rootProject.name = "MammaMusica"
include(":app")
