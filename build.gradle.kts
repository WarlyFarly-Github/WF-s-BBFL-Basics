plugins {
    java
}

group = "dev.whistle"
version = "1.0.0"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // If this exact version doesn't resolve, check https://docs.papermc.io/paper/dev/userdev
    // for the current 26.3 version string.
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}
