package io.github.ns3154.mybatisassistant.dynamic;

import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlComment;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import com.intellij.util.indexing.FileBasedIndex;
import io.github.ns3154.mybatisassistant.index.MyBatisXmlSymbolLocator;
import io.github.ns3154.mybatisassistant.index.MyBatisXmlSymbolIndex;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlSymbolKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 将 MyBatis XML statement 编译为符号化 IR 和字符级来源映射。
 */
public final class MyBatisDynamicSqlCompiler {
    private static final Key<CachedValue<MyBatisDynamicSqlCompileResult>> COMPILE_CACHE_KEY =
            Key.create("mybatis.idea.assistant.dynamic.sql.compile");
    private static final String CDATA_OPEN = "<![CDATA[";
    private static final String CDATA_CLOSE = "]]>";
    private static final Map<String, String> XML_ENTITIES = Map.of(
            "lt", "<",
            "gt", ">",
            "amp", "&",
            "quot", "\"",
            "apos", "'");

    private MyBatisDynamicSqlCompiler() {
    }

    public static @NotNull MyBatisDynamicSqlCompileResult compile(@NotNull XmlTag statement) {
        return compileInternal(statement, true);
    }

    static @NotNull MyBatisDynamicSqlCompileResult compileUncached(@NotNull XmlTag statement) {
        return compileInternal(statement, false);
    }

    private static @NotNull MyBatisDynamicSqlCompileResult compileInternal(
            @NotNull XmlTag statement,
            boolean useCache) {
        ProgressManager.checkCanceled();
        if (!statement.isValid()) {
            return new MyBatisDynamicSqlCompileResult.SourceInvalid();
        }
        Project project = statement.getProject();
        if (project.isDisposed() || !project.isOpen()) {
            return new MyBatisDynamicSqlCompileResult.SourceInvalid();
        }
        if (DumbService.isDumb(project)) {
            return new MyBatisDynamicSqlCompileResult.IndexNotReady();
        }
        XmlTag mapper = statement.getParentTag();
        String namespace = mapper == null ? null : MyBatisXmlModel.namespace(mapper);
        if (!MyBatisXmlModel.isStatement(statement)
                || mapper == null
                || !MyBatisXmlModel.isMapperRoot(mapper)
                || namespace == null) {
            return new MyBatisDynamicSqlCompileResult.UnsupportedSource();
        }
        if (fileUrl(statement) == null) {
            return new MyBatisDynamicSqlCompileResult.SourceInvalid();
        }

        try {
            MyBatisDynamicSqlCompileResult result = useCache
                    ? compileCached(statement, project, namespace)
                    : compute(statement, project, namespace).result();
            if (!statement.isValid() || project.isDisposed() || !project.isOpen()) {
                return new MyBatisDynamicSqlCompileResult.SourceInvalid();
            }
            if (DumbService.isDumb(project)) {
                return new MyBatisDynamicSqlCompileResult.IndexNotReady();
            }
            return result;
        } catch (IndexNotReadyException ignored) {
            return new MyBatisDynamicSqlCompileResult.IndexNotReady();
        } catch (NonCacheableCompileException exception) {
            return exception.result();
        }
    }

    private static @NotNull MyBatisDynamicSqlCompileResult compileCached(
            @NotNull XmlTag statement,
            @NotNull Project project,
            @NotNull String namespace) {
        return CachedValuesManager.getCachedValue(statement, COMPILE_CACHE_KEY, () -> {
            CompileComputation computation = compute(statement, project, namespace);
            MyBatisDynamicSqlCompileResult result = computation.result();
            if (result instanceof MyBatisDynamicSqlCompileResult.SourceInvalid
                    || result instanceof MyBatisDynamicSqlCompileResult.IndexNotReady) {
                throw new NonCacheableCompileException(result);
            }
            List<Object> dependencies = new ArrayList<>();
            for (PsiFile file : computation.dependencies().files()) {
                dependencies.add((ModificationTracker) () -> file.isValid()
                        ? file.getModificationStamp()
                        : Long.MAX_VALUE);
            }
            if (computation.dependencies().indexConsulted()) {
                dependencies.add((ModificationTracker) () -> project.isDisposed()
                        ? Long.MAX_VALUE
                        : FileBasedIndex.getInstance().getIndexModificationStamp(
                                MyBatisXmlSymbolIndex.NAME,
                                project));
            }
            dependencies.add(DumbService.getInstance(project).getModificationTracker());
            dependencies.add(ProjectRootModificationTracker.getInstance(project));
            return CachedValueProvider.Result.create(result, dependencies);
        });
    }

