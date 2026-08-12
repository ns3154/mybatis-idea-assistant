package io.github.ns3154.mybatisassistant.sqltool.annotation;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.PsiWildcardType;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.generator.MyBatisGeneratedArtifact;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationArtifactKind;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlan;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlanEntry;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlanStatus;
import io.github.ns3154.mybatisassistant.index.MyBatisXmlSymbolLocator;
import io.github.ns3154.mybatisassistant.model.MyBatisAnnotationModel;
import io.github.ns3154.mybatisassistant.model.MyBatisStatementSourceKind;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 为“注解 SQL 迁移到已有 Mapper XML”生成双文件原子更新计划。
 */
public final class MyBatisAnnotationSqlMigrationPlanner {
    private static final int MAX_SQL_LENGTH = 1_048_576;
    private static final String MYBATIS_ANNOTATION_PREFIX = "org.apache.ibatis.annotations.";
    private static final Set<String> CONTAINER_TYPES = Set.of(
            "java.lang.Iterable",
            "java.util.Collection",
            "java.util.List",
            "java.util.Set",
            "java.util.Optional",
            "org.apache.ibatis.cursor.Cursor");
    private static final Set<String> MAP_TYPES = Set.of(
            "java.util.Map",
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.SortedMap",
            "java.util.NavigableMap",
            "java.util.TreeMap");

    private MyBatisAnnotationSqlMigrationPlanner() {
    }

    public static @NotNull Result plan(
            @NotNull PsiMethod method,
            @NotNull VirtualFile projectRoot) {
        ProgressManager.checkCanceled();
        if (!method.isValid()
                || !projectRoot.isValid()
                || !projectRoot.isDirectory()
                || !projectRoot.isWritable()) {
            return failure(FailureCode.SOURCE_INVALID, "Mapper 方法或项目目录已失效");
        }
        PsiClass mapper = method.getContainingClass();
        String namespace = mapper == null ? null : mapper.getQualifiedName();
        if (mapper == null || !mapper.isInterface() || namespace == null) {
            return failure(FailureCode.UNSUPPORTED_SOURCE, "当前位置不是具名 Mapper 接口方法");
        }
        if (mapper.findMethodsByName(method.getName(), false).length != 1) {
            return failure(FailureCode.OVERLOADED_METHOD,
                    "XML statement 无法区分 Java 重载，请先消除同名重载");
        }

        AnnotationSelection selection = selectAnnotation(method);
        if (selection.failure() != null) {
            return selection.failure();
        }
        PsiAnnotation annotation = selection.annotation();
        String statementTag = selection.statementTag();
        String sql = sql(annotation);
        if (sql == null || sql.isBlank()) {
            return failure(FailureCode.UNSUPPORTED_SQL,
                    "注解 SQL 不是可静态求值的字符串或字符串数组");
        }
        if (sql.length() > MAX_SQL_LENGTH) {
            return failure(FailureCode.SQL_TOO_LARGE, "注解 SQL 超过 1 MiB，已拒绝迁移");
        }
        if (sql.toLowerCase(java.util.Locale.ROOT).contains("<script")) {
            return failure(FailureCode.UNSUPPORTED_SQL,
                    "动态 <script> 注解 SQL 需要人工核对，当前不自动迁移");
        }

        List<XmlTag> mapperRoots = MyBatisXmlSymbolLocator.findMapperRoots(
                method.getProject(),
                namespace,
                method.getResolveScope());
        if (mapperRoots.isEmpty()) {
            return failure(FailureCode.MAPPER_XML_NOT_FOUND,
                    "未找到 namespace 精确匹配的 Mapper XML");
        }
        if (mapperRoots.size() != 1) {
            return failure(FailureCode.AMBIGUOUS_MAPPER_XML,
                    "找到多个 namespace 相同的 Mapper XML，无法安全选择目标");
        }
        XmlTag mapperRoot = mapperRoots.getFirst();
        for (XmlTag child : mapperRoot.getSubTags()) {
            ProgressManager.checkCanceled();
            if (MyBatisXmlModel.isStatement(child)
                    && method.getName().equals(MyBatisXmlModel.statementId(child))) {
                return failure(FailureCode.STATEMENT_ALREADY_EXISTS,
                        "目标 XML 已存在同名 statement：" + method.getName());
            }
        }

        String resultType = "select".equals(statementTag)
                ? resultType(method.getReturnType())
                : "";
        if ("select".equals(statementTag) && resultType == null) {
            return failure(FailureCode.UNSUPPORTED_RETURN_TYPE,
                    "无法可靠推导 @Select 的 XML resultType，请先改为明确返回类型");
        }

        PsiFile javaFile = method.getContainingFile();
        PsiFile xmlFile = mapperRoot.getContainingFile();
        if (!(javaFile instanceof PsiJavaFile)
                || javaFile.getVirtualFile() == null
                || xmlFile == null
                || xmlFile.getVirtualFile() == null) {
            return failure(FailureCode.SOURCE_INVALID, "Java 或 XML 文件没有可写磁盘目标");
        }
        if (!javaFile.getVirtualFile().isWritable() || !xmlFile.getVirtualFile().isWritable()) {
            return failure(FailureCode.READ_ONLY_TARGET, "Java 或 XML 目标文件只读");
        }
        String javaPath = relativePath(projectRoot, javaFile.getVirtualFile());
        String xmlPath = relativePath(projectRoot, xmlFile.getVirtualFile());
        if (javaPath == null || xmlPath == null || javaPath.equals(xmlPath)) {
            return failure(FailureCode.TARGET_OUTSIDE_PROJECT,
                    "Java 或 XML 文件不在当前项目目录内");
        }

        String javaText = currentText(javaFile);
        String xmlText = currentText(xmlFile);
        if (javaText == null || xmlText == null) {
            return failure(FailureCode.SOURCE_INVALID, "无法读取 Java 或 XML 当前文档");
        }
        String proposedJava = removeAnnotation(javaText, annotation.getTextRange());
        String proposedXml = insertStatement(
                xmlText,
                mapperRoot,
                statementTag,
                method.getName(),
                resultType,
                sql);
        if (proposedJava == null || proposedXml == null) {
            return failure(FailureCode.SOURCE_CHANGED,
                    "PSI 与当前文档偏移不一致，请提交文档后重试");
        }

        String regionId = "annotation-sql:" + namespace + '#' + method.getName();
        List<MyBatisGenerationPlanEntry> entries = List.of(
                updateEntry(
                        MyBatisGenerationArtifactKind.MAPPER,
                        javaPath,
                        javaText,
                        proposedJava,
                        regionId),
                updateEntry(
                        MyBatisGenerationArtifactKind.XML,
                        xmlPath,
                        xmlText,
                        proposedXml,
                        regionId));
        return new Result.Success(
                new MyBatisGenerationPlan(entries),
                namespace,
                method.getName());
    }

