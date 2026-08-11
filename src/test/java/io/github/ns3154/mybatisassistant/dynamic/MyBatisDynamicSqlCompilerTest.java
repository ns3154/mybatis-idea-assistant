package io.github.ns3154.mybatisassistant.dynamic;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public final class MyBatisDynamicSqlCompilerTest extends BasePlatformTestCase {
    public void testCompilesTextEntityCdataAndSkipsCommentWithExactMappings() {
        XmlFile file = configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        select * from users
                        where age &lt; #{age}
                        <![CDATA[and score > 10]]>
                        <!-- hidden_sql -->
                    </select>
                </mapper>
                """);
        XmlTag statement = statement(file, "select");

        MyBatisDynamicSqlCompileResult.Compiled compiled = assertInstanceOf(
                MyBatisDynamicSqlCompiler.compile(statement),
                MyBatisDynamicSqlCompileResult.Compiled.class);
        MyBatisMappedText sql = compiled.program().staticSql().orElseThrow();

        assertTrue(sql.text().contains("where age < #{age}"));
        assertTrue(sql.text().contains("and score > 10"));
        assertFalse(sql.text().contains("CDATA"));
        assertFalse(sql.text().contains("hidden_sql"));

        int virtualEntity = sql.text().indexOf("age <") + "age ".length();
        int sourceEntity = file.getText().indexOf("&lt;");
        MyBatisSourceMapping entityMapping = sql.sourceMap().sourceMappings(
                new MyBatisTextRange(virtualEntity, virtualEntity + 1)).getFirst();
        assertEquals(MyBatisSourceMapKind.DECODED, entityMapping.kind());
        assertEquals(new MyBatisTextRange(sourceEntity, sourceEntity + 4),
                entityMapping.sourceRange().range());
        assertEquals(new MyBatisTextRange(virtualEntity, virtualEntity + 1),
                sql.sourceMap().virtualMappings(new MyBatisSourceRange(
                        file.getVirtualFile().getUrl(),
                        new MyBatisTextRange(sourceEntity + 1, sourceEntity + 2)))
                        .getFirst().virtualRange());

        int virtualCdata = sql.text().indexOf("score >") + "score ".length();
        int sourceCdata = file.getText().indexOf("score >") + "score ".length();
        assertEquals(new MyBatisTextRange(sourceCdata, sourceCdata + 1),
                sql.sourceMap().sourceMappings(
                        new MyBatisTextRange(virtualCdata, virtualCdata + 1))
                        .getFirst().sourceRange().range());
        int commentOffset = file.getText().indexOf("hidden_sql");
        assertEmpty(sql.sourceMap().virtualMappings(new MyBatisSourceRange(
                file.getVirtualFile().getUrl(),
                new MyBatisTextRange(commentOffset, commentOffset + 1))));
    }

    public void testNumericEntitiesMapWholeSourceToDecodedCodePoint() {
        XmlFile file = configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">select 1 where score &#62; 10 and marker = &#x1F600;</select>
                </mapper>
                """);

        MyBatisMappedText sql = assertInstanceOf(
                MyBatisDynamicSqlCompiler.compile(statement(file, "select")),
                MyBatisDynamicSqlCompileResult.Compiled.class)
                .program().staticSql().orElseThrow();

        assertTrue(sql.text().contains("score > 10"));
        assertTrue(sql.text().contains("😀"));
        int virtualEmoji = sql.text().indexOf("😀");
        int sourceEmoji = file.getText().indexOf("&#x1F600;");
        List<MyBatisSourceMapping> mapping = sql.sourceMap().sourceMappings(
                new MyBatisTextRange(virtualEmoji, virtualEmoji + 2));
        assertEquals(1, mapping.size());
        assertEquals(MyBatisSourceMapKind.DECODED, mapping.getFirst().kind());
        assertEquals(new MyBatisTextRange(sourceEmoji, sourceEmoji + 9),
                mapping.getFirst().sourceRange().range());
    }

    public void testIfCompilesToSymbolicNodeAndNoFalseStaticSql() {
        XmlFile file = configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">select * from users <if test="id != null">where id = #{id}</if></select>
                </mapper>
                """);

        MyBatisDynamicSqlCompileResult.Compiled compiled = assertInstanceOf(
                MyBatisDynamicSqlCompiler.compile(statement(file, "select")),
                MyBatisDynamicSqlCompileResult.Compiled.class);

        assertTrue(compiled.program().staticSql().isEmpty());
        assertEmpty(compiled.program().diagnostics());
        MyBatisIfNode ifNode = findFirst(compiled.program().root(), MyBatisIfNode.class);
        assertNotNull(ifNode);
        assertEquals("id != null", ifNode.condition().text());
        assertTrue(findFirst(ifNode.body(), MyBatisSqlTextNode.class)
                .content().text().contains("where id = #{id}"));
    }

    public void testChooseWhereSetAndTrimPreserveOrderedRuntimeSemantics() {
        XmlFile file = configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        select * from users
                        <where>
                            <choose>
                                <when test="name != null">and name = #{name}</when>
                                <when test="id != null">and id = #{id}</when>
                                <otherwise>and enabled = 1</otherwise>
                            </choose>
                        </where>
                        <trim prefix="ORDER BY" suffix="LIMIT 1"
                              prefixOverrides="," suffixOverrides=",">
                            created_at,
                        </trim>
                    </select>
                    <update id="update">
                        update users
                        <set><if test="name != null">name = #{name},</if></set>
                    </update>
                </mapper>
                """);

        MyBatisDynamicSqlProgram select = assertInstanceOf(
                MyBatisDynamicSqlCompiler.compile(statement(file, "select")),
                MyBatisDynamicSqlCompileResult.Compiled.class).program();
        MyBatisTrimNode where = findTrim(select.root(), MyBatisTrimKind.WHERE);
        assertEquals("WHERE", where.prefix());
        assertEquals("AND |OR ", where.prefixOverrides());
        MyBatisChooseNode choose = findFirst(where.body(), MyBatisChooseNode.class);
        assertNotNull(choose);
        assertEquals(List.of("name != null", "id != null"),
                choose.branches().stream().map(branch -> branch.condition().text()).toList());
        assertTrue(choose.otherwiseBranch().isPresent());

        MyBatisTrimNode trim = findTrim(select.root(), MyBatisTrimKind.TRIM);
        assertEquals("ORDER BY", trim.prefix());
        assertEquals("LIMIT 1", trim.suffix());
        assertEquals(",", trim.prefixOverrides());
        assertEquals(",", trim.suffixOverrides());

        MyBatisDynamicSqlProgram update = assertInstanceOf(
                MyBatisDynamicSqlCompiler.compile(statement(file, "update")),
                MyBatisDynamicSqlCompileResult.Compiled.class).program();
        MyBatisTrimNode set = findTrim(update.root(), MyBatisTrimKind.SET);
        assertEquals("SET", set.prefix());
        assertEquals(",", set.suffixOverrides());
    }

    public void testInvalidChooseAndMissingConditionProduceTypedDiagnostics() {
        XmlFile file = configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <choose>
                            <when>select 1</when>
                            <otherwise>select 2</otherwise>
                            <otherwise>select 3</otherwise>
                            <if test="ok">select 4</if>
                        </choose>
                    </select>
                </mapper>
                """);

        MyBatisDynamicSqlProgram program = assertInstanceOf(
                MyBatisDynamicSqlCompiler.compile(statement(file, "select")),
                MyBatisDynamicSqlCompileResult.Compiled.class).program();

        assertEquals(
                List.of(
                        MyBatisDynamicSqlDiagnosticCode.MISSING_REQUIRED_ATTRIBUTE,
                        MyBatisDynamicSqlDiagnosticCode.INVALID_CHOOSE_CHILD,
                        MyBatisDynamicSqlDiagnosticCode.DUPLICATE_OTHERWISE),
                program.diagnostics().stream().map(MyBatisDynamicSqlDiagnostic::code)
                        .sorted().toList());
    }

    public void testManyIndependentConditionsRemainLinearNodes() {
        StringBuilder xml = new StringBuilder(
                "<mapper namespace=\"com.example.UserMapper\"><select id=\"find\">select 1 ");
        int conditions = 200;
        for (int index = 0; index < conditions; index++) {
            xml.append("<if test=\"p")
                    .append(index)
                    .append(" != null\"> and c")
                    .append(index)
                    .append(" = #{p")
                    .append(index)
                    .append("}</if>");
        }
        xml.append("</select></mapper>");
        XmlFile file = configure(xml.toString());

        MyBatisDynamicSqlProgram program = assertInstanceOf(
                MyBatisDynamicSqlCompiler.compile(statement(file, "select")),
                MyBatisDynamicSqlCompileResult.Compiled.class).program();

        assertEquals(conditions, count(program.root(), MyBatisIfNode.class));
        assertTrue("符号化节点数必须与 XML 节点数线性，实际：" + countAll(program.root()),
                countAll(program.root()) <= conditions * 3 + 2);
    }

    public void testForeachAndBindPreserveLexicalBindingsAndRuntimeAttributes() {
        XmlFile file = configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <bind name="pattern" value="'%' + keyword + '%'"/>
                        select * from users where id in
                        <foreach collection="users" item="user" index="position"
                                 open="(" close=")" separator="," nullable="true">
                            #{user.id}
                        </foreach>
                    </select>
                </mapper>
                """);

        MyBatisDynamicSqlProgram program = assertInstanceOf(
                MyBatisDynamicSqlCompiler.compile(statement(file, "select")),
                MyBatisDynamicSqlCompileResult.Compiled.class).program();

        MyBatisBindNode bind = findFirst(program.root(), MyBatisBindNode.class);
        assertNotNull(bind);
        assertEquals("pattern", bind.binding().name());
        assertEquals(MyBatisDynamicSqlBindingKind.BIND, bind.binding().kind());
        assertEquals("'%' + keyword + '%'", bind.binding().initializer().orElseThrow().text());

        MyBatisForeachNode foreach = findFirst(program.root(), MyBatisForeachNode.class);
        assertNotNull(foreach);
        assertEquals("users", foreach.collection().text());
        assertEquals(List.of("user", "position"),
                foreach.scopedBindings().stream()
                        .map(MyBatisDynamicSqlBinding::name)
                        .toList());
        assertEquals(List.of(
                        MyBatisDynamicSqlBindingKind.FOREACH_ITEM,
                        MyBatisDynamicSqlBindingKind.FOREACH_INDEX),
                foreach.scopedBindings().stream()
                        .map(MyBatisDynamicSqlBinding::kind)
                        .toList());
        assertEquals("(", foreach.open());
        assertEquals(")", foreach.close());
        assertEquals(",", foreach.separator());
        assertEquals(Boolean.TRUE, foreach.nullable().orElseThrow());
        assertTrue(findFirst(foreach.body(), MyBatisSqlTextNode.class)
                .content().text().contains("#{user.id}"));

        List<MyBatisDynamicSqlNode> topLevel = assertInstanceOf(
                program.root(), MyBatisSqlSequenceNode.class).children();
        assertTrue(topLevel.indexOf(bind) < topLevel.indexOf(foreach));
    }

    public void testForeachDefaultsAndInvalidNullableAreTyped() {
        XmlFile file = configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <foreach collection="ids" nullable="sometimes">#{item}</foreach>
                    </select>
                </mapper>
                """);

        MyBatisDynamicSqlProgram program = assertInstanceOf(
                MyBatisDynamicSqlCompiler.compile(statement(file, "select")),
                MyBatisDynamicSqlCompileResult.Compiled.class).program();
        MyBatisForeachNode foreach = findFirst(program.root(), MyBatisForeachNode.class);
        assertNotNull(foreach);
        assertEquals(List.of("item", "index"),
                foreach.scopedBindings().stream()
                        .map(MyBatisDynamicSqlBinding::name)
                        .toList());
        assertTrue(foreach.nullable().isEmpty());
        assertEquals(List.of(MyBatisDynamicSqlDiagnosticCode.INVALID_BOOLEAN_ATTRIBUTE),
                program.diagnostics().stream().map(MyBatisDynamicSqlDiagnostic::code).toList());
    }

    public void testLocalIncludePropertyExpandsStaticSqlAndMapsToPropertyValue() {
        XmlFile file = configure("""
                <mapper namespace="com.example.UserMapper">
                    <sql id="Columns">${column}, name</sql>
                    <select id="find">
                        select <include refid="Columns"><property name="column" value="id"/></include>
                        from users
                    </select>
                </mapper>
                """);

        MyBatisDynamicSqlProgram program = assertInstanceOf(
                MyBatisDynamicSqlCompiler.compile(statement(file, "select")),
                MyBatisDynamicSqlCompileResult.Compiled.class).program();

        assertEmpty(program.diagnostics());
        MyBatisIncludeNode include = findFirst(program.root(), MyBatisIncludeNode.class);
        assertNotNull(include);
        assertEquals("com.example.UserMapper", include.namespace());
        assertEquals("Columns", include.fragmentId());
        assertEquals("column", include.properties().getFirst().name());
        MyBatisMappedText sql = program.staticSql().orElseThrow();
        assertTrue(sql.text().contains("select id, name"));
        int virtualId = sql.text().indexOf("id, name");
        int propertyValue = file.getText().indexOf("value=\"id\"") + "value=\"".length();
        MyBatisSourceMapping mapping = sql.sourceMap()
                .sourceMappings(new MyBatisTextRange(virtualId, virtualId + 2))
                .getFirst();
        assertEquals(MyBatisSourceMapKind.SYNTHETIC, mapping.kind());
        assertEquals(new MyBatisTextRange(propertyValue, propertyValue + 2),
                mapping.sourceRange().range());
    }

    public void testCrossNamespaceNestedIncludePropertiesExpandInOrder() {
        myFixture.addFileToProject("shared/SharedMapper.xml", """
                <mapper namespace="com.example.SharedMapper">
                    <sql id="Inner">${column}, name</sql>
                    <sql id="Outer">
                        <include refid="Inner">
                            <property name="column" value="${prefix}_id"/>
                        </include>
                    </sql>
                </mapper>
                """);
        XmlFile file = configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        select <include refid="com.example.SharedMapper.Outer">
                            <property name="prefix" value="user"/>
                        </include>
                        from users
                    </select>
                </mapper>
                """);

        MyBatisDynamicSqlProgram program = assertInstanceOf(
                MyBatisDynamicSqlCompiler.compile(statement(file, "select")),
                MyBatisDynamicSqlCompileResult.Compiled.class).program();

        assertEmpty(program.diagnostics());
        assertEquals(2, count(program.root(), MyBatisIncludeNode.class));
        MyBatisMappedText sql = program.staticSql().orElseThrow();
        assertTrue(sql.text(), sql.text().contains("user_id, name"));
        MyBatisSourceMapping mapping = sql.sourceMap().sourceMappings(new MyBatisTextRange(
                sql.text().indexOf("user_id"),
                sql.text().indexOf("user_id") + "user_id".length())).getFirst();
        assertEquals(MyBatisSourceMapKind.SYNTHETIC, mapping.kind());
        assertTrue(mapping.sourceRange().fileUrl().endsWith("/shared/SharedMapper.xml"));
    }

    public void testNestedIncludePropertyOverridesInheritedValue() {
        XmlFile file = configure("""
                <mapper namespace="com.example.UserMapper">
                    <sql id="Inner">${column}</sql>
                    <sql id="Outer">
                        <include refid="Inner">
                            <property name="column" value="local_id"/>
                        </include>
                    </sql>
                    <select id="find">
                        select <include refid="Outer">
                            <property name="column" value="parent_id"/>
                        </include>
                    </select>
                </mapper>
                """);

        MyBatisDynamicSqlProgram program = assertInstanceOf(
                MyBatisDynamicSqlCompiler.compile(statement(file, "select")),
                MyBatisDynamicSqlCompileResult.Compiled.class).program();

        assertEmpty(program.diagnostics());
        assertTrue(program.staticSql().orElseThrow().text().contains("local_id"));
        assertFalse(program.staticSql().orElseThrow().text().contains("parent_id"));
    }

    public void testIncludedRuntimePlaceholderIsPreservedWithoutPropertyDiagnostic() {
        XmlFile file = configure("""
                <mapper namespace="com.example.UserMapper">
                    <sql id="Filter">where tenant_id = ${tenantId}</sql>
                    <select id="find">select * from users <include refid="Filter"/></select>
                </mapper>
                """);

        MyBatisDynamicSqlProgram program = assertInstanceOf(
                MyBatisDynamicSqlCompiler.compile(statement(file, "select")),
                MyBatisDynamicSqlCompileResult.Compiled.class).program();

        assertEmpty(program.diagnostics());
        assertTrue(program.staticSql().orElseThrow().text().contains("${tenantId}"));
    }

    public void testDuplicateFragmentAndDynamicRefidStopConservatively() {
        myFixture.addFileToProject("one/Shared.xml", """
                <mapper namespace="com.example.SharedMapper"><sql id="Columns">id</sql></mapper>
                """);
        myFixture.addFileToProject("two/Shared.xml", """
                <mapper namespace="com.example.SharedMapper"><sql id="Columns">name</sql></mapper>
                """);
        XmlFile file = configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <include refid="com.example.SharedMapper.Columns"/>
                        <include refid="${fragment}"/>
                    </select>
                </mapper>
                """);

        MyBatisDynamicSqlProgram program = assertInstanceOf(
                MyBatisDynamicSqlCompiler.compile(statement(file, "select")),
                MyBatisDynamicSqlCompileResult.Compiled.class).program();

        assertTrue(program.staticSql().isEmpty());
        assertTrue(program.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == MyBatisDynamicSqlDiagnosticCode.INCLUDE_TARGET_NOT_UNIQUE));
        assertTrue(program.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == MyBatisDynamicSqlDiagnosticCode.INVALID_INCLUDE_REFID));
    }

    public void testIncludePropertyAndFragmentCyclesProduceTypedDiagnostics() {
        XmlFile propertyCycle = configure("""
                <mapper namespace="com.example.UserMapper">
                    <sql id="Columns">${a}</sql>
                    <select id="find"><include refid="Columns">
                        <property name="a" value="${b}"/>
                        <property name="b" value="${a}"/>
                    </include></select>
                </mapper>
                """);
        MyBatisDynamicSqlProgram propertyProgram = assertInstanceOf(
                MyBatisDynamicSqlCompiler.compile(statement(propertyCycle, "select")),
                MyBatisDynamicSqlCompileResult.Compiled.class).program();
        assertTrue(propertyProgram.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == MyBatisDynamicSqlDiagnosticCode.INCLUDE_PROPERTY_CYCLE));

        XmlFile includeCycle = configure("""
                <mapper namespace="com.example.CycleMapper">
                    <sql id="A"><include refid="B"/></sql>
                    <sql id="B"><include refid="A"/></sql>
                    <select id="find"><include refid="A"/></select>
                </mapper>
                """);
        MyBatisDynamicSqlProgram includeProgram = assertInstanceOf(
                MyBatisDynamicSqlCompiler.compile(statement(includeCycle, "select")),
                MyBatisDynamicSqlCompileResult.Compiled.class).program();
        MyBatisDynamicSqlDiagnostic cycle = includeProgram.diagnostics().stream()
                .filter(diagnostic -> diagnostic.code()
                        == MyBatisDynamicSqlDiagnosticCode.INCLUDE_CYCLE)
                .findFirst()
                .orElseThrow();
        assertTrue(cycle.message().contains("A"));
        assertTrue(cycle.message().contains("B"));
        assertEquals(3, count(includeProgram.root(), MyBatisIncludeNode.class));
    }

    public void testUnsupportedSourceDumbModeInvalidSourceAndCancellationAreTyped() throws Exception {
        XmlFile file = configure("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="map" type="map"/>
                    <select id="find">select 1</select>
                </mapper>
                """);
        XmlTag root = file.getRootTag();
        assertNotNull(root);
        assertInstanceOf(MyBatisDynamicSqlCompiler.compile(root.findFirstSubTag("resultMap")),
                MyBatisDynamicSqlCompileResult.UnsupportedSource.class);
        XmlTag statement = root.findFirstSubTag("select");
        assertNotNull(statement);
        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> assertInstanceOf(
                MyBatisDynamicSqlCompiler.compile(statement),
                MyBatisDynamicSqlCompileResult.IndexNotReady.class));

        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return MyBatisDynamicSqlCompiler.compile(statement);
                    },
                    indicator);
            fail("取消后的动态 SQL 编译必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是正常控制流，编译器不得吞掉。
        }

        WriteCommandAction.runWriteCommandAction(getProject(), statement::delete);
        assertInstanceOf(MyBatisDynamicSqlCompiler.compile(statement),
                MyBatisDynamicSqlCompileResult.SourceInvalid.class);
    }

    private XmlFile configure(String text) {
        return (XmlFile) myFixture.configureByText("UserMapper.xml", text);
    }

    private static XmlTag statement(XmlFile file, String name) {
        XmlTag root = file.getRootTag();
        assertNotNull(root);
        XmlTag statement = root.findFirstSubTag(name);
        assertNotNull(statement);
        return statement;
    }

    private static MyBatisTrimNode findTrim(
            MyBatisDynamicSqlNode root,
            MyBatisTrimKind kind) {
        MyBatisTrimNode result = findAll(root, MyBatisTrimNode.class).stream()
                .filter(node -> node.kind() == kind)
                .findFirst()
                .orElse(null);
        assertNotNull(result);
        return result;
    }

    private static <T extends MyBatisDynamicSqlNode> T findFirst(
            MyBatisDynamicSqlNode root,
            Class<T> type) {
        return findAll(root, type).stream().findFirst().orElse(null);
    }

    private static <T extends MyBatisDynamicSqlNode> List<T> findAll(
            MyBatisDynamicSqlNode root,
            Class<T> type) {
        java.util.ArrayList<T> result = new java.util.ArrayList<>();
        visit(root, node -> {
            if (type.isInstance(node)) {
                result.add(type.cast(node));
            }
        });
        return List.copyOf(result);
    }

    private static int count(MyBatisDynamicSqlNode root, Class<?> type) {
        int[] count = {0};
        visit(root, node -> {
            if (type.isInstance(node)) {
                count[0]++;
            }
        });
        return count[0];
    }

    private static int countAll(MyBatisDynamicSqlNode root) {
        int[] count = {0};
        visit(root, ignored -> count[0]++);
        return count[0];
    }

    private static void visit(
            MyBatisDynamicSqlNode node,
            java.util.function.Consumer<MyBatisDynamicSqlNode> consumer) {
        consumer.accept(node);
        switch (node) {
            case MyBatisSqlSequenceNode sequence -> sequence.children()
                    .forEach(child -> visit(child, consumer));
            case MyBatisIfNode conditional -> visit(conditional.body(), consumer);
            case MyBatisChooseNode choose -> {
                choose.branches().forEach(branch -> visit(branch.body(), consumer));
                choose.otherwiseBranch().ifPresent(child -> visit(child, consumer));
            }
            case MyBatisTrimNode trim -> visit(trim.body(), consumer);
            case MyBatisForeachNode foreach -> visit(foreach.body(), consumer);
            case MyBatisIncludeNode include -> visit(include.expandedBody(), consumer);
            case MyBatisBindNode ignored -> {
                // bind 仅声明变量，没有子节点。
            }
            case MyBatisSqlTextNode ignored -> {
                // 文本节点没有子节点。
            }
        }
    }
}
