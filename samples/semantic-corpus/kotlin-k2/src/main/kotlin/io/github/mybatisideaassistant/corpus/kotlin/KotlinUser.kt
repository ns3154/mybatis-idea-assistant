package io.github.mybatisideaassistant.corpus.kotlin

data class KotlinUser(
    val id: Long,
    val name: String,
)

data class Profile(
    val city: String?,
)

data class KotlinFilter(
    val tags: List<String> = emptyList(),
    val profile: Profile? = null,
)
