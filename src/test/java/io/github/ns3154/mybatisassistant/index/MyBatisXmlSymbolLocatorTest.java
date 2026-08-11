package io.github.ns3154.mybatisassistant.index;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlSymbolKind;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;

import java.util.List;

public final class MyBatisXmlSymbolLocatorTest extends BasePlatformTestCase {
    public void testFindsResultMapAndSqlFragmentIndependently() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="shared" type="java.lang.Object"/>
                    <sql id="shared">id</sql>
                    <select id="shared">select 1</select>
                </mapper>
                """);

        List<XmlTag> resultMaps = find(MyBatisXmlSymbolKind.RESULT_MAP, "shared");
        List<XmlTag> sqlFragments = find(MyBatisXmlSymbolKind.SQL_FRAGMENT, "shared");
        List<XmlTag> statements = find(MyBatisXmlSymbolKind.STATEMENT, "shared");

        assertSize(1, resultMaps);
        assertEquals("resultMap", resultMaps.getFirst().getLocalName());
        assertSize(1, sqlFragments);
        assertEquals("sql", sqlFragments.getFirst().getLocalName());
        assertSize(1, statements);
        assertEquals("select", statements.getFirst().getLocalName());
    }

    public void testPreservesDuplicateNamedSymbolsAsCandidates() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="userMap" type="java.lang.Object"/>
                    <resultMap id="userMap" type="java.lang.String"/>
                </mapper>
                """);

        assertSize(2, find(MyBatisXmlSymbolKind.RESULT_MAP, "userMap"));
    }

    public void testMalformedXmlDoesNotEnterIndex() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <sql id="columns">id
                """);

        assertEmpty(find(MyBatisXmlSymbolKind.SQL_FRAGMENT, "columns"));
        assertFalse(ReadAction.compute(() -> MyBatisXmlSymbolLocator.hasMapperXml(
                getProject(),
                "com.example.UserMapper")));
    }

    public void testUnsavedIdChangeInvalidatesIndex() {
        PsiFile psiFile = addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="before" type="java.lang.Object"/>
                </mapper>
                """);
        XmlTag resultMap = ((XmlFile) psiFile).getRootTag().findFirstSubTag("resultMap");
        assertNotNull(resultMap);
        assertSize(1, find(MyBatisXmlSymbolKind.RESULT_MAP, "before"));

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            resultMap.setAttribute("id", "after");
            PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        });

        assertEmpty(find(MyBatisXmlSymbolKind.RESULT_MAP, "before"));
        assertSize(1, find(MyBatisXmlSymbolKind.RESULT_MAP, "after"));
    }

    public void testUnsavedNamespaceChangeInvalidatesIndex() {
        PsiFile psiFile = addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <sql id="columns">id, name</sql>
                </mapper>
                """);
        XmlTag mapper = ((XmlFile) psiFile).getRootTag();
        assertNotNull(mapper);
        assertSize(1, find(MyBatisXmlSymbolKind.SQL_FRAGMENT, "columns"));

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            mapper.setAttribute("namespace", "com.example.RenamedMapper");
            PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        });

        assertEmpty(find(MyBatisXmlSymbolKind.SQL_FRAGMENT, "columns"));
        assertFalse(ReadAction.compute(() -> MyBatisXmlSymbolLocator.hasMapperXml(
                getProject(),
                "com.example.UserMapper")));
        assertTrue(ReadAction.compute(() -> MyBatisXmlSymbolLocator.hasMapperXml(
                getProject(),
                "com.example.RenamedMapper")));
    }

    public void testCancellationPropagates() {
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();

        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return find(MyBatisXmlSymbolKind.SQL_FRAGMENT, "columns");
                    },
                    indicator);
            fail("取消后的符号查询必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是正常控制流，定位器必须向上传播。
        }
    }

    public void testIndexesAndQueriesOneHundredThousandStatements() {
        int statementCount = 100_000;
        StringBuilder xml = new StringBuilder(statementCount * 28);
        xml.append("<mapper namespace=\"com.example.HugeMapper\">");
        for (int index = 0; index < statementCount; index++) {
            xml.append("<select id=\"s").append(index).append("\"/>");
        }
        xml.append("</mapper>");
        myFixture.addFileToProject(
                "src/main/resources/mapper/HugeMapper.xml",
                xml.toString());

        List<XmlTag> last = ReadAction.compute(() -> MyBatisXmlSymbolLocator.find(
                getProject(),
                MyBatisXmlSymbolKind.STATEMENT,
                "com.example.HugeMapper",
                "s99999"));
        List<XmlTag> missing = ReadAction.compute(() -> MyBatisXmlSymbolLocator.find(
                getProject(),
                MyBatisXmlSymbolKind.STATEMENT,
                "com.example.HugeMapper",
                "missing"));

        assertSize(1, last);
        assertEquals("s99999", MyBatisXmlModel.symbolId(last.getFirst()));
        assertEmpty(missing);
    }

    private PsiFile addMapperXml(String xml) {
        return myFixture.addFileToProject("src/main/resources/mapper/UserMapper.xml", xml);
    }

    private List<XmlTag> find(MyBatisXmlSymbolKind kind, String id) {
        return ReadAction.compute(() -> MyBatisXmlSymbolLocator.find(
                getProject(),
                kind,
                "com.example.UserMapper",
                id));
    }
}
