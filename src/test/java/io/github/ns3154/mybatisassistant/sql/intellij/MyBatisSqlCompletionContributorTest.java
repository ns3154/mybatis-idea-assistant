package io.github.ns3154.mybatisassistant.sql.intellij;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataProvider;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataService;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseRequest;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseSnapshot;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisMetadataFreshness;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;

import java.sql.Types;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class MyBatisSqlCompletionContributorTest extends BasePlatformTestCase {
    public void testContributorIsRegisteredForXmlHostAndBuildsInjectedGenericSql() {
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find" databaseId="generic">
                        select * from us<caret>
                    </select>
                </mapper>
                """);
        PsiFile file = myFixture.getFile();
        assertEquals("GenericSQL", file.getLanguage().getID());
        assertTrue(CompletionContributor.forLanguage(
                        com.intellij.lang.xml.XMLLanguage.INSTANCE).stream()
                .anyMatch(MyBatisSqlCompletionContributor.class::isInstance));
    }

    public void testCompletesCommonKeywordsFunctionsAndStatementAliasWithoutMetadata() {
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        select <caret> from users u
                    </select>
                </mapper>
                """);

        List<String> lookups = lookupStrings();

        assertContainsElements(lookups, "SELECT", "WHERE", "COUNT", "COALESCE", "u");
    }

    public void testCompletesTablesFromReadySnapshotInGenericSql() throws Exception {
        warmMetadata();
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        select * from us<caret>
                    </select>
                </mapper>
                """);

        List<String> lookups = lookupStrings();

        assertContainsElements(lookups, "users", "user_sessions");
    }

    public void testCompletesColumnsInMysqlDialectWithoutBlockingForIo() throws Exception {
        warmMetadata();
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find" databaseId="mysql">
                        select na<caret> from users
                    </select>
                </mapper>
                """);

        List<String> lookups = lookupStrings();

        assertContainsElements(lookups, "name", "nickname");
    }

    public void testCompletesColumnsAfterDynamicTag() throws Exception {
        warmMetadata();
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        select id from users
                        <where>
                            <if test="enabled">AND na<caret> IS NOT NULL</if>
                        </where>
                    </select>
                </mapper>
                """);

        List<String> lookups = lookupStrings();

        assertContainsElements(lookups, "name", "nickname");
    }

    public void testDoesNotOfferDatabaseMetadataOutsideMyBatisInjection() throws Exception {
        warmMetadata();
        myFixture.configureByText("Plain.java", """
                class Plain {
                    String value = "us<caret>";
                }
                """);

        List<String> lookups = lookupStrings();

        assertFalse(lookups.contains("users"));
        assertFalse(lookups.contains("user_sessions"));
    }

    public void testDoesNotOfferSqlCandidatesInsideStatementAttributes() throws Exception {
        warmMetadata();
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="fi<caret>nd">select * from users</select>
                </mapper>
                """);

        List<String> lookups = lookupStrings();

        assertFalse(lookups.contains("SELECT"));
        assertFalse(lookups.contains("users"));
    }

    private void warmMetadata() throws Exception {
        MyBatisDatabaseMetadataProvider.EP_NAME.getPoint().registerExtension(
                new FakeProvider(),
                getTestRootDisposable());
        MyBatisDatabaseMetadataService.getInstance(getProject()).load(
                MyBatisDatabaseRequest.all(),
                Duration.ofSeconds(1)).get(2, TimeUnit.SECONDS);
    }

    private List<String> lookupStrings() {
        LookupElement[] elements = myFixture.completeBasic();
        if (elements == null) {
            return List.of();
        }
        return Arrays.stream(elements).map(LookupElement::getLookupString).toList();
    }

    private static final class FakeProvider implements MyBatisDatabaseMetadataProvider {
        @Override
        public String id() {
            return "completion-fixture";
        }

        @Override
        public List<MyBatisDatabaseSnapshot> load(
                Project project,
                MyBatisDatabaseRequest request,
                ProgressIndicator indicator) {
            return List.of(new MyBatisDatabaseSnapshot(
                    "main",
                    "Main",
                    MyBatisSqlDialect.MYSQL,
                    MyBatisMetadataFreshness.READY,
                    1,
                    List.of(
                            table("users", "id", "name", "nickname"),
                            table("user_sessions", "id", "user_id"))));
        }

        private static MyBatisDatabaseTable table(String name, String... columns) {
            List<MyBatisDatabaseColumn> metadata = java.util.stream.IntStream
                    .range(0, columns.length)
                    .mapToObj(index -> new MyBatisDatabaseColumn(
                            columns[index],
                            "VARCHAR",
                            Types.VARCHAR,
                            true,
                            false,
                            false,
                            index + 1))
                    .toList();
            return new MyBatisDatabaseTable(
                    Optional.empty(),
                    Optional.of("public"),
                    name,
                    metadata);
        }
    }
}
