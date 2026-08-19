plugins {
    `java-library`
}

dependencies {
    api(project(":api"))
    implementation("org.mybatis:mybatis:3.5.19")

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
