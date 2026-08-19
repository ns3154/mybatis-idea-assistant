package io.github.ns3154.mybatisassistant.generator;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 在测试临时目录中生成可重复的大型多模块项目，避免把数千个生成文件提交到仓库。
 */
final class S13LargeProjectFixtureGenerator {
    static final int TABLE_COUNT = 1_024;
    static final int STATEMENTS_PER_TABLE = 5;
    static final int MODULE_COUNT = 4;
    static final int DATA_SOURCE_COUNT = 5;
    private static final String[] STATEMENT_IDS = {
        "findById",
        "findPage",
        "insertRow",
        "updateRow",
        "deleteRow"
    };

    private S13LargeProjectFixtureGenerator() {
    }

    static @NotNull Manifest generate(@NotNull Path root) throws IOException {
        Files.createDirectories(root);
        MessageDigest digest = sha256();
        List<Path> mapperFiles = new ArrayList<>(TABLE_COUNT);
        List<StringBuilder> schemas = new ArrayList<>(MODULE_COUNT * DATA_SOURCE_COUNT);
        for (int index = 0; index < MODULE_COUNT * DATA_SOURCE_COUNT; index++) {
            schemas.add(new StringBuilder());
        }

        write(root, Path.of("settings.gradle.kts"), settingsFile(), digest);
        for (int moduleIndex = 0; moduleIndex < MODULE_COUNT; moduleIndex++) {
            String moduleName = moduleName(moduleIndex);
            write(root, Path.of(moduleName, "build.gradle.kts"), moduleBuildFile(), digest);
        }

        for (int tableIndex = 0; tableIndex < TABLE_COUNT; tableIndex++) {
            int moduleIndex = tableIndex % MODULE_COUNT;
            int dataSourceIndex = tableIndex % DATA_SOURCE_COUNT;
            String moduleName = moduleName(moduleIndex);
            String tableName = tableName(tableIndex);
            String namespace = namespace(moduleIndex, dataSourceIndex, tableIndex);
            Path mapperPath = Path.of(
                    moduleName,
                    "src/main/resources/mappers",
                    dataSourceName(dataSourceIndex),
                    "Table" + fourDigits(tableIndex) + "Mapper.xml");
            write(root, mapperPath, mapperXml(namespace, tableName, 1), digest);
            mapperFiles.add(root.resolve(mapperPath));
            write(
                    root,
                    Path.of(
                            moduleName,
                            "src/main/java",
                            namespace.replace('.', '/') + ".java"),
                    mapperInterface(namespace),
                    digest);
            schemas.get(schemaIndex(moduleIndex, dataSourceIndex))
                    .append(tableDdl(tableName));
        }

        List<Path> schemaFiles = new ArrayList<>(schemas.size());
        for (int moduleIndex = 0; moduleIndex < MODULE_COUNT; moduleIndex++) {
            for (int dataSourceIndex = 0;
                    dataSourceIndex < DATA_SOURCE_COUNT;
                    dataSourceIndex++) {
                Path schemaPath = Path.of(
                        moduleName(moduleIndex),
                        "src/main/resources/schema",
                        dataSourceName(dataSourceIndex) + ".sql");
                write(
                        root,
                        schemaPath,
                        schemas.get(schemaIndex(moduleIndex, dataSourceIndex)).toString(),
                        digest);
                schemaFiles.add(root.resolve(schemaPath));
            }
        }
        write(root, Path.of("fixture-manifest.tsv"), manifestFile(), digest);

        return new Manifest(
                List.copyOf(mapperFiles),
                List.copyOf(schemaFiles),
                HexFormat.of().formatHex(digest.digest()),
                TABLE_COUNT,
                TABLE_COUNT * STATEMENTS_PER_TABLE);
    }

    static @NotNull String desiredMapperXml(int tableIndex) {
        int moduleIndex = tableIndex % MODULE_COUNT;
        int dataSourceIndex = tableIndex % DATA_SOURCE_COUNT;
        return mapperXml(
                namespace(moduleIndex, dataSourceIndex, tableIndex),
                tableName(tableIndex),
                2);
    }

