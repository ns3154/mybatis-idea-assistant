package io.github.ns3154.mybatisassistant.index;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.util.xml.NanoXmlBuilder;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlSymbol;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlSymbolKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.util.LinkedHashSet;
import java.util.Set;

public final class MyBatisXmlSymbolScanner {
    private MyBatisXmlSymbolScanner() {
    }

    public static @NotNull Set<MyBatisXmlSymbol> scan(@NotNull CharSequence content) {
        ProgressManager.checkCanceled();
        SymbolBuilder builder = new SymbolBuilder();
        return SafeNanoXmlParser.parse(content, builder) ? builder.result() : Set.of();
    }

    private static @Nullable String normalized(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static boolean isUnprefixed(@NotNull String name, @Nullable String namespacePrefix) {
        return (namespacePrefix == null || namespacePrefix.isEmpty()) && name.indexOf(':') < 0;
    }

    private static final class SymbolBuilder implements NanoXmlBuilder {
        private final Set<MyBatisXmlSymbol> symbols = new LinkedHashSet<>();
        private int depth;
        private boolean rootSeen;
        private boolean mapperRoot;
        private String namespace;
        private String currentId;
        private MyBatisXmlSymbolKind currentKind;

        @Override
        public void startElement(
                String name,
                String namespacePrefix,
                String namespaceSystemId,
                String systemId,
                int lineNr) throws Exception {
            ProgressManager.checkCanceled();
            if (depth == 0) {
                if (rootSeen) {
                    NanoXmlBuilder.stop();
                }
                rootSeen = true;
            }
            depth++;
            if (depth == 1) {
                mapperRoot = isUnprefixed(name, namespacePrefix) && "mapper".equals(name);
                if (!mapperRoot) {
                    NanoXmlBuilder.stop();
                }
                return;
            }
            if (depth == 2 && mapperRoot && isUnprefixed(name, namespacePrefix)) {
                currentKind = MyBatisXmlSymbolKind.fromMapperChildTag(name);
                currentId = null;
            }
        }

        @Override
        public void addAttribute(
                String key,
                String namespacePrefix,
                String namespaceSystemId,
                String value,
                String type) {
            ProgressManager.checkCanceled();
            if (!isUnprefixed(key, namespacePrefix)) {
                return;
            }
            if (depth == 1 && mapperRoot && "namespace".equals(key)) {
                namespace = normalized(value);
            } else if (depth == 2 && currentKind != null && "id".equals(key)) {
                currentId = normalized(value);
            }
        }

        @Override
        public void elementAttributesProcessed(
                String name,
                String namespacePrefix,
                String namespaceSystemId) {
            ProgressManager.checkCanceled();
            if (depth == 1 && mapperRoot && namespace != null) {
                symbols.add(MyBatisXmlSymbol.namespace(namespace));
            } else if (depth == 2 && namespace != null && currentKind != null && currentId != null) {
                symbols.add(MyBatisXmlSymbol.named(currentKind, namespace, currentId));
            }
        }

        @Override
        public void endElement(String name, String namespacePrefix, String namespaceSystemId) {
            ProgressManager.checkCanceled();
            if (depth == 2) {
                currentKind = null;
                currentId = null;
            }
            depth--;
        }

        @Override
        public void addPCData(Reader reader, String systemId, int lineNr) {
            ProgressManager.checkCanceled();
        }

        private @NotNull Set<MyBatisXmlSymbol> result() {
            return Set.copyOf(symbols);
        }
    }

}
