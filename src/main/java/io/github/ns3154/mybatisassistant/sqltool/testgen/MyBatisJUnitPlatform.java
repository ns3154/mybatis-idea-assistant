package io.github.ns3154.mybatisassistant.sqltool.testgen;

/**
 * 支持的常用 JUnit 平台。
 */
public enum MyBatisJUnitPlatform {
    JUNIT_5("JUnit 5 / Jupiter"),
    JUNIT_4("JUnit 4");

    private final String displayName;

    MyBatisJUnitPlatform(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
