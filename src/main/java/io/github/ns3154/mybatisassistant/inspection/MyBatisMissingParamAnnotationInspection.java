package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.model.MyBatisAnnotationModel;
import io.github.ns3154.mybatisassistant.model.MyBatisMapperModelResolution;
import io.github.ns3154.mybatisassistant.model.MyBatisMapperModelResolver;
import io.github.ns3154.mybatisassistant.model.MyBatisStatementSourceKind;
import org.jetbrains.annotations.NotNull;

/**
 * 建议为多参数 XML Mapper 方法添加显式 {@code @Param} 名称。
 */
public final class MyBatisMissingParamAnnotationInspection
        extends AbstractBaseJavaLocalInspectionTool {
    @Override
    public @NotNull PsiElementVisitor buildVisitor(
            @NotNull ProblemsHolder holder,
            boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                ProgressManager.checkCanceled();
                if (!isCandidate(method) || DumbService.isDumb(method.getProject())) {
                    return;
                }
                try {
                    PsiClass mapper = method.getContainingClass();
                    if (mapper == null
                            || !(MyBatisMapperModelResolver.resolve(mapper)
                            instanceof MyBatisMapperModelResolution.Found)
                            || MyBatisAnnotationModel.statementSource(method)
                            != MyBatisStatementSourceKind.XML) {
                        return;
                    }
                    registerMissingParameters(holder, method);
                } catch (IndexNotReadyException ignored) {
                    // Smart -> Dumb 竞态时不提供可能过期的建议。
                }
            }
        };
    }

    private static boolean isCandidate(@NotNull PsiMethod method) {
        PsiClass mapper = method.getContainingClass();
        return method.isValid()
                && !method.getProject().isDisposed()
                && method.getProject().isOpen()
                && mapper != null
                && mapper.isValid()
                && mapper.isInterface()
                && !mapper.isAnnotationType()
                && method.hasModifierProperty(PsiModifier.ABSTRACT)
                && !method.hasModifierProperty(PsiModifier.STATIC)
                && !method.hasModifierProperty(PsiModifier.DEFAULT)
                && method.getBody() == null
                && mapper.findMethodsByName(method.getName(), false).length == 1
                && method.getParameterList().getParametersCount() > 1;
    }

    private static void registerMissingParameters(
            @NotNull ProblemsHolder holder,
            @NotNull PsiMethod method) {
        SmartPsiElementPointer<PsiMethod> methodPointer = SmartPointerManager
                .getInstance(method.getProject())
                .createSmartPsiElementPointer(method);
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int index = 0; index < parameters.length; index++) {
            ProgressManager.checkCanceled();
            PsiParameter parameter = parameters[index];
            if (AddMyBatisParamAnnotationQuickFix.hasParamAnnotation(parameter)) {
                continue;
            }
            PsiIdentifier nameIdentifier = parameter.getNameIdentifier();
            if (nameIdentifier == null || !nameIdentifier.isValid()) {
                continue;
            }
            LocalQuickFix parameterFix = AddMyBatisParamAnnotationQuickFix.forParameter(
                    methodPointer,
                    index,
                    parameter.getName());
            LocalQuickFix allFix = AddMyBatisParamAnnotationQuickFix.forAll(methodPointer);
            holder.registerProblem(
                    nameIdentifier,
                    MyBatisAssistantBundle.message(
                            "inspection.missing.param.problem",
                            parameter.getName()),
                    parameterFix,
                    allFix);
        }
    }
}
