plugins {
    java
    id("org.springframework.boot")
}

val springBootVersion = "4.1.1"

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    implementation(project(":data"))
    implementation("org.mybatis.spring.boot:mybatis-spring-boot-starter:4.1.0")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