    private static @NotNull AnnotationSelection selectAnnotation(@NotNull PsiMethod method) {
        List<PsiAnnotation> statementAnnotations = MyBatisAnnotationModel.statementAnnotations(
                method,
                MyBatisStatementSourceKind.ANNOTATION_SQL);
        if (statementAnnotations.size() != 1) {
            return AnnotationSelection.failure(FailureCode.UNSUPPORTED_ANNOTATION,
                    "仅支持迁移一个 @Select/@Insert/@Update/@Delete 注解");
        }
        PsiAnnotation target = statementAnnotations.getFirst();
        boolean directlyDeclared = Arrays.stream(method.getAnnotations())
                .anyMatch(annotation -> annotation.getManager()
                        .areElementsEquivalent(annotation, target));
        if (!directlyDeclared) {
            return AnnotationSelection.failure(FailureCode.UNSUPPORTED_ANNOTATION,
                    "重复注解容器需要保留 databaseId 等语义，当前不自动迁移");
        }
        for (PsiAnnotation annotation : method.getAnnotations()) {
            ProgressManager.checkCanceled();
            String qualifiedName = annotation.getQualifiedName();
            if (annotation.getManager().areElementsEquivalent(annotation, target)) {
                continue;
            }
            if (qualifiedName != null && qualifiedName.startsWith(MYBATIS_ANNOTATION_PREFIX)) {
                return AnnotationSelection.failure(FailureCode.UNSUPPORTED_ANNOTATION,
                        "方法还包含 " + qualifiedName
                                + "，自动迁移可能丢失 MyBatis 运行时语义");
            }
        }
        for (var pair : target.getParameterList().getAttributes()) {
            ProgressManager.checkCanceled();
            String name = pair.getName();
            if (name != null && !"value".equals(name)) {
                return AnnotationSelection.failure(FailureCode.UNSUPPORTED_ANNOTATION,
                        "注解显式设置了 " + name + "，当前不自动迁移");
            }
        }
        String qualifiedName = target.getQualifiedName();
        String statementTag = switch (qualifiedName == null ? "" : qualifiedName) {
            case "org.apache.ibatis.annotations.Select" -> "select";
            case "org.apache.ibatis.annotations.Insert" -> "insert";
            case "org.apache.ibatis.annotations.Update" -> "update";
            case "org.apache.ibatis.annotations.Delete" -> "delete";
            default -> null;
        };
        return statementTag == null
                ? AnnotationSelection.failure(FailureCode.UNSUPPORTED_ANNOTATION,
                "不是可迁移的 MyBatis 内联 SQL 注解")
                : new AnnotationSelection(target, statementTag, null);
    }

