package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationConfiguration;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodField;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodNameParser;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodParseResult;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodQuery;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodSchema;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class MyBatisOptionalConditionDialogTest extends BasePlatformTestCase {
    public void testPureAndParameterizedConditionsCanBeSelectedExplicitly() {
        MyBatisOptionalConditionDialog dialog = dialog(
                "findByNameAndAgeGreaterThan");
        try {
            assertTrue(dialog.isConditionEnabled(0));
            assertTrue(dialog.isConditionEnabled(1));
            assertEquals(Set.of(), dialog.optionalConditionIndexes());

            dialog.setConditionOptional(1, true);

            assertEquals(Set.of(1), dialog.optionalConditionIndexes());
            assertEquals(Optional.of(Set.of(1)), dialog.selectionResult(true));
        } finally {
            dialog.disposeIfNeeded();
        }
    }

    public void testOrAndParameterlessConditionsAreNotSelectable() {
        MyBatisOptionalConditionDialog orDialog = dialog(
                "findByNameOrAgeGreaterThan");
        try {
            assertFalse(orDialog.isConditionEnabled(0));
            assertFalse(orDialog.isConditionEnabled(1));
            assertTrue(orDialog.conditionTooltip(0).contains("OR"));
            assertThrows(IllegalArgumentException.class,
                    () -> orDialog.setConditionOptional(0, true));
        } finally {
            orDialog.disposeIfNeeded();
        }

        MyBatisOptionalConditionDialog parameterlessDialog = dialog(
                "findByNameIsNullAndAgeGreaterThan");
        try {
            assertFalse(parameterlessDialog.isConditionEnabled(0));
            assertTrue(parameterlessDialog.conditionTooltip(0).contains("没有参数"));
            assertTrue(parameterlessDialog.isConditionEnabled(1));
        } finally {
            parameterlessDialog.disposeIfNeeded();
        }
    }

    public void testConditionalWriteAlwaysKeepsOneRequiredPredicate() {
        MyBatisOptionalConditionDialog single = dialog("updateNameById");
        try {
            assertFalse(single.isConditionEnabled(0));
            assertTrue(single.conditionTooltip(0).contains("至少一个必选谓词"));
            assertThrows(IllegalArgumentException.class,
                    () -> single.setConditionOptional(0, true));
        } finally {
            single.disposeIfNeeded();
        }

        MyBatisOptionalConditionDialog multiple = dialog(
                "updateNameByIdAndAgeGreaterThan");
        try {
            assertTrue(multiple.isConditionEnabled(0));
            assertTrue(multiple.isConditionEnabled(1));

            multiple.setConditionOptional(0, true);

            assertFalse(multiple.isConditionEnabled(1));
            assertTrue(multiple.conditionTooltip(1).contains("至少一个必选谓词"));
            assertThrows(IllegalArgumentException.class,
                    () -> multiple.setConditionOptional(1, true));
            assertEquals(Set.of(0), multiple.optionalConditionIndexes());
        } finally {
            multiple.disposeIfNeeded();
        }

        MyBatisOptionalConditionDialog delete = dialog(
                "deleteByIdAndAgeGreaterThan");
        try {
            delete.setConditionOptional(1, true);
            assertFalse(delete.isConditionEnabled(0));
            assertThrows(IllegalArgumentException.class,
                    () -> delete.setConditionOptional(0, true));
            assertEquals(Set.of(1), delete.optionalConditionIndexes());
        } finally {
            delete.disposeIfNeeded();
        }
    }

    public void testPrimitiveScalarIsDisabledButPrimitiveElementCollectionIsSelectable() {
        MyBatisMethodField primitiveAge = new MyBatisMethodField(
                "age",
                "Age",
                "age",
                "int",
                Optional.empty(),
                Types.INTEGER,
                false,
                false,
                false,
                false,
                false,
                Optional.empty());
        MyBatisMethodSchema schema = new MyBatisMethodSchema(
                "user", List.of(primitiveAge));

        MyBatisOptionalConditionDialog scalar = dialog(
                query("findByAgeGreaterThan", schema));
        try {
            assertFalse(scalar.isConditionEnabled(0));
            assertTrue(scalar.conditionTooltip(0).contains("基本类型参数"));
            assertThrows(IllegalArgumentException.class,
                    () -> scalar.setConditionOptional(0, true));
        } finally {
            scalar.disposeIfNeeded();
        }

        MyBatisOptionalConditionDialog range = dialog(
                query("findByAgeBetween", schema));
        try {
            assertFalse(range.isConditionEnabled(0));
            assertTrue(range.conditionTooltip(0).contains("基本类型参数"));
            assertThrows(IllegalArgumentException.class,
                    () -> range.setConditionOptional(0, true));
        } finally {
            range.disposeIfNeeded();
        }

        MyBatisOptionalConditionDialog collection = dialog(
                query("findByAgeIn", schema));
        try {
            assertTrue(collection.isConditionEnabled(0));
            collection.setConditionOptional(0, true);
            assertEquals(Set.of(0), collection.optionalConditionIndexes());
        } finally {
            collection.disposeIfNeeded();
        }
    }

    public void testCancelDoesNotReturnThePendingSelection() {
        MyBatisOptionalConditionDialog dialog = dialog(
                "findByNameAndAgeGreaterThan");
        try {
            dialog.setConditionOptional(0, true);

            assertEquals(Optional.empty(), dialog.selectionResult(false));
        } finally {
            dialog.disposeIfNeeded();
        }
    }

    public void testMethodWithoutPredicatesReturnsEmptySelectionWithoutDialog() {
        MyBatisDatabaseMethodGenerationModel model =
                MyBatisDatabaseMethodGenerationModel.prepare(
                        table(),
                        MyBatisSqlDialect.MYSQL,
                        MyBatisGenerationConfiguration.standard("com.example"),
                        "findAllOrderById");

        assertFalse(MyBatisOptionalConditionSelectionModel.from(model.query())
                .hasConditions());
        assertEquals(
                Optional.of(Set.of()),
                MyBatisOptionalConditionDialog.select(getProject(), model.query()));
    }

    private MyBatisOptionalConditionDialog dialog(String methodName) {
        MyBatisDatabaseMethodGenerationModel model =
                MyBatisDatabaseMethodGenerationModel.prepare(
                        table(),
                        MyBatisSqlDialect.MYSQL,
                        MyBatisGenerationConfiguration.standard("com.example"),
                        methodName);
        return dialog(model.query());
    }

    private MyBatisOptionalConditionDialog dialog(MyBatisMethodQuery query) {
        return new MyBatisOptionalConditionDialog(
                getProject(), MyBatisOptionalConditionSelectionModel.from(query));
    }

    private static MyBatisMethodQuery query(
            String methodName,
            MyBatisMethodSchema schema) {
        return ((MyBatisMethodParseResult.Success) MyBatisMethodNameParser.parse(
                methodName, schema)).query();
    }

    private static MyBatisDatabaseTable table() {
        return new MyBatisDatabaseTable(
                Optional.empty(),
                Optional.empty(),
                "user",
                List.of(
                        column("id", Types.BIGINT, true, 0),
                        column("name", Types.VARCHAR, false, 1),
                        column("age", Types.INTEGER, false, 2)));
    }

    private static MyBatisDatabaseColumn column(
            String name,
            int jdbcType,
            boolean primary,
            int position) {
        return new MyBatisDatabaseColumn(
                name,
                "TYPE",
                jdbcType,
                true,
                primary,
                false,
                primary,
                Optional.empty(),
                position);
    }
}
