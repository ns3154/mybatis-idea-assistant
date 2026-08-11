package io.github.ns3154.mybatisassistant.index;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileBasedIndexExtension;
import com.intellij.util.indexing.FileContent;
import com.intellij.util.indexing.ID;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import com.intellij.util.io.VoidDataExternalizer;
import io.github.ns3154.mybatisassistant.model.MyBatisBootConfigurationEntry;
import io.github.ns3154.mybatisassistant.model.MyBatisBootConfigurationKey;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class MyBatisBootConfigurationIndex extends FileBasedIndexExtension<String, Void> {
    public static final ID<String, Void> NAME = ID.create("mybatis.idea.assistant.boot.configuration");
    private static final Set<String> EXTENSIONS = Set.of("properties", "yml", "yaml");

    @Override
    public @NotNull ID<String, Void> getName() {
        return NAME;
    }

    @Override
    public @NotNull DataIndexer<String, Void, FileContent> getIndexer() {
        return inputData -> {
            Map<String, Void> keys = new LinkedHashMap<>();
            String extension = inputData.getFile().getExtension();
            if (extension == null) {
                return keys;
            }
            for (MyBatisBootConfigurationEntry entry : MyBatisBootConfigurationScanner.scan(
                    inputData.getContentAsText(),
                    extension)) {
                ProgressManager.checkCanceled();
                keys.put(entry.indexKey(), null);
                keys.put(MyBatisBootConfigurationKey.all(entry.kind()), null);
            }
            return keys;
        };
    }

    @Override
    public @NotNull KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    @Override
    public @NotNull DataExternalizer<Void> getValueExternalizer() {
        return VoidDataExternalizer.INSTANCE;
    }

    @Override
    public @NotNull FileBasedIndex.InputFilter getInputFilter() {
        return file -> file.getExtension() != null
                && EXTENSIONS.contains(file.getExtension().toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public boolean dependsOnFileContent() {
        return true;
    }

    @Override
    public int getVersion() {
        return 1;
    }
}
