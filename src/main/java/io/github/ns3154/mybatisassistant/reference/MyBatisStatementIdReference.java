package io.github.ns3154.mybatisassistant.reference;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPolyVariantReferenceBase;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.xml.XmlAttributeValue;
import io.github.ns3154.mybatisassistant.resolve.MyBatisMapperMethodResolver;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

final class MyBatisStatementIdReference extends PsiPolyVariantReferenceBase<XmlAttributeValue> {
    private final String namespace;

    MyBatisStatementIdReference(
            @NotNull XmlAttributeValue element,
            @NotNull String namespace) {
        super(element, MyBatisReferenceSupport.valueRange(element), false);
        this.namespace = namespace;
    }

    @Override
    public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        ProgressManager.checkCanceled();
        if (!getElement().isValid()) {
            return ResolveResult.EMPTY_ARRAY;
        }
        Project project = getElement().getProject();
        String statementId = getElement().getValue().trim();
        if (project.isDisposed()
                || !project.isOpen()
                || DumbService.isDumb(project)
                || !MyBatisReferenceSupport.isStaticReferenceValue(statementId)) {
            return ResolveResult.EMPTY_ARRAY;
        }
        List<ResolveResult> results = new ArrayList<>();
        for (PsiMethod target : MyBatisMapperMethodResolver.find(
                getElement(),
                namespace,
                statementId)) {
            ProgressManager.checkCanceled();
            results.add(new PsiElementResolveResult(target));
        }
        return results.toArray(ResolveResult.EMPTY_ARRAY);
    }
}
