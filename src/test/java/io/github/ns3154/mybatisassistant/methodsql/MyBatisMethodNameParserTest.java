package io.github.ns3154.mybatisassistant.methodsql;

import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationArtifactKind;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationColumnOverride;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationConfiguration;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationTemplateGroup;
import org.junit.Test;

import java.sql.Types;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MyBatisMethodNameParserTest {
    private static final MyBatisMethodSchema SCHEMA = new MyBatisMethodSchema(
            "user_account",
            List.of(
                    field("id", "Id", "id", "java.lang.Long"),
                    field("name", "Name", "name", "java.lang.String"),
                    field("email", "Email", "email", "java.lang.String"),
                    field("status", "Status", "status", "java.lang.String"),
                    field("age", "Age", "age", "java.lang.Integer"),
                    field("active", "Active", "active", "java.lang.Boolean"),
                    field("balance", "Balance", "balance", "java.math.BigDecimal"),
                    field("createdAt", "CreatedAt", "created_at", "java.time.LocalDateTime")));

    @Test
    public void parsesProjectionPredicateAndOrdering() {
        MyBatisMethodQuery query = success(
                "findNameAndEmailByStatusAndAgeGreaterThanOrderByCreatedAtDescIdAsc");

        assertEquals(MyBatisMethodOperation.SELECT, query.operation());
        assertEquals(List.of("name", "email"), properties(query.subjectFields()));
        assertEquals(List.of("createdAt", "id"), query.orders().stream()
                .map(order -> order.field().propertyName())
                .toList());
        assertEquals(MyBatisMethodOrder.Direction.DESCENDING,
                query.orders().get(0).direction());
        assertEquals(MyBatisMethodOrder.Direction.ASCENDING,
                query.orders().get(1).direction());
    }

    @Test
    public void buildsAndBeforeOrPredicateTree() {
        MyBatisMethodQuery query = success(
                "findByStatusOrAgeGreaterThanAndActiveTrue");
        MyBatisMethodJunction root = cast(
                MyBatisMethodJunction.class, query.predicate().orElseThrow());

        assertEquals(MyBatisMethodJunctionKind.OR, root.kind());
        assertEquals(2, root.children().size());
        MyBatisMethodJunction right = cast(
                MyBatisMethodJunction.class, root.children().get(1));
        assertEquals(MyBatisMethodJunctionKind.AND, right.kind());
        assertEquals(MyBatisMethodComparison.GREATER_THAN,
                ((MyBatisMethodCondition) right.children().get(0)).comparison());
        assertEquals(MyBatisMethodComparison.TRUE,
                ((MyBatisMethodCondition) right.children().get(1)).comparison());
    }

    @Test
    public void parsesDistinctLimitAndPagingModifiers() {
        MyBatisMethodQuery limited = success("findDistinctTop10ByStatus");
        MyBatisMethodQuery paged = success("queryPagedByStatusOrderByIdDesc");

        assertTrue(limited.distinct());
        assertEquals(10, limited.limit().orElseThrow());
        assertFalse(limited.paged());
        assertTrue(paged.paged());
        assertTrue(paged.limit().isEmpty());
        assertTrue(success("getById").singleResult());
        assertTrue(success("findFirstByStatus").singleResult());
        assertFalse(success("findByStatus").singleResult());
    }

    @Test
    public void parsesUpdateDeleteCountExistsAndAggregates() {
        assertEquals(List.of("status", "name"), properties(
                success("updateStatusAndNameById").subjectFields()));
        assertEquals(MyBatisMethodOperation.DELETE, success("removeById").operation());
        MyBatisMethodQuery count = success("countDistinctEmailByStatus");
        assertEquals(MyBatisMethodOperation.COUNT, count.operation());
        assertTrue(count.distinct());
        assertEquals(List.of("email"), properties(count.subjectFields()));
        assertEquals(MyBatisMethodOperation.EXISTS, success("existsByEmail").operation());
        assertEquals(MyBatisMethodOperation.SUM, success("sumBalanceByStatus").operation());
        assertEquals(MyBatisMethodOperation.AVERAGE,
                success("averageBalanceByStatus").operation());
        assertEquals(MyBatisMethodOperation.MINIMUM,
                success("minimumCreatedAtByStatus").operation());
        assertEquals(MyBatisMethodOperation.MAXIMUM,
                success("maxCreatedAtByStatus").operation());
    }

    @Test
    public void allowsReadWithoutPredicateButRejectsUnboundedWrites() {
        assertTrue(success("findAllOrderById").predicate().isEmpty());
        assertTrue(success("countAll").predicate().isEmpty());
        assertFailure("updateStatus", MyBatisMethodDiagnosticCode.PREDICATE_REQUIRED);
        assertFailure("deleteAll", MyBatisMethodDiagnosticCode.PREDICATE_REQUIRED);
    }

    @Test
    public void rejectsAmbiguousFieldAndOperatorSegmentation() {
        MyBatisMethodSchema ambiguous = new MyBatisMethodSchema(
                "sample",
                List.of(
                        field("name", "Name", "name", "java.lang.String"),
                        field("nameContaining", "NameContaining", "name_containing",
                                "java.lang.String")));

        assertFailure(
                "findByNameContaining",
                ambiguous,
                MyBatisMethodDiagnosticCode.AMBIGUOUS_SYNTAX);
    }

    @Test
    public void rejectsInvalidLimitAndUnsupportedCombinations() {
        MyBatisMethodParseResult.Failure zero = assertFailure(
                "findTop0ByStatus", MyBatisMethodDiagnosticCode.INVALID_LIMIT);
        assertEquals("findTop".length(), zero.diagnostic().offset());
        assertFailure("findTop10001ByStatus", MyBatisMethodDiagnosticCode.INVALID_LIMIT);
        assertFailure("findTop10PagedByStatus", MyBatisMethodDiagnosticCode.UNKNOWN_FIELD);
        assertFailure("countDistinctByStatus", MyBatisMethodDiagnosticCode.UNKNOWN_FIELD);
        assertFailure("deleteNameById", MyBatisMethodDiagnosticCode.UNKNOWN_FIELD);
    }

    @Test
    public void returnsPositionedDiagnosticsForEmptyUnknownAndLongNames() {
        assertFailure("", MyBatisMethodDiagnosticCode.EMPTY_NAME);
        assertFailure("loadById", MyBatisMethodDiagnosticCode.UNKNOWN_OPERATION);
        MyBatisMethodParseResult.Failure unknown = assertFailure(
                "findByMissing", MyBatisMethodDiagnosticCode.UNKNOWN_FIELD);
        assertEquals("find".length(), unknown.diagnostic().offset());
        assertFailure("find" + "A".repeat(509),
                MyBatisMethodDiagnosticCode.METHOD_NAME_TOO_LONG);
    }

    @Test
    public void derivesSchemaFromGenerationConfiguration() {
        MyBatisDatabaseTable table = new MyBatisDatabaseTable(
                Optional.empty(),
                Optional.empty(),
                "user_account",
                List.of(
                        column("user_id", Types.BIGINT, true, 0),
                        column("display_name", Types.VARCHAR, false, 1),
                        column("ignored", Types.VARCHAR, false, 2)));
        MyBatisGenerationConfiguration configuration = new MyBatisGenerationConfiguration(
                "com.example",
                "src/main/java",
                "src/main/resources",
                EnumSet.allOf(MyBatisGenerationArtifactKind.class),
                MyBatisGenerationTemplateGroup.STANDARD,
                "",
                "",
                true,
                true,
                Set.of("ignored"),
                Map.of("display_name", new MyBatisGenerationColumnOverride(
                        Optional.of("nickname"),
                        Optional.empty(),
                        Optional.empty())));

        MyBatisMethodSchema schema = MyBatisMethodSchema.from(table, configuration);

        assertEquals(List.of("userId", "nickname"), properties(schema.fields()));
        assertEquals(List.of("UserId", "Nickname"), schema.fields().stream()
                .map(MyBatisMethodField::methodToken)
                .toList());
        assertEquals("java.lang.Long", schema.fields().get(0).javaType());
        assertTrue(schema.fields().get(0).primaryKey());
    }

    @Test
    public void rejectsDuplicateSchemaTokensBeforeParsing() {
        IllegalArgumentException failure = org.junit.Assert.assertThrows(
                IllegalArgumentException.class,
                () -> new MyBatisMethodSchema(
                        "sample",
                        List.of(
                                field("url", "Url", "url", "java.lang.String"),
                                field("URL", "Url", "url_upper", "java.lang.String"))));

        assertTrue(failure.getMessage().contains("词元重复"));
    }

    @Test
    public void remainsDeterministicWithLargeFieldDictionary() {
        List<MyBatisMethodField> fields = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            fields.add(field(
                    "field" + index,
                    "Field" + index,
                    "field_" + index,
                    "java.lang.String"));
        }
        MyBatisMethodSchema schema = new MyBatisMethodSchema("large_table", fields);
        MyBatisMethodParseResult first = MyBatisMethodNameParser.parse(
                "findField1AndField199ByField50AndField150GreaterThanOrderByField0Desc",
                schema);

        assertTrue(first instanceof MyBatisMethodParseResult.Success);
        for (int attempt = 0; attempt < 100; attempt++) {
            assertEquals(first, MyBatisMethodNameParser.parse(
                    "findField1AndField199ByField50AndField150GreaterThanOrderByField0Desc",
                    schema));
        }
    }

    private static MyBatisMethodQuery success(String methodName) {
        MyBatisMethodParseResult.Success success = cast(
                MyBatisMethodParseResult.Success.class,
                MyBatisMethodNameParser.parse(methodName, SCHEMA));
        return success.query();
    }

    private static MyBatisMethodParseResult.Failure assertFailure(
            String methodName,
            MyBatisMethodDiagnosticCode code) {
        return assertFailure(methodName, SCHEMA, code);
    }

    private static MyBatisMethodParseResult.Failure assertFailure(
            String methodName,
            MyBatisMethodSchema schema,
            MyBatisMethodDiagnosticCode code) {
        MyBatisMethodParseResult.Failure failure = cast(
                MyBatisMethodParseResult.Failure.class,
                MyBatisMethodNameParser.parse(methodName, schema));
        assertEquals(code, failure.diagnostic().code());
        return failure;
    }

    private static List<String> properties(List<MyBatisMethodField> fields) {
        return fields.stream().map(MyBatisMethodField::propertyName).toList();
    }

    private static MyBatisMethodField field(
            String property,
            String token,
            String column,
            String javaType) {
        return new MyBatisMethodField(
                property, token, column, javaType, Optional.empty(), Types.VARCHAR,
                true, false, false);
    }

    private static <T> T cast(Class<T> type, Object value) {
        assertTrue("期望 " + type.getSimpleName() + "，实际为 " + value,
                type.isInstance(value));
        return type.cast(value);
    }

    private static MyBatisDatabaseColumn column(
            String name,
            int jdbcType,
            boolean primaryKey,
            int position) {
        return new MyBatisDatabaseColumn(
                name,
                "TYPE",
                jdbcType,
                true,
                primaryKey,
                false,
                false,
                Optional.empty(),
                position);
    }
}
