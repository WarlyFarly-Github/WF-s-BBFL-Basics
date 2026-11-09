plugins {
    java
}

group = "dev.whistle"
version = "1.3.0"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io") // DecentHolograms
}

dependencies {
    // If this exact version doesn't resolve, check https://docs.papermc.io/paper/dev/userdev
    // for the current 26.3 version string.
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    // Optional at runtime (softdepend). Newer versions: https://github.com/DecentSoftware-eu/DecentHolograms/releases
    compileOnly("com.github.decentsoftware-eu:decentholograms:2.9.9")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}
