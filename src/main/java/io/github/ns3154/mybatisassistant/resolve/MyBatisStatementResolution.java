package io.github.ns3154.mybatisassistant.resolve;

import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

public sealed interface MyBatisStatementResolution {
    record UniqueMatch(@NotNull SmartPsiElementPointer<XmlTag> target)
            implements MyBatisStatementResolution {
        public UniqueMatch {
            Objects.requireNonNull(target, "target");
        }
    }

    record NoMapperXml(@NotNull String namespace) implements MyBatisStatementResolution {
        public NoMapperXml {
            Objects.requireNonNull(namespace, "namespace");
        }
    }

    record StatementMissing(@NotNull String namespace, @NotNull String statementId)
            implements MyBatisStatementResolution {
        public StatementMissing {
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(statementId, "statementId");
        }
    }

    record MultipleMatches(@NotNull List<SmartPsiElementPointer<XmlTag>> targets)
            implements MyBatisStatementResolution {
        public MultipleMatches {
            targets = List.copyOf(targets);
            if (targets.size() < 2) {
                throw new IllegalArgumentException("多候选结果至少需要两个目标");
            }
        }
    }

    record IndexNotReady() implements MyBatisStatementResolution {
    }

    record SourceInvalid() implements MyBatisStatementResolution {
    }

    record UnsupportedSource() implements MyBatisStatementResolution {
    }
}
