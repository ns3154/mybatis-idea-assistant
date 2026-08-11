package io.github.ns3154.mybatisassistant.sqltool.testgen;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiTypes;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 只从 Java PSI 提取 Mapper 方法签名，不加载或反射用户类。
 */
public final class MyBatisMapperTestRequestFactory {
    private MyBatisMapperTestRequestFactory() {
    }

    public static @NotNull Result create(
            @NotNull PsiMethod method,
            @NotNull MyBatisJUnitPlatform platform) {
        ProgressManager.checkCanceled();
        PsiClass owner = method.getContainingClass();
        if (!method.isValid() || owner == null || !owner.isValid()
                || !owner.isInterface() || owner.isAnnotationType()
                || method.isConstructor()
                || method.hasModifierProperty(PsiModifier.STATIC)
                || method.hasModifierProperty(PsiModifier.DEFAULT)
                || method.getBody() != null) {
            return new Result.Failure("请选择 Mapper 接口中的抽象实例方法");
        }
        String qualifiedName = owner.getQualifiedName();
        String simpleName = owner.getName();
        if (qualifiedName == null || qualifiedName.isBlank()
                || simpleName == null || simpleName.isBlank()) {
            return new Result.Failure("Mapper 接口必须具有稳定的全限定名");
        }
        if (owner.getTypeParameters().length > 0 || method.getTypeParameters().length > 0) {
            return new Result.Failure("首版测试骨架不猜测泛型 Mapper 或泛型方法的测试类型");
        }
        List<MyBatisMapperTestParameter> parameters = new ArrayList<>();
        StringBuilder signature = new StringBuilder(method.getName()).append('(');
        PsiParameter[] psiParameters = method.getParameterList().getParameters();
        for (int index = 0; index < psiParameters.length; index++) {
            ProgressManager.checkCanceled();
            PsiParameter parameter = psiParameters[index];
            String type = parameter.getType().getCanonicalText();
            parameters.add(new MyBatisMapperTestParameter(parameter.getName(), type));
            if (index > 0) {
                signature.append(',');
            }
            signature.append(type);
        }
        signature.append(')');
        String packageName = owner.getContainingFile() instanceof PsiJavaFile javaFile
                ? javaFile.getPackageName() : "";
        return new Result.Success(new MyBatisMapperTestRequest(
                packageName,
                qualifiedName,
                simpleName,
                method.getName(),
                signature.toString(),
                PsiTypes.voidType().equals(method.getReturnType()),
                parameters,
                platform));
    }

    public sealed interface Result {
        record Success(@NotNull MyBatisMapperTestRequest request) implements Result {
        }

        record Failure(@NotNull String message) implements Result {
        }
    }
}
