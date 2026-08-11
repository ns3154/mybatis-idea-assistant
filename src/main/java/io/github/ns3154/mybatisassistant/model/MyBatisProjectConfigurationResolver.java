package io.github.ns3154.mybatisassistant.model;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.index.MyBatisBootConfigurationLocator;
import io.github.ns3154.mybatisassistant.index.MyBatisConfigurationLocator;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

public final class MyBatisProjectConfigurationResolver {
    private MyBatisProjectConfigurationResolver() {
    }

    public static @NotNull MyBatisProjectConfigurationResolution resolve(
            @NotNull PsiElement context) {
        ProgressManager.checkCanceled();
        if (!context.isValid()) {
            return new MyBatisProjectConfigurationResolution.SourceInvalid();
        }
        Project project = context.getProject();
        if (project.isDisposed() || !project.isOpen()) {
            return new MyBatisProjectConfigurationResolution.SourceInvalid();
        }
        if (DumbService.isDumb(project)) {
            return new MyBatisProjectConfigurationResolution.IndexNotReady();
        }
        try {
            GlobalSearchScope scope = context.getResolveScope();
            MyBatisProjectConfigurationModel model = new MyBatisProjectConfigurationModel(
                    bootValues(project, MyBatisBootConfigurationEntryKind.CONFIG_LOCATION, scope),
                    bootValues(project, MyBatisBootConfigurationEntryKind.MAPPER_LOCATION, scope),
                    xmlValues(
                            project,
                            MyBatisConfigurationEntryKind.MAPPER_RESOURCE,
                            "resource",
                            scope),
                    xmlValues(project, MyBatisConfigurationEntryKind.MAPPER_URL, "url", scope),
                    xmlValues(project, MyBatisConfigurationEntryKind.MAPPER_CLASS, "class", scope),
                    xmlValues(project, MyBatisConfigurationEntryKind.MAPPER_PACKAGE, "name", scope),
                    mergedAliasPackages(project, scope),
                    bootValues(
                            project,
                            MyBatisBootConfigurationEntryKind.TYPE_HANDLERS_PACKAGE,
                            scope));
            if (!context.isValid() || project.isDisposed() || !project.isOpen()) {
                return new MyBatisProjectConfigurationResolution.SourceInvalid();
            }
            if (DumbService.isDumb(project)) {
                return new MyBatisProjectConfigurationResolution.IndexNotReady();
            }
            return new MyBatisProjectConfigurationResolution.Found(model);
        } catch (IndexNotReadyException ignored) {
            return new MyBatisProjectConfigurationResolution.IndexNotReady();
        }
    }

    private static @NotNull List<String> mergedAliasPackages(
            @NotNull Project project,
            @NotNull GlobalSearchScope scope) {
        return java.util.stream.Stream.concat(
                        xmlValues(
                                project,
                                MyBatisConfigurationEntryKind.TYPE_ALIAS_PACKAGE,
                                "name",
                                scope).stream(),
                        bootValues(
                                project,
                                MyBatisBootConfigurationEntryKind.TYPE_ALIASES_PACKAGE,
                                scope).stream())
                .distinct()
                .sorted()
                .toList();
    }

    private static @NotNull List<String> bootValues(
            @NotNull Project project,
            @NotNull MyBatisBootConfigurationEntryKind kind,
            @NotNull GlobalSearchScope scope) {
        return MyBatisBootConfigurationLocator.findAll(project, kind, scope)
                .stream()
                .map(MyBatisBootConfigurationEntry::value)
                .distinct()
                .sorted()
                .toList();
    }

    private static @NotNull List<String> xmlValues(
            @NotNull Project project,
            @NotNull MyBatisConfigurationEntryKind kind,
            @NotNull String attributeName,
            @NotNull GlobalSearchScope scope) {
        return MyBatisConfigurationLocator.findAll(project, kind, scope)
                .stream()
                .map(tag -> unprefixedAttributeValue(tag, attributeName))
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    private static String unprefixedAttributeValue(
            @NotNull XmlTag tag,
            @NotNull String attributeName) {
        var attribute = tag.getAttribute(attributeName);
        return attribute != null && attributeName.equals(attribute.getName())
                ? attribute.getValue()
                : null;
    }
}
