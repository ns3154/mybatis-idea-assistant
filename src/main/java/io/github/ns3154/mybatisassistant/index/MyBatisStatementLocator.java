package io.github.ns3154.mybatisassistant.index;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.indexing.FileBasedIndex;
import io.github.ns3154.mybatisassistant.model.MyBatisStatementKey;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MyBatisStatementLocator {
    private MyBatisStatementLocator() {
    }

    public static @NotNull List<XmlTag> find(
            @NotNull Project project,
            @NotNull String namespace,
            @NotNull String statementId) {
        if (project.isDisposed() || DumbService.isDumb(project)) {
            return List.of();
        }
        ProgressManager.checkCanceled();

        String key = MyBatisStatementKey.of(namespace, statementId);
        var files = FileBasedIndex.getInstance().getContainingFiles(
                MyBatisStatementIndex.NAME,
                key,
                GlobalSearchScope.projectScope(project));

        PsiManager psiManager = PsiManager.getInstance(project);
        List<XmlTag> targets = new ArrayList<>();
        for (VirtualFile file : files) {
            ProgressManager.checkCanceled();
            if (project.isDisposed()) {
                return List.of();
            }
            PsiFile psiFile = psiManager.findFile(file);
            if (!(psiFile instanceof XmlFile xmlFile)) {
                continue;
            }
            collectMatchingTags(xmlFile, namespace, statementId, targets);
        }
        ProgressManager.checkCanceled();
        targets.sort(Comparator
                .comparing((XmlTag tag) -> tag.getContainingFile().getVirtualFile().getPath())
                .thenComparingInt(XmlTag::getTextOffset));
        return List.copyOf(targets);
    }

    private static void collectMatchingTags(
            @NotNull XmlFile xmlFile,
            @NotNull String namespace,
            @NotNull String statementId,
            @NotNull List<XmlTag> targets) {
        XmlTag rootTag = xmlFile.getRootTag();
        if (rootTag == null
                || !MyBatisXmlModel.isMapperRoot(rootTag)
                || !namespace.equals(MyBatisXmlModel.namespace(rootTag))) {
            return;
        }

        for (XmlTag child : rootTag.getSubTags()) {
            ProgressManager.checkCanceled();
            if (MyBatisXmlModel.isStatement(child)
                    && statementId.equals(MyBatisXmlModel.statementId(child))) {
                targets.add(child);
            }
        }
    }
}
