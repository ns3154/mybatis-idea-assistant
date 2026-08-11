package io.github.ns3154.mybatisassistant.resolve;

import com.intellij.openapi.project.Project;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.search.GlobalSearchScope;
import io.github.ns3154.mybatisassistant.index.MyBatisStatementLocator;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 基于插件增量索引的生产查询实现。
 */
enum IndexedMyBatisStatementLookup implements MyBatisStatementLookup {
    INSTANCE;

    @Override
    public @NotNull List<XmlTag> find(
            @NotNull Project project,
            @NotNull String namespace,
            @NotNull String statementId,
            @NotNull GlobalSearchScope scope) {
        return MyBatisStatementLocator.find(project, namespace, statementId, scope);
    }

    @Override
    public boolean hasMapperXml(
            @NotNull Project project,
            @NotNull String namespace,
            @NotNull GlobalSearchScope scope) {
        return MyBatisStatementLocator.hasMapperXml(project, namespace, scope);
    }
}
