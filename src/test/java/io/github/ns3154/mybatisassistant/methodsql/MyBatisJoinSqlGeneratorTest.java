package io.github.ns3154.mybatisassistant.methodsql;

import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import org.junit.Test;

import java.sql.Types;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MyBatisJoinSqlGeneratorTest {
    private static final MyBatisMethodField USER_ID = field(
            "id", "Id", "id", true, false);
    private static final MyBatisMethodField USER_ROLE_ID = field(
            "roleId", "RoleId", "role_id", false, true);
    private static final MyBatisMethodField USER_NAME = field(
            "name", "Name", "name", false, false);
    private static final MyBatisMethodSchema USERS = new MyBatisMethodSchema(
            "users", List.of(USER_ID, USER_ROLE_ID, USER_NAME));
    private static final MyBatisMethodField ROLE_ID = field(
            "id", "Id", "id", true, false);
    private static final MyBatisMethodField ROLE_NAME = field(
            "name", "Name", "name", false, false);
    private static final MyBatisMethodSchema ROLES = new MyBatisMethodSchema(
            "roles", List.of(ROLE_ID, ROLE_NAME));

    @Test
    public void generatesExplicitForeignKeyJoin() {
        MyBatisJoinGeneration generation = MyBatisJoinSqlGenerator.generate(request(
                List.of(new MyBatisJoinSpec(
                        MyBatisJoinType.LEFT,
                        ROLES,
                        "r",
                        List.of(new MyBatisJoinRelation("u", USER_ROLE_ID, ROLE_ID)))),
                List.of(
                        new MyBatisJoinSelection("u", USER_ID, Optional.of("userId")),
                        new MyBatisJoinSelection("u", USER_NAME, Optional.of("userName")),
                        new MyBatisJoinSelection("r", ROLE_NAME, Optional.of("roleName")))));

        assertEquals(List.of("userId", "userName", "roleName"), generation.outputLabels());
        assertEquals(
                "SELECT `u`.`id` AS `userId`, `u`.`name` AS `userName`, "
                        + "`r`.`name` AS `roleName` FROM `users` `u` "
                        + "LEFT JOIN `roles` `r` ON `u`.`role_id` = `r`.`id`",
                generation.sql());
    }

    @Test
    public void supportsCompositeAndChainedRelations() {
        MyBatisMethodField permissionId = field(
                "permissionId", "PermissionId", "permission_id", false, true);
        MyBatisMethodSchema rolePermissions = new MyBatisMethodSchema(
                "role_permissions",
                List.of(
                        field("roleId", "RoleId", "role_id", false, true),
                        permissionId));
        MyBatisMethodField targetPermissionId = field(
                "id", "Id", "id", true, false);
        MyBatisMethodSchema permissions = new MyBatisMethodSchema(
                "permissions", List.of(targetPermissionId));
        MyBatisJoinSpec rolePermission = new MyBatisJoinSpec(
                MyBatisJoinType.INNER,
                rolePermissions,
                "rp",
                List.of(new MyBatisJoinRelation(
                        "r",
                        ROLE_ID,
                        rolePermissions.fields().get(0))));
        MyBatisJoinSpec permission = new MyBatisJoinSpec(
                MyBatisJoinType.INNER,
                permissions,
                "p",
                List.of(new MyBatisJoinRelation("rp", permissionId, targetPermissionId)));

        MyBatisJoinGeneration generation = MyBatisJoinSqlGenerator.generate(request(
                List.of(
                        new MyBatisJoinSpec(
                                MyBatisJoinType.LEFT,
                                ROLES,
                                "r",
                                List.of(new MyBatisJoinRelation("u", USER_ROLE_ID, ROLE_ID))),
                        rolePermission,
                        permission),
                List.of(new MyBatisJoinSelection(
                        "p", targetPermissionId, Optional.of("permissionId")))));

        assertTrue(generation.sql().contains(
                "INNER JOIN `role_permissions` `rp` ON `r`.`id` = `rp`.`role_id`"));
        assertTrue(generation.sql().contains(
                "INNER JOIN `permissions` `p` ON `rp`.`permission_id` = `p`.`id`"));
    }

    @Test
    public void rejectsRelationsWithoutForeignKeyEvidence() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisJoinSqlGenerator.generate(request(
                        List.of(new MyBatisJoinSpec(
                                MyBatisJoinType.INNER,
                                ROLES,
                                "r",
                                List.of(new MyBatisJoinRelation("u", USER_NAME, ROLE_NAME)))),
                        List.of(new MyBatisJoinSelection(
                                "u", USER_ID, Optional.of("userId"))))));

        assertTrue(failure.getMessage().contains("缺少外键到主键证据"));
    }

    @Test
    public void rejectsUnknownOrDuplicateTableAliases() {
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisJoinSqlGenerator.generate(request(
                        List.of(new MyBatisJoinSpec(
                                MyBatisJoinType.LEFT,
                                ROLES,
                                "u",
                                List.of(new MyBatisJoinRelation("u", USER_ROLE_ID, ROLE_ID)))),
                        List.of(new MyBatisJoinSelection(
                                "u", USER_ID, Optional.of("userId"))))))
                .getMessage().contains("别名重复"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisJoinSqlGenerator.generate(request(
                        List.of(new MyBatisJoinSpec(
                                MyBatisJoinType.LEFT,
                                ROLES,
                                "r",
                                List.of(new MyBatisJoinRelation("missing", USER_ROLE_ID, ROLE_ID)))),
                        List.of(new MyBatisJoinSelection(
                                "u", USER_ID, Optional.of("userId"))))))
                .getMessage().contains("尚未注册"));
    }

    @Test
    public void rejectsFieldsFromAnotherSchemaAndUnsafeAliases() {
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisJoinSqlGenerator.generate(request(
                        List.of(new MyBatisJoinSpec(
                                MyBatisJoinType.LEFT,
                                ROLES,
                                "r",
                                List.of(new MyBatisJoinRelation("u", ROLE_ID, ROLE_ID)))),
                        List.of(new MyBatisJoinSelection(
                                "u", USER_ID, Optional.of("userId"))))))
                .getMessage().contains("不属于 Join 表"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisJoinSqlGenerator.generate(new MyBatisJoinGenerationRequest(
                        USERS,
                        "u;drop",
                        List.of(new MyBatisJoinSpec(
                                MyBatisJoinType.LEFT,
                                ROLES,
                                "r",
                                List.of(new MyBatisJoinRelation("u;drop", USER_ROLE_ID, ROLE_ID)))),
                        List.of(new MyBatisJoinSelection(
                                "u;drop", USER_ID, Optional.of("userId"))),
                        MyBatisSqlDialect.MYSQL,
                        true)))
                .getMessage().contains("安全标识符"));
    }

    @Test
    public void rejectsDuplicateOutputLabelsUnlessExplicitlyAliased() {
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisJoinSqlGenerator.generate(request(
                        List.of(new MyBatisJoinSpec(
                                MyBatisJoinType.LEFT,
                                ROLES,
                                "r",
                                List.of(new MyBatisJoinRelation("u", USER_ROLE_ID, ROLE_ID)))),
                        List.of(
                                new MyBatisJoinSelection("u", USER_NAME, Optional.empty()),
                                new MyBatisJoinSelection("r", ROLE_NAME, Optional.empty())))))
                .getMessage().contains("输出列标签重复"));
    }

    @Test
    public void rejectsJoinTypesUnsupportedBySelectedDialect() {
        MyBatisJoinSpec rightJoin = new MyBatisJoinSpec(
                MyBatisJoinType.RIGHT,
                ROLES,
                "r",
                List.of(new MyBatisJoinRelation("u", USER_ROLE_ID, ROLE_ID)));
        MyBatisJoinSpec fullJoin = new MyBatisJoinSpec(
                MyBatisJoinType.FULL,
                ROLES,
                "r",
                List.of(new MyBatisJoinRelation("u", USER_ROLE_ID, ROLE_ID)));
        List<MyBatisJoinSelection> selections = List.of(
                new MyBatisJoinSelection("u", USER_ID, Optional.of("userId")));

        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisJoinSqlGenerator.generate(new MyBatisJoinGenerationRequest(
                        USERS, "u", List.of(fullJoin), selections,
                        MyBatisSqlDialect.MYSQL, true)))
                .getMessage().contains("MySQL 不支持 FULL JOIN"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisJoinSqlGenerator.generate(new MyBatisJoinGenerationRequest(
                        USERS, "u", List.of(rightJoin), selections,
                        MyBatisSqlDialect.SQLITE, true)))
                .getMessage().contains("SQLite 版本差异"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisJoinSqlGenerator.generate(new MyBatisJoinGenerationRequest(
                        USERS, "u", List.of(fullJoin), selections,
                        MyBatisSqlDialect.SQLITE, true)))
                .getMessage().contains("SQLite 版本差异"));
    }

    @Test
    public void supportsDamengFullJoinWithDoubleQuotedIdentifiers() {
        MyBatisJoinGeneration generation = MyBatisJoinSqlGenerator.generate(
                new MyBatisJoinGenerationRequest(
                        USERS,
                        "u",
                        List.of(new MyBatisJoinSpec(
                                MyBatisJoinType.FULL,
                                ROLES,
                                "r",
                                List.of(new MyBatisJoinRelation(
                                        "u", USER_ROLE_ID, ROLE_ID)))),
                        List.of(new MyBatisJoinSelection(
                                "u", USER_ID, Optional.of("userId"))),
                        MyBatisSqlDialect.DAMENG,
                        true));

        assertTrue(generation.sql().contains(
                "FULL JOIN \"roles\" \"r\" ON \"u\".\"role_id\" = \"r\".\"id\""));
    }

    private static MyBatisJoinGenerationRequest request(
            List<MyBatisJoinSpec> joins,
            List<MyBatisJoinSelection> selections) {
        return new MyBatisJoinGenerationRequest(
                USERS,
                "u",
                joins,
                selections,
                MyBatisSqlDialect.MYSQL,
                true);
    }

    private static MyBatisMethodField field(
            String property,
            String token,
            String column,
            boolean primary,
            boolean foreign) {
        return new MyBatisMethodField(
                property,
                token,
                column,
                "java.lang.Long",
                Optional.empty(),
                Types.BIGINT,
                false,
                primary,
                foreign);
    }
}
