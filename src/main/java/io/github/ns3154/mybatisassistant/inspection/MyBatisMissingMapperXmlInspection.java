package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiManager;
import com.intellij.psi.SmartPointerManager;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.index.MyBatisXmlSymbolLocator;
import io.github.ns3154.mybatisassistant.model.MyBatisMapperMethodModel;
import io.github.ns3154.mybatisassistant.model.MyBatisMapperModel;
import io.github.ns3154.mybatisassistant.model.MyBatisMapperModelResolution;
import io.github.ns3154.mybatisassistant.model.MyBatisMapperModelResolver;
import io.github.ns3154.mybatisassistant.model.MyBatisStatementSourceKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.java.JavaResourceRootType;

import java.util.ArrayList;
import java.util.List;

/**
 * 检查已经确认的 Mapper 接口是否缺少 Mapper XML。
 */
public final class MyBatisMissingMapperXmlInspection
        extends AbstractBaseJavaLocalInspectionTool {
    @Override
    public @NotNull PsiElementVisitor buildVisitor(
            @NotNull ProblemsHolder holder,
            boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitClass(@NotNull PsiClass psiClass) {
                ProgressManager.checkCanceled();
                if (!psiClass.isValid()
                        || !psiClass.isInterface()
                        || psiClass.isAnnotationType()
                        || psiClass.getProject().isDisposed()
                        || !psiClass.getProject().isOpen()
                        || DumbService.isDumb(psiClass.getProject())) {
                    return;
                }
                String namespace = psiClass.getQualifiedName();
                PsiIdentifier nameIdentifier = psiClass.getNameIdentifier();
                if (namespace == null || nameIdentifier == null || !nameIdentifier.isValid()) {
                    return;
                }
                try {
                    MyBatisMapperModelResolution resolution =
                            MyBatisMapperModelResolver.resolve(psiClass);
                    if (!(resolution instanceof MyBatisMapperModelResolution.Found found)
                            || !requiresXml(found.model())
                            || MyBatisXmlSymbolLocator.hasMapperXml(
                                    psiClass.getProject(),
                                    namespace,
                                    psiClass.getResolveScope())) {
                        return;
                    }
                    holder.registerProblem(
                            nameIdentifier,
                            MyBatisAssistantBundle.message(
                                    "inspection.missing.mapper.xml.problem",
                                    namespace),
                            quickFixes(psiClass, namespace));
                } catch (IndexNotReadyException ignored) {
                    // Smart -> Dumb 竞态时不报告缺失文件。
                }
            }
        };
    }

    private static boolean requiresXml(@NotNull MyBatisMapperModel model) {
        for (MyBatisMapperMethodModel method : model.methods()) {
            ProgressManager.checkCanceled();
            if (method.statementSource() == MyBatisStatementSourceKind.XML) {
                return true;
            }
        }
        return false;
    }

    private static LocalQuickFix @NotNull [] quickFixes(
            @NotNull PsiClass mapper,
            @NotNull String namespace) {
        Module module = ModuleUtilCore.findModuleForPsiElement(mapper);
        if (module == null || module.isDisposed()) {
            return LocalQuickFix.EMPTY_ARRAY;
        }
        SmartPointerManager pointerManager = SmartPointerManager.getInstance(
                mapper.getProject());
        PsiManager psiManager = PsiManager.getInstance(mapper.getProject());
        String simpleName = mapper.getName();
        if (simpleName == null) {
            return LocalQuickFix.EMPTY_ARRAY;
        }
        String fileName = simpleName + ".xml";
        List<LocalQuickFix> fixes = new ArrayList<>();
        for (VirtualFile resourceRoot : ModuleRootManager.getInstance(module)
                .getSourceRoots(JavaResourceRootType.RESOURCE)) {
            ProgressManager.checkCanceled();
            PsiDirectory directory = psiManager.findDirectory(resourceRoot);
            if (directory == null || !directory.isValid()) {
                continue;
            }
            String targetName = targetName(mapper, module, resourceRoot, fileName);
            fixes.add(new CreateMyBatisMapperXmlQuickFix(
                    pointerManager.createSmartPsiElementPointer(directory),
                    namespace,
                    fileName,
                    targetName));
        }
        return fixes.toArray(LocalQuickFix.EMPTY_ARRAY);
    }

    private static @NotNull String targetName(
            @NotNull PsiClass mapper,
            @NotNull Module module,
            @NotNull VirtualFile resourceRoot,
            @NotNull String fileName) {
        VirtualFile projectDirectory = ProjectUtil.guessProjectDir(mapper.getProject());
        if (projectDirectory != null) {
            String relativePath = VfsUtilCore.getRelativePath(
                    resourceRoot,
                    projectDirectory,
                    '/');
            if (relativePath != null) {
                return relativePath + "/mapper/" + fileName;
            }
        }
        return module.getName()
                + ':'
                + resourceRoot.getPresentableUrl()
                + "/mapper/"
                + fileName;
    }
}
