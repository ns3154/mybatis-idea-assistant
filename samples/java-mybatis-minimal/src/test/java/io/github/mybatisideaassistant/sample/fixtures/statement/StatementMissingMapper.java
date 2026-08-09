package io.github.mybatisideaassistant.sample.fixtures.statement;

/**
 * 失败语料：namespace 正确，但 XML 中没有与该方法同名的 statement。
 */
public interface StatementMissingMapper {

    Long findById(long id);
}
