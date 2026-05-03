plugins {
    java
    `java-library`
    id("com.gradleup.shadow") version "8.3.5"
}

group = "dev.user"
version = "1.0.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.rosewooddev.io/repository/public/")
}

dependencies {
    compileOnly("dev.folia:folia-api:1.21.4-R0.1-SNAPSHOT")

    // PlaceholderAPI (硬依赖)
    compileOnly("me.clip:placeholderapi:2.11.6")

    // XConomy 经济插件 (软依赖)
    compileOnly("com.github.YiC200333:XConomyAPI:2.25.1")

    // PlayerPoints 点券插件 (软依赖)
    compileOnly("org.black_ixx:playerpoints:3.3.3")

    // 数据库连接池和驱动
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("com.h2database:h2:2.3.232")
    implementation("com.mysql:mysql-connector-j:9.2.0")

    // JSON 序列化
    implementation("com.google.code.gson:gson:2.12.1")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("SimpleRewards-${version}.jar")

    // 重定位依赖包
    relocate("org.h2", "dev.user.rewards.libs.org.h2")
    relocate("com.zaxxer", "dev.user.rewards.libs.com.zaxxer")
    relocate("com.mysql", "dev.user.rewards.libs.com.mysql")
    relocate("com.google.gson", "dev.user.rewards.libs.com.google.gson")

    // 排除不需要的文件
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/LICENSE*")
    exclude("LICENSE*")

    mergeServiceFiles {
        include("META-INF/services/java.sql.Driver")
    }

    minimize {
        exclude(dependency("com.h2database:h2:.*"))
        exclude(dependency("com.mysql:mysql-connector-j:.*"))
        exclude(dependency("com.zaxxer:HikariCP:.*"))
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
