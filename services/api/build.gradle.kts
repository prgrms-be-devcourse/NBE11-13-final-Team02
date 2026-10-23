plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.9.0"
    jacoco
}

group = "kr.mujuthaek"
version = "0.0.1-SNAPSHOT"
description = "api"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.modulith:spring-modulith-starter-core:2.0.0")
    implementation("org.postgresql:postgresql") // area 적재기가 COPY(CopyManager)를 직접 써요

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test:2.0.0")
    testImplementation("org.springframework.modulith:spring-modulith-docs:2.0.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

spotless {
    java {
        googleJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// 법정동코드표는 AI(extraction)와 같은 저장소 원본 하나를 써요. 빌드 때 api 리소스(common/regions/)로 복사해요.
// 저장소 전체가 보여야 해서(CI는 전체 checkout), services/api만 보이는 곳에서 빌드하면 여기서 멈춰요.
val legalDongCodes = layout.projectDirectory.dir("../extraction/data/legal-dong-codes")
tasks.processResources {
    inputs.dir(legalDongCodes).withPropertyName("legalDongCodes")
    from(legalDongCodes) {
        include("legal_dong_*.csv")
        into("common/regions")
    }
    doFirst {
        val files = legalDongCodes.asFile.listFiles { f -> f.name.matches(Regex("legal_dong_\\d{8}\\.csv")) }
        check(!files.isNullOrEmpty()) {
            "법정동코드표가 없어요: ${legalDongCodes.asFile} (저장소 전체가 있는 곳에서 빌드하세요)"
        }
    }
}

// 커버리지는 각 모듈의 service 패키지와 eligibility/rule만 재요 (controller·repository·dto 등은 제외)
jacoco {
    toolVersion = "0.8.14" // 자바 25 클래스 파일 지원
}

val coverageIncludes = listOf("kr/mujuthaek/api/*/service/**", "kr/mujuthaek/api/eligibility/rule/**")

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
    classDirectories.setFrom(files(classDirectories.files.map { fileTree(it) { include(coverageIncludes) } }))
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(files(classDirectories.files.map { fileTree(it) { include(coverageIncludes) } }))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