    private static @NotNull CompileComputation compute(
            @NotNull XmlTag statement,
            @NotNull Project project,
            @NotNull String namespace) {
        List<MyBatisDynamicSqlDiagnostic> diagnostics = new ArrayList<>();
        CompilationDependencies dependencies = new CompilationDependencies();
        CompilerContext context = new CompilerContext(
                project,
                statement.getResolveScope(),
                namespace,
                Map.of(),
                List.of(),
                false,
                diagnostics,
                dependencies);
        Compilation compilation = compileChildren(statement, context);
        ProgressManager.checkCanceled();
        if (!statement.isValid() || project.isDisposed() || !project.isOpen()) {
            return new CompileComputation(
                    new MyBatisDynamicSqlCompileResult.SourceInvalid(),
                    dependencies);
        }
        return new CompileComputation(new MyBatisDynamicSqlCompileResult.Compiled(
                new MyBatisDynamicSqlProgram(
                        compilation.node(),
                        diagnostics,
                        diagnostics.isEmpty()
                                ? compilation.staticSql()
                                : Optional.empty())), dependencies);
    }

    private static @NotNull Compilation compileChildren(
            @NotNull XmlTag container,
            @NotNull CompilerContext context) {
        PsiFile containingFile = container.getContainingFile();
        if (containingFile != null) {
            context.dependencies().addFile(containingFile);
        }
        MyBatisSourceMapBuilder staticBuilder = new MyBatisSourceMapBuilder();
        List<MyBatisDynamicSqlNode> nodes = new ArrayList<>();
        boolean allStatic = true;
        for (PsiElement child : container.getValue().getChildren()) {
            ProgressManager.checkCanceled();
            if (child instanceof XmlComment) {
                continue;
            }
            if (child instanceof XmlText text) {
                MyBatisMappedText mapped = compileText(text, context);
                if (!mapped.text().isEmpty()) {
                    nodes.add(new MyBatisSqlTextNode(mapped));
                    appendMapped(staticBuilder, mapped);
                }
                continue;
            }
            if (child instanceof XmlTag tag) {
                Compilation nested = compileTag(tag, context);
                nodes.add(nested.node());
                if (nested.staticSql().isPresent()) {
                    appendMapped(staticBuilder, nested.staticSql().orElseThrow());
                } else {
                    allStatic = false;
                }
            }
        }
        MyBatisDynamicSqlNode root = nodes.size() == 1
                ? nodes.getFirst()
                : new MyBatisSqlSequenceNode(nodes);
        return new Compilation(
                root,
                allStatic ? Optional.of(staticBuilder.build()) : Optional.empty());
    }

    private static @NotNull Compilation compileTag(
            @NotNull XmlTag tag,
            @NotNull CompilerContext context) {
        return switch (tag.getName()) {
            case "if" -> new Compilation(new MyBatisIfNode(
                    expression(tag, "test", context),
                    compileChildren(tag, context).node(),
                    sourceRange(tag)), Optional.empty());
            case "choose" -> compileChoose(tag, context);
            case "where" -> compileTrim(
                    tag,
                    context,
                    MyBatisTrimKind.WHERE,
                    "WHERE",
                    "",
                    "AND |OR ",
                    "");
            case "set" -> compileTrim(
                    tag,
                    context,
                    MyBatisTrimKind.SET,
                    "SET",
                    "",
                    "",
                    ",");
            case "trim" -> compileTrim(
                    tag,
                    context,
                    MyBatisTrimKind.TRIM,
                    value(tag, "prefix", context),
                    value(tag, "suffix", context),
                    value(tag, "prefixOverrides", context),
                    value(tag, "suffixOverrides", context));
            case "foreach" -> compileForeach(tag, context);
            case "bind" -> compileBind(tag, context);
            case "include" -> compileInclude(tag, context);
            default -> compileUnsupported(tag, context);
        };
    }

    private static @NotNull Compilation compileChoose(
            @NotNull XmlTag choose,
            @NotNull CompilerContext context) {
        List<MyBatisWhenBranch> branches = new ArrayList<>();
        Optional<MyBatisDynamicSqlNode> otherwise = Optional.empty();
        for (XmlTag child : choose.getSubTags()) {
            ProgressManager.checkCanceled();
            if ("when".equals(child.getName())) {
                branches.add(new MyBatisWhenBranch(
                        expression(child, "test", context),
                        compileChildren(child, context).node(),
                        sourceRange(child)));
            } else if ("otherwise".equals(child.getName())) {
                MyBatisDynamicSqlNode body = compileChildren(child, context).node();
                if (otherwise.isPresent()) {
                    diagnostic(
                            context,
                            MyBatisDynamicSqlDiagnosticCode.DUPLICATE_OTHERWISE,
                            MyBatisDynamicMessages.message(
                                    "dynamic.diagnostic.choose.duplicate.otherwise"),
                            sourceRange(child));
                } else {
                    otherwise = Optional.of(body);
                }
            } else {
                diagnostic(
                        context,
                        MyBatisDynamicSqlDiagnosticCode.INVALID_CHOOSE_CHILD,
                        MyBatisDynamicMessages.message(
                                "dynamic.diagnostic.choose.invalid.child",
                                child.getName()),
                        sourceRange(child));
            }
        }
        return new Compilation(
                new MyBatisChooseNode(branches, otherwise, sourceRange(choose)),
                Optional.empty());
    }

