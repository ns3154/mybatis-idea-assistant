package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.modcommand.ModCommand;
import com.intellij.modcommand.ModCommandQuickFix;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.model.MyBatisAnnotationModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 为 Mapper 方法参数添加显式 {@code @Param} 名称。
 */
final class AddMyBatisParamAnnotationQuickFix extends ModCommandQuickFix {
    private static final int ALL_PARAMETERS = -1;
    private final SmartPsiElementPointer<PsiMethod> methodPointer;
    private final int parameterIndex;
    private final String parameterName;

    private AddMyBatisParamAnnotationQuickFix(
            @NotNull SmartPsiElementPointer<PsiMethod> methodPointer,
            int parameterIndex,
            @NotNull String parameterName) {
        this.methodPointer = methodPointer;
        this.parameterIndex = parameterIndex;
        this.parameterName = parameterName;
    }

    static @NotNull AddMyBatisParamAnnotationQuickFix forParameter(
            @NotNull SmartPsiElementPointer<PsiMethod> methodPointer,
            int parameterIndex,
            @NotNull String parameterName) {
        return new AddMyBatisParamAnnotationQuickFix(
                methodPointer,
                parameterIndex,
                parameterName);
    }

    static @NotNull AddMyBatisParamAnnotationQuickFix forAll(
            @NotNull SmartPsiElementPointer<PsiMethod> methodPointer) {
        return new AddMyBatisParamAnnotationQuickFix(
                methodPointer,
                ALL_PARAMETERS,
                "");
    }

    @Override
    public @NotNull String getFamilyName() {
        return MyBatisAssistantBundle.message("quickfix.add.param.family");
    }

    @Override
    public @NotNull String getName() {
        return parameterIndex == ALL_PARAMETERS
                ? MyBatisAssistantBundle.message("quickfix.add.param.all.name")
                : MyBatisAssistantBundle.message(
                        "quickfix.add.param.name",
                        parameterName);
    }

    @Override
    public @NotNull ModCommand perform(
            @NotNull Project project,
            @NotNull ProblemDescriptor descriptor) {
        ProgressManager.checkCanceled();
        PsiMethod method = methodPointer.getElement();
        if (method == null
                || !method.isValid()
                || project.isDisposed()
                || !project.isOpen()) {
            return ModCommand.error(MyBatisAssistantBundle.message(
                    "quickfix.add.param.target.invalid"));
        }
        return ModCommand.psiUpdate(method, (writableMethod, updater) -> {
            ProgressManager.checkCanceled();
            PsiParameter[] parameters = writableMethod.getParameterList().getParameters();
            if (parameterIndex == ALL_PARAMETERS) {
                PsiAnnotation lastAdded = null;
                for (PsiParameter parameter : parameters) {
                    ProgressManager.checkCanceled();
                    PsiAnnotation added = addAnnotationIfMissing(project, parameter);
                    if (added != null) {
                        lastAdded = added;
                    }
                }
                if (lastAdded != null) {
                    updater.select(lastAdded);
                    updater.moveCaretTo(lastAdded);
                }
                return;
            }
            if (parameterIndex < 0 || parameterIndex >= parameters.length) {
                return;
            }
            PsiParameter parameter = parameters[parameterIndex];
            if (!parameterName.equals(parameter.getName())) {
                return;
            }
            PsiAnnotation added = addAnnotationIfMissing(project, parameter);
            if (added != null) {
                updater.select(added);
                updater.moveCaretTo(added);
            }
        });
    }

    static boolean hasParamAnnotation(@NotNull PsiParameter parameter) {
        for (PsiAnnotation annotation : parameter.getAnnotations()) {
            ProgressManager.checkCanceled();
            if (MyBatisAnnotationModel.PARAM_ANNOTATION.equals(annotation.getQualifiedName())) {
                return true;
            }
            PsiJavaCodeReferenceElement nameReference = annotation.getNameReferenceElement();
            if (nameReference != null && "Param".equals(nameReference.getReferenceName())) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable PsiAnnotation addAnnotationIfMissing(
            @NotNull Project project,
            @NotNull PsiParameter parameter) {
        if (hasParamAnnotation(parameter)) {
            return null;
        }
        PsiModifierList modifierList = parameter.getModifierList();
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        PsiFile containingFile = parameter.getContainingFile();
        PsiClass annotationClass = facade.findClass(
                MyBatisAnnotationModel.PARAM_ANNOTATION,
                parameter.getResolveScope());
        boolean shortNameAvailable = containingFile instanceof PsiJavaFile javaFile
                && annotationClass != null
                && JavaCodeStyleManager.getInstance(project).addImport(
                        javaFile,
                        annotationClass);
        String annotationName = shortNameAvailable
                ? "Param"
                : MyBatisAnnotationModel.PARAM_ANNOTATION;
        PsiAnnotation template = facade.getElementFactory().createAnnotationFromText(
                "@" + annotationName + "(\"" + parameter.getName() + "\")",
                parameter);
        return (PsiAnnotation) modifierList.add(template);
    }
}
