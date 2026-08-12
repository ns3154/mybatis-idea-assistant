package io.github.ns3154.mybatisassistant.sqltool.conversion;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.generator.MyBatisGeneratedArtifact;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationArtifactKind;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class MyBatisSqlArtifactConverterTest extends BasePlatformTestCase {
    public void testConvertsDdlWithExistingGeneratorIntoEntityMapperAndResultMapXml() {
        MyBatisSqlArtifactConversionResult result = MyBatisSqlArtifactConverter.convert(
                """
                        CREATE TABLE user_account (
                          id BIGINT PRIMARY KEY,
                          display_name VARCHAR(100) NOT NULL,
                          created_at TIMESTAMP
                        ) COMMENT='用户';
                        """,
                MyBatisSqlDialect.MYSQL,
                "com.example.account");

        assertInstanceOf(result, MyBatisSqlArtifactConversionResult.Success.class);
        MyBatisSqlArtifactConversionResult.Success success =
                (MyBatisSqlArtifactConversionResult.Success) result;
        assertEquals("UserAccount", success.bundle().entityName());
        assertEquals(3, success.bundle().artifacts().size());
        Map<MyBatisGenerationArtifactKind, MyBatisGeneratedArtifact> artifacts =
                success.bundle().artifacts().stream().collect(Collectors.toMap(
                        MyBatisGeneratedArtifact::kind, Function.identity()));
        assertTrue(artifacts.get(MyBatisGenerationArtifactKind.ENTITY).content()
                .contains("private String displayName;"));
        assertTrue(artifacts.get(MyBatisGenerationArtifactKind.MAPPER).content()
                .contains("interface UserAccountMapper"));
        String xml = artifacts.get(MyBatisGenerationArtifactKind.XML).content();
        assertTrue(xml.contains("<resultMap id=\"BaseResultMap\""));
        assertTrue(xml.contains("column=\"display_name\" property=\"displayName\""));
        assertFalse(artifacts.containsKey(MyBatisGenerationArtifactKind.SERVICE));
        assertFalse(success.confirmationRequired());
    }

    public void testPropagatesParseFailureAndUnknownTypeConfirmation() {
        MyBatisSqlArtifactConversionResult invalid = MyBatisSqlArtifactConverter.convert(
                "DELETE FROM user_account",
                MyBatisSqlDialect.MYSQL,
                "com.example");
        MyBatisSqlArtifactConversionResult unknown = MyBatisSqlArtifactConverter.convert(
                "CREATE TABLE sample (payload GEOGRAPHY)",
                MyBatisSqlDialect.POSTGRESQL,
                "com.example");

        assertInstanceOf(invalid, MyBatisSqlArtifactConversionResult.Failure.class);
        assertEquals(MyBatisDdlDiagnosticCode.NOT_CREATE_TABLE,
                ((MyBatisSqlArtifactConversionResult.Failure) invalid).code());
        assertInstanceOf(unknown, MyBatisSqlArtifactConversionResult.Success.class);
        assertTrue(((MyBatisSqlArtifactConversionResult.Success) unknown)
                .confirmationRequired());
    }

    public void testKeepsParsedComputedColumnsOutOfEngineWrites() {
        MyBatisSqlArtifactConversionResult result = MyBatisSqlArtifactConverter.convert(
                """
                        CREATE TABLE metric (
                          id BIGINT PRIMARY KEY,
                          raw_value INTEGER NOT NULL,
                          doubled INTEGER GENERATED ALWAYS AS (raw_value * 2) STORED
                        )
                        """,
                MyBatisSqlDialect.POSTGRESQL,
                "com.example");

        assertInstanceOf(result, MyBatisSqlArtifactConversionResult.Success.class);
        MyBatisSqlArtifactConversionResult.Success success =
                (MyBatisSqlArtifactConversionResult.Success) result;
        String xml = success.bundle().artifacts().stream()
                .filter(artifact -> artifact.kind() == MyBatisGenerationArtifactKind.XML)
                .findFirst()
                .orElseThrow()
                .content();

        assertTrue(success.table().columns().get(2).generated());
        assertTrue(xml.contains("column=\"doubled\" property=\"doubled\""));
        assertTrue(xml.contains("INSERT INTO \"metric\" (\"id\", \"raw_value\")"));
        assertFalse(xml.contains("(\"id\", \"raw_value\", \"doubled\") VALUES"));
        assertFalse(xml.contains("\"doubled\" = #{doubled"));
    }

    public void testRejectsInvalidPackageAsTypedFailure() {
        MyBatisSqlArtifactConversionResult result = MyBatisSqlArtifactConverter.convert(
                "CREATE TABLE sample (id BIGINT)",
                MyBatisSqlDialect.GENERIC,
                "not-a-package");

        assertInstanceOf(result, MyBatisSqlArtifactConversionResult.Failure.class);
        assertEquals(MyBatisDdlDiagnosticCode.UNSUPPORTED_DEFINITION,
                ((MyBatisSqlArtifactConversionResult.Failure) result).code());
    }
}
