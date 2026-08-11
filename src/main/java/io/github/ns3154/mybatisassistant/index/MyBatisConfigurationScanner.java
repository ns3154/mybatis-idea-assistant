package io.github.ns3154.mybatisassistant.index;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.util.xml.NanoXmlBuilder;
import io.github.ns3154.mybatisassistant.model.MyBatisConfigurationEntries;
import io.github.ns3154.mybatisassistant.model.MyBatisConfigurationEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class MyBatisConfigurationScanner {
    private MyBatisConfigurationScanner() {
    }

    public static @NotNull Set<MyBatisConfigurationEntry> scan(@NotNull CharSequence content) {
        ProgressManager.checkCanceled();
        ConfigurationBuilder builder = new ConfigurationBuilder();
        return SafeNanoXmlParser.parse(content, builder) ? builder.result() : Set.of();
    }

    private static boolean isUnprefixed(@NotNull String name, @Nullable String namespacePrefix) {
        return (namespacePrefix == null || namespacePrefix.isEmpty()) && name.indexOf(':') < 0;
    }

    private static final class ConfigurationBuilder implements NanoXmlBuilder {
        private final Set<MyBatisConfigurationEntry> entries = new LinkedHashSet<>();
        private final Map<String, String> attributes = new HashMap<>();
        private int depth;
        private boolean rootSeen;
        private boolean configurationRoot;
        private String section;
        private String entryTag;

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
                configurationRoot = isUnprefixed(name, namespacePrefix) && "configuration".equals(name);
                if (!configurationRoot) {
                    NanoXmlBuilder.stop();
                }
            } else if (depth == 2 && configurationRoot && isUnprefixed(name, namespacePrefix)) {
                section = "typeAliases".equals(name) || "mappers".equals(name) ? name : null;
            } else if (depth == 3 && section != null && isUnprefixed(name, namespacePrefix)) {
                entryTag = name;
                attributes.clear();
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
            if (depth == 3 && entryTag != null && isUnprefixed(key, namespacePrefix)) {
                attributes.put(key, value);
            }
        }

        @Override
        public void elementAttributesProcessed(
                String name,
                String namespacePrefix,
                String namespaceSystemId) {
            ProgressManager.checkCanceled();
            if (depth == 3 && entryTag != null) {
                entries.addAll(MyBatisConfigurationEntries.fromTag(section, entryTag, attributes::get));
            }
        }

        @Override
        public void endElement(String name, String namespacePrefix, String namespaceSystemId) {
            ProgressManager.checkCanceled();
            if (depth == 3) {
                entryTag = null;
                attributes.clear();
            } else if (depth == 2) {
                section = null;
            }
            depth--;
        }

        @Override
        public void addPCData(Reader reader, String systemId, int lineNr) {
            ProgressManager.checkCanceled();
        }

        private @NotNull Set<MyBatisConfigurationEntry> result() {
            return Set.copyOf(entries);
        }
    }
}
