allprojects { repositories { google(); mavenCentral() } }
val buildDir = rootProject.layout.buildDirectory.dir("../../build").get()
rootProject.layout.buildDirectory.value(buildDir)
subprojects { layout.buildDirectory.value(buildDir.dir(project.name)) }
tasks.register<Delete>("clean") { delete(rootProject.layout.buildDirectory) }
