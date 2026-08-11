package io.github.ns3154.mybatisassistant.index;

import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.fileTypes.FileType;
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
import io.github.ns3154.mybatisassistant.model.MyBatisXmlSymbol;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MyBatisXmlSymbolIndex extends FileBasedIndexExtension<String, Void> {
    public static final ID<String, Void> NAME = ID.create("mybatis.idea.assistant.xml.symbol");

    @Override
    public @NotNull ID<String, Void> getName() {
        return NAME;
    }

    @Override
    public @NotNull DataIndexer<String, Void, FileContent> getIndexer() {
        return inputData -> {
            Map<String, Void> keys = new LinkedHashMap<>();
            for (MyBatisXmlSymbol symbol : MyBatisXmlSymbolScanner.scan(inputData.getContentAsText())) {
                ProgressManager.checkCanceled();
                keys.put(symbol.indexKey(), null);
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
        return file -> {
            FileType fileType = file.getFileType();
            return fileType == XmlFileType.INSTANCE;
        };
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
