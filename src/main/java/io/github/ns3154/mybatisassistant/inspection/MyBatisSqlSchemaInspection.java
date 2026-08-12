package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiType;
import com.intellij.psi.XmlElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataService;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseSnapshot;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisSourceMapKind;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisSourceMapping;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import io.github.ns3154.mybatisassistant.sql.MyBatisVirtualSqlDiagnosticCode;
import io.github.ns3154.mybatisassistant.sql.intellij.MyBatisSqlPsiResult;
import io.github.ns3154.mybatisassistant.sql.intellij.MyBatisSqlPsiService;
import io.github.ns3154.mybatisassistant.sql.intellij.MyBatisResultMapMappingPlanner;
import io.github.ns3154.mybatisassistant.sql.intellij.MyBatisResultMapSchemaResolver;
import io.github.ns3154.mybatisassistant.sql.intellij.MyBatisSqlSchemaAnalysis;
import io.github.ns3154.mybatisassistant.sql.intellij.MyBatisSqlSchemaAnalyzer;
import io.github.ns3154.mybatisassistant.sql.intellij.MyBatisSqlSymbolKind;
import io.github.ns3154.mybatisassistant.sql.intellij.MyBatisSqlSymbolOccurrence;
import io.github.ns3154.mybatisassistant.sql.intellij.MyBatisSqlSymbolStatus;
import io.github.ns3154.mybatisassistant.reference.MyBatisResultPropertyReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Types;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 仅基于已完成元数据快照报告可证明的表列问题。
 */
public final class MyBatisSqlSchemaInspection extends LocalInspectionTool {
    @Override
    public @NotNull PsiElementVisitor buildVisitor(
            @NotNull ProblemsHolder holder,
            boolean isOnTheFly) {
        return new XmlElementVisitor() {
            @Override
            public void visitXmlTag(@NotNull XmlTag tag) {
                ProgressManager.checkCanceled();
                if (MyBatisXmlModel.isStatement(tag)) {
                    inspectStatement(holder, tag);
                } else if ("resultMap".equals(tag.getName())) {
                    inspectResultMap(holder, tag);
                }
            }
        };
    }

    private static void inspectResultMap(
            @NotNull ProblemsHolder holder,
            @NotNull XmlTag resultMap) {
        MyBatisDatabaseMetadataService metadata = MyBatisDatabaseMetadataService
                .getInstance(resultMap.getProject());
        var latest = metadata.latestVersioned();
        if (latest.isEmpty()) {
            return;
        }
        var versioned = latest.orElseThrow();
        List<MyBatisDatabaseSnapshot> snapshots = versioned.loaded().snapshots();
        var resolvedTable = MyBatisResultMapSchemaResolver.resolve(resultMap, snapshots);
        if (resolvedTable.isEmpty()) {
            return;
        }
        MyBatisDatabaseTable table = resolvedTable.orElseThrow().table();
        for (XmlTag mapping : PsiTreeUtil.findChildrenOfType(resultMap, XmlTag.class)) {
            ProgressManager.checkCanceled();
            XmlAttribute columnAttribute = mapping.getAttribute("column");
            XmlAttributeValue columnValue = columnAttribute == null
                    ? null
                    : columnAttribute.getValueElement();
            if (columnValue == null || !isSimpleColumnName(columnValue.getValue().trim())) {
                continue;
            }
            List<MyBatisDatabaseColumn> columns = table.columns().stream()
                    .filter(column -> equalsName(column.name(), columnValue.getValue().trim()))
                    .toList();
            if (columns.isEmpty()) {
                holder.registerProblem(
                        columnValue,
                        valueRange(columnValue),
                        MyBatisAssistantBundle.message(
                                "inspection.sql.schema.resultmap.column.missing",
                                columnValue.getValue().trim()));
                continue;
            }
            if (columns.size() == 1) {
                inspectPropertyType(holder, mapping, columns.getFirst());
            }
        }
        var plan = MyBatisResultMapMappingPlanner.plan(
                resultMap,
                resolvedTable.orElseThrow());
        if (plan.isEmpty() || plan.orElseThrow().entries().isEmpty()) {
            return;
        }
        XmlAttribute idAttribute = resultMap.getAttribute("id");
        XmlAttributeValue idValue = idAttribute == null ? null : idAttribute.getValueElement();
        XmlTag mapper = resultMap.getParentTag();
        PsiFile resultMapFile = resultMap.getContainingFile();
        var virtualFile = resultMapFile == null ? null : resultMapFile.getVirtualFile();
        String id = resultMap.getAttributeValue("id");
        String namespace = mapper == null ? null : MyBatisXmlModel.namespace(mapper);
        if (idValue == null || id == null || namespace == null || virtualFile == null) {
            return;
        }
        holder.registerProblem(
                idValue,
                MyBatisAssistantBundle.message(
                        "inspection.sql.schema.resultmap.unmapped",
                        table.name(),
                        plan.orElseThrow().entries().size()),
                ProblemHighlightType.INFORMATION,
                valueRange(idValue),
                new FillMyBatisResultMapFieldsQuickFix(
                        namespace,
                        id,
                        virtualFile.getUrl(),
                        resultMap.getText(),
                        plan.orElseThrow(),
                        versioned.generation()));
    }

