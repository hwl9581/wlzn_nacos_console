package com.wlzn.nacos.service

import org.junit.Assert.*
import org.junit.Test

class NacosConfigFileParserTest {
    private val parser = NacosConfigFileParser()

    @Test
    fun `camel case address is inherited by test and overridden by prod`() {
        val environments = parser.parse("""
            spring:
              profiles:
                active: dev
            nacos:
              serverAddr: dev.example.test:8848
              namespace: dev-space
              username: demo-user
              password: demo-password
            ---
            spring:
              config:
                activate:
                  on-profile: test
            nacos:
              namespace: test-space
            ---
            spring:
              config:
                activate:
                  on-profile: prod
            nacos:
              serverAddr: prod.example.test:8848
              namespace: prod-space
              username: prod-user
              password: prod-password
        """.trimIndent())
        assertEquals(listOf("dev", "test", "prod"), environments.map { it.profileName })
        assertEquals(listOf("dev.example.test:8848", "dev.example.test:8848", "prod.example.test:8848"), environments.map { it.serverAddr })
        assertEquals(listOf("dev-space", "test-space", "prod-space"), environments.map { it.namespace })
        assertEquals("demo-user", environments[1].username)
        assertEquals("prod-password", environments[2].password)
    }

    @Test
    fun `hyphen address keeps current precedence when both spellings are present`() {
        val environment = parser.parse("""
            nacos:
              server-addr: first.example.test:8848
              serverAddr: second.example.test:8848
        """.trimIndent()).single()
        assertEquals("first.example.test:8848", environment.serverAddr)
    }

    @Test
    fun `profile override may use different spelling than base document`() {
        for ((base, override) in listOf("server-addr" to "serverAddr", "serverAddr" to "server-addr")) {
            val environments = parser.parse("""
                spring:
                  profiles:
                    active: prod
                nacos:
                  $base: dev.example.test:8848
                ---
                spring:
                  config:
                    activate:
                      on-profile: prod
                nacos:
                  $override: prod.example.test:8848
            """.trimIndent())
            assertEquals(1, environments.size)
            assertEquals("prod.example.test:8848", environments.single().serverAddr)
        }
    }
}
