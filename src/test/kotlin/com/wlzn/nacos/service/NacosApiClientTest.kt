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
    private val emptyList = """{"code":0,"data":{"totalCount":0,"pageItems":[]}}"""

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
        assertEquals("/nacos/v1/cs/configs", requests.last().path)

        routes["GET /nacos/v3/console/cs/config/list"] = 200 to emptyList
        val failureClient = NacosApiClient()
        failureClient.getConfigList(address, "test", "token")

        for (status in listOf(401, 403, 500)) {
            requests.clear()
            routes["POST /nacos/v3/console/cs/config"] = status to "denied"
            assertThrows(IllegalStateException::class.java) {
                failureClient.publishConfig(address, "a", "g", "test", "a: 1", "yaml", "token")
            }
            assertEquals(1, requests.size)
        }
    }

    @Test
    fun `business failures and missing data never become success or retry`() {
        routes["GET /nacos/v3/console/cs/config/list"] = 200 to emptyList
        client.getConfigList(address, "", "token")
        requests.clear()
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
        routes["GET /nacos/v3/console/cs/config/list"] = 200 to emptyList
        routes["GET /custom/v3/console/cs/config/list"] = 200 to emptyList
        routes["GET /nacos/v3/console/cs/config"] = 200 to """{"code":0,"data":{"content":"default"}}"""
        routes["GET /custom/v3/console/cs/config"] = 200 to """{"code":0,"data":{"content":"custom"}}"""
        assertEquals("default", client.getConfig("$address/", "a", "g", "", "token"))
        assertEquals("default", client.getConfig("$address/nacos/", "a", "g", "", "token"))
        assertEquals("custom", client.getConfig("$address/custom/", "a", "g", "", "token"))
    }

    @Test
    fun `legacy fallback preserves original namespace including literal public`() {
        routes["GET /nacos/v1/cs/configs"] = 200 to """{"totalCount":0,"pageItems":[]}"""
        routes["POST /nacos/v1/cs/configs"] = 200 to "true"
        for (tenant in listOf("", "public", "custom-id")) {
            assertTrue(client.publishConfig("$address/", "a", "g", tenant, "a: 1", "yaml", "token"))
            assertEquals(tenant, requests.last().params["tenant"])
        }
        assertEquals("public", requests.first().params["namespaceId"])
        assertEquals(3, requests.count { it.method == "POST" })
    }

    @Test
    fun `server port uses admin API when console is absent and v1 is gone`() {
        routes["POST /nacos/v3/auth/user/login"] = 200 to """{"accessToken":"admin-token"}"""
        routes["GET /nacos/v1/cs/configs"] = 410 to "Current API will be deprecated"
        routes["POST /nacos/v1/cs/configs"] = 410 to "Current API will be deprecated"
        routes["GET /nacos/v3/admin/cs/config/list"] = 200 to """
            {"code":0,"data":{"totalCount":1,"pageItems":[
              {"id":"123","dataId":"应用.yml","groupName":"GROUP A","namespaceId":"test-id","type":"yaml"}
            ]}}
        """.trimIndent()
        routes["GET /nacos/v3/admin/cs/config"] = 200 to """{"code":0,"data":{"content":"name: 测试\n"}}"""
        routes["POST /nacos/v3/admin/cs/config"] = 200 to """{"code":0,"data":true}"""

        val token = client.login(address, "user", "pass").accessToken
        val item = client.getConfigList(address, "test-id", token).pageItems.single()
        assertEquals("name: 测试\n", client.getConfig(address, item.dataId, item.group, item.tenant, token))
        assertTrue(client.publishConfig(address, item.dataId, item.group, item.tenant, "name: 修改&=\n", "yaml", token))
        assertEquals(listOf(
            "/nacos/v3/auth/user/login", "/nacos/v3/console/cs/config/list",
            "/nacos/v3/admin/cs/config/list", "/nacos/v3/admin/cs/config", "/nacos/v3/admin/cs/config"
        ), requests.map { it.path })
        assertEquals("GROUP A", requests.last().params["groupName"])
        assertEquals("test-id", requests.last().params["namespaceId"])
        assertEquals("name: 修改&=\n", requests.last().params["content"])
        assertEquals("admin-token", requests.last().params["accessToken"])
    }

    @Test
    fun `bare address supports root console but explicit contexts do not reuse its route`() {
        routes["POST /v3/auth/user/login"] = 200 to """{"accessToken":"root-token"}"""
        routes["GET /v3/console/cs/config/list"] = 200 to emptyList
        routes["GET /v3/console/cs/config"] = 200 to """{"code":0,"data":{"content":"root"}}"""
        routes["POST /v3/console/cs/config"] = 200 to """{"code":0,"data":true}"""
        val token = client.login("$address/", "user", "pass").accessToken
        assertEquals("root-token", token)
        client.getConfigList(address, "", token)
        assertEquals("root", client.getConfig(address, "a", "g", "", token))
        assertTrue(client.publishConfig(address, "a", "g", "", "root", "text", token))
        assertEquals("/v3/console/cs/config", requests.last().path)

        for (context in listOf("nacos", "custom")) {
            requests.clear()
            assertThrows(IllegalStateException::class.java) {
                client.getConfigList("$address/$context/", "", token)
            }
            assertEquals(listOf(
                "/$context/v3/console/cs/config/list", "/$context/v3/admin/cs/config/list", "/$context/v1/cs/configs"
            ), requests.map { it.path })
        }
    }

    @Test
    fun `selected admin route never falls back on config errors including not found`() {
        routes["GET /nacos/v3/admin/cs/config/list"] = 200 to emptyList
        routes["GET /nacos/v1/cs/configs"] = 200 to """{"totalCount":0,"pageItems":[]}"""
        routes["POST /nacos/v1/cs/configs"] = 200 to "true"
        client.getConfigList(address, "", "token")
        for (status in listOf(404, 410, 401, 403, 500)) {
            requests.clear()
            routes["GET /nacos/v3/admin/cs/config"] = status to "failed"
            routes["POST /nacos/v3/admin/cs/config"] = status to "failed"
            assertThrows(IllegalStateException::class.java) { client.getConfig(address, "a", "g", "", "token") }
            assertThrows(IllegalStateException::class.java) {
                client.publishConfig(address, "a", "g", "", "content", "text", "token")
            }
            assertEquals(listOf("/nacos/v3/admin/cs/config", "/nacos/v3/admin/cs/config"), requests.map { it.path })
        }
    }

    @Test
    fun `first publish probes with reads and sends content only once`() {
        routes["GET /nacos/v3/admin/cs/config/list"] = 200 to emptyList
        routes["POST /nacos/v3/admin/cs/config"] = 200 to """{"code":0,"data":true}"""
        assertTrue(client.publishConfig(address, "a", "g", "", "private content", "text", "token"))
        assertEquals(listOf("GET", "GET", "POST"), requests.map { it.method })
        assertTrue(requests.dropLast(1).none { it.params.containsKey("content") })
        assertEquals("public", requests.last().params["namespaceId"])
    }

    @Test
    fun `discovery stops on authorization server business and malformed list responses`() {
        routes["GET /nacos/v3/admin/cs/config/list"] = 200 to emptyList
        for ((status, body) in listOf(
            401 to "unauthorized", 403 to "forbidden", 500 to "failed",
            200 to """{"code":403,"message":"forbidden","data":{"pageItems":[]}}""",
            200 to """{"code":0,"data":{}}""", 200 to "<html>Proxy login page</html>"
        )) {
            requests.clear()
            routes["GET /nacos/v3/console/cs/config/list"] = status to body
            assertThrows(Exception::class.java) {
                NacosApiClient().publishConfig(address, "a", "g", "", "private content", "text", "token")
            }
            assertEquals(1, requests.size)
            assertEquals("GET", requests.single().method)
        }
    }

    @Test
    fun `custom context containing v1 does not change v3 response contract`() {
        routes["GET /proxy/v1/mounted/v3/admin/cs/config/list"] = 200 to emptyList
        routes["GET /proxy/v1/mounted/v3/admin/cs/config"] = 200 to """{"code":0,"data":{"content":"actual content"}}"""
        assertEquals("actual content", client.getConfig("$address/proxy/v1/mounted/", "a", "g", "", "token"))
        assertTrue(requests.all { it.path.startsWith("/proxy/v1/mounted/") })
    }

    @Test
    fun `login reselects route after server changes without sharing another address cache`() {
        routes["POST /nacos/v3/auth/user/login"] = 200 to """{"accessToken":"token"}"""
        routes["GET /nacos/v3/admin/cs/config/list"] = 200 to emptyList
        routes["GET /custom/v3/console/cs/config/list"] = 200 to emptyList
        client.getConfigList(address, "", "token")
        client.getConfigList("$address/custom", "", "token")
        requests.clear()
        client.getConfigList(address, "", "token")
        assertEquals("/nacos/v3/admin/cs/config/list", requests.last().path)

        routes["GET /nacos/v3/console/cs/config/list"] = 200 to emptyList
        client.login(address, "user", "pass")
        requests.clear()
        client.getConfigList(address, "", "token")
        assertEquals("/nacos/v3/console/cs/config/list", requests.single().path)
    }

    @Test
    fun `legacy default context works when proxy denies root context`() {
        routes["POST /nacos/v1/auth/login"] = 200 to """{"accessToken":"legacy-token"}"""
        routes["GET /nacos/v1/cs/configs"] = 200 to """{"totalCount":0,"pageItems":[]}"""
        routes["POST /v3/auth/user/login"] = 403 to "root denied"
        routes["GET /v3/console/cs/config/list"] = 403 to "root denied"
        assertEquals("legacy-token", client.login(address, "user", "pass").accessToken)
        assertEquals(0, client.getConfigList(address, "", "legacy-token").totalCount)
        assertTrue(requests.all { it.path.startsWith("/nacos/") })
    }
}
