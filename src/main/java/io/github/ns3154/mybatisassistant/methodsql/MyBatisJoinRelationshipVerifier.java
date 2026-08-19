package io.github.ns3154.mybatisassistant.methodsql;

import io.github.ns3154.mybatisassistant.database.MyBatisForeignKeyReference;
import org.jetbrains.annotations.NotNull;

/**
 * 校验 Join 两端是否具有指向所选目标对象的明确 FK/PK 证据。
 */
public final class MyBatisJoinRelationshipVerifier {
    private MyBatisJoinRelationshipVerifier() {
    }

    public static boolean isVerifiedForeignKeyToPrimaryKey(
            @NotNull MyBatisMethodSchema firstSchema,
            @NotNull MyBatisMethodField firstField,
            @NotNull MyBatisMethodSchema secondSchema,
            @NotNull MyBatisMethodField secondField) {
        return references(firstField, secondSchema, secondField)
                || references(secondField, firstSchema, firstField);
    }

    private static boolean references(
            @NotNull MyBatisMethodField foreignField,
            @NotNull MyBatisMethodSchema primarySchema,
            @NotNull MyBatisMethodField primaryField) {
        if (!foreignField.foreignKey() || !primaryField.primaryKey()) {
            return false;
        }
        return foreignField.foreignKeyReference()
                .filter(reference -> matches(reference, primarySchema, primaryField))
                .isPresent();
    }

    private static boolean matches(
            @NotNull MyBatisForeignKeyReference reference,
            @NotNull MyBatisMethodSchema schema,
            @NotNull MyBatisMethodField field) {
        return reference.matches(
                schema.catalog(), schema.schema(), schema.tableName(), field.columnName());
    }
}