    private static @NotNull Compilation compileTrim(
            @NotNull XmlTag tag,
            @NotNull CompilerContext context,
            @NotNull MyBatisTrimKind kind,
            @NotNull String prefix,
            @NotNull String suffix,
            @NotNull String prefixOverrides,
            @NotNull String suffixOverrides) {
        MyBatisDynamicSqlNode body = compileChildren(tag, context).node();
        return new Compilation(new MyBatisTrimNode(
                kind,
                prefix,
                suffix,
                prefixOverrides,
                suffixOverrides,
                body,
                sourceRange(tag)), Optional.empty());
    }

    private static @NotNull Compilation compileForeach(
            @NotNull XmlTag foreach,
            @NotNull CompilerContext context) {
        MyBatisDynamicSqlExpression collection = expression(foreach, "collection", context);
        String item = defaultValue(value(foreach, "item", context), "item");
        String index = defaultValue(value(foreach, "index", context), "index");
        List<MyBatisDynamicSqlBinding> bindings = List.of(
                binding(foreach, "item", item, MyBatisDynamicSqlBindingKind.FOREACH_ITEM),
                binding(foreach, "index", index, MyBatisDynamicSqlBindingKind.FOREACH_INDEX));
        Optional<Boolean> nullable = booleanValue(foreach, "nullable", context);
        return new Compilation(new MyBatisForeachNode(
                collection,
                bindings,
                value(foreach, "open", context),
                value(foreach, "close", context),
                value(foreach, "separator", context),
                nullable,
                compileChildren(foreach, context).node(),
                sourceRange(foreach)), Optional.empty());
    }

    private static @NotNull Compilation compileBind(
            @NotNull XmlTag bind,
            @NotNull CompilerContext context) {
        ResolvedAttribute name = requiredAttribute(bind, "name", context);
        MyBatisDynamicSqlExpression value = expression(bind, "value", context);
        MyBatisDynamicSqlBinding binding = new MyBatisDynamicSqlBinding(
                name.text(),
                MyBatisDynamicSqlBindingKind.BIND,
                Optional.of(value),
                name.sourceRange());
        return new Compilation(new MyBatisBindNode(binding, sourceRange(bind)), Optional.empty());
    }

    private static @NotNull Compilation compileInclude(
            @NotNull XmlTag include,
            @NotNull CompilerContext context) {
        PropertyResolution properties = resolveIncludeProperties(include, context);
        CompilerContext propertyContext = context.withProperties(properties.values(), false);
        ResolvedAttribute refid = requiredAttribute(include, "refid", propertyContext);
        MyBatisSourceRange includeRange = sourceRange(include);
        if (!properties.valid()
                || refid.text().isBlank()
                || hasPlaceholder(refid.text())) {
            diagnostic(
                    context,
                    MyBatisDynamicSqlDiagnosticCode.INVALID_INCLUDE_REFID,
                    MyBatisDynamicMessages.message(
                            "dynamic.diagnostic.include.refid.not.static",
                            refid.text()),
                    refid.sourceRange());
            return unresolvedInclude(refid.text(), properties.bindings(), includeRange);
        }

        QualifiedFragment qualified = QualifiedFragment.parse(refid.text(), context.namespace());
        if (qualified == null) {
            diagnostic(
                    context,
                    MyBatisDynamicSqlDiagnosticCode.INVALID_INCLUDE_REFID,
                    MyBatisDynamicMessages.message(
                            "dynamic.diagnostic.include.refid.invalid",
                            refid.text()),
                    refid.sourceRange());
            return unresolvedInclude(refid.text(), properties.bindings(), includeRange);
        }
        context.dependencies().markIndexConsulted();
        List<XmlTag> targets = MyBatisXmlSymbolLocator.find(
                context.project(),
                MyBatisXmlSymbolKind.SQL_FRAGMENT,
                qualified.namespace(),
                qualified.id(),
                context.scope());
        if (targets.size() != 1) {
            diagnostic(
                    context,
                    MyBatisDynamicSqlDiagnosticCode.INCLUDE_TARGET_NOT_UNIQUE,
                    MyBatisDynamicMessages.message(
                            "dynamic.diagnostic.include.target.not.unique",
                            targets.size(),
                            refid.text()),
                    refid.sourceRange());
            return unresolvedInclude(refid.text(), properties.bindings(), includeRange);
        }

        XmlTag fragment = targets.getFirst();
        String fragmentKey = qualified.namespace() + '.' + qualified.id()
                + '@' + fragment.getContainingFile().getVirtualFile().getUrl()
                + ':' + fragment.getTextOffset();
        if (context.includeChain().contains(fragmentKey)) {
            List<String> cycle = new ArrayList<>(context.includeChain());
            cycle.add(fragmentKey);
            diagnostic(
                    context,
                    MyBatisDynamicSqlDiagnosticCode.INCLUDE_CYCLE,
                    MyBatisDynamicMessages.message(
                            "dynamic.diagnostic.include.cycle",
                            String.join(" -> ", cycle)),
                    includeRange);
            return new Compilation(new MyBatisIncludeNode(
                    qualified.namespace(),
                    qualified.id(),
                    properties.bindings(),
                    new MyBatisSqlSequenceNode(List.of()),
                    includeRange,
                    sourceRange(fragment)), Optional.empty());
        }

        CompilerContext nestedContext = propertyContext.forInclude(
                qualified.namespace(),
                fragmentKey);
        Compilation expanded = compileChildren(fragment, nestedContext);
        return new Compilation(new MyBatisIncludeNode(
                qualified.namespace(),
                qualified.id(),
                properties.bindings(),
                expanded.node(),
                includeRange,
                sourceRange(fragment)), expanded.staticSql());
    }

