package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.XmlElementVisitor;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.index.MyBatisXmlSymbolLocator;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlSymbolKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 检查相同 namespace、id 与 databaseId 下的重复 statement。
 */
public final class MyBatisDuplicateStatementInspection extends LocalInspectionTool {
    @Override
    public @NotNull PsiElementVisitor buildVisitor(
            @NotNull ProblemsHolder holder,
            boolean isOnTheFly) {
        return new XmlElementVisitor() {
            @Override
            public void visitXmlTag(@NotNull XmlTag tag) {
                ProgressManager.checkCanceled();
                if (!MyBatisXmlInspectionSupport.isDirectStatement(tag)
                        || !MyBatisXmlInspectionSupport.isReady(tag)) {
                    return;
                }
                XmlTag mapper = tag.getParentTag();
                String namespace = mapper == null ? null : MyBatisXmlModel.namespace(mapper);
                String statementId = MyBatisXmlModel.statementId(tag);
                XmlAttributeValue idValue = MyBatisXmlInspectionSupport.exactAttributeValue(
                        tag,
                        "id");
                DatabaseId databaseId = DatabaseId.from(tag);
                if (namespace == null
                        || statementId == null
                        || idValue == null
                        || !MyBatisXmlInspectionSupport.isStaticValue(namespace)
                        || !MyBatisXmlInspectionSupport.isStaticValue(statementId)
                        || !databaseId.known()) {
                    return;
                }
                try {
                    List<XmlTag> duplicates = MyBatisXmlSymbolLocator.find(
                                    tag.getProject(),
                                    MyBatisXmlSymbolKind.STATEMENT,
                                    namespace,
                                    statementId,
                                    MyBatisXmlInspectionSupport.resolveScope(tag))
                            .stream()
                            .filter(candidate -> databaseId.equals(DatabaseId.from(candidate)))
                            .toList();
                    if (duplicates.size() > 1) {
                        holder.registerProblem(
                                idValue,
                                MyBatisAssistantBundle.message(
                                        "inspection.duplicate.statement.problem",
                                        namespace,
                                        statementId,
                                        databaseId.displayName(),
                                        duplicates.size()));
                    }
                } catch (IndexNotReadyException ignored) {
                    // 索引竞态时不报告可能过期的重复声明。
                }
            }
        };
    }

    private record DatabaseId(boolean known, @Nullable String value) {
        private static @NotNull DatabaseId from(@NotNull XmlTag tag) {
            XmlAttributeValue valueElement = MyBatisXmlInspectionSupport.exactAttributeValue(
                    tag,
                    "databaseId");
            if (valueElement == null) {
                return new DatabaseId(true, null);
            }
            String value = valueElement.getValue().trim();
            return MyBatisXmlInspectionSupport.isStaticValue(value)
                    ? new DatabaseId(true, value)
                    : new DatabaseId(false, null);
        }

        private @NotNull String displayName() {
            return Objects.requireNonNullElse(value, MyBatisAssistantBundle.message(
                    "inspection.duplicate.statement.database.default"));
        }
    }
}
