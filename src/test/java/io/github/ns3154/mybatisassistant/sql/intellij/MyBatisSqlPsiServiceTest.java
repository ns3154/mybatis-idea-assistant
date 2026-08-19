package io.github.ns3154.mybatisassistant.sql.intellij;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import com.intellij.sql.dialects.generic.GenericDialect;
import com.intellij.sql.dialects.mysql.MysqlDialect;
import com.intellij.sql.psi.SqlSelectStatement;
import com.intellij.sql.psi.SqlReferenceExpression;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataProvider;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataService;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseRequest;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseSnapshot;
import io.github.ns3154.mybatisassistant.database.MyBatisMetadataFreshness;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisSourceMapping;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisTextRange;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class MyBatisSqlPsiServiceTest extends BasePlatformTestCase {
    public void testOptionalServiceBuildsMysqlPsiFromStaticStatement() {
        XmlTag statement = statement(configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find" databaseId="mysql">
                        select id, name from users where id = #{id}
                    </select>
                </mapper>
                """), "select");

        MyBatisSqlPsiResult.Ready ready = assertInstanceOf(
                service().parse(statement),
                MyBatisSqlPsiResult.Ready.class);

        assertEquals(MyBatisSqlDialect.MYSQL, ready.dialect());
        assertSame(MysqlDialect.INSTANCE, ready.psiFile().getLanguage());
        assertEquals(
                "\n        select id, name from users where id = ?\n    ",
                ready.psiFile().getText());
        assertNotNull(PsiTreeUtil.findChildOfType(ready.psiFile(), SqlSelectStatement.class));
    }

    public void testDynamicFragmentsStayContinuousAndMapPlaceholderToXml() {
        XmlFile file = configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find" databaseId="generic">
                        select id from users
                        <where>
                            <if test="name != null"> AND name = #{name}</if>
                        </where>
                    </select>
                </mapper>
                """);
        XmlTag statement = statement(file, "select");

        MyBatisSqlPsiResult.Ready ready = assertInstanceOf(
                service().parse(statement),
                MyBatisSqlPsiResult.Ready.class);

        assertEquals(MyBatisSqlDialect.GENERIC, ready.dialect());
        assertSame(GenericDialect.INSTANCE, ready.psiFile().getLanguage());
        assertTrue(ready.virtualSql().representativeOnly());
        assertTrue(ready.psiFile().getText().contains("WHERE name = ?"));
        assertNotNull(PsiTreeUtil.findChildOfType(ready.psiFile(), SqlSelectStatement.class));
        int parameter = ready.psiFile().getText().indexOf('?');
        MyBatisSourceMapping mapping = ready.virtualSql().mappedText().sourceMap()
                .sourceMappings(new MyBatisTextRange(parameter, parameter + 1))
                .getFirst();
        assertEquals(
                "#{name}",
                file.getText().substring(
                        mapping.sourceRange().range().startOffset(),
                        mapping.sourceRange().range().endOffset()));
    }

    public void testOptionalInjectorBuildsOneContinuousSqlFileAcrossDynamicHosts() {
        XmlFile file = configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find" databaseId="mysql">
                        select id from users
                        <where>
                            <if test="name != null"> AND name = #{name}</if>
                            <if test="age != null"> AND age &gt; #{age}</if>
                        </where>
                    </select>
                </mapper>
                """);
        XmlText firstHost = PsiTreeUtil.findChildrenOfType(file, XmlText.class).stream()
                .filter(text -> text.getText().contains("select id"))
                .findFirst()
                .orElseThrow();

        PsiFile injected = injectedFile(firstHost);

        assertSame(MysqlDialect.INSTANCE, injected.getLanguage());
        assertTrue(injected.getText().contains("select id from users"));
        assertEquals(
                "select id from users WHERE name = ? AND age > ?",
                injected.getText().replaceAll("\\s+", " ").trim());
        assertNotNull(PsiTreeUtil.findChildOfType(injected, SqlSelectStatement.class));
        assertEmpty(PsiTreeUtil.findChildrenOfType(injected, PsiErrorElement.class));
    }

    public void testUnsupportedDumbAndDeletedSourcesAreTyped() throws Exception {
        XmlFile file = configure("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="map" type="map"/>
                    <select id="find">select 1</select>
                </mapper>
                """);
        XmlTag resultMap = statement(file, "resultMap");
        XmlTag select = statement(file, "select");

        assertInstanceOf(service().parse(resultMap), MyBatisSqlPsiResult.UnsupportedSource.class);
        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> assertInstanceOf(
                service().parse(select),
                MyBatisSqlPsiResult.IndexNotReady.class));
        WriteCommandAction.runWriteCommandAction(getProject(), select::delete);
        assertInstanceOf(service().parse(select), MyBatisSqlPsiResult.SourceInvalid.class);
    }

    public void testSqlReferenceRolesExposeTableAndColumnKinds() {
        XmlTag statement = statement(configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        select u.id, u.name from public.users u where u.id = #{id}
                    </select>
                </mapper>
                """), "select");
        MyBatisSqlPsiResult.Ready ready = assertInstanceOf(
                service().parse(statement),
                MyBatisSqlPsiResult.Ready.class);
        List<String> roles = PsiTreeUtil.findChildrenOfType(
                ready.psiFile(),
                SqlReferenceExpression.class).stream()
                .map(reference -> reference.getText()
                        + ":" + reference.getReferenceElementType().getTargetKind().name())
                .toList();

        assertEquals(List.of(
                "u.id:COLUMN",
                "u:ANY",
                "u.name:COLUMN",
                "u:ANY",
                "public.users:TABLE",
                "public:ANY",
                "u.id:COLUMN",
                "u:ANY"), roles);
    }

    public void testSqlDialectLanguageIdsAreStable() {
        assertEquals(List.of(
                "GenericSQL",
                "MySQL",
                "PostgreSQL",
                "Oracle",
                "TSQL"), List.of(
                GenericDialect.INSTANCE.getID(),
                MysqlDialect.INSTANCE.getID(),
                com.intellij.sql.dialects.postgres.PgDialect.INSTANCE.getID(),
                com.intellij.sql.dialects.oracle.OraDialect.INSTANCE.getID(),
                com.intellij.sql.dialects.mssql.MsDialect.INSTANCE.getID()));
        assertSame(GenericDialect.INSTANCE,
                MyBatisSqlPsiService.language(MyBatisSqlDialect.SQLITE));
        assertSame(GenericDialect.INSTANCE,
                MyBatisSqlPsiService.language(MyBatisSqlDialect.DAMENG));
        assertSame(GenericDialect.INSTANCE,
                MyBatisSqlPsiService.language(MyBatisSqlDialect.H2));
    }

    public void testReadyMetadataSelectsDialectWhenStatementHasNoDatabaseId()
            throws Exception {
        warmDialects(MyBatisSqlDialect.MYSQL);
        XmlTag statement = statement(configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">select 1</select>
                </mapper>
                """), "select");

        MyBatisSqlPsiResult.Ready ready = assertInstanceOf(
                service().parse(statement),
                MyBatisSqlPsiResult.Ready.class);

        assertEquals(MyBatisSqlDialect.MYSQL, ready.dialect());
        assertSame(MysqlDialect.INSTANCE, ready.psiFile().getLanguage());
    }

    public void testMultipleReadyMetadataDialectsKeepGenericParsing()
            throws Exception {
        warmDialects(MyBatisSqlDialect.MYSQL, MyBatisSqlDialect.POSTGRESQL);
        XmlTag statement = statement(configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">select 1</select>
                </mapper>
                """), "select");

        MyBatisSqlPsiResult.Ready ready = assertInstanceOf(
                service().parse(statement),
                MyBatisSqlPsiResult.Ready.class);

        assertEquals(MyBatisSqlDialect.GENERIC, ready.dialect());
        assertSame(GenericDialect.INSTANCE, ready.psiFile().getLanguage());
    }

    private MyBatisSqlPsiService service() {
        MyBatisSqlPsiService service = MyBatisSqlPsiService.getInstance(getProject());
        assertNotNull("Database Tools 可用时必须从可选描述符注册 SQL PSI 服务", service);
        return service;
    }

    private void warmDialects(MyBatisSqlDialect... dialects) throws Exception {
        MyBatisDatabaseMetadataProvider.EP_NAME.getPoint().registerExtension(
                new MyBatisDatabaseMetadataProvider() {
                    @Override
                    public String id() {
                        return "dialect-fixture";
                    }

                    @Override
                    public List<MyBatisDatabaseSnapshot> load(
                            Project project,
                            MyBatisDatabaseRequest request,
                            ProgressIndicator indicator) {
                        return Arrays.stream(dialects)
                                .map(dialect -> new MyBatisDatabaseSnapshot(
                                        dialect.name(),
                                        dialect.name(),
                                        dialect,
                                        MyBatisMetadataFreshness.READY,
                                        1,
                                        List.of()))
                                .toList();
                    }
                },
                getTestRootDisposable());
        MyBatisDatabaseMetadataService.getInstance(getProject()).load(
                MyBatisDatabaseRequest.all(),
                Duration.ofSeconds(1)).get(2, TimeUnit.SECONDS);
    }

    private XmlFile configure(String text) {
        return (XmlFile) myFixture.configureByText("UserMapper.xml", text);
    }

    private PsiFile injectedFile(XmlText host) {
        List<Pair<PsiElement, TextRange>> files = InjectedLanguageManager
                .getInstance(getProject())
                .getInjectedPsiFiles(host);
        assertNotNull(files);
        assertSize(1, files);
        PsiElement element = files.getFirst().getFirst();
        return element instanceof PsiFile psiFile ? psiFile : element.getContainingFile();
    }

    private static XmlTag statement(XmlFile file, String name) {
        XmlTag root = file.getRootTag();
        assertNotNull(root);
        XmlTag tag = root.findFirstSubTag(name);
        assertNotNull(tag);
        return tag;
    }
}
