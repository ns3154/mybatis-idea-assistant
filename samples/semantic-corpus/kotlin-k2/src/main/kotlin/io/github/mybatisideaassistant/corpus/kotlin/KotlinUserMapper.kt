package io.github.mybatisideaassistant.corpus.kotlin

import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select

interface KotlinUserMapper {
    fun findById(id: Long): KotlinUser?

    fun findByNames(@Param("names") names: List<String>): List<KotlinUser>

    fun findByFilter(@Param("filter") filter: KotlinFilter): List<KotlinUser>

    fun findByState(@Param("state") state: String? = null): List<KotlinUser>

    @Select("select id, name from users where name = #{name}")
    fun findAnnotated(name: String): KotlinUser?
}
