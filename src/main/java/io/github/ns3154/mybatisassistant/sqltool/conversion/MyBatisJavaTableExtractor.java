package io.github.ns3154.mybatisassistant.sqltool.conversion;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.javadoc.PsiDocComment;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 仅通过 Java PSI 与常量求值提取字段，不加载或执行项目类。
 */
public final class MyBatisJavaTableExtractor {
    private static final String PLUS_TABLE = "com.baomidou.mybatisplus.annotation.TableName";
    private static final String PLUS_FIELD = "com.baomidou.mybatisplus.annotation.TableField";
    private static final String PLUS_ID = "com.baomidou.mybatisplus.annotation.TableId";
    private static final List<String> JPA_TABLES = List.of(
            "jakarta.persistence.Table", "javax.persistence.Table");
    private static final List<String> JPA_COLUMNS = List.of(
            "jakarta.persistence.Column", "javax.persistence.Column");
    private static final List<String> JPA_IDS = List.of(
            "jakarta.persistence.Id", "javax.persistence.Id");
    private static final List<String> JPA_GENERATED = List.of(
            "jakarta.persistence.GeneratedValue", "javax.persistence.GeneratedValue");
    private static final List<String> TRANSIENT = List.of(
            "jakarta.persistence.Transient", "javax.persistence.Transient");
    private static final List<String> NULLABLE = List.of(
            "org.jetbrains.annotations.Nullable", "jakarta.annotation.Nullable",
            "javax.annotation.Nullable");
    private static final List<String> NOT_NULL = List.of(
            "org.jetbrains.annotations.NotNull", "jakarta.annotation.Nonnull",
            "javax.annotation.Nonnull");

    private MyBatisJavaTableExtractor() {
    }

    public static @NotNull MyBatisJavaTableExtractionResult extract(@NotNull PsiClass source) {
        if (!source.isValid() || source.isInterface() || source.isAnnotationType()
                || source.isEnum() || source.getName() == null) {
            return new MyBatisJavaTableExtractionResult.Failure(
                    "请选择有效的 Java 实体类、record 或普通类");
        }
        List<String> warnings = new ArrayList<>();
        PsiAnnotation tableAnnotation = firstAnnotation(source, JPA_TABLES);
        String tableName = firstNonBlank(
                stringAttribute(source.getAnnotation(PLUS_TABLE), "value"),
                stringAttribute(tableAnnotation, "name"),
                camelToSnake(source.getName()));
        Optional<String> schema = Optional.ofNullable(
                nonBlank(stringAttribute(tableAnnotation, "schema")));
        List<MyBatisJavaFieldSchema> fields = new ArrayList<>();
        for (PsiField field : source.getAllFields()) {
            ProgressManager.checkCanceled();
            if (field.hasModifierProperty(PsiModifier.STATIC)
                    || field.hasModifierProperty(PsiModifier.TRANSIENT)
                    || firstAnnotation(field, TRANSIENT) != null
                    || tableFieldExcluded(field)) {
                continue;
            }
            String columnName = firstNonBlank(
                    stringAttribute(field.getAnnotation(PLUS_ID), "value"),
                    stringAttribute(field.getAnnotation(PLUS_FIELD), "value"),
                    stringAttribute(firstAnnotation(field, JPA_COLUMNS), "name"),
                    camelToSnake(field.getName()));
            PsiAnnotation jpaColumn = firstAnnotation(field, JPA_COLUMNS);
            boolean nullable = nullable(field, jpaColumn);
            boolean primary = field.getAnnotation(PLUS_ID) != null
                    || firstAnnotation(field, JPA_IDS) != null;
            boolean autoIncrement = plusAuto(field.getAnnotation(PLUS_ID))
                    || jpaIdentity(firstAnnotation(field, JPA_GENERATED));
            fields.add(new MyBatisJavaFieldSchema(
                    field.getName(),
                    columnName,
                    field.getType().getCanonicalText(),
                    primary ? false : nullable,
                    primary,
                    autoIncrement,
                    field.getContainingClass() != source,
                    javaDoc(field.getDocComment())));
        }
        if (fields.isEmpty()) {
            return new MyBatisJavaTableExtractionResult.Failure(
                    "所选 Java 类没有可转换的实例字段");
        }
        List<MyBatisJavaIndexSchema> indexes = indexes(tableAnnotation, warnings);
        MyBatisJavaTableSchema table = new MyBatisJavaTableSchema(
                tableName, schema, javaDoc(source.getDocComment()), fields, indexes);
        return new MyBatisJavaTableExtractionResult.Success(
                table, warnings, !warnings.isEmpty());
    }

