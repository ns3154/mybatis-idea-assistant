package io.github.ns3154.mybatisassistant.sqltool.conversion;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public final class MyBatisJavaTableExtractorTest extends BasePlatformTestCase {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addSource("com/baomidou/mybatisplus/annotation/TableName.java", """
                package com.baomidou.mybatisplus.annotation;
                public @interface TableName { String value() default ""; }
                """);
        addSource("com/baomidou/mybatisplus/annotation/TableField.java", """
                package com.baomidou.mybatisplus.annotation;
                public @interface TableField {
                    String value() default "";
                    boolean exist() default true;
                }
                """);
        addSource("com/baomidou/mybatisplus/annotation/TableId.java", """
                package com.baomidou.mybatisplus.annotation;
                public @interface TableId {
                    String value() default "";
                    IdType type() default IdType.NONE;
                }
                """);
        addSource("com/baomidou/mybatisplus/annotation/IdType.java", """
                package com.baomidou.mybatisplus.annotation;
                public enum IdType { NONE, AUTO }
                """);
        addSource("jakarta/persistence/Table.java", """
                package jakarta.persistence;
                public @interface Table {
                    String name() default "";
                    String schema() default "";
                    Index[] indexes() default {};
                }
                """);
        addSource("jakarta/persistence/Index.java", """
                package jakarta.persistence;
                public @interface Index {
                    String name();
                    String columnList();
                    boolean unique() default false;
                }
                """);
        addSource("jakarta/persistence/Column.java", """
                package jakarta.persistence;
                public @interface Column {
                    String name() default "";
                    boolean nullable() default true;
                }
                """);
        addSource("jakarta/persistence/Id.java",
                "package jakarta.persistence; public @interface Id {}\n");
        addSource("jakarta/persistence/GeneratedValue.java", """
                package jakarta.persistence;
                public @interface GeneratedValue {
                    GenerationType strategy() default GenerationType.AUTO;
                }
                """);
        addSource("jakarta/persistence/GenerationType.java", """
                package jakarta.persistence;
                public enum GenerationType { AUTO, IDENTITY, SEQUENCE }
                """);
        addSource("jakarta/persistence/Transient.java",
                "package jakarta.persistence; public @interface Transient {}\n");
    }

    public void testExtractsAnnotationsInheritedFieldsCommentsAndIndexes() {
        addSource("com/example/BaseEntity.java", """
                package com.example;
                import jakarta.persistence.Column;
                public class BaseEntity {
                    /** 创建时间 */
                    @Column(name = "created_at", nullable = false)
                    protected java.time.LocalDateTime createdAt;
                }
                """);
        PsiClass entity = psiClass("""
                package com.example;
                import com.baomidou.mybatisplus.annotation.*;
                import jakarta.persistence.*;
                /** 用户账户 */
                @TableName("user_account")
                @Table(name = "ignored", schema = "app", indexes = {
                    @Index(name = "idx_name", columnList = "display_name DESC", unique = true)
                })
                public class UserAccount extends BaseEntity {
                    /** 主键 */
                    @TableId(value = "user_id", type = IdType.AUTO)
                    private Long id;

                    @Column(name = "display_name", nullable = false)
                    private String displayName;

                    @TableField(exist = false)
                    private String computed;

                    @Transient
                    private String transientValue;

                    private static String KIND;
                }
                """);

        MyBatisJavaTableExtractionResult result = MyBatisJavaTableExtractor.extract(entity);

        assertInstanceOf(result, MyBatisJavaTableExtractionResult.Success.class);
        MyBatisJavaTableExtractionResult.Success success =
                (MyBatisJavaTableExtractionResult.Success) result;
        assertEquals("user_account", success.table().tableName());
        assertEquals("app", success.table().schema().orElseThrow());
        assertEquals("用户账户", success.table().comment().orElseThrow());
        assertEquals(List.of("id", "displayName", "createdAt"),
                success.table().fields().stream()
                        .map(MyBatisJavaFieldSchema::propertyName).toList());
        MyBatisJavaFieldSchema id = success.table().fields().get(0);
        assertEquals("user_id", id.columnName());
        assertTrue(id.primaryKey());
        assertTrue(id.autoIncrement());
        assertFalse(id.nullable());
        assertFalse(id.inherited());
        assertEquals("主键", id.comment().orElseThrow());
        MyBatisJavaFieldSchema inherited = success.table().fields().get(2);
        assertTrue(inherited.inherited());
        assertEquals("created_at", inherited.columnName());
        assertFalse(inherited.nullable());
        assertEquals(1, success.table().indexes().size());
        assertEquals(List.of("display_name"), success.table().indexes().get(0).columns());
        assertTrue(success.table().indexes().get(0).unique());
        assertEmpty(success.warnings());
    }

    public void testUsesDeterministicNameAndPrimitiveNullabilityDefaults() {
        PsiClass entity = psiClass("""
                package com.example;
                public record HTTPAuditEntry(long id, String displayName) {}
                """);

        MyBatisJavaTableExtractionResult.Success success =
                (MyBatisJavaTableExtractionResult.Success) MyBatisJavaTableExtractor.extract(entity);

        assertEquals("http_audit_entry", success.table().tableName());
        assertEquals("id", success.table().fields().get(0).columnName());
        assertFalse(success.table().fields().get(0).nullable());
        assertTrue(success.table().fields().get(1).nullable());
    }

    public void testInvalidIndexBecomesConfirmationWarning() {
        PsiClass entity = psiClass("""
                package com.example;
                import jakarta.persistence.*;
                @Table(indexes = @Index(name = "idx_bad", columnList = "lower(name)"))
                public class Sample {
                    private String name;
                }
                """);

        MyBatisJavaTableExtractionResult.Success success =
                (MyBatisJavaTableExtractionResult.Success) MyBatisJavaTableExtractor.extract(entity);

        assertEmpty(success.table().indexes());
        assertEquals(1, success.warnings().size());
        assertTrue(success.confirmationRequired());
    }

    public void testRejectsInterfaceAndClassWithoutFields() {
        PsiClass interfaceClass = psiClass(
                "package com.example; public interface Sample {}\n");
        PsiClass emptyClass = psiClass(
                "package com.example; public class Empty {}\n");

        assertInstanceOf(MyBatisJavaTableExtractor.extract(interfaceClass),
                MyBatisJavaTableExtractionResult.Failure.class);
        assertInstanceOf(MyBatisJavaTableExtractor.extract(emptyClass),
                MyBatisJavaTableExtractionResult.Failure.class);
    }

    private PsiClass psiClass(String source) {
        PsiJavaFile file = (PsiJavaFile) myFixture.configureByText("Entity.java", source);
        return file.getClasses()[0];
    }

    private void addSource(String path, String source) {
        myFixture.addFileToProject(path, source);
    }
}
