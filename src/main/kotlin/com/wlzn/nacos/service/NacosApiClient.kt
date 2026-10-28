package com.wlzn.nacos.service

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.wlzn.nacos.model.ConfigListResponse
import com.wlzn.nacos.model.LoginResponse
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

    fun login(serverAddr: String, username: String, password: String): LoginResponse {
        LOG.info("Login request to ${normalizeAddr(serverAddr)}")
        val response = request(
            serverAddr, "POST", "/v3/auth/user/login", "/v1/auth/login",
            mapOf("username" to username, "password" to password)
        )
        // 登录接口在 Nacos 3 中仍直接返回 accessToken，不使用控制台的 data 包装。
        return gson.fromJson(response.body(), LoginResponse::class.java).also {
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
        val response = request(
            serverAddr, "GET", "/v3/console/cs/config/list", "/v1/cs/configs",
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
        val response = request(
            serverAddr, "GET", "/v3/console/cs/config", "/v1/cs/configs",
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
        val response = request(
            serverAddr, "POST", "/v3/console/cs/config", "/v1/cs/configs",
            mapOf(
                "dataId" to dataId, "groupName" to group,
                "namespaceId" to tenant, "content" to content,
                "type" to type, "accessToken" to accessToken
            )
        )
        return if (isLegacy(response)) response.body().trim() == "true" else responseData(response).asBoolean
    }

    private fun request(
        serverAddr: String,
        method: String,
        path: String,
        legacyPath: String,
        parameters: Map<String, String>
    ): HttpResponse<String> {
        val baseUrl = normalizeAddr(serverAddr)
        val currentParameters = parameters.mapValues { (key, value) ->
            if (key == "namespaceId") value.ifBlank { "public" } else value
        }
        var response = send("$baseUrl$path", method, currentParameters)
        // 仅在明确不支持新端点时回退；鉴权失败、业务失败、超时均不重试，避免重复发布。
        if (response.statusCode() == 404 || response.statusCode() == 410) {
            val legacyParameters = parameters.map { (key, value) ->
                when (key) {
                    "groupName" -> "group" to value
                    "namespaceId" -> "tenant" to value
                    else -> key to value
                }
            }.toMap()
            response = send("$baseUrl$legacyPath", method, legacyParameters)
        }
        check(response.statusCode() == 200) { "Nacos 请求失败 (${response.statusCode()}): ${response.body()}" }
        return response
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

    private fun isLegacy(response: HttpResponse<String>): Boolean = response.uri().path.contains("/v1/")

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
        val address = serverAddr.trim()
        val uri = URI.create(if (address.startsWith("http://") || address.startsWith("https://")) address else "http://$address")
        // 裸地址及普通尾斜杠均保留历史 /nacos 默认值，已指定上下文时不重复追加。
        return uri.toString().trimEnd('/') + if (uri.path.isNullOrEmpty() || uri.path == "/") "/nacos" else ""
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        private val LOG = Logger.getInstance(NacosApiClient::class.java)
    }
}