    private static @Nullable String sql(@NotNull PsiAnnotation annotation) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue("value");
        if (value == null) {
            value = annotation.findDeclaredAttributeValue(null);
        }
        if (value == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (value instanceof PsiArrayInitializerMemberValue array) {
            for (PsiAnnotationMemberValue initializer : array.getInitializers()) {
                ProgressManager.checkCanceled();
                String part = constantString(initializer);
                if (part == null) {
                    return null;
                }
                parts.add(part);
            }
        } else {
            String part = constantString(value);
            if (part == null) {
                return null;
            }
            parts.add(part);
        }
        return String.join(" ", parts).trim();
    }

    private static @Nullable String constantString(@NotNull PsiAnnotationMemberValue value) {
        Object constant = JavaPsiFacade.getInstance(value.getProject())
                .getConstantEvaluationHelper()
                .computeConstantExpression(value);
        return constant instanceof String text ? text : null;
    }

    private static @Nullable String resultType(@Nullable PsiType type) {
        ProgressManager.checkCanceled();
        if (type == null || PsiTypes.voidType().equals(type) || type instanceof PsiWildcardType) {
            return null;
        }
        if (type instanceof PsiPrimitiveType primitive) {
            return primitive.getCanonicalText();
        }
        if (type instanceof PsiArrayType array) {
            return resultType(array.getComponentType());
        }
        if (!(type instanceof PsiClassType classType)) {
            return null;
        }
        PsiClass resolved = classType.resolve();
        if (resolved == null || resolved instanceof PsiTypeParameter
                || resolved.getContainingClass() != null) {
            return null;
        }
        String qualifiedName = resolved.getQualifiedName();
        if (qualifiedName == null) {
            return null;
        }
        if (MAP_TYPES.contains(qualifiedName)) {
            return "java.util.Map";
        }
        PsiType[] parameters = classType.getParameters();
        if (CONTAINER_TYPES.contains(qualifiedName)) {
            return parameters.length == 1 ? resultType(parameters[0]) : null;
        }
        return parameters.length == 0 ? qualifiedName : null;
    }

    private static @Nullable String removeAnnotation(
            @NotNull String javaText,
            @NotNull TextRange range) {
        if (range.getStartOffset() < 0 || range.getEndOffset() > javaText.length()) {
            return null;
        }
        int lineStart = javaText.lastIndexOf('\n', Math.max(0, range.getStartOffset() - 1)) + 1;
        int nextNewline = javaText.indexOf('\n', range.getEndOffset());
        int lineEnd = nextNewline < 0 ? javaText.length() : nextNewline + 1;
        String before = javaText.substring(lineStart, range.getStartOffset());
        String after = javaText.substring(
                range.getEndOffset(),
                nextNewline < 0 ? javaText.length() : nextNewline);
        if (before.isBlank() && after.isBlank()) {
            return javaText.substring(0, lineStart) + javaText.substring(lineEnd);
        }
        int end = range.getEndOffset();
        while (end < javaText.length()
                && (javaText.charAt(end) == ' ' || javaText.charAt(end) == '\t')) {
            end++;
        }
        return javaText.substring(0, range.getStartOffset()) + javaText.substring(end);
    }

