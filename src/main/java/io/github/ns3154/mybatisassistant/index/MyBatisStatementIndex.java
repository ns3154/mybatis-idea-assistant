package io.github.ns3154.mybatisassistant.index;

import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileBasedIndexExtension;
import com.intellij.util.indexing.FileContent;
import com.intellij.util.indexing.ID;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import com.intellij.util.io.VoidDataExternalizer;
import io.github.ns3154.mybatisassistant.model.MyBatisStatementKey;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MyBatisStatementIndex extends FileBasedIndexExtension<String, Void> {
    public static final ID<String, Void> NAME = ID.create("mybatis.idea.assistant.statement");

    @Override
    public @NotNull ID<String, Void> getName() {
        return NAME;
    }

    @Override
    public @NotNull DataIndexer<String, Void, FileContent> getIndexer() {
        return inputData -> {
            if (!(inputData.getPsiFile() instanceof XmlFile xmlFile)) {
                return Map.of();
            }
            if (PsiTreeUtil.hasErrorElements(xmlFile)) {
                return Map.of();
            }

            XmlTag rootTag = xmlFile.getRootTag();
            if (rootTag == null || !MyBatisXmlModel.isMapperRoot(rootTag)) {
                return Map.of();
            }

            String namespace = MyBatisXmlModel.namespace(rootTag);
            if (namespace == null) {
                return Map.of();
            }

            Map<String, Void> keys = new LinkedHashMap<>();
            for (XmlTag child : rootTag.getSubTags()) {
                ProgressManager.checkCanceled();
                if (!MyBatisXmlModel.isStatement(child)) {
                    continue;
                }
                String statementId = MyBatisXmlModel.statementId(child);
                if (statementId != null) {
                    keys.put(MyBatisStatementKey.of(namespace, statementId), null);
                }
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
