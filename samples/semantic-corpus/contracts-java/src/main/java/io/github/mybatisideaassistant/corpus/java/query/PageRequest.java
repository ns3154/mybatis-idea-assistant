package io.github.mybatisideaassistant.corpus.java.query;

public record PageRequest<T>(T criteria, int offset, int limit) {
}