    private static @Nullable String insertStatement(
            @NotNull String xmlText,
            @NotNull XmlTag mapperRoot,
            @NotNull String statementTag,
            @NotNull String statementId,
            @Nullable String resultType,
            @NotNull String sql) {
        TextRange mapperRange = mapperRoot.getTextRange();
        if (mapperRange.getEndOffset() > xmlText.length()) {
            return null;
        }
        int localClosing = mapperRoot.getText().lastIndexOf("</mapper>");
        if (localClosing < 0) {
            return null;
        }
        int closingStart = mapperRange.getStartOffset() + localClosing;
        int lineStart = xmlText.lastIndexOf('\n', Math.max(0, closingStart - 1)) + 1;
        String closingIndent = xmlText.substring(lineStart, closingStart);
        if (!closingIndent.isBlank()) {
            return null;
        }
        String newline = xmlText.contains("\r\n") ? "\r\n" : "\n";
        String childIndent = closingIndent + "    ";
        String sqlIndent = childIndent + "    ";
        String normalizedSql = StringUtil.convertLineSeparators(sql, "\n");
        String escapedSql = escapeXml(normalizedSql)
                .replace("\n", newline + sqlIndent);
        StringBuilder statement = new StringBuilder()
                .append(childIndent)
                .append('<').append(statementTag)
                .append(" id=\"").append(escapeXml(statementId)).append('"');
        if (resultType != null && !resultType.isBlank()) {
            statement.append(" resultType=\"")
                    .append(escapeXml(resultType))
                    .append('"');
        }
        statement.append('>').append(newline)
                .append(sqlIndent).append(escapedSql).append(newline)
                .append(childIndent).append("</").append(statementTag).append('>')
                .append(newline);
        return xmlText.substring(0, lineStart)
                + statement
                + xmlText.substring(lineStart);
    }

    private static @NotNull String escapeXml(@NotNull String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static @Nullable String currentText(@NotNull PsiFile file) {
        VirtualFile virtualFile = file.getVirtualFile();
        if (virtualFile == null) {
            return null;
        }
        Document document = FileDocumentManager.getInstance().getCachedDocument(virtualFile);
        if (document != null) {
            return document.getText();
        }
        try {
            return VfsUtilCore.loadText(virtualFile);
        } catch (java.io.IOException ignored) {
            return null;
        }
    }

    private static @Nullable String relativePath(
            @NotNull VirtualFile projectRoot,
            @NotNull VirtualFile file) {
        return VfsUtilCore.isAncestor(projectRoot, file, false)
                ? VfsUtilCore.getRelativePath(file, projectRoot, '/')
                : null;
    }

    private static @NotNull MyBatisGenerationPlanEntry updateEntry(
            @NotNull MyBatisGenerationArtifactKind kind,
            @NotNull String path,
            @NotNull String existing,
            @NotNull String proposed,
            @NotNull String regionId) {
        MyBatisGeneratedArtifact artifact = new MyBatisGeneratedArtifact(
                kind,
                path,
                proposed,
                Set.of(regionId));
        return new MyBatisGenerationPlanEntry(
                artifact,
                MyBatisGenerationPlanStatus.UPDATE,
                Optional.of(existing),
                Optional.of(proposed),
                Optional.empty(),
                Optional.empty());
    }

    private static @NotNull Result.Failure failure(
            @NotNull FailureCode code,
            @NotNull String message) {
        return new Result.Failure(code, message);
    }

    public enum FailureCode {
        SOURCE_INVALID,
        UNSUPPORTED_SOURCE,
        OVERLOADED_METHOD,
        UNSUPPORTED_ANNOTATION,
        UNSUPPORTED_SQL,
        SQL_TOO_LARGE,
        MAPPER_XML_NOT_FOUND,
        AMBIGUOUS_MAPPER_XML,
        STATEMENT_ALREADY_EXISTS,
        UNSUPPORTED_RETURN_TYPE,
        READ_ONLY_TARGET,
        TARGET_OUTSIDE_PROJECT,
        SOURCE_CHANGED
    }

    public sealed interface Result {
        record Success(
                @NotNull MyBatisGenerationPlan plan,
                @NotNull String namespace,
                @NotNull String statementId) implements Result {
        }

        record Failure(
                @NotNull FailureCode code,
                @NotNull String message) implements Result {
        }
    }

    private record AnnotationSelection(
            @Nullable PsiAnnotation annotation,
            @Nullable String statementTag,
            @Nullable Result.Failure failure) {
        private static @NotNull AnnotationSelection failure(
                @NotNull FailureCode code,
                @NotNull String message) {
            return new AnnotationSelection(null, null,
                    MyBatisAnnotationSqlMigrationPlanner.failure(code, message));
        }
    }
}
