package io.github.ns3154.mybatisassistant.model;

import junit.framework.TestCase;
import org.junit.Assert;

public final class MyBatisXmlSymbolModelTest extends TestCase {
    public void testNormalizesStableKeysAndSeparatesKinds() {
        MyBatisXmlSymbol statement = MyBatisXmlSymbol.named(
                MyBatisXmlSymbolKind.STATEMENT,
                " com.example.UserMapper ",
                " findOne ");
        MyBatisXmlSymbol resultMap = MyBatisXmlSymbol.named(
                MyBatisXmlSymbolKind.RESULT_MAP,
                "com.example.UserMapper",
                "findOne");

        assertEquals("com.example.UserMapper", statement.namespace());
        assertEquals("findOne", statement.id());
        assertFalse(statement.indexKey().equals(resultMap.indexKey()));
        assertEquals(
                statement.indexKey(),
                MyBatisXmlSymbolKey.of(
                        MyBatisXmlSymbolKind.STATEMENT,
                        " com.example.UserMapper ",
                        " findOne "));
    }

    public void testRejectsKindAndIdMismatch() {
        Assert.assertThrows(IllegalArgumentException.class, () -> new MyBatisXmlSymbol(
                MyBatisXmlSymbolKind.NAMESPACE,
                "com.example.UserMapper",
                "unexpected"));
        Assert.assertThrows(IllegalArgumentException.class, () -> MyBatisXmlSymbol.named(
                MyBatisXmlSymbolKind.STATEMENT,
                "com.example.UserMapper",
                " "));
        Assert.assertThrows(IllegalArgumentException.class, () -> MyBatisXmlSymbolKey.of(
                MyBatisXmlSymbolKind.NAMESPACE,
                "com.example.UserMapper",
                "unexpected"));
    }

    public void testRejectsBlankOrAmbiguousKeySegments() {
        Assert.assertThrows(IllegalArgumentException.class, () -> MyBatisXmlSymbol.namespace(" "));
        Assert.assertThrows(IllegalArgumentException.class, () -> MyBatisXmlSymbol.named(
                MyBatisXmlSymbolKind.SQL_FRAGMENT,
                "com.example.UserMapper",
                "bad\u0000id"));
        Assert.assertThrows(IllegalArgumentException.class, () -> MyBatisXmlSymbol.namespace(
                "com.example\u0000UserMapper"));
    }

    public void testMapperModelRecordsDefensivelyCopyCollections() {
        MyBatisEntityModel longEntity = new MyBatisEntityModel(
                "java.lang.Long",
                MyBatisEntityKind.CLASS,
                "java.lang.Long",
                java.util.List.of(),
                false);
        MyBatisParameterModel parameter = new MyBatisParameterModel(
                "id",
                "java.lang.Long",
                false,
                longEntity);
        MyBatisEntityModel userEntity = new MyBatisEntityModel(
                "com.example.User",
                MyBatisEntityKind.CLASS,
                "com.example.User",
                java.util.List.of(),
                false);
        MyBatisMapperMethodModel method = new MyBatisMapperMethodModel(
                "find",
                "com.example.ParentMapper",
                "com.example.User",
                userEntity,
                java.util.List.of(parameter),
                MyBatisStatementSourceKind.XML,
                true);
        MyBatisMapperEvidence evidence = new MyBatisMapperEvidence(
                MyBatisMapperEvidenceKind.XML_NAMESPACE,
                "com.example.UserMapper#sources=1");
        MyBatisMapperModel model = new MyBatisMapperModel(
                "com.example.UserMapper",
                java.util.List.of(evidence),
                java.util.List.of(method));

        assertEquals("find(java.lang.Long)", method.stableSignature());
        assertEquals(1, model.evidence().size());
        assertEquals(1, model.methods().size());
    }
}
