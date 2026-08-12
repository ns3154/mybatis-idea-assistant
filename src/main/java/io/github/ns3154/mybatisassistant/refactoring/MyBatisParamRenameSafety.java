package io.github.ns3154.mybatisassistant.refactoring;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.index.MyBatisStatementLocator;
import io.github.ns3154.mybatisassistant.index.MyBatisXmlSymbolLocator;
import io.github.ns3154.mybatisassistant.model.MyBatisAnnotationModel;
import io.github.ns3154.mybatisassistant.model.MyBatisStatementSourceKind;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlSymbolKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在 {@code @Param} 改名写入前识别 S4 尚不能完整改写的 MyBatis 动态语义。
 */
final class MyBatisParamRenameSafety {
    private static final Pattern PLACEHOLDER = Pattern.compile("[#$]\\{([^}]*)}");
    private static final Pattern SIMPLE_PATH = Pattern.compile(
            "[\\p{L}_$][\\p{L}\\p{N}_$]*"
                    + "(?:\\.[\\p{L}_$][\\p{L}\\p{N}_$]*"
                    + "|\\[(?:\\d+|'[^']*'|\"[^\"]*\")])*" );

    private MyBatisParamRenameSafety() {
    }

    static @Nullable Conflict findConflict(
            @NotNull PsiLiteralExpression literal,
            @NotNull PsiMethod method) {
        ProgressManager.checkCanceled();
        Project project = literal.getProject();
        if (!literal.isValid()
                || !method.isValid()
                || project.isDisposed()
                || !project.isOpen()) {
            return new Conflict(literal, MyBatisRefactoringMessages.message(
                    "refactoring.conflict.param.declaration.invalid"));
        }
        Object literalValue = literal.getValue();
        if (!(literalValue instanceof String currentName)) {
            return new Conflict(literal, MyBatisRefactoringMessages.message(
                    "refactoring.conflict.param.not.constant"));
        }
        if (MyBatisAnnotationModel.statementSource(method) != MyBatisStatementSourceKind.XML) {
            return new Conflict(literal, MyBatisRefactoringMessages.message(
                    "refactoring.conflict.param.source.unsupported"));
        }
        PsiClass declaringMapper = method.getContainingClass();
        if (declaringMapper == null || declaringMapper.getQualifiedName() == null) {
            return new Conflict(literal, MyBatisRefactoringMessages.message(
                    "refactoring.conflict.param.mapper.name"));
        }

        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        List<PsiClass> mapperTypes = mapperTypes(declaringMapper, scope);
        for (PsiClass mapperType : mapperTypes) {
            ProgressManager.checkCanceled();
            if (mapperType.findMethodsByName(method.getName(), true).length > 1) {
                return new Conflict(literal, MyBatisRefactoringMessages.message(
                        "refactoring.conflict.param.overloaded"));
            }
        }

        Set<XmlTag> statements = new LinkedHashSet<>();
        for (PsiClass mapperType : mapperTypes) {
            ProgressManager.checkCanceled();
            String namespace = mapperType.getQualifiedName();
            if (namespace != null) {
                statements.addAll(MyBatisStatementLocator.find(
                        project,
                        namespace,
                        method.getName(),
                        scope));
            }
        }
        for (XmlTag statement : statements) {
            ProgressManager.checkCanceled();
            Conflict conflict = inspectContainer(
                    statement,
                    currentName,
                    true,
                    scope,
                    new LinkedHashSet<>());
            if (conflict != null) {
                return conflict;
            }
        }
        return null;
    }

    private static @NotNull List<PsiClass> mapperTypes(
            @NotNull PsiClass declaringMapper,
            @NotNull GlobalSearchScope scope) {
        List<PsiClass> result = new ArrayList<>();
        result.add(declaringMapper);
        ClassInheritorsSearch.search(declaringMapper, scope, true).forEach(inheritor -> {
            ProgressManager.checkCanceled();
            if (inheritor.isValid() && inheritor.getQualifiedName() != null) {
                result.add(inheritor);
            }
            return true;
        });
        return List.copyOf(result);
    }

    private static @Nullable Conflict inspectContainer(
            @NotNull XmlTag container,
            @NotNull String currentName,
            boolean directStatement,
            @NotNull GlobalSearchScope scope,
            @NotNull Set<String> visitedFragments) {
        ProgressManager.checkCanceled();
        for (XmlTag tag : tags(container)) {
            ProgressManager.checkCanceled();
            Conflict attributeConflict = inspectAttributes(tag, currentName);
            if (attributeConflict != null) {
                return attributeConflict;
            }
            if ("include".equals(tag.getName())) {
                Conflict includeConflict = inspectInclude(
                        tag,
                        currentName,
                        scope,
                        visitedFragments);
                if (includeConflict != null) {
                    return includeConflict;
                }
            }
        }

        Matcher matcher = PLACEHOLDER.matcher(container.getText());
        while (matcher.find()) {
            ProgressManager.checkCanceled();
            String expression = matcher.group(1);
            if (!containsIdentifier(expression, currentName)) {
                continue;
            }
            String path = expression.split(",", 2)[0].trim();
            if (!directStatement || !SIMPLE_PATH.matcher(path).matches()) {
                return new Conflict(container, directStatement
                        ? MyBatisRefactoringMessages.message(
                                "refactoring.conflict.param.path.unsupported",
                                expression.trim())
                        : MyBatisRefactoringMessages.message(
                                "refactoring.conflict.param.include.reference",
                                currentName));
            }
        }
        return null;
    }