    private static @NotNull Compilation unresolvedInclude(
            @NotNull String refid,
            @NotNull List<MyBatisDynamicSqlBinding> properties,
            @NotNull MyBatisSourceRange range) {
        return new Compilation(new MyBatisIncludeNode(
                "",
                refid,
                properties,
                new MyBatisSqlSequenceNode(List.of()),
                range,
                range), Optional.empty());
    }

    private static @NotNull PropertyResolution resolveIncludeProperties(
            @NotNull XmlTag include,
            @NotNull CompilerContext context) {
        Map<String, PropertyDefinition> definitions = new LinkedHashMap<>();
        boolean valid = true;
        for (XmlTag child : include.getSubTags()) {
            ProgressManager.checkCanceled();
            if (!"property".equals(child.getName())) {
                diagnostic(
                        context,
                        MyBatisDynamicSqlDiagnosticCode.INVALID_INCLUDE_PROPERTY,
                        MyBatisDynamicMessages.message(
                                "dynamic.diagnostic.include.property.invalid.child",
                                child.getName()),
                        sourceRange(child));
                valid = false;
                continue;
            }
            ResolvedAttribute name = rawRequiredAttribute(child, "name", context);
            ResolvedAttribute value = rawRequiredAttribute(child, "value", context);
            if (name.text().isBlank() || hasPlaceholder(name.text())) {
                diagnostic(
                        context,
                        MyBatisDynamicSqlDiagnosticCode.INVALID_INCLUDE_PROPERTY,
                        MyBatisDynamicMessages.message(
                                "dynamic.diagnostic.include.property.name.invalid"),
                        name.sourceRange());
                valid = false;
                continue;
            }
            PropertyDefinition previous = definitions.putIfAbsent(
                    name.text(),
                    new PropertyDefinition(name.text(), value.text(), name.sourceRange(),
                            value.sourceRange()));
            if (previous != null) {
                diagnostic(
                        context,
                        MyBatisDynamicSqlDiagnosticCode.DUPLICATE_INCLUDE_PROPERTY,
                        MyBatisDynamicMessages.message(
                                "dynamic.diagnostic.include.property.duplicate",
                                name.text()),
                        name.sourceRange());
                valid = false;
            }
        }

        Map<String, PropertyValue> localResolved = new LinkedHashMap<>();
        List<MyBatisDynamicSqlBinding> bindings = new ArrayList<>();
        Set<String> failed = new java.util.HashSet<>();
        for (PropertyDefinition definition : definitions.values()) {
            ProgressManager.checkCanceled();
            PropertyValue property = resolveProperty(
                    definition.name(),
                    definitions,
                    context.properties(),
                    localResolved,
                    new ArrayDeque<>(),
                    failed,
                    context);
            if (property == null) {
                valid = false;
                continue;
            }
            bindings.add(new MyBatisDynamicSqlBinding(
                    definition.name(),
                    MyBatisDynamicSqlBindingKind.INCLUDE_PROPERTY,
                    Optional.of(new MyBatisDynamicSqlExpression(
                            property.value(),
                            property.sourceRange())),
                    definition.nameRange()));
        }
        Map<String, PropertyValue> merged = new LinkedHashMap<>(context.properties());
        merged.putAll(localResolved);
        return new PropertyResolution(Map.copyOf(merged), bindings, valid && failed.isEmpty());
    }

