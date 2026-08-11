package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.index.MyBatisXmlSymbolLocator;
import io.github.ns3154.mybatisassistant.resolve.MyBatisStatementResolution;
import io.github.ns3154.mybatisassistant.resolve.MyBatisStatementResolver;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 检查已有 Mapper XML 中缺失的 statement。
 */
public final class MyBatisMissingStatementInspection extends AbstractBaseJavaLocalInspectionTool {
    @Override
    public @NotNull PsiElementVisitor buildVisitor(
            @NotNull ProblemsHolder holder,
            boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                ProgressManager.checkCanceled();
                MyBatisStatementResolution resolution = MyBatisStatementResolver.resolve(method);
                if (!(resolution instanceof MyBatisStatementResolution.StatementMissing missing)) {
                    return;
                }

                PsiIdentifier nameIdentifier = method.getNameIdentifier();
                if (nameIdentifier == null || !nameIdentifier.isValid()) {
                    return;
                }
                holder.registerProblem(
                        nameIdentifier,
                        MyBatisAssistantBundle.message(
                                "inspection.missing.statement.problem",
                                missing.namespace(),
                                missing.statementId()),
                        quickFixes(method, missing));
            }
        };
    }

    private static LocalQuickFix @NotNull [] quickFixes(
            @NotNull PsiMethod method,
            @NotNull MyBatisStatementResolution.StatementMissing missing) {
        List<XmlTag> mapperRoots = MyBatisXmlSymbolLocator.findMapperRoots(
                method.getProject(),
                missing.namespace(),
                method.getResolveScope());
        SmartPointerManager pointerManager = SmartPointerManager.getInstance(
                method.getProject());
        List<LocalQuickFix> fixes = new ArrayList<>();
        for (XmlTag mapperRoot : mapperRoots) {
            ProgressManager.checkCanceled();
            String targetName = targetName(method.getProject(), mapperRoot);
            for (String statementTag : List.of("select", "insert", "update", "delete")) {
                fixes.add(new CreateMyBatisStatementQuickFix(
                        pointerManager.createSmartPsiElementPointer(mapperRoot),
                        missing.namespace(),
                        missing.statementId(),
                        statementTag,
                        targetName));
            }
        }
        return fixes.toArray(LocalQuickFix.EMPTY_ARRAY);
    }

    private static @NotNull String targetName(
            @NotNull Project project,
            @NotNull XmlTag mapperRoot) {
        VirtualFile targetFile = mapperRoot.getContainingFile().getVirtualFile();
        if (targetFile == null) {
            return mapperRoot.getContainingFile().getName();
        }
        VirtualFile projectDirectory = ProjectUtil.guessProjectDir(project);
        if (projectDirectory != null) {
            String relativePath = VfsUtilCore.getRelativePath(
                    targetFile,
                    projectDirectory,
                    '/');
            if (relativePath != null) {
                return relativePath;
            }
        }
        return targetFile.getPresentableUrl();
    }
}
