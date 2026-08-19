package io.github.ns3154.mybatisassistant.generator;

import com.intellij.ide.highlighter.JavaFileType;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;

/**
 * 候选文本必须经 Java/XML PSI 解析通过才能进入写入计划。
 */
final class MyBatisGenerationPsiValidator {
    private MyBatisGenerationPsiValidator() {
    }

    static void validate(
            @NotNull Project project,
            @NotNull MyBatisGeneratedArtifact artifact,
            @NotNull String text) {
        PsiFile file = artifact.kind() == MyBatisGenerationArtifactKind.XML
                ? PsiFileFactory.getInstance(project).createFileFromText(
                        artifact.relativePath(),
                        com.intellij.ide.highlighter.XmlFileType.INSTANCE,
                        text)
                : PsiFileFactory.getInstance(project).createFileFromText(
                        artifact.relativePath(),
                        JavaFileType.INSTANCE,
                        text);
        if (!PsiTreeUtil.findChildrenOfType(file, PsiErrorElement.class).isEmpty()) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "generator.error.candidate.syntax", artifact.relativePath()));
        }
        if (artifact.kind() == MyBatisGenerationArtifactKind.XML) {
            XmlTag root = file instanceof XmlFile xmlFile ? xmlFile.getRootTag() : null;
            if (root == null || !"mapper".equals(root.getName())) {
                throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                        "generator.error.candidate.mapper.root", artifact.relativePath()));
            }
        } else if (!(file instanceof PsiJavaFile javaFile)
                || javaFile.getClasses().length != 1) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "generator.error.candidate.java.top.level", artifact.relativePath()));
        }
    }
}
