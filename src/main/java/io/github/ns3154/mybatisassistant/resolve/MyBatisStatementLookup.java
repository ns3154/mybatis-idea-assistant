package io.github.ns3154.mybatisassistant.resolve;

import com.intellij.openapi.project.Project;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Mapper XML 索引查询边界。生产实现访问 FileBasedIndex，测试实现可记录查询预算。
 */
interface MyBatisStatementLookup {
    @NotNull List<XmlTag> find(
            @NotNull Project project,
            @NotNull String namespace,
            @NotNull String statementId);

    boolean hasMapperXml(@NotNull Project project, @NotNull String namespace);
}
