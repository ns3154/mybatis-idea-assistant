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
import java.util.concurrent.atomic.AtomicInteger;

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

    public void testCompletesOnlyColumnsFromUniqueResultMapTable() throws Exception {
        warmMetadata();
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="UserMap" type="com.example.User">
                        <result property="name" column="na<caret>"/>
                    </resultMap>
                    <select id="find" resultMap="UserMap">
                        select id, name, nickname from users
                    </select>
                </mapper>
                """);

        List<String> lookups = lookupStrings();

        assertContainsElements(lookups, "name", "nickname");
        assertFalse(lookups.contains("user_id"));

        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="UserMap" type="com.example.User">
                        <result property="name" column="<caret>"/>
                    </resultMap>
                    <select id="find" resultMap="UserMap">select id, name from users</select>
                </mapper>
                """);
        assertContainsElements(lookupStrings(), "id", "name", "nickname");
    }

    public void testResultMapColumnCompletionRejectsNestedAmbiguousAndDuplicateTargets()
            throws Exception {
        warmMetadata();
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="UserMap" type="com.example.User">
                        <association property="profile">
                            <result property="name" column="na<caret>"/>
                        </association>
                    </resultMap>
                    <select id="find" resultMap="UserMap">
                        select id, name from users
                    </select>
                </mapper>
                """);
        assertFalse(lookupStrings().contains("nickname"));

        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="UserMap" type="com.example.User">
                        <association property="profile" column="{foreignId=i<caret>d}"/>
                    </resultMap>
                    <select id="find" resultMap="UserMap">select id, name from users</select>
                </mapper>
                """);
        assertFalse(lookupStrings().contains("nickname"));

        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="UserMap" type="com.example.User">
                        <result property="name" column="na<caret>"/>
                    </resultMap>
                    <select id="find" resultMap="UserMap">
                        select u.name, o.user_id from users u
                        join user_sessions o on u.id = o.user_id
                    </select>
                </mapper>
                """);
        assertFalse(lookupStrings().contains("nickname"));

        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="UserMap" type="com.example.User">
                        <result property="name" column="na<caret>"/>
                    </resultMap>
                    <resultMap id="UserMap" type="com.example.User"/>
                    <select id="find" resultMap="UserMap">select id, name from users</select>
                </mapper>
                """);
        assertFalse(lookupStrings().contains("nickname"));
    }

    public void testResultMapColumnCompletionNeverStartsMetadataRefresh() {
        AtomicInteger loads = new AtomicInteger();
        MyBatisDatabaseMetadataProvider.EP_NAME.getPoint().registerExtension(
                new CountingProvider(loads),
                getTestRootDisposable());
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="UserMap" type="com.example.User">
                        <result property="name" column="na<caret>"/>
                    </resultMap>
                    <select id="find" resultMap="UserMap">
                        select id, name from users
                    </select>
                </mapper>
                """);

        lookupStrings();
        assertEquals(0, loads.get());
    }

    public void testResultMapColumnCompletionRequiresOneReadyTableCandidate()
            throws Exception {
        List<MyBatisDatabaseSnapshot> snapshots = List.of(
                snapshot("main", "Main", MyBatisMetadataFreshness.READY),
                snapshot("replica", "Replica", MyBatisMetadataFreshness.READY));
        MyBatisDatabaseMetadataProvider.EP_NAME.getPoint().registerExtension(
                new SnapshotProvider(snapshots),
                getTestRootDisposable());
        MyBatisDatabaseMetadataService.getInstance(getProject()).load(
                MyBatisDatabaseRequest.all(),
                Duration.ofSeconds(1)).get(2, TimeUnit.SECONDS);
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="UserMap" type="com.example.User">
                        <result property="name" column="nick<caret>"/>
                    </resultMap>
                    <select id="find" resultMap="UserMap">select id from users</select>
                </mapper>
                """);

        assertFalse(lookupStrings().contains("nickname"));
    }

    public void testResultMapColumnCompletionRejectsLoadingSnapshot() throws Exception {
        List<MyBatisDatabaseSnapshot> snapshots =
                List.of(snapshot("main", "Main", MyBatisMetadataFreshness.LOADING));
        MyBatisDatabaseMetadataProvider.EP_NAME.getPoint().registerExtension(
                new SnapshotProvider(snapshots),
                getTestRootDisposable());
        MyBatisDatabaseMetadataService.getInstance(getProject()).load(
                MyBatisDatabaseRequest.all(),
                Duration.ofSeconds(1)).get(2, TimeUnit.SECONDS);
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="UserMap" type="com.example.User">
                        <result property="name" column="nick<caret>"/>
                    </resultMap>
                    <select id="find" resultMap="UserMap">select id from users</select>
                </mapper>
                """);

        assertFalse(lookupStrings().contains("nickname"));
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

    private static final class CountingProvider implements MyBatisDatabaseMetadataProvider {
        private final AtomicInteger loads;

        private CountingProvider(AtomicInteger loads) {
            this.loads = loads;
        }

        @Override
        public String id() {
            return "completion-counting-fixture";
        }

        @Override
        public List<MyBatisDatabaseSnapshot> load(
                Project project,
                MyBatisDatabaseRequest request,
                ProgressIndicator indicator) {
            loads.incrementAndGet();
            return List.of();
        }
    }

    private static MyBatisDatabaseSnapshot snapshot(
            String dataSourceId,
            String displayName,
            MyBatisMetadataFreshness freshness) {
        return new MyBatisDatabaseSnapshot(
                dataSourceId,
                displayName,
                MyBatisSqlDialect.MYSQL,
                freshness,
                1,
                List.of(FakeProvider.table("users", "id", "name", "nickname")));
    }

    private record SnapshotProvider(List<MyBatisDatabaseSnapshot> snapshots)
            implements MyBatisDatabaseMetadataProvider {
        private SnapshotProvider {
            snapshots = List.copyOf(snapshots);
        }

        @Override
        public String id() {
            return "completion-snapshots-" + snapshots.getFirst().dataSourceId();
        }

        @Override
        public List<MyBatisDatabaseSnapshot> load(
                Project project,
                MyBatisDatabaseRequest request,
                ProgressIndicator indicator) {
            return snapshots;
        }
    }
}
