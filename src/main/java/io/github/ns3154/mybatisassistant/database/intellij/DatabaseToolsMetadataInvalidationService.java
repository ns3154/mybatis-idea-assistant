package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.database.psi.DbPsiFacade;
import com.intellij.openapi.project.Project;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataService;
import org.jetbrains.annotations.NotNull;

/**
 * 监听 Database Tools 模型变化，使表列补全与检查不会继续消费旧快照。
 */
public final class DatabaseToolsMetadataInvalidationService {
    public DatabaseToolsMetadataInvalidationService(@NotNull Project project) {
        project.getMessageBus().connect(project).subscribe(
                DbPsiFacade.TOPIC,
                dataSource -> MyBatisDatabaseMetadataService
                        .getInstance(project)
                        .invalidate());
    }

    static @NotNull DatabaseToolsMetadataInvalidationService getInstance(
            @NotNull Project project) {
        return project.getService(DatabaseToolsMetadataInvalidationService.class);
    }
}
