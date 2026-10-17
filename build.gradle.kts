
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.9.2")
}

tasks.test {
    useJUnitPlatform()
}
