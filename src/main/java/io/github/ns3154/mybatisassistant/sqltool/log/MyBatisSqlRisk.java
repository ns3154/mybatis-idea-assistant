package io.github.ns3154.mybatisassistant.sqltool.log;

/**
 * SQL 的保守风险等级。未知语句绝不按只读处理。
 */
public enum MyBatisSqlRisk {
    READ_ONLY("只读"),
    WRITE("写入"),
    DDL("结构变更"),
    UNKNOWN("未知");

    private final String displayName;

    MyBatisSqlRisk(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
