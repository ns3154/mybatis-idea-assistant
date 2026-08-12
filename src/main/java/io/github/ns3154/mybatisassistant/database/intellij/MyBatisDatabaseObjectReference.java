package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.xml.XmlToken;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 从 Mapper XML 的 SQL 标识符指向 Database Tools 表列 PSI。
 */
final class MyBatisDatabaseObjectReference extends PsiReferenceBase<XmlToken> {
    private final MyBatisDatabaseObjectTarget target;

    MyBatisDatabaseObjectReference(
            @NotNull XmlToken element,
            @NotNull TextRange range,
            @NotNull MyBatisDatabaseObjectTarget target) {
        // 元数据快照与 live Database Tools 模型之间可能短暂错位，不能制造 IDE 原生红线。
        super(element, range, true);
        this.target = target;
    }

    @Override
    public @Nullable PsiElement resolve() {
        ProgressManager.checkCanceled();
        XmlToken element = getElement();
        Project project = element.getProject();
        if (!element.isValid()
                || project.isDisposed()
                || !project.isOpen()
                || DumbService.isDumb(project)) {
            return null;
        }
        try {
            return MyBatisDatabaseObjectLocator.find(project, target);
        } catch (IndexNotReadyException ignored) {
            return null;
        }
    }
}
