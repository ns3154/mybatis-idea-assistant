package io.github.ns3154.mybatisassistant.sqltool.log;

import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;

/**
 * SQL 的保守风险等级。未知语句绝不按只读处理。
 */
public enum MyBatisSqlRisk {
    READ_ONLY("sqltool.risk.read.only"),
    WRITE("sqltool.risk.write"),
    DDL("sqltool.risk.ddl"),
    UNKNOWN("sqltool.risk.unknown");

    private final String messageKey;

    MyBatisSqlRisk(String messageKey) {
        this.messageKey = messageKey;
    }

    public String displayName() {
        return MyBatisAssistantBundle.message(messageKey);
    }
}
