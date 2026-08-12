package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.XmlElementVisitor;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlTokenType;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisDynamicSqlCompileResult;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisDynamicSqlCompiler;
import io.github.ns3154.mybatisassistant.sql.MyBatisVirtualSql;
import io.github.ns3154.mybatisassistant.sql.MyBatisVirtualSqlDiagnosticCode;
import io.github.ns3154.mybatisassistant.sql.MyBatisVirtualSqlBuilder;
import io.github.ns3154.mybatisassistant.sqltool.log.MyBatisSqlRiskClassifier;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * 保守报告代表 SQL 中可证明缺少 WHERE 的 update/delete statement。
 */
public final class MyBatisDangerousStatementInspection extends LocalInspectionTool {
    private static final Set<String> WRITE_TAGS = Set.of("update", "delete");
    private static final String DYNAMIC_IDENTIFIER = "__mybatis_dynamic__";

    @Override
    public @NotNull PsiElementVisitor buildVisitor(
            @NotNull ProblemsHolder holder,
            boolean isOnTheFly) {
        return new XmlElementVisitor() {
            @Override
            public void visitXmlTag(@NotNull XmlTag tag) {
                ProgressManager.checkCanceled();
                if (!MyBatisXmlInspectionSupport.isDirectStatement(tag)
                        || !WRITE_TAGS.contains(tag.getName())
                        || !MyBatisXmlInspectionSupport.isReady(tag)) {
                    return;
                }
                MyBatisDynamicSqlCompileResult compilation = MyBatisDynamicSqlCompiler
                        .compile(tag);
                if (!(compilation instanceof MyBatisDynamicSqlCompileResult.Compiled compiled)
                        || !compiled.program().diagnostics().isEmpty()) {
                    return;
                }
                MyBatisVirtualSql virtualSql = MyBatisVirtualSqlBuilder.build(compiled.program());
                if (virtualSql.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code()
                        == MyBatisVirtualSqlDiagnosticCode.MALFORMED_PARAMETER_PLACEHOLDER)) {
                    return;
                }
                String sql = virtualSql.mappedText().text();
                if (sql.contains(DYNAMIC_IDENTIFIER)
                        || MyBatisSqlRiskClassifier.findWhereKeyword(sql, tag.getName())
                                != MyBatisSqlRiskClassifier.SqlKeywordResult.ABSENT) {
                    return;
                }
                holder.registerProblem(
                        problemElement(tag),
                        MyBatisAssistantBundle.message(
                                "inspection.dangerous.statement.problem",
                                tag.getName()));
            }
        };
    }

    private static @NotNull PsiElement problemElement(@NotNull XmlTag tag) {
        ASTNode name = tag.getNode().findChildByType(XmlTokenType.XML_NAME);
        return name == null ? tag : name.getPsi();
    }
}
