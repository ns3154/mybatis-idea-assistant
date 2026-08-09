package io.github.mybatisideaassistant.sample.fixtures.namespace;

/**
 * 失败语料：XML 使用了另一个 namespace，因此该方法不应产生关联目标。
 */
public interface NamespaceMismatchMapper {

    Long findById(long id);
}