    private static @Nullable Conflict inspectAttributes(
            @NotNull XmlTag tag,
            @NotNull String currentName) {
        for (XmlAttribute attribute : tag.getAttributes()) {
            ProgressManager.checkCanceled();
            String value = attribute.getValue();
            if (value == null || !containsIdentifier(value, currentName)) {
                continue;
            }
            String attributeName = attribute.getName();
            if ("test".equals(attributeName)
                    || "bind".equals(tag.getName()) && "value".equals(attributeName)) {
                return new Conflict(attribute, MyBatisRefactoringMessages.message(
                        "refactoring.conflict.param.ognl.reference", currentName));
            }
            if (isModeledParameterAttribute(tag, attributeName)
                    && !isSimplePathList(value)) {
                return new Conflict(attribute, MyBatisRefactoringMessages.message(
                        "refactoring.conflict.param.attribute.path", value.trim()));
            }
        }
        return null;
    }

    private static boolean isModeledParameterAttribute(
            @NotNull XmlTag tag,
            @NotNull String attributeName) {
        return "foreach".equals(tag.getName()) && "collection".equals(attributeName)
                || "keyProperty".equals(attributeName)
                && (MyBatisXmlModel.isStatement(tag) || "selectKey".equals(tag.getName()));
    }

    private static boolean isSimplePathList(@NotNull String value) {
        String[] paths = value.split(",", -1);
        for (String path : paths) {
            ProgressManager.checkCanceled();
            if (!SIMPLE_PATH.matcher(path.trim()).matches()) {
                return false;
            }
        }
        return paths.length > 0;
    }

    private static @Nullable Conflict inspectInclude(
            @NotNull XmlTag include,
            @NotNull String currentName,
            @NotNull GlobalSearchScope scope,
            @NotNull Set<String> visitedFragments) {
        XmlAttribute refidAttribute = include.getAttribute("refid");
        String refid = refidAttribute == null ? null : refidAttribute.getValue();
        XmlTag mapper = mapperRoot(include);
        String localNamespace = mapper == null ? null : MyBatisXmlModel.namespace(mapper);
        if (refid == null
                || refid.isBlank()
                || localNamespace == null
                || refid.contains("${")
                || refid.contains("#{")) {
            return new Conflict(include, MyBatisRefactoringMessages.message(
                    "refactoring.conflict.param.include.unresolved"));
        }
        String normalized = refid.trim();
        int separator = normalized.lastIndexOf('.');
        String namespace = separator > 0
                ? normalized.substring(0, separator)
                : localNamespace;
        String id = separator > 0
                ? normalized.substring(separator + 1)
                : normalized;
        List<XmlTag> fragments = MyBatisXmlSymbolLocator.find(
                include.getProject(),
                MyBatisXmlSymbolKind.SQL_FRAGMENT,
                namespace,
                id,
                scope);
        if (fragments.size() != 1) {
            return new Conflict(include, MyBatisRefactoringMessages.message(
                    "refactoring.conflict.param.include.ambiguous", normalized));
        }
        XmlTag fragment = fragments.getFirst();
        String fragmentKey = fragment.getContainingFile().getVirtualFile().getPath()
                + '#'
                + fragment.getTextOffset();
        if (!visitedFragments.add(fragmentKey)) {
            return null;
        }
        return inspectContainer(fragment, currentName, false, scope, visitedFragments);
    }

    private static @NotNull List<XmlTag> tags(@NotNull XmlTag container) {
        List<XmlTag> tags = new ArrayList<>();
        tags.add(container);
        tags.addAll(PsiTreeUtil.findChildrenOfType(container, XmlTag.class));
        return tags;
    }

    private static @Nullable XmlTag mapperRoot(@NotNull XmlTag tag) {
        XmlTag current = tag;
        while (current != null) {
            ProgressManager.checkCanceled();
            if (MyBatisXmlModel.isMapperRoot(current)) {
                return current;
            }
            current = current.getParentTag();
        }
        return null;
    }

    private static boolean containsIdentifier(
            @NotNull String text,
            @NotNull String identifier) {
        int start = text.indexOf(identifier);
        while (start >= 0) {
            ProgressManager.checkCanceled();
            int end = start + identifier.length();
            boolean beforeBoundary = start == 0
                    || !Character.isJavaIdentifierPart(text.charAt(start - 1));
            boolean afterBoundary = end == text.length()
                    || !Character.isJavaIdentifierPart(text.charAt(end));
            if (beforeBoundary && afterBoundary) {
                return true;
            }
            start = text.indexOf(identifier, start + 1);
        }
        return false;
    }

    record Conflict(@NotNull PsiElement element, @NotNull String message) {
    }
}
