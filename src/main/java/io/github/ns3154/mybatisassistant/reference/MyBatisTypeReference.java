package io.github.ns3154.mybatisassistant.reference;

import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiPolyVariantReferenceBase;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlAttributeValue;
import io.github.ns3154.mybatisassistant.model.MyBatisTypeAliasResolution;
import io.github.ns3154.mybatisassistant.model.MyBatisTypeAliasResolver;
import io.github.ns3154.mybatisassistant.model.MyBatisConfigurationEntryKind;
import io.github.ns3154.mybatisassistant.index.MyBatisConfigurationLocator;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class MyBatisTypeReference extends PsiPolyVariantReferenceBase<XmlAttributeValue> {
    private boolean stableAliasForRename;

    public MyBatisTypeReference(@NotNull XmlAttributeValue element) {
        super(element, MyBatisReferenceSupport.valueRange(element), true);
    }

    @Override
    public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        ProgressManager.checkCanceled();
        return resolveType().targets().stream()
                .map(PsiElementResolveResult::new)
                .toArray(ResolveResult[]::new);
    }

    @Override
    public boolean isReferenceTo(@NotNull PsiElement element) {
        ResolveResult[] results = multiResolve(false);
        boolean equivalent = results.length == 1
                && element.getManager().areElementsEquivalent(
                element,
                results[0].getElement());
        if (equivalent
                && results[0].getElement() instanceof PsiClass psiClass
                && getElement().getValue().indexOf('.') < 0) {
            stableAliasForRename = hasStableExplicitAlias(
                    getElement().getValue().trim(),
                    psiClass);
        }
        return equivalent;
    }

    @Override
    public Object @NotNull [] getVariants() {
        ProgressManager.checkCanceled();
        return MyBatisTypeAliasResolver.variants(getElement()).stream()
                .map(LookupElementBuilder::create)
                .toArray();
    }

    @Override
    public PsiElement handleElementRename(@NotNull String newElementName) {
        String current = getElement().getValue().trim();
        int separator = current.lastIndexOf('.');
        if (separator >= 0) {
            return MyBatisReferenceRenameSupport.renameRange(
                    this,
                    current.substring(0, separator + 1) + newElementName);
        }
        if (stableAliasForRename) {
            return getElement();
        }
        PsiElement target = resolve();
        if (target instanceof PsiClass psiClass
                && hasStableExplicitAlias(current, psiClass)) {
            return getElement();
        }
        return MyBatisReferenceRenameSupport.renameRange(this, newElementName);
    }

    public boolean isDefinitelyMissing() {
        return resolveType().status() == TypeStatus.DEFINITE_MISSING;
    }

    private @NotNull TypeResolution resolveType() {
        XmlAttributeValue element = getElement();
        if (!element.isValid()) {
            return new TypeResolution(TypeStatus.SOURCE_INVALID, List.of());
        }
        Project project = element.getProject();
        if (project.isDisposed() || !project.isOpen()) {
            return new TypeResolution(TypeStatus.SOURCE_INVALID, List.of());
        }
        String typeName = element.getValue().trim();
        if (DumbService.isDumb(project)
                || !MyBatisReferenceSupport.isStaticReferenceValue(typeName)) {
            return new TypeResolution(TypeStatus.UNKNOWN, List.of());
        }
        try {
            Set<PsiClass> targets = new LinkedHashSet<>(findClasses(typeName));
            MyBatisTypeAliasResolution aliasResolution = MyBatisTypeAliasResolver.resolve(
                    element,
                    typeName);
            List<String> canonicalTypes = switch (aliasResolution) {
                case MyBatisTypeAliasResolution.Unique unique -> List.of(unique.canonicalType());
                case MyBatisTypeAliasResolution.Multiple multiple -> multiple.canonicalTypes();
                default -> List.of();
            };
            for (String canonicalType : canonicalTypes) {
                ProgressManager.checkCanceled();
                targets.addAll(findClasses(componentType(canonicalType)));
            }
            if (!targets.isEmpty()) {
                return new TypeResolution(TypeStatus.FOUND, List.copyOf(targets));
            }
            if (!canonicalTypes.isEmpty()) {
                return new TypeResolution(TypeStatus.RESOLVED_WITHOUT_CLASS, List.of());
            }
            if (aliasResolution instanceof MyBatisTypeAliasResolution.IndexNotReady) {
                return new TypeResolution(TypeStatus.UNKNOWN, List.of());
            }
            boolean explicitAlias = !MyBatisConfigurationLocator.find(
                    project,
                    MyBatisConfigurationEntryKind.TYPE_ALIAS,
                    typeName,
                    MyBatisReferenceSupport.resolveScope(element)).isEmpty();
            return new TypeResolution(
                    typeName.indexOf('.') >= 0 || explicitAlias
                            ? TypeStatus.DEFINITE_MISSING
                            : TypeStatus.UNKNOWN,
                    List.of());
        } catch (IndexNotReadyException ignored) {
            return new TypeResolution(TypeStatus.UNKNOWN, List.of());
        }
    }

    private @NotNull List<PsiClass> findClasses(@NotNull String canonicalType) {
        if (isPrimitive(canonicalType)) {
            return List.of();
        }
        GlobalSearchScope scope = MyBatisReferenceSupport.resolveScope(getElement());
        PsiClass[] classes = JavaPsiFacade.getInstance(getElement().getProject())
                .findClasses(canonicalType, scope);
        List<PsiClass> result = new ArrayList<>();
        for (PsiClass psiClass : classes) {
            ProgressManager.checkCanceled();
            if (canonicalType.equals(psiClass.getQualifiedName())) {
                result.add(psiClass);
            }
        }
        return List.copyOf(result);
    }

    private boolean hasStableExplicitAlias(
            @NotNull String alias,
            @NotNull PsiClass target) {
        for (com.intellij.psi.xml.XmlTag declaration : MyBatisConfigurationLocator.find(
                getElement().getProject(),
                MyBatisConfigurationEntryKind.TYPE_ALIAS,
                alias,
                MyBatisReferenceSupport.resolveScope(getElement()))) {
            ProgressManager.checkCanceled();
            String declaredAlias = declaration.getAttributeValue("alias");
            if (declaredAlias != null && alias.equalsIgnoreCase(declaredAlias.trim())) {
                return true;
            }
        }
        PsiAnnotation annotation = target.getAnnotation("org.apache.ibatis.type.Alias");
        if (annotation == null) {
            return false;
        }
        Object value = JavaPsiFacade.getInstance(getElement().getProject())
                .getConstantEvaluationHelper()
                .computeConstantExpression(annotation.findAttributeValue("value"));
        return value instanceof String declaredAlias
                && alias.equalsIgnoreCase(declaredAlias.trim());
    }

    private static @NotNull String componentType(@NotNull String canonicalType) {
        String component = canonicalType;
        while (component.endsWith("[]")) {
            component = component.substring(0, component.length() - 2);
        }
        return component;
    }

    private static boolean isPrimitive(@NotNull String canonicalType) {
        return Set.of("byte", "char", "short", "int", "long", "float", "double", "boolean")
                .contains(canonicalType);
    }

    private enum TypeStatus {
        FOUND,
        RESOLVED_WITHOUT_CLASS,
        DEFINITE_MISSING,
        UNKNOWN,
        SOURCE_INVALID
    }

    private record TypeResolution(@NotNull TypeStatus status, @NotNull List<PsiClass> targets) {
        private TypeResolution {
            targets = List.copyOf(targets);
        }
    }
}