    private static @Nullable PropertyValue resolveProperty(
            @NotNull String name,
            @NotNull Map<String, PropertyDefinition> definitions,
            @NotNull Map<String, PropertyValue> inherited,
            @NotNull Map<String, PropertyValue> localResolved,
            @NotNull Deque<String> resolving,
            @NotNull Set<String> failed,
            @NotNull CompilerContext context) {
        PropertyValue existing = localResolved.get(name);
        if (existing != null) {
            return existing;
        }
        PropertyDefinition definition = definitions.get(name);
        if (definition == null) {
            return inherited.get(name);
        }
        if (failed.contains(name)) {
            return null;
        }
        if (resolving.contains(name)) {
            List<String> cycle = new ArrayList<>(resolving);
            cycle.add(name);
            failed.addAll(cycle);
            diagnostic(
                    context,
                    MyBatisDynamicSqlDiagnosticCode.INCLUDE_PROPERTY_CYCLE,
                    MyBatisDynamicMessages.message(
                            "dynamic.diagnostic.include.property.cycle",
                            String.join(" -> ", cycle)),
                    definition.valueRange());
            return null;
        }

        resolving.addLast(name);
        String resolvedValue = substitute(
                definition.rawValue(),
                propertyName -> resolveProperty(
                        propertyName,
                        definitions,
                        inherited,
                        localResolved,
                        resolving,
                        failed,
                        context),
                definition.valueRange(),
                context,
                true);
        resolving.removeLast();
        if (resolvedValue == null) {
            failed.add(name);
            return null;
        }
        PropertyValue property = new PropertyValue(resolvedValue, definition.valueRange());
        localResolved.put(name, property);
        return property;
    }

    private static @NotNull Compilation compileUnsupported(
            @NotNull XmlTag tag,
            @NotNull CompilerContext context) {
        diagnostic(
                context,
                MyBatisDynamicSqlDiagnosticCode.UNSUPPORTED_DYNAMIC_TAG,
                MyBatisDynamicMessages.message(
                        "dynamic.diagnostic.tag.unsupported",
                        tag.getName()),
                sourceRange(tag));
        return new Compilation(compileChildren(tag, context).node(), Optional.empty());
    }

    private static @NotNull MyBatisDynamicSqlExpression expression(
            @NotNull XmlTag tag,
            @NotNull String attributeName,
            @NotNull CompilerContext context) {
        ResolvedAttribute value = requiredAttribute(tag, attributeName, context);
        return new MyBatisDynamicSqlExpression(value.text(), value.sourceRange());
    }

    private static @NotNull ResolvedAttribute requiredAttribute(
            @NotNull XmlTag tag,
            @NotNull String attributeName,
            @NotNull CompilerContext context) {
        ResolvedAttribute raw = rawRequiredAttribute(tag, attributeName, context);
        String resolved = substitute(
                raw.text(),
                context.properties()::get,
                raw.sourceRange(),
                context,
                context.strictProperties());
        return new ResolvedAttribute(resolved == null ? raw.text() : resolved, raw.sourceRange());
    }

    private static @NotNull ResolvedAttribute rawRequiredAttribute(
            @NotNull XmlTag tag,
            @NotNull String attributeName,
            @NotNull CompilerContext context) {
        XmlAttribute attribute = tag.getAttribute(attributeName);
        XmlAttributeValue value = attribute == null ? null : attribute.getValueElement();
        if (value == null || value.getValue().isBlank()) {
            diagnostic(
                    context,
                    MyBatisDynamicSqlDiagnosticCode.MISSING_REQUIRED_ATTRIBUTE,
                    MyBatisDynamicMessages.message(
                            "dynamic.diagnostic.attribute.required",
                            tag.getName(),
                            attributeName),
                    sourceRange(tag));
            return new ResolvedAttribute("", sourceRange(tag));
        }
        return new ResolvedAttribute(value.getValue(), attributeValueRange(value));
    }

    private static @NotNull String value(
            @NotNull XmlTag tag,
            @NotNull String attributeName,
            @NotNull CompilerContext context) {
        XmlAttribute attribute = tag.getAttribute(attributeName);
        XmlAttributeValue value = attribute == null ? null : attribute.getValueElement();
        if (value == null) {
            return "";
        }
        String resolved = substitute(
                value.getValue(),
                context.properties()::get,
                attributeValueRange(value),
                context,
                context.strictProperties());
        return resolved == null ? value.getValue() : resolved;
    }

