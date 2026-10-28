package com.wlzn.nacos.service

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.wlzn.nacos.model.ConfigListResponse
import com.wlzn.nacos.model.LoginResponse
import com.wlzn.nacos.model.HistoryListResponse
import com.wlzn.nacos.model.NacosHistoryItem
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class NacosApiClient {

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val gson = Gson()

    private data class ConfigEndpoint(val serverAddr: String, val baseUrl: String, val apiPath: String)

    // 每个面板只保留最后一次成功探测的路由，不缓存凭据；不同地址的并发请求不会复用彼此的路由。
    @Volatile
    private var configEndpoint: ConfigEndpoint? = null
    @Volatile
    private var historyEndpoint: ConfigEndpoint? = null

    fun login(serverAddr: String, username: String, password: String): LoginResponse {
        LOG.info("Login request to ${normalizeAddr(serverAddr)}")
        val serverKey = addressUri(serverAddr).toString().trimEnd('/')
        if (configEndpoint?.serverAddr == serverKey) configEndpoint = null
        if (historyEndpoint?.serverAddr == serverKey) historyEndpoint = null
        val bases = baseUrls(serverAddr)
        val urls = bases.flatMap { listOf("$it/v3/auth/user/login", "$it/v1/auth/login") }
        var response: HttpResponse<String>? = null
        for (url in urls) {
            response = send(url, "POST", mapOf("username" to username, "password" to password))
            logProbe(response)
            if (response.statusCode() != 404 && response.statusCode() != 410) break
        }
        val loginResponse = checkResponse(checkNotNull(response))
        // 登录接口在 Nacos 3 中仍直接返回 accessToken，不使用控制台的 data 包装。
        return gson.fromJson(loginResponse.body(), LoginResponse::class.java).also {
            check(it.accessToken.isNotBlank()) { "登录失败：服务端未返回 accessToken" }
        }
    }

    fun getConfigList(
        serverAddr: String,
        tenant: String,
        accessToken: String,
        dataId: String = "",
        group: String = "",
        pageNo: Int = 1,
        pageSize: Int = 999
    ): ConfigListResponse {
        LOG.info("Fetching config list from ${normalizeAddr(serverAddr)}, tenant=$tenant")
        val response = requestConfig(
            serverAddr, "GET", "/cs/config/list",
            mapOf(
                "search" to "accurate", "dataId" to dataId, "groupName" to group,
                "namespaceId" to tenant,
                "pageNo" to pageNo.toString(), "pageSize" to pageSize.toString(),
                "accessToken" to accessToken
            )
        )
        return gson.fromJson(responseData(response), ConfigListResponse::class.java)
    }

    fun getConfig(
        serverAddr: String,
        dataId: String,
        group: String,
        tenant: String,
        accessToken: String
    ): String {
        LOG.info("Fetching config: dataId=$dataId, group=$group")
        val response = requestConfig(
            serverAddr, "GET", "/cs/config",
            mapOf(
                "dataId" to dataId, "groupName" to group,
                "namespaceId" to tenant, "accessToken" to accessToken
            )
        )
        if (isLegacy(response)) return response.body()
        val content = responseData(response).asJsonObject.get("content")
        check(content != null && !content.isJsonNull) { "获取配置失败：服务端未返回配置内容" }
        return content.asString
    }

    fun publishConfig(
        serverAddr: String,
        dataId: String,
        group: String,
        tenant: String,
        content: String,
        type: String,
        accessToken: String
    ): Boolean {
        LOG.info("Publishing config: dataId=$dataId, group=$group")
        val response = requestConfig(
            serverAddr, "POST", "/cs/config",
            mapOf(
                "dataId" to dataId, "groupName" to group,
                "namespaceId" to tenant, "content" to content,
                "type" to type, "accessToken" to accessToken
            )
        )
        return if (isLegacy(response)) response.body().trim() == "true" else responseData(response).asBoolean
    }

    fun getHistoryList(serverAddr: String, dataId: String, group: String, tenant: String,
                       accessToken: String, pageNo: Int = 1, pageSize: Int = 50): HistoryListResponse {
        require(pageNo >= 1 && pageSize in 1..500)
        val serverKey = addressUri(serverAddr).toString().trimEnd('/')
        if (configEndpoint?.serverAddr != serverKey) getConfigList(serverAddr, tenant, accessToken, pageSize = 1)
        val config = checkNotNull(configEndpoint?.takeIf { it.serverAddr == serverKey })
        val cached = historyEndpoint?.takeIf { it.serverAddr == serverKey && it.baseUrl == config.baseUrl }
        val candidates = cached?.let { listOf(it) } ?: if (config.apiPath == "/v1") {
            listOf(config.copy(apiPath = "/v2"), config)
        } else listOf(config)
        val parameters = mapOf("dataId" to dataId, "groupName" to group, "namespaceId" to tenant,
            "accessToken" to accessToken, "pageNo" to pageNo.toString(), "pageSize" to pageSize.toString())
        var lastResponse: HttpResponse<String>? = null
        for (endpoint in candidates) {
            val response = sendHistory(endpoint, true, parameters)
            lastResponse = response
            if (cached == null && (response.statusCode() == 404 || response.statusCode() == 410)) continue
            val data = responseData(checkResponse(response))
            check(data.isJsonObject && data.asJsonObject.get("pageItems")?.isJsonArray == true) {
                "获取历史版本失败：服务端未返回有效的分页数据"
            }
            val result = gson.fromJson(data, HistoryListResponse::class.java)
            check(result.pageItems.orEmpty().all { record ->
                !record.id.isNullOrBlank() && record.dataId == dataId && record.group == group &&
                    record.namespace.orEmpty().ifBlank { "public" } == tenant.ifBlank { "public" }
            }) { "历史版本列表与请求的配置或命名空间不一致" }
            historyEndpoint = endpoint
            return result
        }
        checkResponse(checkNotNull(lastResponse))
        error("当前 Nacos 服务未提供历史版本查询接口")
    }

    fun getHistory(serverAddr: String, dataId: String, group: String, tenant: String,
                   accessToken: String, historyId: String): NacosHistoryItem {
        require(historyId.isNotBlank())
        val serverKey = addressUri(serverAddr).toString().trimEnd('/')
        if (historyEndpoint?.serverAddr != serverKey) getHistoryList(serverAddr, dataId, group, tenant, accessToken, pageSize = 1)
        val endpoint = checkNotNull(historyEndpoint?.takeIf { it.serverAddr == serverKey })
        val response = checkResponse(sendHistory(endpoint, false, mapOf(
            "dataId" to dataId, "groupName" to group, "namespaceId" to tenant,
            "accessToken" to accessToken, "nid" to historyId
        )))
        val data = responseData(response)
        check(data.isJsonObject) { "此历史版本不存在或已被清理" }
        val result = gson.fromJson(data, NacosHistoryItem::class.java)
        check(result.content != null) { "此历史版本没有可读取的配置正文，可能已被清理" }
        // 旧版本 public 历史可能省略 tenant；仅将缺失值归一为 public，不能匹配其他命名空间。
        check(result.id == historyId && result.dataId == dataId && result.group == group &&
            result.namespace.orEmpty().ifBlank { "public" } == tenant.ifBlank { "public" }) {
            "历史版本响应与请求的配置不一致"
        }
        return result
    }

    private fun sendHistory(endpoint: ConfigEndpoint, list: Boolean, parameters: Map<String, String>): HttpResponse<String> {
        val legacy = endpoint.apiPath == "/v1"
        val v2 = endpoint.apiPath == "/v2"
        val path = if (list && !legacy) "/cs/history/list" else "/cs/history"
        val actualParameters = parameters.map { (key, value) ->
            when {
                (legacy || v2) && key == "groupName" -> "group" to value
                legacy && key == "namespaceId" -> "tenant" to value
                !legacy && !v2 && key == "namespaceId" -> key to value.ifBlank { "public" }
                else -> key to value
            }
        }.toMap() + if (legacy && list) mapOf("search" to "accurate") else emptyMap()
        return send("${endpoint.baseUrl}${endpoint.apiPath}$path", "GET", actualParameters)
    }

    private fun requestConfig(
        serverAddr: String,
        method: String,
        path: String,
        parameters: Map<String, String>
    ): HttpResponse<String> {
        val serverKey = addressUri(serverAddr).toString().trimEnd('/')
        var endpoint = configEndpoint?.takeIf { it.serverAddr == serverKey }
        if (endpoint == null) {
            val bases = baseUrls(serverAddr)
            val candidates = bases.flatMap { base ->
                listOf(
                    ConfigEndpoint(serverKey, base, "/v3/console"),
                    ConfigEndpoint(serverKey, base, "/v3/admin"),
                    ConfigEndpoint(serverKey, base, "/v1")
                )
            }
            // 首次详情或发布先通过只读列表探测路由，发布正文只发送一次。
            val probeParameters = if (path == "/cs/config/list") parameters else mapOf(
                "search" to "accurate", "dataId" to "", "groupName" to "",
                "namespaceId" to parameters.getValue("namespaceId"),
                "pageNo" to "1", "pageSize" to "1", "accessToken" to parameters.getValue("accessToken")
            )
            var lastResponse: HttpResponse<String>? = null
            for (candidate in candidates) {
                val response = sendConfig(candidate, "GET", "/cs/config/list", probeParameters)
                lastResponse = response
                logProbe(response)
                // 只有列表端点明确不存在/停用才探测下一族，鉴权、业务失败和超时直接报错。
                if (response.statusCode() == 404 || response.statusCode() == 410) continue
                checkResponse(response)
                val data = responseData(response)
                check(data.isJsonObject && data.asJsonObject.get("pageItems")?.isJsonArray == true) {
                    "获取配置列表失败：服务端未返回有效的分页数据"
                }
                endpoint = candidate
                configEndpoint = candidate
                if (path == "/cs/config/list") return response
                break
            }
            if (endpoint == null) {
                checkResponse(checkNotNull(lastResponse))
                error("未找到可用的 Nacos 配置接口，请检查服务地址和上下文")
            }
        }
        // 已选路由的详情/发布失败不能触发跨族重试，404 也可能是配置不存在。
        return checkResponse(sendConfig(checkNotNull(endpoint), method, path, parameters))
    }

    private fun sendConfig(
        endpoint: ConfigEndpoint,
        method: String,
        path: String,
        parameters: Map<String, String>
    ): HttpResponse<String> {
        val legacy = endpoint.apiPath == "/v1"
        val actualPath = if (legacy) "/cs/configs" else path
        val actualParameters = parameters.map { (key, value) ->
            when {
                legacy && key == "groupName" -> "group" to value
                legacy && key == "namespaceId" -> "tenant" to value
                key == "namespaceId" -> key to value.ifBlank { "public" }
                else -> key to value
            }
        }.toMap()
        return send("${endpoint.baseUrl}${endpoint.apiPath}$actualPath", method, actualParameters)
    }

    private fun checkResponse(response: HttpResponse<String>): HttpResponse<String> {
        check(response.statusCode() == 200) {
            "Nacos 请求失败 (${response.statusCode()}, ${response.uri().path}): ${response.body()}"
        }
        return response
    }

    private fun logProbe(response: HttpResponse<String>) {
        // 不记录查询参数或请求体，避免 accessToken、用户名和密码进入日志。
        LOG.info("Nacos endpoint probe: ${response.uri().path} -> HTTP ${response.statusCode()}")
    }

    private fun send(url: String, method: String, parameters: Map<String, String>): HttpResponse<String> {
        val body = parameters.entries.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        val builder = HttpRequest.newBuilder().timeout(Duration.ofSeconds(30))
        if (method == "POST") {
            builder.uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        } else {
            builder.uri(URI.create("$url?$body")).GET()
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun isLegacy(response: HttpResponse<String>): Boolean = response.uri().path.endsWith("/v1/cs/configs") ||
        response.uri().path.endsWith("/v1/cs/history")

    private fun responseData(response: HttpResponse<String>): JsonElement {
        val json = JsonParser.parseString(response.body())
        if (isLegacy(response)) return json
        val result = json.asJsonObject
        check(result.get("code")?.asInt == 0) {
            "Nacos 请求失败 (${result.get("code")}): ${result.get("message")?.asString.orEmpty()}"
        }
        val data = result.get("data")
        check(data != null && !data.isJsonNull) { "Nacos 请求失败：服务端未返回 data" }
        return data
    }

    private fun normalizeAddr(serverAddr: String): String {
        val uri = addressUri(serverAddr)
        // 裸地址及普通尾斜杠均保留历史 /nacos 默认值，已指定上下文时不重复追加。
        return uri.toString().trimEnd('/') + if (uri.path.isNullOrEmpty() || uri.path == "/") "/nacos" else ""
    }

    private fun baseUrls(serverAddr: String): List<String> {
        val uri = addressUri(serverAddr)
        // 裸地址兼容同端口根上下文控制台；显式路径只在指定上下文内探测，不猜测或切换端口。
        return if (uri.path.isNullOrEmpty() || uri.path == "/") {
            listOf(normalizeAddr(serverAddr), uri.toString().trimEnd('/'))
        } else listOf(normalizeAddr(serverAddr))
    }

    private fun addressUri(serverAddr: String): URI {
        val address = serverAddr.trim()
        return URI.create(if (address.startsWith("http://") || address.startsWith("https://")) address else "http://$address")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        private val LOG = Logger.getInstance(NacosApiClient::class.java)
    }
}