    private static void write(
            @NotNull Path root,
            @NotNull Path relativePath,
            @NotNull String content,
            @NotNull MessageDigest digest) throws IOException {
        Path target = root.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
        digest.update(relativePath.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(content.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static @NotNull String settingsFile() {
        StringBuilder text = new StringBuilder("rootProject.name = \"mybatis-large-fixture\"\n\n");
        for (int moduleIndex = 0; moduleIndex < MODULE_COUNT; moduleIndex++) {
            text.append("include(\"").append(moduleName(moduleIndex)).append("\")\n");
        }
        return text.toString();
    }

    private static @NotNull String moduleBuildFile() {
        return """
                plugins {
                    java
                }

                java {
                    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
                }
                """;
    }

    private static @NotNull String mapperInterface(@NotNull String namespace) {
        int separator = namespace.lastIndexOf('.');
        String packageName = namespace.substring(0, separator);
        String typeName = namespace.substring(separator + 1);
        return "package " + packageName + ";\n\n"
                + "public interface " + typeName + " {\n"
                + "    Object findById(long id);\n\n"
                + "    Object findPage(long offset, long size);\n\n"
                + "    int insertRow(Object row);\n\n"
                + "    int updateRow(Object row);\n\n"
                + "    int deleteRow(long id);\n"
                + "}\n";
    }

    private static @NotNull String mapperXml(
            @NotNull String namespace,
            @NotNull String tableName,
            int revision) {
        String generatedBody = """
                    <select id="findById">SELECT id, payload FROM %s WHERE id = #{id}</select>
                    <select id="findPage">SELECT id, payload FROM %s ORDER BY id LIMIT #{size} OFFSET #{offset}</select>
                    <insert id="insertRow">INSERT INTO %s (payload) VALUES (#{payload})</insert>
                    <update id="updateRow">
                        UPDATE %s SET payload = #{payload}, fixture_revision = %d WHERE id = #{id}
                    </update>
                    <delete id="deleteRow">DELETE FROM %s WHERE id = #{id}</delete>
                """.formatted(tableName, tableName, tableName, tableName, revision, tableName);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<mapper namespace=\"" + namespace + "\">\n"
                + "    <sql id=\"manualColumns\">id, payload</sql>\n"
                + MyBatisGeneratedRegion.render(
                        MyBatisGeneratedRegion.Style.XML,
                        tableName + ":statements",
                        generatedBody)
                + "</mapper>\n";
    }

    private static @NotNull String tableDdl(@NotNull String tableName) {
        return "CREATE TABLE " + tableName + " (\n"
                + "    id BIGINT PRIMARY KEY,\n"
                + "    payload VARCHAR(255) NOT NULL,\n"
                + "    fixture_revision INTEGER NOT NULL DEFAULT 1\n"
                + ");\n\n";
    }

    private static @NotNull String manifestFile() {
        return "metric\tvalue\n"
                + "tables\t" + TABLE_COUNT + "\n"
                + "mappers\t" + TABLE_COUNT + "\n"
                + "statements\t" + TABLE_COUNT * STATEMENTS_PER_TABLE + "\n"
                + "modules\t" + MODULE_COUNT + "\n"
                + "dataSources\t" + DATA_SOURCE_COUNT + "\n"
                + "generatorRevision\t1\n";
    }

    private static int schemaIndex(int moduleIndex, int dataSourceIndex) {
        return moduleIndex * DATA_SOURCE_COUNT + dataSourceIndex;
    }

    private static @NotNull String moduleName(int moduleIndex) {
        return "module-" + twoDigits(moduleIndex);
    }

    private static @NotNull String dataSourceName(int dataSourceIndex) {
        return "datasource-" + twoDigits(dataSourceIndex);
    }

    private static @NotNull String tableName(int tableIndex) {
        return "fixture_table_" + fourDigits(tableIndex);
    }

    private static @NotNull String namespace(
            int moduleIndex,
            int dataSourceIndex,
            int tableIndex) {
        return "io.github.mybatisideaassistant.fixture.module"
                + twoDigits(moduleIndex)
                + ".datasource"
                + twoDigits(dataSourceIndex)
                + ".Table"
                + fourDigits(tableIndex)
                + "Mapper";
    }

    private static @NotNull String twoDigits(int value) {
        return String.format(Locale.ROOT, "%02d", value);
    }

    private static @NotNull String fourDigits(int value) {
        return String.format(Locale.ROOT, "%04d", value);
    }

    private static @NotNull MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256", impossible);
        }
    }

    record Manifest(
            @NotNull List<Path> mapperFiles,
            @NotNull List<Path> schemaFiles,
            @NotNull String fingerprint,
            int tableCount,
            int statementCount) {
    }
}
