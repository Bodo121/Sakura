allprojects { repositories { google(); mavenCentral() } }
val sakuraBuildDir = rootProject.layout.buildDirectory.dir("../../build").get()
rootProject.layout.buildDirectory.value(sakuraBuildDir)
subprojects { layout.buildDirectory.value(sakuraBuildDir.dir(project.name)) }
tasks.register<Delete>("clean") { delete(rootProject.layout.buildDirectory) }
