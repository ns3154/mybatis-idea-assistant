package io.github.ns3154.mybatisassistant.generator;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodGeneration;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodGenerationRequest;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodNameParser;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodParseResult;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodQuery;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodSchema;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodSqlGenerator;

import java.io.IOException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class MyBatisMethodPlanIntegratorTest extends BasePlatformTestCase {
    private VirtualFile root;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        root = myFixture.getTempDirFixture().findOrCreateDir("method-generation-root");
    }

    public void testIntegratesJavaAndXmlIntoCreatePlan() {
        MyBatisDatabaseTable table = table(Types.VARCHAR);
        MyBatisGenerationBundle bundle = bundle(table);
        MyBatisGenerationPlan base = MyBatisGenerationPlanner.plan(
                getProject(), root, List.of(bundle));

        MyBatisGenerationPlan integrated = MyBatisMethodPlanIntegrator.integrate(
                getProject(), base, method(table, "findByName"));

        assertFalse(describe(integrated), integrated.hasConflicts());
        String mapper = proposed(integrated, MyBatisGenerationArtifactKind.MAPPER);
        String xml = proposed(integrated, MyBatisGenerationArtifactKind.XML);
        assertTrue(mapper.contains("<mybatis-assistant-method id=\"findByName\""));
        assertTrue(mapper.contains("findByName("));
        assertTrue(xml.contains("<mybatis-assistant-method id=\"findByName\""));
        assertTrue(xml.contains("<select id=\"findByName\""));
        assertEquals(MyBatisGenerationPlanStatus.CREATE,
                entry(integrated, MyBatisGenerationArtifactKind.MAPPER).status());
    }

    public void testRepeatedIntegrationIsUnchangedAndSchemaRegenerationKeepsMethod() {
        MyBatisDatabaseTable firstTable = table(Types.VARCHAR);
        MyBatisGenerationBundle firstBundle = bundle(firstTable);
        MyBatisGenerationPlan create = MyBatisMethodPlanIntegrator.integrate(
                getProject(),
                MyBatisGenerationPlanner.plan(getProject(), root, List.of(firstBundle)),
                method(firstTable, "findByName"));
        MyBatisGenerationCommandExecutor.execute(getProject(), root, create);

        MyBatisGenerationPlan repeated = MyBatisMethodPlanIntegrator.integrate(
                getProject(),
                MyBatisGenerationPlanner.plan(getProject(), root, List.of(firstBundle)),
                method(firstTable, "findByName"));

        assertFalse(repeated.hasChanges());
        MyBatisDatabaseTable changedTable = new MyBatisDatabaseTable(
                Optional.empty(),
                Optional.empty(),
                "user",
                List.of(
                        column("id", Types.BIGINT, true, 0),
                        column("name", Types.VARCHAR, false, 1),
                        column("email", Types.VARCHAR, false, 2)));
        MyBatisGenerationPlan changed = MyBatisMethodPlanIntegrator.integrate(
                getProject(),
                MyBatisGenerationPlanner.plan(
                        getProject(), root, List.of(bundle(changedTable))),
                method(changedTable, "findByName"));

        assertFalse(describe(changed), changed.hasConflicts());
        assertTrue(proposed(changed, MyBatisGenerationArtifactKind.MAPPER)
                .contains("findByName("));
        assertTrue(proposed(changed, MyBatisGenerationArtifactKind.XML)
                .contains("<select id=\"findByName\""));
    }

    public void testUpdatesUnmodifiedMethodRegionWhenFieldTypeChanges() {
        MyBatisDatabaseTable stringTable = table(Types.VARCHAR);
        MyBatisGenerationPlan create = MyBatisMethodPlanIntegrator.integrate(
                getProject(),
                MyBatisGenerationPlanner.plan(
                        getProject(), root, List.of(bundle(stringTable))),
                method(stringTable, "findByName"));
        MyBatisGenerationCommandExecutor.execute(getProject(), root, create);
        MyBatisDatabaseTable integerTable = table(Types.INTEGER);

        MyBatisGenerationPlan update = MyBatisMethodPlanIntegrator.integrate(
                getProject(),
                MyBatisGenerationPlanner.plan(
                        getProject(), root, List.of(bundle(integerTable))),
                method(integerTable, "findByName"));

        assertFalse(describe(update), update.hasConflicts());
        assertTrue(proposed(update, MyBatisGenerationArtifactKind.MAPPER)
                .contains("java.lang.Integer name"));
        assertTrue(proposed(update, MyBatisGenerationArtifactKind.XML)
                .contains("jdbcType=INTEGER"));
    }

    public void testRejectsModifiedMethodRegion() throws Exception {
        MyBatisDatabaseTable table = table(Types.VARCHAR);
        MyBatisGenerationPlan create = MyBatisMethodPlanIntegrator.integrate(
                getProject(),
                MyBatisGenerationPlanner.plan(getProject(), root, List.of(bundle(table))),
                method(table, "findByName"));
        MyBatisGenerationCommandExecutor.execute(getProject(), root, create);
        VirtualFile mapper = file(create, MyBatisGenerationArtifactKind.MAPPER);
        write(mapper, VfsUtil.loadText(mapper).replace(
                "java.lang.String name", "java.lang.String changedName"));

        MyBatisGenerationPlan update = MyBatisMethodPlanIntegrator.integrate(
                getProject(),
                MyBatisGenerationPlanner.plan(getProject(), root, List.of(bundle(table))),
                method(table, "findByName"));

        MyBatisGenerationPlanEntry conflict = entry(
                update, MyBatisGenerationArtifactKind.MAPPER);
        assertEquals(MyBatisGenerationPlanStatus.CONFLICT, conflict.status());
        assertEquals(MyBatisSafeMergeConflictCode.METHOD_REGION_MODIFIED,
                conflict.conflictCode().orElseThrow());
    }

    public void testRejectsExistingManualJavaOrXmlDeclaration() throws Exception {
        MyBatisDatabaseTable table = table(Types.VARCHAR);
        MyBatisGenerationBundle bundle = bundle(table);
        for (MyBatisGeneratedArtifact artifact : bundle.artifacts()) {
            String text = artifact.content();
            if (artifact.kind() == MyBatisGenerationArtifactKind.MAPPER) {
                text = text.replace("\n}\n", "\n    void findByName();\n}\n");
            }
            if (artifact.kind() == MyBatisGenerationArtifactKind.XML) {
                text = text.replace("\n</mapper>\n",
                        "\n    <select id=\"findByName\">SELECT 1</select>\n</mapper>\n");
            }
            write(artifact.relativePath(), text);
        }

        MyBatisGenerationPlan integrated = MyBatisMethodPlanIntegrator.integrate(
                getProject(),
                MyBatisGenerationPlanner.plan(getProject(), root, List.of(bundle)),
                method(table, "findByName"));

        assertEquals(MyBatisSafeMergeConflictCode.METHOD_DECLARATION_EXISTS,
                entry(integrated, MyBatisGenerationArtifactKind.MAPPER)
                        .conflictCode().orElseThrow());
        assertEquals(MyBatisSafeMergeConflictCode.METHOD_DECLARATION_EXISTS,
                entry(integrated, MyBatisGenerationArtifactKind.XML)
                        .conflictCode().orElseThrow());
    }

    public void testRequiresMapperAndXmlTargets() {
        MyBatisDatabaseTable table = table(Types.VARCHAR);
        MyBatisGenerationBundle bundle = bundle(table);
        MyBatisGenerationBundle incomplete = new MyBatisGenerationBundle(
                bundle.entityName(),
                bundle.artifacts().stream()
                        .filter(artifact -> artifact.kind() != MyBatisGenerationArtifactKind.XML)
                        .toList());

        IllegalArgumentException failure = org.junit.Assert.assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisMethodPlanIntegrator.integrate(
                        getProject(),
                        MyBatisGenerationPlanner.plan(
                                getProject(), root, List.of(incomplete)),
                        method(table, "findByName")));

        assertTrue(failure.getMessage().contains("恰好包含一个 Mapper 与一个 XML"));
    }

    public void testRejectsMultiTablePlanInsteadOfWritingMethodToEveryMapper() {
        MyBatisDatabaseTable user = table(Types.VARCHAR);
        MyBatisDatabaseTable role = new MyBatisDatabaseTable(
                Optional.empty(),
                Optional.empty(),
                "role",
                List.of(column("id", Types.BIGINT, true, 0)));
        MyBatisGenerationPlan multiTable = MyBatisGenerationPlanner.plan(
                getProject(), root, List.of(bundle(user), bundle(role)));

        IllegalArgumentException failure = org.junit.Assert.assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisMethodPlanIntegrator.integrate(
                        getProject(), multiTable, method(user, "findByName")));

        assertTrue(failure.getMessage().contains("恰好包含一个 Mapper 与一个 XML"));
    }

    private MyBatisGenerationBundle bundle(MyBatisDatabaseTable table) {
        return MyBatisGenerationEngine.generate(new MyBatisGenerationRequest(
                "test",
                MyBatisSqlDialect.GENERIC,
                table,
                MyBatisGenerationConfiguration.standard("com.example")));
    }

    private MyBatisMethodGeneration method(MyBatisDatabaseTable table, String methodName) {
        MyBatisGenerationConfiguration configuration =
                MyBatisGenerationConfiguration.standard("com.example");
        MyBatisMethodSchema schema = MyBatisMethodSchema.from(table, configuration);
        MyBatisMethodParseResult parsed = MyBatisMethodNameParser.parse(methodName, schema);
        assertTrue(parsed instanceof MyBatisMethodParseResult.Success);
        MyBatisMethodQuery query = ((MyBatisMethodParseResult.Success) parsed).query();
        return MyBatisMethodSqlGenerator.generate(new MyBatisMethodGenerationRequest(
                schema,
                query,
                MyBatisSqlDialect.GENERIC,
                "com.example.entity.User",
                true,
                Set.of()));
    }

    private static MyBatisDatabaseTable table(int nameType) {
        return new MyBatisDatabaseTable(
                Optional.empty(),
                Optional.empty(),
                "user",
                List.of(
                        column("id", Types.BIGINT, true, 0),
                        column("name", nameType, false, 1)));
    }

    private static MyBatisDatabaseColumn column(
            String name,
            int type,
            boolean primary,
            int position) {
        return new MyBatisDatabaseColumn(
                name,
                "TYPE",
                type,
                true,
                primary,
                false,
                false,
                Optional.empty(),
                position);
    }

    private MyBatisGenerationPlanEntry entry(
            MyBatisGenerationPlan plan,
            MyBatisGenerationArtifactKind kind) {
        return plan.entries().stream()
                .filter(candidate -> candidate.artifact().kind() == kind)
                .findFirst()
                .orElseThrow();
    }

    private String proposed(
            MyBatisGenerationPlan plan,
            MyBatisGenerationArtifactKind kind) {
        return entry(plan, kind).proposedText().orElseThrow();
    }

    private VirtualFile file(
            MyBatisGenerationPlan plan,
            MyBatisGenerationArtifactKind kind) {
        return root.findFileByRelativePath(entry(plan, kind).artifact().relativePath());
    }

    private void write(String relativePath, String text) throws Exception {
        WriteAction.runAndWait(() -> {
            try {
                VfsUtil.saveText(VfsUtil.createDirectoryIfMissing(
                        root,
                        relativePath.substring(0, relativePath.lastIndexOf('/')))
                        .createChildData(this,
                                relativePath.substring(relativePath.lastIndexOf('/') + 1)), text);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private void write(VirtualFile file, String text) {
        WriteAction.runAndWait(() -> {
            try {
                VfsUtil.saveText(file, text);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private String describe(MyBatisGenerationPlan plan) {
        return plan.entries().stream()
                .filter(entry -> entry.status() == MyBatisGenerationPlanStatus.CONFLICT)
                .map(entry -> entry.artifact().relativePath() + ": "
                        + entry.message().orElse(""))
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
