package io.github.mybatisideaassistant.corpus.java.mapper;

public final class UserSqlProvider {
    private UserSqlProvider() {
    }

    public static String selectActive() {
        return "select id, name, state from users where state = 'ACTIVE'";
    }
}
