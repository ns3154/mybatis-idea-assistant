package io.github.ns3154.mybatisassistant.sql.intellij;

import com.intellij.psi.PsiFile;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.sql.MyBatisVirtualSql;
import org.jetbrains.annotations.NotNull;

/**
 * 可选 SQL PSI 构建的类型化结果。
 */
public sealed interface MyBatisSqlPsiResult {
    record Ready(
            @NotNull PsiFile psiFile,
            @NotNull MyBatisVirtualSql virtualSql,
            @NotNull MyBatisSqlDialect dialect) implements MyBatisSqlPsiResult {
    }

    record IndexNotReady() implements MyBatisSqlPsiResult {
    }

    record SourceInvalid() implements MyBatisSqlPsiResult {
    }

    record UnsupportedSource() implements MyBatisSqlPsiResult {
    }
}
