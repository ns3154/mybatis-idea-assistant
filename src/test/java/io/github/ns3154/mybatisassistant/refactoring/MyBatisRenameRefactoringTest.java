package io.github.ns3154.mybatisassistant.refactoring;

import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.refactoring.BaseRefactoringProcessor;
import com.intellij.refactoring.rename.RenameProcessor;
import com.intellij.refactoring.rename.RenameHandler;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.openapi.ui.TestDialog;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.util.containers.MultiMap;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.model.MyBatisJavaPropertyAccess;
import io.github.ns3154.mybatisassistant.model.MyBatisJavaPropertyResolution;
import io.github.ns3154.mybatisassistant.model.MyBatisJavaPropertyResolver;

public final class MyBatisRenameRefactoringTest extends BasePlatformTestCase {
    private PsiFile javaFile;
    private PsiFile userFile;
    private PsiFile configurationFile;
    private XmlFile xmlFile;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        PsiTestUtil.addSourceRoot(
                getModule(),
                myFixture.getTempDirFixture().findOrCreateDir("src/main/java"));
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/annotations/Param.java",
                """
                        package org.apache.ibatis.annotations;
                        public @interface Param { String value(); }
                        """);
        userFile = myFixture.addFileToProject("src/main/java/com/example/User.java", """
                package com.example;
                public final class User {
                    public long id;
                    private String name;
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        javaFile = myFixture.addFileToProject("src/main/java/com/example/UserMapper.java", """
                package com.example;
                import org.apache.ibatis.annotations.Param;
                public interface UserMapper {
                    User find(@Param("user") User payload, @Param("other") String other);
                }
                """);
        configurationFile = myFixture.addFileToProject(
                "src/main/resources/mybatis-config.xml",
                """
                <configuration><typeAliases>
                    <typeAlias alias="Person" type="com.example.User"/>
                    <package name="com.example"/>
                </typeAliases></configuration>
                """);
        xmlFile = (XmlFile) myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <sql id="columns">id, name</sql>
                    <resultMap id="userMap" type="Person">
                        <id property="id" column="id"/>
                        <result property="name" column="name"/>
                    </resultMap>
                    <resultMap id="existingMap" type="com.example.User"/>
                    <resultMap id="packageMap" type="User"/>
                    <select id="find" resultMap="userMap">
                        select <include refid="columns"/> from users where name = #{user.name}
                          and id = #{user.id}
                    </select>
                </mapper>
                """);
    }

    public void testMapperMethodRenameUpdatesStatementAndSingleUndoRestoresBoth() {
        PsiMethod method = mapper().findMethodsByName("find", false)[0];

        rename(method, "findRenamed");

        assertTrue(javaFile.getText().contains("User findRenamed("));
        assertTrue(xmlFile.getText().contains("<select id=\"findRenamed\""));
        undo();
        assertTrue(javaFile.getText().contains("User find("));
        assertTrue(xmlFile.getText().contains("<select id=\"find\""));
    }

    public void testParamLiteralRenameUpdatesOnlyStableAliasAndXmlUsages() {
        PsiParameter parameter = mapper().findMethodsByName("find", false)[0]
                .getParameterList().getParameter(0);
        PsiLiteralExpression literal = PsiTreeUtil.findChildOfType(parameter, PsiLiteralExpression.class);
        assertNotNull(literal);

        rename(literal, "criteria");

        assertTrue(javaFile.getText().contains("@Param(\"criteria\") User payload"));
        assertTrue(xmlFile.getText().contains("#{criteria.name}"));
        assertTrue(javaFile.getText().contains("User payload"));
    }

    public void testParamRenameStopsBeforeUnsupportedOgnlWrite() {
        myFixture.addFileToProject("src/main/resources/DynamicUserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <update id="find">
                        update users
                        <set>
                            <if test="user.name != null">name = #{user.name}</if>
                            <bind name="pattern" value="'%' + user.name + '%'"/>
                        </set>
                    </update>
                </mapper>
                """);
        PsiLiteralExpression literal = paramLiteral("find", 0);

        assertConflict(literal, "criteria", "不解析完整 OGNL");

        assertTrue(javaFile.getText().contains("@Param(\"user\") User payload"));
        assertTrue(xmlFile.getText().contains("#{user.name}"));
    }

    public void testParamRenameStopsForUnsupportedIncludedFragmentUsage() {
        PsiFile fragmentFile = myFixture.addFileToProject(
                "src/main/resources/UnsafeFragmentMapper.xml",
                """
                        <mapper namespace="com.example.UserMapper">
                            <sql id="unsafeFilter">where name = #{user.name}</sql>
                        </mapper>
                        """);
        xmlFile = (XmlFile) myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        select * from users <include refid="unsafeFilter"/>
                    </select>
                </mapper>
                """);
        PsiLiteralExpression literal = paramLiteral("find", 0);

        assertConflict(literal, "criteria", "被 include 的 SQL 片段仍引用");

        assertTrue(javaFile.getText().contains("@Param(\"user\") User payload"));
        assertTrue(fragmentFile.getText().contains("#{user.name}"));
    }

    public void testParamRenameStopsForAnnotationSqlAndInvalidAlias() {
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/annotations/Select.java",
                """
                        package org.apache.ibatis.annotations;
                        public @interface Select { String[] value(); }
                        """);
        PsiFile annotatedFile = myFixture.addFileToProject(
                "src/main/java/com/example/AnnotatedMapper.java",
                """
                        package com.example;
                        import org.apache.ibatis.annotations.Param;
                        import org.apache.ibatis.annotations.Select;
                        public interface AnnotatedMapper {
                            @Select("select * from users where id = #{user.id}")
                            Object find(@Param("user") User user);
                        }
                        """);
        PsiClass annotatedMapper = ((com.intellij.psi.PsiJavaFile) annotatedFile).getClasses()[0];
        PsiLiteralExpression annotatedLiteral = PsiTreeUtil.findChildOfType(
                annotatedMapper.findMethodsByName("find", false)[0]
                        .getParameterList().getParameter(0),
                PsiLiteralExpression.class);
        assertNotNull(annotatedLiteral);

        assertConflict(annotatedLiteral, "criteria", "不改写该来源");
        assertConflict(paramLiteral("find", 0), "user-name", "合法的 Java/OGNL 标识符");
    }

    public void testParamRenameStopsForOverloadedMapperStatement() {
        PsiFile overloaded = myFixture.addFileToProject(
                "src/main/java/com/example/ParamOverloadedMapper.java",
                """
                        package com.example;
                        import org.apache.ibatis.annotations.Param;
                        public interface ParamOverloadedMapper {
                            Object find(@Param("id") long id);
                            Object find(@Param("name") String name);
                        }
                        """);
        myFixture.addFileToProject(
                "src/main/resources/ParamOverloadedMapper.xml",
                """
                        <mapper namespace="com.example.ParamOverloadedMapper">
                            <select id="find">select 1 where id = #{id}</select>
                        </mapper>
                        """);
        PsiClass overloadedMapper = ((com.intellij.psi.PsiJavaFile) overloaded).getClasses()[0];
        PsiLiteralExpression literal = PsiTreeUtil.findChildOfType(
                overloadedMapper.findMethodsByName("find", false)[0]
                        .getParameterList().getParameter(0),
                PsiLiteralExpression.class);
        assertNotNull(literal);

        assertConflict(literal, "identifier", "同名重载方法");
    }

    public void testParamRenameHandlerIsRegisteredAndAvailableAtEditorCaret() {
        myFixture.configureFromExistingVirtualFile(javaFile.getVirtualFile());
        int offset = javaFile.getText().indexOf("@Param(\"user\")")
                + "@Param(\"u".length();
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        DataContext dataContext = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject())
                .add(CommonDataKeys.EDITOR, myFixture.getEditor())
                .add(CommonDataKeys.PSI_FILE, javaFile)
                .build();

        MyBatisParamRenameHandler handler = RenameHandler.EP_NAME.getExtensionList().stream()
                .filter(MyBatisParamRenameHandler.class::isInstance)
                .map(MyBatisParamRenameHandler.class::cast)
                .findFirst()
                .orElseThrow();

        assertTrue("@Param 字符串上的原生 Rename 入口必须可用",
                handler.isAvailableOnDataContext(dataContext));
        myFixture.getEditor().getCaretModel().moveToOffset(javaFile.getText().indexOf("interface"));
        assertFalse("普通 Java 位置不能被 MyBatis RenameHandler 接管",
                handler.isAvailableOnDataContext(dataContext));
    }

    public void testGetterAndSetterRenameUpdateParameterAndResultProperties() {
        PsiClass user = user();
        PsiMethod getter = user.findMethodsByName("getName", false)[0];
        PsiParameter mapperParameter = mapper().findMethodsByName("find", false)[0]
                .getParameterList().getParameter(0);
        assertTrue(mapperParameter.getType() instanceof PsiClassType);
        PsiClass parameterClass = ((PsiClassType) mapperParameter.getType()).resolve();
        assertNotNull("Mapper 参数类型必须可解析", parameterClass);
        assertTrue("Mapper 参数类型必须解析到 User，实际目标：" + parameterClass,
                user.getManager().areElementsEquivalent(user, parameterClass));
        MyBatisJavaPropertyResolution directResolution = MyBatisJavaPropertyResolver.resolve(
                mapperParameter.getType(),
                "name",
                MyBatisJavaPropertyAccess.READ);
        assertFalse("JavaBean 属性模型必须直接找到 getter",
                directResolution.targets().isEmpty());

        int rootOffset = xmlFile.getText().indexOf("#{user.name}") + "#{us".length();
        myFixture.getEditor().getCaretModel().moveToOffset(rootOffset);
        PsiReference rootReference = myFixture.getReferenceAtCaretPosition();
        assertNotNull("参数根 user 必须存在引用", rootReference);
        assertTrue("参数根 user 必须解析到 @Param 字面量，实际目标：" + rootReference.resolve(),
                rootReference.resolve() instanceof PsiLiteralExpression);

        int propertyOffset = xmlFile.getText().indexOf("#{user.name}") + "#{user.".length() + 1;
        myFixture.getEditor().getCaretModel().moveToOffset(propertyOffset);
        PsiReference propertyReference = myFixture.getReferenceAtCaretPosition();
        assertNotNull(propertyReference);
        PsiElement propertyTarget = propertyReference.resolve();
        assertNotNull("参数属性引用目标必须存在，引用类型："
                + propertyReference.getClass().getName()
                + "，文本：" + propertyReference.getCanonicalText(), propertyTarget);
        assertTrue("参数属性引用目标必须是 getter，实际目标：" + propertyTarget,
                getter.getManager().areElementsEquivalent(getter, propertyTarget));
        assertFalse("getter 必须能找到 XML 参数属性引用",
                ReferencesSearch.search(getter).findAll().isEmpty());

        rename(getter, "getFullName");

        assertTrue(userFile.getText().contains("String getFullName()"));
        assertTrue(xmlFile.getText().contains("#{user.fullName}"));
        assertTrue(xmlFile.getText().contains("property=\"name\""));

        PsiMethod setter = user.findMethodsByName("setName", false)[0];
        rename(setter, "setDisplayName");

        assertTrue(userFile.getText().contains("void setDisplayName("));
        assertTrue(xmlFile.getText().contains("property=\"displayName\""));
    }

    public void testFieldRenameUpdatesReadableAndWritablePropertyReferences() {
        com.intellij.psi.PsiField field = user().findFieldByName("id", false);
        assertNotNull(field);

        rename(field, "identifier");

        assertTrue(userFile.getText().contains("long identifier"));
        assertTrue(xmlFile.getText().contains("#{user.identifier}"));
        assertTrue(xmlFile.getText().contains("property=\"identifier\""));
    }

    public void testAccessorRenamedToOrdinaryMethodDoesNotWriteInvalidPropertyName() {
        PsiMethod getter = user().findMethodsByName("getName", false)[0];

        rename(getter, "computeName");

        assertTrue(userFile.getText().contains("String computeName()"));
        assertTrue(xmlFile.getText().contains("#{user.name}"));
        assertFalse("普通方法名不能被写成 MyBatis 属性路径",
                xmlFile.getText().contains("#{user.computeName}"));
    }

    public void testClassRenameUpdatesQualifiedTypesAndKeepsExplicitAliasStable() {
        PsiClass user = user();
        int typeOffset = xmlFile.getText().indexOf("type=\"User\"") + "type=\"Us".length();
        myFixture.getEditor().getCaretModel().moveToOffset(typeOffset);
        PsiReference aliasReference = myFixture.getReferenceAtCaretPosition();
        assertNotNull("包扫描 TypeAlias 必须建立引用", aliasReference);
        assertTrue("包扫描 TypeAlias 必须解析到 User，实际：" + aliasReference.resolve(),
                user.getManager().areElementsEquivalent(user, aliasReference.resolve()));
        assertTrue("Java 类查找使用必须包含包扫描 TypeAlias",
                ReferencesSearch.search(user).findAll().stream().anyMatch(
                        reference -> "User".equals(reference.getCanonicalText())));

        rename(user, "RenamedUser");

        assertTrue(userFile.getText().contains("class RenamedUser"));
        assertTrue(javaFile.getText().contains("RenamedUser find("));
        assertTrue(javaFile.getText().contains("RenamedUser payload"));
        assertTrue(configurationFile.getText().contains("type=\"com.example.RenamedUser\""));
        assertTrue(xmlFile.getText().contains("type=\"Person\""));
        assertTrue(xmlFile.getText().contains("type=\"com.example.RenamedUser\""));
        assertTrue(xmlFile.getText().contains("type=\"RenamedUser\""));
    }

    public void testClassRenameKeepsSameNameExplicitAliasStable() {
        myFixture.addFileToProject("src/main/resources/explicit-alias.xml", """
                <configuration><typeAliases>
                    <typeAlias alias="User" type="com.example.User"/>
                </typeAliases></configuration>
                """);

        rename(user(), "RenamedUser");

        assertTrue(xmlFile.getText().contains("type=\"User\""));
        assertFalse(xmlFile.getText().contains("type=\"RenamedUser\""));
    }

    public void testBlankAndDumbModeXmlSymbolRenameConflictsAreActionable() {
        XmlTag resultMap = root().findFirstSubTag("resultMap");
        assertNotNull(resultMap);

        assertConflict(resultMap, " ", "不能为空或包含空白");
        DumbModeTestUtils.runInDumbModeSynchronously(
                getProject(),
                () -> {
                    MultiMap<PsiElement, String> conflicts = new MultiMap<>();
                    new MyBatisXmlSymbolRenameProcessor().findExistingNameConflicts(
                            resultMap,
                            "renamedMap",
                            conflicts);
                    assertTrue("Dumb Mode 必须返回可操作的停止原因，实际：" + conflicts.values(),
                            conflicts.values().stream().anyMatch(
                                    message -> message.contains("索引更新期间")));
                });
    }

    public void testResultMapAndSqlFragmentRenamePreserveQualifiedReferences() {
        PsiFile other = myFixture.addFileToProject("src/main/resources/OtherMapper.xml", """
                <mapper namespace="com.example.OtherMapper">
                    <resultMap id="other" type="com.example.User"
                               extends="com.example.UserMapper.userMap"/>
                    <select id="otherFind" resultMap="com.example.UserMapper.userMap">
                        select <include refid="com.example.UserMapper.columns"/> from users
                    </select>
                </mapper>
                """);
        XmlTag resultMap = root().findFirstSubTag("resultMap");
        XmlTag sql = root().findFirstSubTag("sql");
        assertNotNull(resultMap);
        assertNotNull(sql);

        rename(resultMap, "renamedMap");

        assertTrue(xmlFile.getText().contains("id=\"renamedMap\""));
        assertTrue(xmlFile.getText().contains("resultMap=\"renamedMap\""));
        assertTrue(other.getText().contains("com.example.UserMapper.renamedMap"));

        rename(sql, "renamedColumns");

        assertTrue(xmlFile.getText().contains("id=\"renamedColumns\""));
        assertTrue(xmlFile.getText().contains("refid=\"renamedColumns\""));
        assertTrue(other.getText().contains("com.example.UserMapper.renamedColumns"));
    }

    public void testDuplicateParamAliasStopsBeforeAnyWrite() {
        PsiParameter parameter = mapper().findMethodsByName("find", false)[0]
                .getParameterList().getParameter(0);
        PsiLiteralExpression literal = PsiTreeUtil.findChildOfType(parameter, PsiLiteralExpression.class);
        assertNotNull(literal);

        assertConflict(literal, "other", "已存在 @Param");

        assertTrue(javaFile.getText().contains("@Param(\"user\") User payload"));
        assertTrue(xmlFile.getText().contains("#{user.name}"));
    }

    public void testDuplicateXmlSymbolStopsBeforeAnyWrite() {
        XmlTag resultMap = root().findFirstSubTag("resultMap");
        assertNotNull(resultMap);

        assertConflict(resultMap, "existingMap", "已存在 existingMap");

        assertTrue(xmlFile.getText().contains("id=\"userMap\""));
        assertTrue(xmlFile.getText().contains("resultMap=\"userMap\""));
    }

    public void testDuplicateCurrentXmlDeclarationsStopBeforeAnyWrite() {
        myFixture.addFileToProject("src/main/resources/DuplicateMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="userMap" type="com.example.User"/>
                </mapper>
                """);
        XmlTag resultMap = root().findFirstSubTag("resultMap");
        assertNotNull(resultMap);

        assertConflict(resultMap, "renamedMap", "存在多个声明");

        assertTrue(xmlFile.getText().contains("id=\"userMap\""));
        assertTrue(xmlFile.getText().contains("resultMap=\"userMap\""));
    }

    public void testOverloadedMapperMethodDoesNotRewriteMultiTargetStatement() {
        PsiFile overloaded = myFixture.addFileToProject(
                "src/main/java/com/example/OverloadedMapper.java",
                """
                        package com.example;
                        public interface OverloadedMapper {
                            Object find(long id);
                            Object find(String name);
                        }
                        """);
        PsiFile overloadedXml = myFixture.addFileToProject(
                "src/main/resources/OverloadedMapper.xml",
                """
                        <mapper namespace="com.example.OverloadedMapper">
                            <select id="find">select 1</select>
                        </mapper>
                        """);
        PsiClass overloadedMapper = ((com.intellij.psi.PsiJavaFile) overloaded).getClasses()[0];
        PsiMethod method = overloadedMapper.findMethodsByName("find", false)[0];

        rename(method, "findById");

        assertTrue(overloaded.getText().contains("findById("));
        assertTrue("多目标 statement 不能随任一重载方法自动改名",
                overloadedXml.getText().contains("id=\"find\""));
    }

    public void testReadOnlyXmlStopsMapperMethodRenameBeforeAnyWrite() throws Exception {
        PsiMethod method = mapper().findMethodsByName("find", false)[0];
        assertTrue(xmlFile.getVirtualFile().isWritable());
        WriteAction.runAndWait(() -> xmlFile.getVirtualFile().setWritable(false));
        TestDialogManager.setTestDialog(TestDialog.NO, getTestRootDisposable());
        try {
            try {
                rename(method, "findBlocked");
                fail("存在只读 XML 时重命名必须停止");
            } catch (RuntimeException expected) {
                assertTrue("只读冲突必须明确指出文件不可写，实际：" + expected.getMessage(),
                        expected.getMessage() != null
                                && expected.getMessage().contains("read-only"));
            }
        } finally {
            WriteAction.runAndWait(() -> xmlFile.getVirtualFile().setWritable(true));
        }

        assertTrue("只读 XML 存在时 Java 声明不得被部分改写",
                javaFile.getText().contains("User find("));
        assertTrue("只读 XML 存在时 statement id 必须保持原值",
                xmlFile.getText().contains("<select id=\"find\""));
    }

    private PsiClass mapper() {
        return java.util.Arrays.stream(((com.intellij.psi.PsiJavaFile) javaFile).getClasses())
                .filter(candidate -> "UserMapper".equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
    }

    private PsiClass user() {
        return java.util.Arrays.stream(((com.intellij.psi.PsiJavaFile) userFile).getClasses())
                .filter(candidate -> "User".equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
    }

    private XmlTag root() {
        XmlTag root = xmlFile.getRootTag();
        assertNotNull(root);
        return root;
    }

    private PsiLiteralExpression paramLiteral(String methodName, int parameterIndex) {
        PsiParameter parameter = mapper().findMethodsByName(methodName, false)[0]
                .getParameterList().getParameter(parameterIndex);
        assertNotNull(parameter);
        PsiLiteralExpression literal = PsiTreeUtil.findChildOfType(
                parameter,
                PsiLiteralExpression.class);
        assertNotNull(literal);
        return literal;
    }

    private void rename(com.intellij.psi.PsiElement element, String newName) {
        new RenameProcessor(getProject(), element, newName, false, false).run();
        com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
    }

    private void assertConflict(PsiElement element, String newName, String expectedMessage) {
        try {
            rename(element, newName);
            fail("存在冲突时重命名必须停止：" + expectedMessage);
        } catch (BaseRefactoringProcessor.ConflictsInTestsException expected) {
            assertTrue("冲突信息必须可操作，实际：" + expected.getMessages(),
                    expected.getMessages().stream().anyMatch(message -> message.contains(expectedMessage)));
        }
    }

    private void undo() {
        FileEditor editor = FileEditorManager.getInstance(getProject())
                .getSelectedEditor(xmlFile.getVirtualFile());
        assertNotNull(editor);
        UndoManager undoManager = UndoManager.getInstance(getProject());
        assertTrue(undoManager.isUndoAvailable(editor));
        TestDialogManager.setTestDialog(TestDialog.OK, getTestRootDisposable());
        EdtTestUtil.runInEdtAndWait(() -> undoManager.undo(editor));
        com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
    }
}
