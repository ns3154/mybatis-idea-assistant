package io.github.ns3154.mybatisassistant.methodsql;

import io.github.ns3154.mybatisassistant.database.MyBatisForeignKeyReference;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 方法名词法可引用的确定字段。
 */
public record MyBatisMethodField(
        @NotNull String propertyName,
        @NotNull String methodToken,
        @NotNull String columnName,
        @NotNull String javaType,
        @NotNull Optional<String> typeHandler,
        int jdbcType,
        boolean nullable,
        boolean primaryKey,
        boolean foreignKey,
        boolean autoIncrement,
        boolean generated,
        @NotNull Optional<MyBatisForeignKeyReference> foreignKeyReference) {
    public MyBatisMethodField {
        if (propertyName.isBlank() || methodToken.isBlank() || columnName.isBlank()
                || javaType.isBlank()) {
            throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                    "methodsql.error.field.identity.empty"));
        }
        if (!Character.isUpperCase(methodToken.codePointAt(0))) {
            throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                    "methodsql.error.field.token.case", methodToken));
        }
        MyBatisJavaTypeValidator.requireIdentifier(
                propertyName, MyBatisMethodSqlMessages.message("methodsql.role.field.property"));
        MyBatisJavaTypeValidator.requireIdentifier(
                methodToken, MyBatisMethodSqlMessages.message("methodsql.role.field.token"));
        MyBatisJavaTypeValidator.requireTypeName(
                javaType, MyBatisMethodSqlMessages.message("methodsql.role.field.type"));
        typeHandler.ifPresent(handler -> MyBatisJavaTypeValidator.requireQualifiedName(
                handler, MyBatisMethodSqlMessages.message("methodsql.role.field.type.handler")));
        if (!foreignKey && foreignKeyReference.isPresent()) {
            throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                    "methodsql.error.field.foreign.reference.without.flag"));
        }
    }

    /**
     * 保留尚未携带外键目标身份的完整字段模型调用方；目标身份默认未知。
     */
    public MyBatisMethodField(
            @NotNull String propertyName,
            @NotNull String methodToken,
            @NotNull String columnName,
            @NotNull String javaType,
            @NotNull Optional<String> typeHandler,
            int jdbcType,
            boolean nullable,
            boolean primaryKey,
            boolean foreignKey,
            boolean autoIncrement,
            boolean generated) {
        this(propertyName, methodToken, columnName, javaType, typeHandler, jdbcType,
                nullable, primaryKey, foreignKey, autoIncrement, generated, Optional.empty());
    }

    /**
     * 兼容不携带自增元数据的调用方；这类字段默认需要显式写入。
     */
    public MyBatisMethodField(
            @NotNull String propertyName,
            @NotNull String methodToken,
            @NotNull String columnName,
            @NotNull String javaType,
            @NotNull Optional<String> typeHandler,
            int jdbcType,
            boolean nullable,
            boolean primaryKey,
            boolean foreignKey) {
        this(propertyName, methodToken, columnName, javaType, typeHandler, jdbcType,
                nullable, primaryKey, foreignKey, false, false, Optional.empty());
    }

    public MyBatisMethodField(
            @NotNull String propertyName,
            @NotNull String methodToken,
            @NotNull String columnName,
            @NotNull String javaType,
            @NotNull Optional<String> typeHandler,
            int jdbcType,
            boolean nullable,
            boolean primaryKey,
            boolean foreignKey,
            boolean autoIncrement) {
        this(propertyName, methodToken, columnName, javaType, typeHandler, jdbcType,
                nullable, primaryKey, foreignKey, autoIncrement, false, Optional.empty());
    }
}