    private static @NotNull Optional<Boolean> booleanValue(
            @NotNull XmlTag tag,
            @NotNull String attributeName,
            @NotNull CompilerContext context) {
        String value = value(tag, attributeName, context).trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        if ("true".equalsIgnoreCase(value)) {
            return Optional.of(true);
        }
        if ("false".equalsIgnoreCase(value)) {
            return Optional.of(false);
        }
        diagnostic(
                context,
                MyBatisDynamicSqlDiagnosticCode.INVALID_BOOLEAN_ATTRIBUTE,
                MyBatisDynamicMessages.message(
                        "dynamic.diagnostic.attribute.boolean",
                        tag.getName(),
                        attributeName),
                sourceRange(tag));
        return Optional.empty();
    }

    private static @NotNull MyBatisDynamicSqlBinding binding(
            @NotNull XmlTag tag,
            @NotNull String attributeName,
            @NotNull String name,
            @NotNull MyBatisDynamicSqlBindingKind kind) {
        XmlAttribute attribute = tag.getAttribute(attributeName);
        XmlAttributeValue value = attribute == null ? null : attribute.getValueElement();
        return new MyBatisDynamicSqlBinding(
                name,
                kind,
                Optional.empty(),
                value == null ? sourceRange(tag) : attributeValueRange(value));
    }

    private static @NotNull MyBatisMappedText compileText(
            @NotNull XmlText text,
            @NotNull CompilerContext context) {
        String fileUrl = fileUrl(text);
        if (fileUrl == null) {
            throw new NonCacheableCompileException(
                    new MyBatisDynamicSqlCompileResult.SourceInvalid());
        }
        String raw = text.getText();
        int sourceStart = text.getTextRange().getStartOffset();
        MyBatisSourceMapBuilder builder = new MyBatisSourceMapBuilder();
        int cursor = 0;
        while (cursor < raw.length()) {
            ProgressManager.checkCanceled();
            int cdata = raw.indexOf(CDATA_OPEN, cursor);
            int plainEnd = cdata < 0 ? raw.length() : cdata;
            appendTextSection(raw, cursor, plainEnd, true, sourceStart, fileUrl, builder, context);
            if (cdata < 0) {
                break;
            }
            int end = raw.indexOf(CDATA_CLOSE, cdata + CDATA_OPEN.length());
            if (end < 0) {
                diagnostic(
                        context,
                        MyBatisDynamicSqlDiagnosticCode.MALFORMED_CDATA,
                        MyBatisDynamicMessages.message(
                                "dynamic.diagnostic.cdata.unclosed"),
                        new MyBatisSourceRange(fileUrl,
                                new MyBatisTextRange(sourceStart + cdata,
                                        sourceStart + raw.length())));
                builder.appendExact(raw.substring(cdata), fileUrl, sourceStart + cdata);
                break;
            }
            int contentStart = cdata + CDATA_OPEN.length();
            appendTextSection(raw, contentStart, end, false, sourceStart, fileUrl, builder, context);
            cursor = end + CDATA_CLOSE.length();
        }
        return builder.build();
    }

    private static void appendTextSection(
            @NotNull String raw,
            int start,
            int end,
            boolean decodeEntities,
            int sourceStart,
            @NotNull String fileUrl,
            @NotNull MyBatisSourceMapBuilder builder,
            @NotNull CompilerContext context) {
        int cursor = start;
        while (cursor < end) {
            ProgressManager.checkCanceled();
            int property = raw.indexOf("${", cursor);
            if (property >= end) {
                property = -1;
            }
            int entity = decodeEntities ? raw.indexOf('&', cursor) : -1;
            if (entity >= end) {
                entity = -1;
            }
            int next = first(property, entity, end);
            if (next > cursor) {
                builder.appendExact(raw.substring(cursor, next), fileUrl, sourceStart + cursor);
                cursor = next;
            }
            if (cursor == property) {
                int close = raw.indexOf('}', cursor + 2);
                if (close < 0 || close >= end) {
                    builder.appendExact(raw.substring(cursor, end), fileUrl, sourceStart + cursor);
                    return;
                }
                String name = raw.substring(cursor + 2, close).trim();
                PropertyValue replacement = context.properties().get(name);
                if (replacement != null) {
                    builder.appendSynthetic(
                            replacement.value(),
                            replacement.sourceRange().fileUrl(),
                            replacement.sourceRange().range());
                } else {
                    if (context.strictProperties()) {
                        diagnostic(
                                context,
                                MyBatisDynamicSqlDiagnosticCode.UNRESOLVED_INCLUDE_PROPERTY,
                                MyBatisDynamicMessages.message(
                                        "dynamic.diagnostic.include.property.unresolved",
                                        name),
                                new MyBatisSourceRange(fileUrl,
                                        new MyBatisTextRange(sourceStart + cursor,
                                                sourceStart + close + 1)));
                    }
                    builder.appendExact(
                            raw.substring(cursor, close + 1),
                            fileUrl,
                            sourceStart + cursor);
                }
                cursor = close + 1;
                continue;
            }
            if (cursor == entity) {
                int close = raw.indexOf(';', cursor + 1);
                if (close > cursor && close < end) {
                    String entityName = raw.substring(cursor + 1, close);
                    String decoded = decodeEntity(entityName);
                    if (decoded != null) {
                        builder.appendDecoded(
                                decoded,
                                fileUrl,
                                new MyBatisTextRange(
                                        sourceStart + cursor,
                                        sourceStart + close + 1));
                        cursor = close + 1;
                        continue;
                    }
                    diagnostic(
                            context,
                            MyBatisDynamicSqlDiagnosticCode.UNKNOWN_XML_ENTITY,
                            MyBatisDynamicMessages.message(
                                    "dynamic.diagnostic.xml.entity.unknown",
                                    entityName),
                            new MyBatisSourceRange(fileUrl,
                                    new MyBatisTextRange(sourceStart + cursor,
                                            sourceStart + close + 1)));
                }
                builder.appendExact("&", fileUrl, sourceStart + cursor);
                cursor++;
            }
        }
    }