    private static void inspectPropertyType(
            @NotNull ProblemsHolder holder,
            @NotNull XmlTag mapping,
            @NotNull MyBatisDatabaseColumn column) {
        XmlAttribute propertyAttribute = mapping.getAttribute("property");
        XmlAttributeValue propertyValue = propertyAttribute == null
                ? null
                : propertyAttribute.getValueElement();
        if (propertyValue == null) {
            return;
        }
        Set<String> javaTypes = new LinkedHashSet<>();
        for (PsiReference reference : propertyValue.getReferences()) {
            ProgressManager.checkCanceled();
            if (!(reference instanceof MyBatisResultPropertyReference propertyReference)) {
                continue;
            }
            for (var result : propertyReference.multiResolve(false)) {
                PsiType type = targetType(result.getElement());
                if (type != null) {
                    javaTypes.add(TypeConversionUtil.erasure(type).getCanonicalText());
                }
            }
        }
        if (javaTypes.size() != 1) {
            return;
        }
        String javaType = javaTypes.iterator().next();
        if (!compatible(javaType, column.jdbcType())) {
            holder.registerProblem(
                    propertyValue,
                    valueRange(propertyValue),
                    MyBatisAssistantBundle.message(
                            "inspection.sql.schema.property.type.mismatch",
                            javaType,
                            column.name(),
                            column.typeName()));
        }
    }

    private static @Nullable PsiType targetType(@Nullable PsiElement target) {
        if (target instanceof PsiField field) {
            return field.getType();
        }
        if (!(target instanceof PsiMethod method)) {
            return null;
        }
        if (method.getName().startsWith("set")
                && method.getParameterList().getParametersCount() == 1) {
            return method.getParameterList().getParameters()[0].getType();
        }
        return method.getReturnType();
    }

