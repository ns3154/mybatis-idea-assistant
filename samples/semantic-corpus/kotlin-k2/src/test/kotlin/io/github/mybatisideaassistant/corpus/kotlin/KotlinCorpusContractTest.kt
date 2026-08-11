package io.github.mybatisideaassistant.corpus.kotlin

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.session.Configuration
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinCorpusContractTest {
    @Test
    fun `K2 mapper and XML remain loadable`() {
        val configuration = Configuration()
        val path = "/mappers/KotlinUserMapper.xml"
        val input = checkNotNull(javaClass.getResourceAsStream(path)) { "缺少资源：$path" }
        input.use {
            XMLMapperBuilder(it, configuration, path, configuration.sqlFragments).parse()
        }

        val namespace = KotlinUserMapper::class.qualifiedName
        assertTrue(configuration.hasStatement("$namespace.findById"))
        assertTrue(configuration.hasStatement("$namespace.findByNames"))
    }
}