    private static @Nullable String substitute(
            @NotNull String raw,
            @NotNull java.util.function.Function<String, PropertyValue> lookup,
            @NotNull MyBatisSourceRange sourceRange,
            @NotNull CompilerContext context,
            boolean strict) {
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        boolean valid = true;
        while (cursor < raw.length()) {
            ProgressManager.checkCanceled();
            int start = raw.indexOf("${", cursor);
            if (start < 0) {
                result.append(raw, cursor, raw.length());
                break;
            }
            result.append(raw, cursor, start);
            int end = raw.indexOf('}', start + 2);
            if (end < 0) {
                result.append(raw, start, raw.length());
                valid = !strict;
                break;
            }
            String name = raw.substring(start + 2, end).trim();
            PropertyValue property = lookup.apply(name);
            if (property == null) {
                result.append(raw, start, end + 1);
                if (strict) {
                    diagnostic(
                            context,
                            MyBatisDynamicSqlDiagnosticCode.UNRESOLVED_INCLUDE_PROPERTY,
                            MyBatisDynamicMessages.message(
                                    "dynamic.diagnostic.include.property.unresolved",
                                    name),
                            sourceRange);
                    valid = false;
                }
            } else {
                result.append(property.value());
            }
            cursor = end + 1;
        }
        return valid ? result.toString() : null;
    }

    private static void appendMapped(
            @NotNull MyBatisSourceMapBuilder target,
            @NotNull MyBatisMappedText mapped) {
        for (MyBatisSourceMapSegment segment : mapped.sourceMap().segments()) {
            ProgressManager.checkCanceled();
            String value = mapped.text().substring(
                    segment.virtualRange().startOffset(),
                    segment.virtualRange().endOffset());
            switch (segment.kind()) {
                case EXACT -> target.appendExact(
                        value,
                        segment.sourceRange().fileUrl(),
                        segment.sourceRange().range().startOffset());
                case DECODED -> target.appendDecoded(
                        value,
                        segment.sourceRange().fileUrl(),
                        segment.sourceRange().range());
                case SYNTHETIC -> target.appendSynthetic(
                        value,
                        segment.sourceRange().fileUrl(),
                        segment.sourceRange().range());
            }
        }
    }

