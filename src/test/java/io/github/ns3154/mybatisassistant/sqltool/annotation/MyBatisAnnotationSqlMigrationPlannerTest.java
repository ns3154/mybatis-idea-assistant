package io.github.ns3154.mybatisassistant.sqltool.annotation;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.ui.TestDialog;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationArtifactKind;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationCommandExecutor;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlan;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlanEntry;
import io.github.ns3154.mybatisassistant.sqltool.intellij.MyBatisAnnotationSqlMigrateAction;

public final class MyBatisAnnotationSqlMigrationPlannerTest extends BasePlatformTestCase {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addAnnotationStubs();
    }

    public void testSelectMigrationRemovesAnnotationAndAddsEscapedResultTypedStatement() {
        Fixture fixture = fixture("""
                package com.example;
                import org.apache.ibatis.annotations.Select;
                public interface UserMapper {
                    @Select("select * from users where score < #{max} and flags & 1 = 1")
                    User find(long max);
                }
                final class User {}
                """, emptyMapper());

        MyBatisAnnotationSqlMigrationPlanner.Result.Success success = success(fixture);
        String javaText = entry(success.plan(), MyBatisGenerationArtifactKind.MAPPER)
                .proposedText().orElseThrow();
        String xmlText = entry(success.plan(), MyBatisGenerationArtifactKind.XML)
                .proposedText().orElseThrow();

        assertFalse(javaText.contains("@Select"));
        assertTrue(javaText.contains("User find(long max);"));
        assertTrue(xmlText.contains("<select id=\"find\" resultType=\"com.example.User\">"));
        assertTrue(xmlText.contains("score &lt; #{max} and flags &amp; 1 = 1"));
        assertTrue(xmlText.contains("</select>\n</mapper>"));
    }

    public void testStringArrayUsesMyBatisSpaceJoinSemantics() {
        Fixture fixture = fixture("""
                package com.example;
                import org.apache.ibatis.annotations.Select;
                public interface UserMapper {
                    @Select({"select *", "from users", "where id = #{id}"})
                    User[] find(long id);
                }
                final class User {}
                """, emptyMapper());

        String xmlText = entry(success(fixture).plan(), MyBatisGenerationArtifactKind.XML)
                .proposedText().orElseThrow();

        assertTrue(xmlText.contains("select * from users where id = #{id}"));
        assertTrue(xmlText.contains("resultType=\"com.example.User\""));
    }

    public void testInsertMigrationDoesNotInventSelectResultType() {
        Fixture fixture = fixture("""
                package com.example;
                import org.apache.ibatis.annotations.Insert;
                public interface UserMapper {
                    @Insert("insert into users(name) values(#{name})")
                    int insert(String name);
                }
                """, emptyMapper());

        String xmlText = entry(success(fixture).plan(), MyBatisGenerationArtifactKind.XML)
                .proposedText().orElseThrow();

        assertTrue(xmlText.contains("<insert id=\"insert\">"));
        assertFalse(xmlText.contains("resultType="));
    }

    public void testExistingStatementAndMultipleMapperXmlAreRejected() {
        Fixture existing = fixture("""
                package com.example;
                import org.apache.ibatis.annotations.Select;
                public interface UserMapper {
                    @Select("select 1") int find();
                }
                """, """
                <mapper namespace="com.example.UserMapper">
                    <select id="find">select 2</select>
                </mapper>
                """);
        assertFailure(existing,
                MyBatisAnnotationSqlMigrationPlanner.FailureCode.STATEMENT_ALREADY_EXISTS);

        Fixture ambiguous = fixture("""
                package com.example.other;
                import org.apache.ibatis.annotations.Select;
                public interface OtherMapper {
                    @Select("select 1") int find();
                }
                """, """
                <mapper namespace="com.example.other.OtherMapper"></mapper>
                """, "other/OtherMapper-copy.xml");
        myFixture.addFileToProject("src/main/resources/other/OtherMapper-second.xml", """
                <mapper namespace="com.example.other.OtherMapper"></mapper>
                """);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertFailure(ambiguous,
                MyBatisAnnotationSqlMigrationPlanner.FailureCode.AMBIGUOUS_MAPPER_XML);
    }

    public void testOverloadOptionsContainerAndDynamicScriptAreRejected() {
        Fixture overload = fixture("""
                package com.example.overload;
                import org.apache.ibatis.annotations.Select;
                public interface OverloadMapper {
                    @Select("select 1") int find(long id);
                    int find(String id);
                }
                """, mapper("com.example.overload.OverloadMapper"));
        assertFailure(overload,
                MyBatisAnnotationSqlMigrationPlanner.FailureCode.OVERLOADED_METHOD);

        Fixture options = fixture("""
                package com.example.options;
                import org.apache.ibatis.annotations.Options;
                import org.apache.ibatis.annotations.Select;
                public interface OptionsMapper {
                    @Options(useCache = false)
                    @Select("select 1") int find();
                }
                """, mapper("com.example.options.OptionsMapper"));
        assertFailure(options,
                MyBatisAnnotationSqlMigrationPlanner.FailureCode.UNSUPPORTED_ANNOTATION);

        Fixture container = fixture("""
                package com.example.container;
                import org.apache.ibatis.annotations.Select;
                public interface ContainerMapper {
                    @Select.List(@Select("select 1")) int find();
                }
                """, mapper("com.example.container.ContainerMapper"));
        assertFailure(container,
                MyBatisAnnotationSqlMigrationPlanner.FailureCode.UNSUPPORTED_ANNOTATION);

        Fixture script = fixture("""
                package com.example.script;
                import org.apache.ibatis.annotations.Select;
                public interface ScriptMapper {
                    @Select("<script>select 1</script>") int find();
                }
                """, mapper("com.example.script.ScriptMapper"));
        assertFailure(script,
                MyBatisAnnotationSqlMigrationPlanner.FailureCode.UNSUPPORTED_SQL);
    }

    public void testGenericUnknownReturnTypeIsRejected() {
        Fixture fixture = fixture("""
                package com.example.genericresult;
                import org.apache.ibatis.annotations.Select;
                public interface GenericMapper {
                    @Select("select 1") Page<User> find();
                }
                final class Page<T> {}
                final class User {}
                """, mapper("com.example.genericresult.GenericMapper"));

        assertFailure(fixture,
                MyBatisAnnotationSqlMigrationPlanner.FailureCode.UNSUPPORTED_RETURN_TYPE);
    }

    public void testCommonCollectionReturnTypeUsesElementResultType() {
        myFixture.addFileToProject("src/main/java/java/util/List.java", """
                package java.util;
                public interface List<T> {}
                """);
        Fixture fixture = fixture("""
                package com.example.collectionresult;
                import java.util.List;
                import org.apache.ibatis.annotations.Select;
                public interface CollectionMapper {
                    @Select("select * from users") List<User> find();
                }
                final class User {}
                """, mapper("com.example.collectionresult.CollectionMapper"));

        String xmlText = entry(success(fixture).plan(), MyBatisGenerationArtifactKind.XML)
                .proposedText().orElseThrow();

        assertTrue(xmlText.contains("resultType=\"com.example.collectionresult.User\""));
    }

    public void testChangeAfterPreviewStopsBothUpdates() {
        Fixture fixture = fixture("""
                package com.example.race;
                import org.apache.ibatis.annotations.Delete;
                public interface RaceMapper {
                    @Delete("delete from users where id = #{id}") int delete(long id);
                }
                """, mapper("com.example.race.RaceMapper"));
        MyBatisAnnotationSqlMigrationPlanner.Result.Success success = success(fixture);
        Document xmlDocument = FileDocumentManager.getInstance()
                .getDocument(fixture.xmlFile().getVirtualFile());
        assertNotNull(xmlDocument);
        WriteCommandAction.runWriteCommandAction(
                getProject(),
                () -> xmlDocument.insertString(0, "<!-- 用户并发修改 -->\n"));

        try {
            MyBatisGenerationCommandExecutor.execute(
                    getProject(),
                    fixture.projectRoot(),
                    success.plan(),
                    "迁移 MyBatis 注解 SQL 到 XML");
            fail("预览后 XML 变化必须阻止整个迁移");
        } catch (IllegalStateException failure) {
            assertTrue(failure.getMessage().contains("预览后文件内容已变化"));
        }
        assertTrue(currentText(fixture.javaFile().getVirtualFile()).contains("@Delete"));
        assertTrue(currentText(fixture.xmlFile().getVirtualFile())
                .startsWith("<!-- 用户并发修改 -->"));
    }

    public void testCancellationPropagatesBeforePlanning() {
        Fixture fixture = fixture("""
                package com.example.cancel;
                import org.apache.ibatis.annotations.Select;
                public interface CancelMapper {
                    @Select("select 1") int find();
                }
                """, mapper("com.example.cancel.CancelMapper"));
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();

        assertThrows(ProcessCanceledException.class, () -> ProgressManager.getInstance()
                .runProcess(() -> {
                    indicator.cancel();
                    return MyBatisAnnotationSqlMigrationPlanner.plan(
                            fixture.method(),
                            fixture.projectRoot());
                }, indicator));
    }

    public void testAtomicPlanAppliesAndUndoesBothFilesOnce() {
        Fixture fixture = fixture("""
                package com.example.atomic;
                import org.apache.ibatis.annotations.Update;
                public interface AtomicMapper {
                    @Update("update users set name = #{name} where id = #{id}")
                    int rename(long id, String name);
                }
                """, mapper("com.example.atomic.AtomicMapper"));
        MyBatisAnnotationSqlMigrationPlanner.Result.Success success = success(fixture);
        myFixture.configureFromExistingVirtualFile(fixture.javaFile().getVirtualFile());

        MyBatisGenerationCommandExecutor.execute(
                getProject(),
                fixture.projectRoot(),
                success.plan(),
                "迁移 MyBatis 注解 SQL 到 XML");

        assertEquals(entry(success.plan(), MyBatisGenerationArtifactKind.MAPPER)
                        .proposedText().orElseThrow(),
                currentText(fixture.javaFile().getVirtualFile()));
        assertEquals(entry(success.plan(), MyBatisGenerationArtifactKind.XML)
                        .proposedText().orElseThrow(),
                currentText(fixture.xmlFile().getVirtualFile()));

        FileEditor editor = FileEditorManager.getInstance(getProject()).getSelectedEditor();
        assertNotNull(editor);
        UndoManager undoManager = UndoManager.getInstance(getProject());
        assertTrue(undoManager.isUndoAvailable(editor));
        TestDialogManager.setTestDialog(TestDialog.OK, getTestRootDisposable());
        undoManager.undo(editor);

        assertEquals(entry(success.plan(), MyBatisGenerationArtifactKind.MAPPER)
                        .existingText().orElseThrow(),
                currentText(fixture.javaFile().getVirtualFile()));
        assertEquals(entry(success.plan(), MyBatisGenerationArtifactKind.XML)
                        .existingText().orElseThrow(),
                currentText(fixture.xmlFile().getVirtualFile()));
    }

    public void testActionIsRegistered() {
        assertInstanceOf(
                ActionManager.getInstance().getAction(MyBatisAnnotationSqlMigrateAction.ID),
                MyBatisAnnotationSqlMigrateAction.class);
    }

    private Fixture fixture(String javaSource, String xmlSource) {
        return fixture(javaSource, xmlSource, "mapper/Mapper-" + System.nanoTime() + ".xml");
    }

    private Fixture fixture(String javaSource, String xmlSource, String xmlPath) {
        PsiJavaFile javaFile = (PsiJavaFile) myFixture.addFileToProject(
                "src/main/java/fixture" + System.nanoTime() + "/Mapper.java",
                javaSource);
        PsiFile xmlFile = myFixture.addFileToProject(
                "src/main/resources/" + xmlPath,
                xmlSource);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        VirtualFile projectRoot = VfsUtilCore.getCommonAncestor(
                javaFile.getVirtualFile(),
                xmlFile.getVirtualFile());
        assertNotNull(projectRoot);
        PsiMethod method = javaFile.getClasses()[0].getMethods()[0];
        return new Fixture(javaFile, xmlFile, method, projectRoot);
    }

    private MyBatisAnnotationSqlMigrationPlanner.Result.Success success(Fixture fixture) {
        MyBatisAnnotationSqlMigrationPlanner.Result result =
                MyBatisAnnotationSqlMigrationPlanner.plan(
                        fixture.method(),
                        fixture.projectRoot());
        assertInstanceOf(result, MyBatisAnnotationSqlMigrationPlanner.Result.Success.class);
        return (MyBatisAnnotationSqlMigrationPlanner.Result.Success) result;
    }

    private void assertFailure(
            Fixture fixture,
            MyBatisAnnotationSqlMigrationPlanner.FailureCode code) {
        MyBatisAnnotationSqlMigrationPlanner.Result result =
                MyBatisAnnotationSqlMigrationPlanner.plan(
                        fixture.method(),
                        fixture.projectRoot());
        assertInstanceOf(result, MyBatisAnnotationSqlMigrationPlanner.Result.Failure.class);
        assertEquals(code,
                ((MyBatisAnnotationSqlMigrationPlanner.Result.Failure) result).code());
    }

    private static MyBatisGenerationPlanEntry entry(
            MyBatisGenerationPlan plan,
            MyBatisGenerationArtifactKind kind) {
        return plan.entries().stream()
                .filter(candidate -> candidate.artifact().kind() == kind)
                .findFirst()
                .orElseThrow();
    }

    private static String currentText(VirtualFile file) {
        Document document = FileDocumentManager.getInstance().getDocument(file);
        assertNotNull(document);
        return document.getText();
    }

    private static String emptyMapper() {
        return mapper("com.example.UserMapper");
    }

    private static String mapper(String namespace) {
        return "<mapper namespace=\"" + namespace + "\">\n</mapper>\n";
    }

    private void addAnnotationStubs() {
        for (String name : new String[]{"Select", "Insert", "Update", "Delete"}) {
            myFixture.addFileToProject(
                    "src/main/java/org/apache/ibatis/annotations/" + name + ".java",
                    "package org.apache.ibatis.annotations; public @interface " + name
                            + " { String[] value(); @interface List { " + name
                            + "[] value(); } }");
        }
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/annotations/Options.java",
                """
                        package org.apache.ibatis.annotations;
                        public @interface Options { boolean useCache() default true; }
                        """);
    }

    private record Fixture(
            PsiJavaFile javaFile,
            PsiFile xmlFile,
            PsiMethod method,
            VirtualFile projectRoot) {
    }
}