    private static boolean compatible(@NotNull String javaType, int jdbcType) {
        JavaTypeCategory javaCategory = JavaTypeCategory.of(javaType);
        JavaTypeCategory jdbcCategory = switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                    Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL ->
                    JavaTypeCategory.NUMBER;
            case Types.BIT, Types.BOOLEAN -> JavaTypeCategory.BOOLEAN;
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR,
                    Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB ->
                    JavaTypeCategory.STRING;
            case Types.DATE, Types.TIME, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP,
                    Types.TIMESTAMP_WITH_TIMEZONE -> JavaTypeCategory.TEMPORAL;
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB ->
                    JavaTypeCategory.BINARY;
            default -> JavaTypeCategory.UNKNOWN;
        };
        return javaCategory == JavaTypeCategory.UNKNOWN
                || jdbcCategory == JavaTypeCategory.UNKNOWN
                || javaCategory == jdbcCategory;
    }

    private static boolean equalsName(@NotNull String first, @NotNull String second) {
        return first.equalsIgnoreCase(second);
    }

    private static boolean isSimpleColumnName(@NotNull String value) {
        if (value.isEmpty()) {
            return false;
        }
        int first = value.codePointAt(0);
        if (!Character.isUnicodeIdentifierStart(first) && first != '_') {
            return false;
        }
        for (int offset = Character.charCount(first); offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isUnicodeIdentifierPart(codePoint) && codePoint != '$') {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    private static @NotNull TextRange valueRange(@NotNull XmlAttributeValue value) {
        return new TextRange(1, Math.max(1, value.getTextLength() - 1));
    }

    private enum JavaTypeCategory {
        NUMBER,
        BOOLEAN,
        STRING,
        TEMPORAL,
        BINARY,
        UNKNOWN;

        private static @NotNull JavaTypeCategory of(@NotNull String canonicalType) {
            return switch (canonicalType) {
                case "byte", "short", "int", "long", "float", "double",
                        "java.lang.Byte", "java.lang.Short", "java.lang.Integer",
                        "java.lang.Long", "java.lang.Float", "java.lang.Double",
                        "java.lang.Number", "java.math.BigInteger", "java.math.BigDecimal" ->
                        NUMBER;
                case "boolean", "java.lang.Boolean" -> BOOLEAN;
                case "char", "java.lang.Character", "java.lang.String",
                        "java.lang.CharSequence" -> STRING;
                case "java.util.Date", "java.sql.Date", "java.sql.Time",
                        "java.sql.Timestamp", "java.time.LocalDate", "java.time.LocalTime",
                        "java.time.LocalDateTime", "java.time.OffsetTime",
                        "java.time.OffsetDateTime", "java.time.Instant" -> TEMPORAL;
                case "byte[]", "java.lang.Byte[]" -> BINARY;
                default -> UNKNOWN;
            };
        }
    }

    private static void inspectStatement(
            @NotNull ProblemsHolder holder,
            @NotNull XmlTag statement) {
        MyBatisDatabaseMetadataService metadata = MyBatisDatabaseMetadataService
                .getInstance(statement.getProject());
        var latest = metadata.latest();
        if (latest.isEmpty()) {
            return;
        }
        MyBatisSqlPsiResult sqlResult = MyBatisSqlPsiService
                .getInstance(statement.getProject())
                .parse(statement);
        if (!(sqlResult instanceof MyBatisSqlPsiResult.Ready ready)
                || hasBlockingSyntaxProblem(ready)) {
            return;
        }
        MyBatisSqlSchemaAnalysis analysis = MyBatisSqlSchemaAnalyzer.analyze(
                ready,
                latest.orElseThrow().snapshots());
        if (!analysis.metadataComplete()) {
            return;
        }
        for (MyBatisSqlSymbolOccurrence occurrence : analysis.occurrences()) {
            if (occurrence.status() == MyBatisSqlSymbolStatus.MISSING
                    || occurrence.status() == MyBatisSqlSymbolStatus.AMBIGUOUS) {
                registerProblem(holder, statement.getContainingFile(), ready, occurrence);
            }
        }
    }

    private static boolean hasBlockingSyntaxProblem(@NotNull MyBatisSqlPsiResult.Ready ready) {
        boolean malformedPlaceholder = ready.virtualSql().diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code()
                        == MyBatisVirtualSqlDiagnosticCode.MALFORMED_PARAMETER_PLACEHOLDER);
        return malformedPlaceholder || !PsiTreeUtil.findChildrenOfType(
                ready.psiFile(),
                PsiErrorElement.class).isEmpty();
    }

    private static void registerProblem(
            @NotNull ProblemsHolder holder,
            @NotNull PsiFile sourceFile,
            @NotNull MyBatisSqlPsiResult.Ready ready,
            @NotNull MyBatisSqlSymbolOccurrence occurrence) {
        for (MyBatisSourceMapping mapping : ready.virtualSql().mappedText().sourceMap()
                .sourceMappings(occurrence.virtualRange())) {
            if (mapping.kind() == MyBatisSourceMapKind.SYNTHETIC
                    || sourceFile.getVirtualFile() == null
                    || !sourceFile.getVirtualFile().getUrl()
                            .equals(mapping.sourceRange().fileUrl())) {
                continue;
            }
            int sourceStart = mapping.sourceRange().range().startOffset();
            PsiElement element = sourceFile.findElementAt(sourceStart);
            if (element == null) {
                continue;
            }
            int relativeStart = sourceStart - element.getTextRange().getStartOffset();
            int relativeEnd = Math.min(
                    element.getTextLength(),
                    mapping.sourceRange().range().endOffset()
                            - element.getTextRange().getStartOffset());
            if (relativeStart >= 0 && relativeStart < relativeEnd) {
                holder.registerProblem(
                        element,
                        new TextRange(relativeStart, relativeEnd),
                        message(occurrence));
                return;
            }
        }
    }

    private static @NotNull String message(@NotNull MyBatisSqlSymbolOccurrence occurrence) {
        String qualifiedName = occurrence.qualifier()
                .map(qualifier -> qualifier + "." + occurrence.name())
                .orElse(occurrence.name());
        String key;
        if (occurrence.kind() == MyBatisSqlSymbolKind.TABLE) {
            key = occurrence.status() == MyBatisSqlSymbolStatus.MISSING
                    ? "inspection.sql.schema.table.missing"
                    : "inspection.sql.schema.table.ambiguous";
        } else {
            key = occurrence.status() == MyBatisSqlSymbolStatus.MISSING
                    ? "inspection.sql.schema.column.missing"
                    : "inspection.sql.schema.column.ambiguous";
        }
        return MyBatisAssistantBundle.message(key, qualifiedName);
    }
}
