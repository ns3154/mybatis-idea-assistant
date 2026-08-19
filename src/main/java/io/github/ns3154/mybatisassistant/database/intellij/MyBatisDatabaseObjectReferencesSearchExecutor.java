package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.database.psi.DbElement;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.UsageSearchContext;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.util.Processor;
import com.intellij.util.QueryExecutor;
import org.jetbrains.annotations.NotNull;

/**
 * 为 Database Tools 的虚拟数据库 PSI 补充 XML 外语上下文词搜索。
 */
public final class MyBatisDatabaseObjectReferencesSearchExecutor
        implements QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> {
    @Override
    public boolean execute(
            @NotNull ReferencesSearch.SearchParameters parameters,
            @NotNull Processor<? super PsiReference> consumer) {
        ProgressManager.checkCanceled();
        if (!(parameters.getElementToSearch() instanceof DbElement target)
                || !target.isValid()) {
            return true;
        }
        Project project = target.getProject();
        if (project.isDisposed() || !project.isOpen() || DumbService.isDumb(project)) {
            return true;
        }
        String name = target.getName();
        if (name == null || name.isBlank()) {
            return true;
        }
        parameters.getOptimizer().searchWord(
                name,
                parameters.getEffectiveSearchScope(),
                UsageSearchContext.IN_FOREIGN_LANGUAGES,
                false,
                target);
        return true;
    }
}
