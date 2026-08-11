package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeHighlighting.HighlightDisplayLevel;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.profile.codeInspection.InspectionProfileManager;

import java.util.List;
import java.util.Map;

public final class MyBatisXmlInspectionsTest extends BasePlatformTestCase {
    private static final String INVALID_NAMESPACE = "MyBatisInvalidNamespace";
    private static final String DUPLICATE_STATEMENT = "MyBatisDuplicateStatement";
    private static final String UNUSED_STATEMENT = "MyBatisUnusedStatement";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        for (String shortName : List.of(
                INVALID_NAMESPACE,
                DUPLICATE_STATEMENT,
                UNUSED_STATEMENT)) {
            myFixture.enableInspections(registeredExtension(shortName).instantiateTool());
        }
    }

    public void testInspectionsAreRegisteredWithTruthfulDefaultsAndDescriptions() {
        Map<String, RegistrationExpectation> expectations = Map.of(
                INVALID_NAMESPACE,
                new RegistrationExpectation(
                        false,
                        "namespace 未解析到 Mapper 接口",
                        MyBatisInvalidNamespaceInspection.class,
                        "默认关闭"),
                DUPLICATE_STATEMENT,
                new RegistrationExpectation(
                        true,
                        "重复的 Mapper XML statement",
                        MyBatisDuplicateStatementInspection.class,
                        "不同 databaseId"),
                UNUSED_STATEMENT,
                new RegistrationExpectation(
                        false,
                        "statement 未找到 Mapper 方法",
                        MyBatisUnusedStatementInspection.class,
                        "默认关闭"));

        for (Map.Entry<String, RegistrationExpectation> entry : expectations.entrySet()) {
            LocalInspectionEP extension = registeredExtension(entry.getKey());
            RegistrationExpectation expected = entry.getValue();
            InspectionProfileEntry tool = extension.instantiateTool();
            assertEquals("XML", extension.language);
            assertEquals(expected.enabledByDefault(), extension.enabledByDefault);
            assertEquals(HighlightDisplayLevel.WARNING, extension.getDefaultLevel());
            assertEquals(expected.displayName(), extension.getDisplayName());
            assertEquals("MyBatis", extension.getGroupDisplayName());
            assertEquals(expected.implementationClass().getName(), extension.implementationClass);
            assertEquals(expected.implementationClass(), tool.getClass());
            InspectionToolWrapper<?, ?> profileTool = InspectionProfileManager
                    .getInstance(getProject())
                    .getCurrentProfile()
                    .getInspectionTool(entry.getKey(), getProject());
            assertNotNull(profileTool);
            String description = profileTool.loadDescription();
            assertNotNull(description);
            assertTrue(description, description.contains(expected.descriptionEvidence()));
        }
    }

    public void testInvalidNamespaceReportsOnlyUnresolvedExactMapperInterface() {
        addJava("com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper { Object findAll(); }
                """);
        configureXml("""
                <mapper namespace="com.wrong.UserMapper">
                    <select id="findAll">select 1</select>
                </mapper>
                """);

        List<HighlightInfo> warnings = warnings(INVALID_NAMESPACE);

        assertSize(1, warnings);
        assertEquals(HighlightSeverity.WARNING, warnings.getFirst().getSeverity());
        assertEquals(
                "未找到 namespace 对应的 Java Mapper 接口：com.wrong.UserMapper",
                warnings.getFirst().getDescription());

        configureXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findAll">select 1</select>
                </mapper>
                """);
        assertEmpty(warnings(INVALID_NAMESPACE));
    }

    public void testInvalidNamespaceSkipsDynamicValueButRejectsOrdinaryClass() {
        addJava("com/example/UserMapper.java", """
                package com.example;
                public final class UserMapper {}
                """);
        configureXml("""
                <mapper namespace="com.example.UserMapper"/>
                """);
        assertSize(1, warnings(INVALID_NAMESPACE));

        configureXml("""
                <mapper namespace="${mapper.namespace}"/>
                """);
        assertEmpty(warnings(INVALID_NAMESPACE));
    }

    public void testDuplicateStatementMatchesNamespaceIdAndDatabaseId() {
        addJava("com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper { Object findAll(); }
                """);
        myFixture.addFileToProject("src/main/resources/mapper/OtherMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="findAll">select 1</select>
                </mapper>
                """);
        configureXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findAll">select 2</select>
                </mapper>
                """);

        List<HighlightInfo> warnings = warnings(DUPLICATE_STATEMENT);

        assertSize(1, warnings);
        assertEquals(
                "重复的 MyBatis statement：com.example.UserMapper.findAll"
                        + "（databaseId：默认，共 2 个）",
                warnings.getFirst().getDescription());
    }

    public void testDifferentOrDynamicDatabaseIdDoesNotReportDuplicate() {
        addJava("com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper { Object findAll(); }
                """);
        myFixture.addFileToProject("src/main/resources/mapper/MysqlMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="findAll" databaseId="mysql">select 1</select>
                </mapper>
                """);
        configureXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findAll" databaseId="postgres">select 2</select>
                    <select id="findAll" databaseId="${db}">select 3</select>
                </mapper>
                """);

        assertEmpty(warnings(DUPLICATE_STATEMENT));
    }

    public void testUnusedStatementReportsOnlyMissingMapperMethod() {
        addJava("com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper extends BaseMapper {
                    Object findAll();
                }
                interface BaseMapper {
                    Object inherited();
                }
                """);
        configureXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findAll">select 1</select>
                    <select id="inherited">select 2</select>
                    <select id="<caret>orphaned">select 3</select>
                </mapper>
                """);

        List<HighlightInfo> warnings = warnings(UNUSED_STATEMENT);

        assertSize(1, warnings);
        assertEquals(
                "未找到对应的 Java Mapper 方法：com.example.UserMapper.orphaned",
                warnings.getFirst().getDescription());

        IntentionAction action = myFixture.getAvailableIntentions().stream()
                .filter(candidate -> "定位 Mapper 接口（com.example.UserMapper）"
                        .equals(candidate.getText()))
                .findFirst()
                .orElseThrow();
        myFixture.launchAction(action);
        assertEquals(
                "UserMapper.java",
                FileEditorManager.getInstance(getProject()).getSelectedFiles()[0].getName());
    }

    public void testUnusedStatementSkipsUnresolvedNamespaceAndDynamicId() {
        configureXml("""
                <mapper namespace="com.missing.UserMapper">
                    <select id="orphaned">select 1</select>
                    <select id="${dynamicId}">select 2</select>
                </mapper>
                """);

        assertEmpty(warnings(UNUSED_STATEMENT));
    }

    public void testUnsavedIdChangeRefreshesDuplicateInspection() {
        addJava("com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper {
                    Object first();
                    Object second();
                }
                """);
        XmlFile xmlFile = configureXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="first">select 1</select>
                    <select id="second">select 2</select>
                </mapper>
                """);
        assertEmpty(warnings(DUPLICATE_STATEMENT));

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            XmlTag root = xmlFile.getRootTag();
            assertNotNull(root);
            root.getSubTags()[1].setAttribute("id", "first");
            PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        });

        assertSize(2, warnings(DUPLICATE_STATEMENT));
    }

    public void testDumbModeIsSilentAndCancellationPropagates() throws Throwable {
        addJava("com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper { Object findAll(); }
                """);
        XmlFile xmlFile = configureXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findAll">select 1</select>
                </mapper>
                """);
        XmlTag root = xmlFile.getRootTag();
        assertNotNull(root);
        XmlTag statement = root.findFirstSubTag("select");
        assertNotNull(statement);

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
            assertEmpty(inspect(new MyBatisInvalidNamespaceInspection(), xmlFile, root)
                    .getResults());
            assertEmpty(inspect(new MyBatisDuplicateStatementInspection(), xmlFile, statement)
                    .getResults());
            assertEmpty(inspect(new MyBatisUnusedStatementInspection(), xmlFile, statement)
                    .getResults());
        });

        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return inspect(
                                new MyBatisDuplicateStatementInspection(),
                                xmlFile,
                                statement).getResults();
                    },
                    indicator);
            fail("取消后的 XML Inspection 必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是平台正常控制流，不能转成空诊断。
        }
    }

    private void addJava(String relativePath, String source) {
        myFixture.addFileToProject("src/main/java/" + relativePath, source);
    }

    private XmlFile configureXml(String source) {
        return (XmlFile) myFixture.configureByText("UserMapper.xml", source);
    }

    private List<HighlightInfo> warnings(String shortName) {
        return myFixture.doHighlighting(HighlightSeverity.WARNING).stream()
                .filter(info -> shortName.equals(info.getInspectionToolId()))
                .toList();
    }

    private ProblemsHolder inspect(
            LocalInspectionTool inspection,
            XmlFile file,
            XmlTag tag) {
        ProblemsHolder holder = new ProblemsHolder(
                InspectionManager.getInstance(getProject()),
                file,
                true);
        PsiElementVisitor visitor = inspection.buildVisitor(holder, true);
        tag.accept(visitor);
        return holder;
    }

    private LocalInspectionEP registeredExtension(String shortName) {
        List<LocalInspectionEP> matches = LocalInspectionEP.LOCAL_INSPECTION
                .getExtensionList()
                .stream()
                .filter(extension -> shortName.equals(extension.shortName))
                .toList();
        assertSize(1, matches);
        return matches.getFirst();
    }

    private record RegistrationExpectation(
            boolean enabledByDefault,
            String displayName,
            Class<? extends LocalInspectionTool> implementationClass,
            String descriptionEvidence) {
    }
}
