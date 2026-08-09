package io.github.ns3154.mybatisassistant.resolve;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.index.MyBatisStatementLocator;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class MyBatisStatementResolver {
    private MyBatisStatementResolver() {
    }

    /**
     * 解析 Java Mapper 方法到 XML statement。调用方必须持有读锁；取消异常会直接向上传播。
     */
    public static @NotNull MyBatisStatementResolution resolve(@NotNull PsiElement source) {
        ProgressManager.checkCanceled();
        if (!source.isValid()) {
            return new MyBatisStatementResolution.SourceInvalid();
        }
        Project project = source.getProject();
        if (project.isDisposed() || !project.isOpen()) {
            return new MyBatisStatementResolution.SourceInvalid();
        }
        if (!(source instanceof PsiMethod method)) {
            return new MyBatisStatementResolution.UnsupportedSource();
        }

        PsiClass mapperInterface = method.getContainingClass();
        if (mapperInterface == null || !mapperInterface.isValid()) {
            return new MyBatisStatementResolution.SourceInvalid();
        }
        String namespace = mapperInterface.getQualifiedName();
        if (!mapperInterface.isInterface()
                || namespace == null
                || mapperInterface.findMethodsByName(method.getName(), false).length != 1) {
            return new MyBatisStatementResolution.UnsupportedSource();
        }

        if (DumbService.isDumb(project)) {
            return new MyBatisStatementResolution.IndexNotReady();
        }

        try {
            return resolveFromIndex(project, method, namespace, method.getName());
        } catch (IndexNotReadyException ignored) {
            return new MyBatisStatementResolution.IndexNotReady();
        }
    }

    private static @NotNull MyBatisStatementResolution resolveFromIndex(
            @NotNull Project project,
            @NotNull PsiMethod source,
            @NotNull String namespace,
            @NotNull String statementId) {
        ProgressManager.checkCanceled();
        List<XmlTag> tags = MyBatisStatementLocator.find(project, namespace, statementId);
        if (!isSourceUsable(project, source)) {
            return new MyBatisStatementResolution.SourceInvalid();
        }
        if (tags.isEmpty()) {
            boolean mapperXmlExists = MyBatisStatementLocator.hasMapperXml(project, namespace);
            if (!isSourceUsable(project, source)) {
                return new MyBatisStatementResolution.SourceInvalid();
            }
            return mapperXmlExists
                    ? new MyBatisStatementResolution.StatementMissing(namespace, statementId)
                    : new MyBatisStatementResolution.NoMapperXml(namespace);
        }

        SmartPointerManager pointerManager = SmartPointerManager.getInstance(project);
        List<SmartPsiElementPointer<XmlTag>> targets = new ArrayList<>(tags.size());
        for (XmlTag tag : tags) {
            ProgressManager.checkCanceled();
            if (tag.isValid()) {
                targets.add(pointerManager.createSmartPsiElementPointer(tag));
            }
        }
        if (!isSourceUsable(project, source)) {
            return new MyBatisStatementResolution.SourceInvalid();
        }

        if (targets.isEmpty()) {
            return new MyBatisStatementResolution.StatementMissing(namespace, statementId);
        }
        if (targets.size() == 1) {
            return new MyBatisStatementResolution.UniqueMatch(targets.getFirst());
        }
        return new MyBatisStatementResolution.MultipleMatches(targets);
    }

    private static boolean isSourceUsable(
            @NotNull Project project,
            @NotNull PsiMethod source) {
        ProgressManager.checkCanceled();
        return !project.isDisposed() && project.isOpen() && source.isValid();
    }
}
