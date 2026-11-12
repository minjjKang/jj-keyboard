plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":hangul"))
    testImplementation("junit:junit:4.13.2")
}
tasks.test {
    val assets = rootProject.file("app/src/main/assets/language")
    inputs.dir(assets)
    systemProperty("minjj.language.assets", assets.absolutePath)
}
