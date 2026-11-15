plugins {
    java
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("com.formdev:flatlaf:3.5.4")
    implementation("com.fifesoft:rsyntaxtextarea:3.5.3")
    implementation("com.fifesoft:autocomplete:3.3.2")
    implementation("com.fifesoft:rstaui:3.3.1")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

application {
    mainClass.set("dev.pgstudio.Main")
    applicationDefaultJvmArgs = listOf("-Dapple.awt.application.name=PG Studio")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    // 테스트가 실제 ~/.pgstudio 를 건드리지 않도록 홈 디렉터리를 격리
    systemProperty("user.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
}
