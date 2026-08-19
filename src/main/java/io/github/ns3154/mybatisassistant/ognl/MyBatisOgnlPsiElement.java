package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.ContributedReferenceHost;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceService;
import org.jetbrains.annotations.NotNull;

/**
 * 允许平台引用贡献器向 OGNL 复合 PSI 挂载引用。
 */
public final class MyBatisOgnlPsiElement extends ASTWrapperPsiElement
        implements ContributedReferenceHost {
    public MyBatisOgnlPsiElement(@NotNull ASTNode node) {
        super(node);
    }

    @Override
    public @NotNull PsiReference[] getReferences() {
        return PsiReferenceService.getService().getContributedReferences(this);
    }
}
