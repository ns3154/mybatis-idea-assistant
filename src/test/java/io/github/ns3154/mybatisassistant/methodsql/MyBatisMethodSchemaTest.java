package io.github.ns3154.mybatisassistant.methodsql;

import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationConfiguration;
import org.junit.Test;

import java.sql.Types;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MyBatisMethodSchemaTest {
    @Test
    public void preservesCatalogAndSchemaFromTheSelectedDatabaseTable() {
        MyBatisDatabaseTable table = new MyBatisDatabaseTable(
                Optional.of("tenant_catalog"),
                Optional.of("audit"),
                "events",
                List.of(new MyBatisDatabaseColumn(
                        "id", "BIGINT", Types.BIGINT,
                        false, true, false, 1)));

        MyBatisMethodSchema schema = MyBatisMethodSchema.from(
                table, MyBatisGenerationConfiguration.standard("com.example"));

        assertEquals(Optional.of("tenant_catalog"), schema.catalog());
        assertEquals(Optional.of("audit"), schema.schema());
        assertEquals("events", schema.tableName());
        assertEquals("id", schema.fields().getFirst().columnName());
    }

    @Test
    public void keepsTheLegacyConstructorAndNormalizesBlankNamespaces() {
        MyBatisMethodField id = new MyBatisMethodField(
                "id", "Id", "id", "java.lang.Long", Optional.empty(),
                Types.BIGINT, false, true, false);

        MyBatisMethodSchema legacy = new MyBatisMethodSchema("events", List.of(id));
        MyBatisMethodSchema normalized = new MyBatisMethodSchema(
                Optional.of(" "), Optional.of(""), "events", List.of(id));

        assertTrue(legacy.catalog().isEmpty());
        assertTrue(legacy.schema().isEmpty());
        assertTrue(normalized.catalog().isEmpty());
        assertTrue(normalized.schema().isEmpty());
    }
}
