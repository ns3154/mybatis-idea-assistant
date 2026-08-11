package io.github.ns3154.mybatisassistant.generator;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodGeneration;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 把 S9 方法声明和 XML statement 合入 S8 全量计划，继续复用原子写入与 TOCTOU。
 */
public final class MyBatisMethodPlanIntegrator {
    private MyBatisMethodPlanIntegrator() {
    }

    public static @NotNull MyBatisGenerationPlan integrate(
            @NotNull Project project,
            @NotNull MyBatisGenerationPlan plan,
            @NotNull MyBatisMethodGeneration generation) {
        long mapperCount = plan.entries().stream().filter(entry ->
                entry.artifact().kind() == MyBatisGenerationArtifactKind.MAPPER).count();
        long xmlCount = plan.entries().stream().filter(entry ->
                entry.artifact().kind() == MyBatisGenerationArtifactKind.XML).count();
        if (mapperCount != 1 || xmlCount != 1) {
            throw new IllegalArgumentException(
                    "S9 方法生成要求计划恰好包含一个 Mapper 与一个 XML");
        }
        List<MyBatisGenerationPlanEntry> entries = new ArrayList<>();
        for (MyBatisGenerationPlanEntry entry : plan.entries()) {
            if (entry.status() == MyBatisGenerationPlanStatus.CONFLICT
                    || entry.artifact().kind() != MyBatisGenerationArtifactKind.MAPPER
                            && entry.artifact().kind() != MyBatisGenerationArtifactKind.XML) {
                entries.add(entry);
                continue;
            }
            entries.add(integrateEntry(project, entry, generation));
        }
        return new MyBatisGenerationPlan(entries);
    }

    private static @NotNull MyBatisGenerationPlanEntry integrateEntry(
            @NotNull Project project,
            @NotNull MyBatisGenerationPlanEntry entry,
            @NotNull MyBatisMethodGeneration generation) {
        String current = entry.proposedText().orElseThrow();
        boolean java = entry.artifact().kind() == MyBatisGenerationArtifactKind.MAPPER;
        MyBatisGeneratedRegion.Style style = java
                ? MyBatisGeneratedRegion.Style.JAVA : MyBatisGeneratedRegion.Style.XML;
        String body = java
                ? "    " + generation.javaMethod() + "\n"
                : indent(generation.xmlStatement(), 1);
        boolean hasMarker = MyBatisMethodRegionMerger.hasMarker(
                current, generationName(generation));
        boolean duplicate = !hasMarker && (java
                ? hasJavaMethod(project, entry, current, generationName(generation))
                : hasXmlStatement(project, entry, current, generationName(generation)));
        MyBatisSafeMergeResult merged = MyBatisMethodRegionMerger.merge(
                current, style, generationName(generation), body, duplicate);
        if (merged instanceof MyBatisSafeMergeResult.Conflict conflict) {
            return new MyBatisGenerationPlanEntry(
                    entry.artifact(),
                    MyBatisGenerationPlanStatus.CONFLICT,
                    entry.existingText(),
                    Optional.empty(),
                    Optional.of(conflict.code()),
                    Optional.of(conflict.message()));
        }
        String text = ((MyBatisSafeMergeResult.Ready) merged).text();
        try {
            MyBatisGenerationPsiValidator.validate(project, entry.artifact(), text);
        } catch (IllegalArgumentException invalid) {
            return new MyBatisGenerationPlanEntry(
                    entry.artifact(),
                    MyBatisGenerationPlanStatus.CONFLICT,
                    entry.existingText(),
                    Optional.empty(),
                    Optional.of(MyBatisSafeMergeConflictCode.INVALID_GENERATED_CONTENT),
                    Optional.of(invalid.getMessage()));
        }
        MyBatisGenerationPlanStatus status = entry.existingText()
                .filter(text::equals)
                .map(ignored -> MyBatisGenerationPlanStatus.UNCHANGED)
                .orElse(entry.existingText().isPresent()
                        ? MyBatisGenerationPlanStatus.UPDATE
                        : MyBatisGenerationPlanStatus.CREATE);
        return new MyBatisGenerationPlanEntry(
                entry.artifact(),
                status,
                entry.existingText(),
                Optional.of(text),
                Optional.empty(),
                Optional.empty());
    }

    private static boolean hasJavaMethod(
            @NotNull Project project,
            @NotNull MyBatisGenerationPlanEntry entry,
            @NotNull String text,
            @NotNull String name) {
        PsiFile file = PsiFileFactory.getInstance(project).createFileFromText(
                entry.artifact().relativePath(), JavaFileType.INSTANCE, text);
        if (!(file instanceof PsiJavaFile javaFile) || javaFile.getClasses().length != 1) {
            return false;
        }
        PsiClass type = javaFile.getClasses()[0];
        return type.findMethodsByName(name, false).length > 0;
    }

    private static boolean hasXmlStatement(
            @NotNull Project project,
            @NotNull MyBatisGenerationPlanEntry entry,
            @NotNull String text,
            @NotNull String name) {
        PsiFile file = PsiFileFactory.getInstance(project).createFileFromText(
                entry.artifact().relativePath(),
                com.intellij.ide.highlighter.XmlFileType.INSTANCE,
                text);
        XmlTag root = file instanceof XmlFile xmlFile ? xmlFile.getRootTag() : null;
        if (root == null) {
            return false;
        }
        for (XmlTag tag : root.getSubTags()) {
            if (("select".equals(tag.getName()) || "update".equals(tag.getName())
                    || "delete".equals(tag.getName()) || "insert".equals(tag.getName()))
                    && name.equals(tag.getAttributeValue("id"))) {
                return true;
            }
        }
        return false;
    }

    private static @NotNull String generationName(
            @NotNull MyBatisMethodGeneration generation) {
        String method = generation.javaMethod();
        int parameters = method.indexOf('(');
        int separator = method.lastIndexOf(' ', parameters);
        if (parameters <= 0 || separator < 0) {
            throw new IllegalArgumentException("无法从 Java 方法声明提取方法名");
        }
        return method.substring(separator + 1, parameters);
    }

    private static @NotNull String indent(@NotNull String value, int levels) {
        String prefix = "    ".repeat(levels);
        return value.lines()
                .map(line -> prefix + line)
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
    }
}
