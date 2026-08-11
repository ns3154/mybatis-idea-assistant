package io.github.ns3154.mybatisassistant.generator;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class MyBatisSafeMergerTest extends BasePlatformTestCase {
    public void testRegenerationPreservesManualJavaAndCrLf() {
        String first = javaFile("private String name;\n");
        String existing = first.replace("\n}\n", "\n    public String manual() { return \"ok\"; }\n}\n")
                .replace("\n", "\r\n");
        String desired = javaFile("private String name;\nprivate Long id;\n");

        MyBatisSafeMergeResult.Ready ready = assertInstanceOf(
                MyBatisSafeMerger.merge(existing, desired),
                MyBatisSafeMergeResult.Ready.class);

        assertTrue(ready.changed());
        assertTrue(ready.text().contains("private Long id;"));
        assertTrue(ready.text().contains("public String manual()"));
        assertTrue(ready.text().contains("\r\n"));
        assertFalse(ready.text().replace("\r\n", "").contains("\n"));
        MyBatisSafeMergeResult.Ready unchanged = assertInstanceOf(
                MyBatisSafeMerger.merge(ready.text(), desired),
                MyBatisSafeMergeResult.Ready.class);
        assertFalse(unchanged.changed());
    }

    public void testManualGeneratedRegionModificationStopsBeforeWrite() {
        String first = javaFile("private String name;\n");
        String modified = first.replace("private String name;", "private Object name;");

        MyBatisSafeMergeResult.Conflict conflict = assertInstanceOf(
                MyBatisSafeMerger.merge(modified, javaFile("private Long id;\n")),
                MyBatisSafeMergeResult.Conflict.class);

        assertEquals(MyBatisSafeMergeConflictCode.GENERATED_REGION_MODIFIED, conflict.code());
        assertTrue(conflict.message().contains("members"));
    }

    public void testMissingMalformedAndChangedMarkerSetsAreTypedConflicts() {
        assertEquals(
                MyBatisSafeMergeConflictCode.FILE_WITHOUT_MARKERS,
                conflict("class User {}\n", javaFile("private Long id;\n")).code());

        String malformed = javaFile("private String name;\n")
                .replace("// </mybatis-assistant-generated id=\"user:members\">", "");
        assertEquals(
                MyBatisSafeMergeConflictCode.MALFORMED_MARKERS,
                conflict(malformed, javaFile("private Long id;\n")).code());

        String changedSet = javaFile("private String name;\n")
                + MyBatisGeneratedRegion.render(
                        MyBatisGeneratedRegion.Style.JAVA,
                        "user:extra",
                        "int extra;\n");
        assertEquals(
                MyBatisSafeMergeConflictCode.MARKER_SET_CHANGED,
                conflict(changedSet, javaFile("private Long id;\n")).code());
    }

    private static MyBatisSafeMergeResult.Conflict conflict(
            String existing,
            String desired) {
        return assertInstanceOf(
                MyBatisSafeMerger.merge(existing, desired),
                MyBatisSafeMergeResult.Conflict.class);
    }

    private static String javaFile(String members) {
        return MyBatisGeneratedRegion.render(
                MyBatisGeneratedRegion.Style.JAVA,
                "user:header",
                "package com.example;\n")
                + MyBatisGeneratedRegion.render(
                        MyBatisGeneratedRegion.Style.JAVA,
                        "user:declaration",
                        "public class User {\n")
                + MyBatisGeneratedRegion.render(
                        MyBatisGeneratedRegion.Style.JAVA,
                        "user:members",
                        members)
                + "}\n";
    }
}
