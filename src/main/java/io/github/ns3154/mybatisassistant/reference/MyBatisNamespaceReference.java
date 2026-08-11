package io.github.ns3154.mybatisassistant.reference;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiPolyVariantReferenceBase;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

final class MyBatisNamespaceReference extends PsiPolyVariantReferenceBase<XmlAttributeValue> {
    MyBatisNamespaceReference(@NotNull XmlAttributeValue element) {
        super(element, MyBatisReferenceSupport.valueRange(element), false);
    }

    @Override
    public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        ProgressManager.checkCanceled();
        if (!getElement().isValid()) {
            return ResolveResult.EMPTY_ARRAY;
        }
        Project project = getElement().getProject();
        String namespace = getElement().getValue().trim();
        if (project.isDisposed()
                || !project.isOpen()
                || DumbService.isDumb(project)
                || !MyBatisReferenceSupport.isStaticReferenceValue(namespace)) {
            return ResolveResult.EMPTY_ARRAY;
        }
        try {
            List<ResolveResult> results = new ArrayList<>();
            for (PsiClass target : MyBatisReferenceSupport.findMapperClasses(
                    getElement(),
                    namespace)) {
                ProgressManager.checkCanceled();
                results.add(new PsiElementResolveResult(target));
            }
            return results.toArray(ResolveResult.EMPTY_ARRAY);
        } catch (IndexNotReadyException ignored) {
            return ResolveResult.EMPTY_ARRAY;
        }
    }
}
