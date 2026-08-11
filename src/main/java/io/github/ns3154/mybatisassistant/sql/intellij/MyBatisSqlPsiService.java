package io.github.ns3154.mybatisassistant.sql.intellij;

import com.intellij.lang.Language;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.xml.XmlTag;
import com.intellij.sql.dialects.generic.GenericDialect;
import com.intellij.sql.dialects.mssql.MsDialect;
import com.intellij.sql.dialects.mysql.MysqlDialect;
import com.intellij.sql.dialects.oracle.OraDialect;
import com.intellij.sql.dialects.postgres.PgDialect;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataService;
import io.github.ns3154.mybatisassistant.database.MyBatisMetadataFreshness;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisDynamicSqlCompileResult;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisDynamicSqlCompiler;
import io.github.ns3154.mybatisassistant.sql.MyBatisVirtualSql;
import io.github.ns3154.mybatisassistant.sql.MyBatisVirtualSqlBuilder;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 在 Database Tools 可用时把代表性虚拟 SQL 构造成真实 SQL PSI。
 */
public final class MyBatisSqlPsiService {
    private final Project project;

    public MyBatisSqlPsiService(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull MyBatisSqlPsiService getInstance(@NotNull Project project) {
        return project.getService(MyBatisSqlPsiService.class);
    }

    public @NotNull MyBatisSqlPsiResult parse(@NotNull XmlTag statement) {
        if (project.isDisposed() || !project.isOpen() || !statement.isValid()) {
            return new MyBatisSqlPsiResult.SourceInvalid();
        }
        MyBatisDynamicSqlCompileResult compilation = MyBatisDynamicSqlCompiler.compile(statement);
        if (compilation instanceof MyBatisDynamicSqlCompileResult.IndexNotReady) {
            return new MyBatisSqlPsiResult.IndexNotReady();
        }
        if (compilation instanceof MyBatisDynamicSqlCompileResult.SourceInvalid) {
            return new MyBatisSqlPsiResult.SourceInvalid();
        }
        if (!(compilation instanceof MyBatisDynamicSqlCompileResult.Compiled compiled)) {
            return new MyBatisSqlPsiResult.UnsupportedSource();
        }

        MyBatisVirtualSql virtualSql = MyBatisVirtualSqlBuilder.build(compiled.program());
        MyBatisSqlDialect dialect = dialect(statement);
        Language language = language(dialect);
        String statementId = statement.getAttributeValue("id");
        String fileName = "mybatis-"
                + (statementId == null || statementId.isBlank() ? "statement" : statementId)
                + ".sql";
        PsiFile psiFile = PsiFileFactory.getInstance(project).createFileFromText(
                fileName,
                language,
                virtualSql.mappedText().text(),
                false,
                false);
        return new MyBatisSqlPsiResult.Ready(psiFile, virtualSql, dialect);
    }

    static @NotNull Language language(@NotNull MyBatisSqlDialect dialect) {
        return switch (dialect) {
            case MYSQL -> MysqlDialect.INSTANCE;
            case POSTGRESQL -> PgDialect.INSTANCE;
            case ORACLE -> OraDialect.INSTANCE;
            case SQL_SERVER -> MsDialect.INSTANCE;
            case GENERIC, SQLITE, H2 -> GenericDialect.INSTANCE;
        };
    }

    private @NotNull MyBatisSqlDialect dialect(@NotNull XmlTag statement) {
        String databaseId = statement.getAttributeValue("databaseId");
        if (databaseId != null && !databaseId.isBlank()) {
            return MyBatisSqlDialect.fromDatabaseId(databaseId);
        }
        List<MyBatisSqlDialect> readyDialects = MyBatisDatabaseMetadataService
                .getInstance(project)
                .latest()
                .stream()
                .flatMap(loaded -> loaded.snapshots().stream())
                .filter(snapshot -> snapshot.freshness() == MyBatisMetadataFreshness.READY)
                .map(snapshot -> snapshot.dialect())
                .distinct()
                .toList();
        return readyDialects.size() == 1
                ? readyDialects.getFirst()
                : MyBatisSqlDialect.GENERIC;
    }
}
