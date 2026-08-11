package io.github.mybatisideaassistant.corpus.java.mapper;

public interface ParentMapper<T, ID> {
    T findInheritedById(ID id);
}
