package io.github.ns3154.mybatisassistant.index;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlSymbolKind;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class MyBatisStatementLocator {
    private MyBatisStatementLocator() {
    }

    public static @NotNull List<XmlTag> find(
            @NotNull Project project,
            @NotNull String namespace,
            @NotNull String statementId) {
        ProgressManager.checkCanceled();
        return MyBatisXmlSymbolLocator.find(
                project,
                MyBatisXmlSymbolKind.STATEMENT,
                namespace,
                statementId);
    }

    public static @NotNull List<XmlTag> find(
            @NotNull Project project,
            @NotNull String namespace,
            @NotNull String statementId,
            @NotNull GlobalSearchScope scope) {
        ProgressManager.checkCanceled();
        return MyBatisXmlSymbolLocator.find(
                project,
                MyBatisXmlSymbolKind.STATEMENT,
                namespace,
                statementId,
                scope);
    }

    public static boolean hasMapperXml(
            @NotNull Project project,
            @NotNull String namespace) {
        ProgressManager.checkCanceled();
        return MyBatisXmlSymbolLocator.hasMapperXml(project, namespace);
    }

    public static boolean hasMapperXml(
            @NotNull Project project,
            @NotNull String namespace,
            @NotNull GlobalSearchScope scope) {
        ProgressManager.checkCanceled();
        return MyBatisXmlSymbolLocator.hasMapperXml(project, namespace, scope);
    }
}
