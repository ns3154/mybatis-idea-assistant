package io.github.ns3154.mybatisassistant.index;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.indexing.FileBasedIndex;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlSymbolKey;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlSymbolKind;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class MyBatisXmlSymbolLocator {
    private MyBatisXmlSymbolLocator() {
    }

    public static @NotNull List<XmlTag> find(
            @NotNull Project project,
            @NotNull MyBatisXmlSymbolKind kind,
            @NotNull String namespace,
            @NotNull String id) {
        return find(project, kind, namespace, id, GlobalSearchScope.projectScope(project));
    }

    public static @NotNull List<XmlTag> find(
            @NotNull Project project,
            @NotNull MyBatisXmlSymbolKind kind,
            @NotNull String namespace,
            @NotNull String id,
            @NotNull GlobalSearchScope scope) {
        if (!kind.isNamed() || project.isDisposed() || !project.isOpen()) {
            return List.of();
        }
        ProgressManager.checkCanceled();

        String key = MyBatisXmlSymbolKey.of(kind, namespace, id);
        var files = FileBasedIndex.getInstance().getContainingFiles(
                MyBatisXmlSymbolIndex.NAME,
                key,
                scope);
        PsiManager psiManager = PsiManager.getInstance(project);
        List<XmlTag> targets = new ArrayList<>();
        for (VirtualFile file : files) {
            ProgressManager.checkCanceled();
            if (project.isDisposed() || !project.isOpen()) {
                return List.of();
            }
            PsiFile psiFile = psiManager.findFile(file);
            if (psiFile instanceof XmlFile xmlFile) {
                collectMatchingTags(xmlFile, kind, namespace, id, targets);
            }
        }
        sortTargets(targets);
        return List.copyOf(targets);
    }

    public static boolean hasMapperXml(
            @NotNull Project project,
            @NotNull String namespace) {
        return hasMapperXml(project, namespace, GlobalSearchScope.projectScope(project));
    }

    public static boolean hasMapperXml(
            @NotNull Project project,
            @NotNull String namespace,
            @NotNull GlobalSearchScope scope) {
        return !findMapperRoots(project, namespace, scope).isEmpty();
    }

    public static @NotNull List<XmlTag> findMapperRoots(
            @NotNull Project project,
            @NotNull String namespace) {
        return findMapperRoots(project, namespace, GlobalSearchScope.projectScope(project));
    }

    public static @NotNull List<XmlTag> findMapperRoots(
            @NotNull Project project,
            @NotNull String namespace,
            @NotNull GlobalSearchScope scope) {
        if (project.isDisposed() || !project.isOpen()) {
            return List.of();
        }
        ProgressManager.checkCanceled();
        var files = FileBasedIndex.getInstance().getContainingFiles(
                MyBatisXmlSymbolIndex.NAME,
                MyBatisXmlSymbolKey.of(MyBatisXmlSymbolKind.NAMESPACE, namespace, null),
                scope);
        PsiManager psiManager = PsiManager.getInstance(project);
        List<XmlTag> roots = new ArrayList<>();
        for (VirtualFile file : files) {
            ProgressManager.checkCanceled();
            if (project.isDisposed() || !project.isOpen()) {
                return List.of();
            }
            PsiFile psiFile = psiManager.findFile(file);
            if (!(psiFile instanceof XmlFile xmlFile) || PsiTreeUtil.hasErrorElements(xmlFile)) {
                continue;
            }
            XmlTag rootTag = xmlFile.getRootTag();
            if (rootTag != null
                    && MyBatisXmlModel.isMapperRoot(rootTag)
                    && namespace.equals(MyBatisXmlModel.namespace(rootTag))) {
                roots.add(rootTag);
            }
        }
        sortTargets(roots);
        return List.copyOf(roots);
    }

    private static void collectMatchingTags(
            @NotNull XmlFile xmlFile,
            @NotNull MyBatisXmlSymbolKind kind,
            @NotNull String namespace,
            @NotNull String id,
            @NotNull List<XmlTag> targets) {
        XmlTag rootTag = xmlFile.getRootTag();
        if (PsiTreeUtil.hasErrorElements(xmlFile)
                || rootTag == null
                || !MyBatisXmlModel.isMapperRoot(rootTag)
                || !namespace.equals(MyBatisXmlModel.namespace(rootTag))) {
            return;
        }
        for (XmlTag child : rootTag.getSubTags()) {
            ProgressManager.checkCanceled();
            if (MyBatisXmlModel.isSymbolTag(child, kind)
                    && id.equals(MyBatisXmlModel.symbolId(child))) {
                targets.add(child);
            }
        }
    }

    private static void sortTargets(@NotNull List<XmlTag> targets) {
        targets.sort((left, right) -> {
            ProgressManager.checkCanceled();
            int byPath = left.getContainingFile()
                    .getVirtualFile()
                    .getPath()
                    .compareTo(right.getContainingFile().getVirtualFile().getPath());
            return byPath != 0
                    ? byPath
                    : Integer.compare(left.getTextOffset(), right.getTextOffset());
        });
        ProgressManager.checkCanceled();
    }
}
