package io.github.mybatisideaassistant.corpus.java.query;

import io.github.mybatisideaassistant.corpus.java.domain.UserState;

public record UserFilter(String keyword, UserState state) {
}