    private static @Nullable String decodeEntity(@NotNull String entity) {
        String known = XML_ENTITIES.get(entity);
        if (known != null) {
            return known;
        }
        try {
            int codePoint;
            if (entity.startsWith("#x") || entity.startsWith("#X")) {
                codePoint = Integer.parseInt(entity.substring(2), 16);
            } else if (entity.startsWith("#")) {
                codePoint = Integer.parseInt(entity.substring(1));
            } else {
                return null;
            }
            return Character.isValidCodePoint(codePoint)
                    ? new String(Character.toChars(codePoint))
                    : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int first(int left, int right, int fallback) {
        if (left < 0) {
            return right < 0 ? fallback : right;
        }
        return right < 0 ? left : Math.min(left, right);
    }

    private static @NotNull String defaultValue(
            @NotNull String value,
            @NotNull String fallback) {
        return value.isBlank() ? fallback : value;
    }

    private static boolean hasPlaceholder(@NotNull String value) {
        return value.contains("${") || value.contains("#{");
    }

    private static void diagnostic(
            @NotNull CompilerContext context,
            @NotNull MyBatisDynamicSqlDiagnosticCode code,
            @NotNull String message,
            @NotNull MyBatisSourceRange range) {
        context.diagnostics().add(new MyBatisDynamicSqlDiagnostic(code, message, range));
    }

    private static @Nullable String fileUrl(@NotNull PsiElement element) {
        PsiFile file = element.getContainingFile();
        VirtualFile virtualFile = file == null ? null : file.getVirtualFile();
        return virtualFile == null ? null : virtualFile.getUrl();
    }

    private static @NotNull MyBatisSourceRange sourceRange(@NotNull PsiElement element) {
        String url = fileUrl(element);
        if (url == null) {
            throw new NonCacheableCompileException(
                    new MyBatisDynamicSqlCompileResult.SourceInvalid());
        }
        TextRange range = element.getTextRange();
        return new MyBatisSourceRange(
                url,
                new MyBatisTextRange(range.getStartOffset(), range.getEndOffset()));
    }

    private static @NotNull MyBatisSourceRange attributeValueRange(
            @NotNull XmlAttributeValue value) {
        String url = fileUrl(value);
        if (url == null) {
            throw new NonCacheableCompileException(
                    new MyBatisDynamicSqlCompileResult.SourceInvalid());
        }
        TextRange range = value.getValueTextRange();
        return new MyBatisSourceRange(
                url,
                new MyBatisTextRange(range.getStartOffset(), range.getEndOffset()));
    }

    private record CompilerContext(
            @NotNull Project project,
            @NotNull GlobalSearchScope scope,
            @NotNull String namespace,
            @NotNull Map<String, PropertyValue> properties,
            @NotNull List<String> includeChain,
            boolean strictProperties,
            @NotNull List<MyBatisDynamicSqlDiagnostic> diagnostics,
            @NotNull CompilationDependencies dependencies) {
        private @NotNull CompilerContext withProperties(
                @NotNull Map<String, PropertyValue> replacements,
                boolean strict) {
            return new CompilerContext(
                    project,
                    scope,
                    namespace,
                    replacements,
                    includeChain,
                    strict,
                    diagnostics,
                    dependencies);
        }

        private @NotNull CompilerContext forInclude(
                @NotNull String targetNamespace,
                @NotNull String fragmentKey) {
            List<String> chain = new ArrayList<>(includeChain);
            chain.add(fragmentKey);
            return new CompilerContext(
                    project,
                    scope,
                    targetNamespace,
                    properties,
                    List.copyOf(chain),
                    false,
                    diagnostics,
                    dependencies);
        }
    }

    private static final class CompilationDependencies {
        private final Set<PsiFile> files = new LinkedHashSet<>();
        private boolean indexConsulted;

        private void addFile(@NotNull PsiFile file) {
            files.add(file);
        }

        private @NotNull Set<PsiFile> files() {
            return Set.copyOf(files);
        }

        private void markIndexConsulted() {
            indexConsulted = true;
        }

        private boolean indexConsulted() {
            return indexConsulted;
        }
    }

    private record CompileComputation(
            @NotNull MyBatisDynamicSqlCompileResult result,
            @NotNull CompilationDependencies dependencies) {
    }

    private static final class NonCacheableCompileException extends RuntimeException {
        private final MyBatisDynamicSqlCompileResult result;

        private NonCacheableCompileException(@NotNull MyBatisDynamicSqlCompileResult result) {
            this.result = result;
        }

        private @NotNull MyBatisDynamicSqlCompileResult result() {
            return result;
        }
    }

    private record Compilation(
            @NotNull MyBatisDynamicSqlNode node,
            @NotNull Optional<MyBatisMappedText> staticSql) {
    }

    private record ResolvedAttribute(
            @NotNull String text,
            @NotNull MyBatisSourceRange sourceRange) {
    }

    private record PropertyDefinition(
            @NotNull String name,
            @NotNull String rawValue,
            @NotNull MyBatisSourceRange nameRange,
            @NotNull MyBatisSourceRange valueRange) {
    }

    private record PropertyValue(
            @NotNull String value,
            @NotNull MyBatisSourceRange sourceRange) {
    }

    private record PropertyResolution(
            @NotNull Map<String, PropertyValue> values,
            @NotNull List<MyBatisDynamicSqlBinding> bindings,
            boolean valid) {
        private PropertyResolution {
            bindings = List.copyOf(bindings);
        }
    }

    private record QualifiedFragment(@NotNull String namespace, @NotNull String id) {
        private static @Nullable QualifiedFragment parse(
                @NotNull String refid,
                @NotNull String localNamespace) {
            String normalized = refid.trim();
            if (normalized.isEmpty() || normalized.chars().anyMatch(Character::isWhitespace)) {
                return null;
            }
            int separator = normalized.lastIndexOf('.');
            String namespace = separator > 0
                    ? normalized.substring(0, separator)
                    : localNamespace;
            String id = separator > 0 ? normalized.substring(separator + 1) : normalized;
            return namespace.isBlank() || id.isBlank()
                    ? null
                    : new QualifiedFragment(namespace, id);
        }
    }
}
