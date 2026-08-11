package io.github.ns3154.mybatisassistant.database;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 可由 Database Tools 或未来 JDBC 适配器实现的后台元数据提供方。
 */
public interface MyBatisDatabaseMetadataProvider {
    ExtensionPointName<MyBatisDatabaseMetadataProvider> EP_NAME = ExtensionPointName.create(
            "io.github.ns3154.mybatis-idea-assistant.databaseMetadataProvider");

    @NotNull String id();

    @NotNull List<MyBatisDatabaseSnapshot> load(
            @NotNull Project project,
            @NotNull MyBatisDatabaseRequest request,
            @NotNull ProgressIndicator indicator);
}
