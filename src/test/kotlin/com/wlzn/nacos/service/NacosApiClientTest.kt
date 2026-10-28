package com.wlzn.nacos.service

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class NacosApiClientTest {
    private lateinit var server: HttpServer
    private lateinit var address: String
    private val routes = ConcurrentHashMap<String, Pair<Int, String>>()
    private val requests = Collections.synchronizedList(mutableListOf<Request>())
    private val client = NacosApiClient()

    private data class Request(val method: String, val path: String, val params: Map<String, String>)

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.use { String(it.readBytes(), Charsets.UTF_8) }
            val query = if (exchange.requestMethod == "GET") exchange.requestURI.rawQuery.orEmpty() else body
            val params = query.split('&').filter { it.isNotEmpty() }.associate {
                URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
                    URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
            }
            requests.add(Request(exchange.requestMethod, exchange.requestURI.path, params))
            val (status, response) = routes["${exchange.requestMethod} ${exchange.requestURI.path}"] ?: (404 to "Not Found")
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        address = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `nacos 3 login list detail and publish use new contracts`() {
        routes["POST /nacos/v3/auth/user/login"] = 200 to """{"accessToken":"test-token","tokenTtl":18000}"""
        routes["GET /nacos/v3/console/cs/config/list"] = 200 to """
            {"code":0,"data":{"totalCount":1,"pageNumber":1,"pagesAvailable":1,"pageItems":[
              {"id":"123","dataId":"应用.yml","groupName":"GROUP A","namespaceId":"public","type":"yaml"}
            ]}}
        """.trimIndent()
        routes["GET /nacos/v3/console/cs/config"] = 200 to """{"code":0,"data":{"content":"name: 测试\n"}}"""
        routes["POST /nacos/v3/console/cs/config"] = 200 to """{"code":0,"data":true}"""

        val login = client.login(address, "user", "p&= 密码")
        assertEquals("test-token", login.accessToken)
        val result = client.getConfigList(address, "", login.accessToken)
        assertEquals(1, result.totalCount)
        val item = result.pageItems.single()
        assertEquals("GROUP A", item.group)
        assertEquals("public", item.tenant)
        assertEquals("", item.content)
        assertEquals("name: 测试\n", client.getConfig(address, item.dataId, item.group, "", login.accessToken))
        assertTrue(client.publishConfig(address, item.dataId, item.group, "", "name: 测试&=\n", "yaml", login.accessToken))

        assertEquals(4, requests.size)
        assertEquals("p&= 密码", requests[0].params["password"])
        assertEquals("public", requests[1].params["namespaceId"])
        assertFalse(requests[1].params.containsKey("tenant"))
        assertEquals("GROUP A", requests[2].params["groupName"])
        assertEquals("name: 测试&=\n", requests[3].params["content"])
    }

    @Test
    fun `nacos 1 and 2 endpoints and raw bodies remain supported`() {
        routes["POST /nacos/v1/auth/login"] = 200 to """{"accessToken":"legacy-token"}"""
        routes["GET /nacos/v1/cs/configs"] = 200 to """
            {"totalCount":1,"pageItems":[{"dataId":"a.yml","group":"OLD_GROUP","tenant":"","content":"a: 1"}]}
        """.trimIndent()
        routes["POST /nacos/v1/cs/configs"] = 200 to "true"

        assertEquals("legacy-token", client.login(address, "user", "pass").accessToken)
        val item = client.getConfigList(address, "", "legacy-token").pageItems.single()
        assertEquals("OLD_GROUP", item.group)
        assertEquals("a: 1", item.content)
        routes["GET /nacos/v1/cs/configs"] = 200 to "{\"raw\":true}"
        assertEquals("{\"raw\":true}", client.getConfig(address, item.dataId, item.group, "", "legacy-token"))
        assertTrue(client.publishConfig(address, item.dataId, item.group, "", "a: 2", "yaml", "legacy-token"))

        val legacyRequest = requests.last()
        assertEquals("OLD_GROUP", legacyRequest.params["group"])
        assertEquals("", legacyRequest.params["tenant"])
        assertFalse(legacyRequest.params.containsKey("namespaceId"))
    }

    @Test
    fun `gone endpoint falls back but authorization and server failures do not`() {
        routes["GET /nacos/v3/console/cs/config/list"] = 410 to "Gone"
        routes["GET /nacos/v1/cs/configs"] = 200 to """{"totalCount":0,"pageItems":[]}"""
        assertEquals(0, client.getConfigList(address, "test", "token").totalCount)
        assertEquals(2, requests.size)

        for (status in listOf(401, 403, 500)) {
            requests.clear()
            routes["POST /nacos/v3/console/cs/config"] = status to "denied"
            assertThrows(IllegalStateException::class.java) {
                client.publishConfig(address, "a", "g", "test", "a: 1", "yaml", "token")
            }
            assertEquals(1, requests.size)
        }
    }

    @Test
    fun `business failures and missing data never become success or retry`() {
        routes["POST /nacos/v3/console/cs/config"] = 200 to """{"code":403,"message":"forbidden","data":true}"""
        val error = assertThrows(IllegalStateException::class.java) {
            client.publishConfig(address, "a", "g", "", "a: 1", "yaml", "token")
        }
        assertTrue(error.message.orEmpty().contains("forbidden"))
        assertEquals(1, requests.size)
        routes["POST /nacos/v3/console/cs/config"] = 200 to """{"code":0,"data":false}"""
        assertFalse(client.publishConfig(address, "a", "g", "", "a: 1", "yaml", "token"))
        routes["GET /nacos/v3/console/cs/config"] = 200 to """{"code":0,"data":{}}"""
        assertThrows(IllegalStateException::class.java) { client.getConfig(address, "a", "g", "", "token") }
        assertEquals(3, requests.size)
    }

    @Test
    fun `trailing slash keeps default context and explicit context is not duplicated`() {
        routes["GET /nacos/v3/console/cs/config"] = 200 to """{"code":0,"data":{"content":"default"}}"""
        routes["GET /custom/v3/console/cs/config"] = 200 to """{"code":0,"data":{"content":"custom"}}"""
        assertEquals("default", client.getConfig("$address/", "a", "g", "", "token"))
        assertEquals("default", client.getConfig("$address/nacos/", "a", "g", "", "token"))
        assertEquals("custom", client.getConfig("$address/custom/", "a", "g", "", "token"))
    }

    @Test
    fun `legacy fallback preserves original namespace including literal public`() {
        routes["POST /nacos/v1/cs/configs"] = 200 to "true"
        for (tenant in listOf("", "public", "custom-id")) {
            assertTrue(client.publishConfig("$address/", "a", "g", tenant, "a: 1", "yaml", "token"))
            assertEquals(tenant, requests.last().params["tenant"])
            assertEquals(tenant.ifBlank { "public" }, requests[requests.size - 2].params["namespaceId"])
        }
    }
}