    private static boolean tableFieldExcluded(PsiField field) {
        PsiAnnotation annotation = field.getAnnotation(PLUS_FIELD);
        Object value = constant(annotation, "exist");
        return Boolean.FALSE.equals(value);
    }

    private static boolean nullable(PsiField field, PsiAnnotation column) {
        if (field.getType() instanceof com.intellij.psi.PsiPrimitiveType
                || firstAnnotation(field, NOT_NULL) != null) {
            return false;
        }
        if (firstAnnotation(field, NULLABLE) != null) {
            return true;
        }
        Object declared = constant(column, "nullable");
        return !(declared instanceof Boolean value) || value;
    }

    private static boolean plusAuto(PsiAnnotation annotation) {
        PsiAnnotationMemberValue value = annotation == null
                ? null : annotation.findDeclaredAttributeValue("type");
        return value != null && value.getText().endsWith(".AUTO");
    }

    private static boolean jpaIdentity(PsiAnnotation annotation) {
        PsiAnnotationMemberValue value = annotation == null
                ? null : annotation.findDeclaredAttributeValue("strategy");
        return value != null && value.getText().endsWith(".IDENTITY");
    }

    private static List<MyBatisJavaIndexSchema> indexes(
            PsiAnnotation tableAnnotation,
            List<String> warnings) {
        if (tableAnnotation == null) {
            return List.of();
        }
        PsiAnnotationMemberValue value = tableAnnotation.findDeclaredAttributeValue("indexes");
        List<PsiAnnotationMemberValue> values = value instanceof PsiArrayInitializerMemberValue array
                ? List.of(array.getInitializers())
                : value == null ? List.of() : List.of(value);
        List<MyBatisJavaIndexSchema> indexes = new ArrayList<>();
        for (PsiAnnotationMemberValue member : values) {
            if (!(member instanceof PsiAnnotation annotation)) {
                warnings.add("@Table.indexes 含无法静态解析的表达式，已跳过");
                continue;
            }
            String name = stringAttribute(annotation, "name");
            String columnList = stringAttribute(annotation, "columnList");
            if (name == null || name.isBlank() || columnList == null || columnList.isBlank()) {
                warnings.add("@Index 缺少确定的 name 或 columnList，已跳过");
                continue;
            }
            List<String> columns = new ArrayList<>();
            boolean valid = true;
            for (String item : columnList.split(",")) {
                String column = item.strip().replaceFirst("(?i)\\s+(ASC|DESC)$", "").strip();
                if (!column.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                    valid = false;
                    break;
                }
                columns.add(column);
            }
            if (!valid || columns.isEmpty()) {
                warnings.add("@Index " + name + " 的 columnList 无法安全解析，已跳过");
                continue;
            }
            indexes.add(new MyBatisJavaIndexSchema(
                    name, columns, Boolean.TRUE.equals(constant(annotation, "unique"))));
        }
        return indexes;
    }

    private static PsiAnnotation firstAnnotation(
            com.intellij.psi.PsiModifierListOwner owner,
            List<String> names) {
        for (String name : names) {
            PsiAnnotation annotation = owner.getAnnotation(name);
            if (annotation != null) {
                return annotation;
            }
        }
        return null;
    }

    private static String stringAttribute(PsiAnnotation annotation, String name) {
        Object value = constant(annotation, name);
        return value instanceof String text ? nonBlank(text) : null;
    }

    private static Object constant(PsiAnnotation annotation, String name) {
        PsiAnnotationMemberValue value = annotation == null
                ? null : annotation.findDeclaredAttributeValue(name);
        return value == null ? null : JavaPsiFacade.getInstance(annotation.getProject())
                .getConstantEvaluationHelper().computeConstantExpression(value);
    }

    private static Optional<String> javaDoc(PsiDocComment comment) {
        if (comment == null) {
            return Optional.empty();
        }
        String text = comment.getText()
                .replaceFirst("^/\\*\\*", "")
                .replaceFirst("\\*/$", "")
                .replaceAll("(?m)^\\s*\\*\\s?", "")
                .strip();
        int tag = text.indexOf("\n@");
        if (tag >= 0) {
            text = text.substring(0, tag).strip();
        }
        return Optional.of(text).filter(value -> !value.isBlank());
    }

    private static String camelToSnake(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isUpperCase(current) && index > 0
                    && (Character.isLowerCase(value.charAt(index - 1))
                    || index + 1 < value.length()
                    && Character.isLowerCase(value.charAt(index + 1)))) {
                result.append('_');
            }
            result.append(Character.toLowerCase(current));
        }
        return result.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        throw new IllegalArgumentException("候选值不能为空");
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
