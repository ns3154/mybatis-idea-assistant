package io.github.ns3154.mybatisassistant.sqltool.log;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 不执行语义猜测的保守 SQL 风险分类器。
 */
public final class MyBatisSqlRiskClassifier {
    private static final Pattern FIRST_WORD = Pattern.compile("[A-Za-z]+", Pattern.CASE_INSENSITIVE);
    private static final Set<String> READ_ONLY = Set.of(
            "SELECT", "SHOW", "DESCRIBE", "DESC", "VALUES");
    private static final Set<String> WRITE = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "REPLACE", "UPSERT");
    private static final Set<String> DDL = Set.of(
            "CREATE", "ALTER", "DROP", "TRUNCATE", "COMMENT", "GRANT", "REVOKE");

    private MyBatisSqlRiskClassifier() {
    }

    public static @NotNull MyBatisSqlRiskAssessment assess(@NotNull String sql) {
        try {
            List<String> statements = MyBatisSqlLexicalScanner.scan(sql, List.of()).statements();
            MyBatisSqlRisk risk = aggregate(statements);
            boolean multiple = statements.size() > 1;
            return new MyBatisSqlRiskAssessment(
                    risk,
                    statements.size(),
                    multiple,
                    risk != MyBatisSqlRisk.READ_ONLY || multiple,
                    true);
        } catch (MyBatisSqlLexicalScanner.MalformedSqlException malformed) {
            return new MyBatisSqlRiskAssessment(
                    MyBatisSqlRisk.UNKNOWN, 0, false, true, false);
        }
    }

    private static MyBatisSqlRisk aggregate(List<String> statements) {
        if (statements.isEmpty()) {
            return MyBatisSqlRisk.UNKNOWN;
        }
        boolean write = false;
        boolean ddl = false;
        for (String statement : statements) {
            String keyword = firstWord(statement);
            if (DDL.contains(keyword)) {
                ddl = true;
            } else if (WRITE.contains(keyword)) {
                write = true;
            } else if (!READ_ONLY.contains(keyword)) {
                return MyBatisSqlRisk.UNKNOWN;
            }
        }
        if (ddl) {
            return MyBatisSqlRisk.DDL;
        }
        return write ? MyBatisSqlRisk.WRITE : MyBatisSqlRisk.READ_ONLY;
    }

    private static String firstWord(String statement) {
        Matcher matcher = FIRST_WORD.matcher(statement);
        return matcher.find() ? matcher.group().toUpperCase(Locale.ROOT) : "";
    }
}
