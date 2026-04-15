package com.wlzn.nacos.service

import com.google.gson.Gson
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

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val gson = Gson()

    fun login(serverAddr: String, username: String, password: String): LoginResponse {
        val url = "${normalizeAddr(serverAddr)}/nacos/v1/auth/login"
        val body = "username=${encode(username)}&password=${encode(password)}"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        LOG.info("Login request to ${normalizeAddr(serverAddr)}")
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw RuntimeException("登录失败 (${response.statusCode()}): ${response.body()}")
        }
        return gson.fromJson(response.body(), LoginResponse::class.java)
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
        val url = buildString {
            append("${normalizeAddr(serverAddr)}/nacos/v1/cs/configs")
            append("?search=accurate")
            append("&dataId=${encode(dataId)}")
            append("&group=${encode(group)}")
            append("&tenant=${encode(tenant)}")
            append("&pageNo=$pageNo")
            append("&pageSize=$pageSize")
            if (accessToken.isNotEmpty()) append("&accessToken=${encode(accessToken)}")
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()

        LOG.info("Fetching config list from ${normalizeAddr(serverAddr)}, tenant=$tenant")
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw RuntimeException("获取配置列表失败 (${response.statusCode()}): ${response.body()}")
        }
        return gson.fromJson(response.body(), ConfigListResponse::class.java)
    }

    fun getConfig(
        serverAddr: String,
        dataId: String,
        group: String,
        tenant: String,
        accessToken: String
    ): String {
        val url = buildString {
            append("${normalizeAddr(serverAddr)}/nacos/v1/cs/configs")
            append("?dataId=${encode(dataId)}")
            append("&group=${encode(group)}")
            append("&tenant=${encode(tenant)}")
            if (accessToken.isNotEmpty()) append("&accessToken=${encode(accessToken)}")
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()

        LOG.info("Fetching config: dataId=$dataId, group=$group")
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw RuntimeException("获取配置失败 (${response.statusCode()}): ${response.body()}")
        }
        return response.body()
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
        val body = buildString {
            append("dataId=${encode(dataId)}")
            append("&group=${encode(group)}")
            append("&tenant=${encode(tenant)}")
            append("&content=${encode(content)}")
            append("&type=${encode(type)}")
            if (accessToken.isNotEmpty()) append("&accessToken=${encode(accessToken)}")
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("${normalizeAddr(serverAddr)}/nacos/v1/cs/configs"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        LOG.info("Publishing config: dataId=$dataId, group=$group")
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() == 200 && response.body().trim() == "true"
    }

    private fun normalizeAddr(serverAddr: String): String {
        return if (serverAddr.startsWith("http://") || serverAddr.startsWith("https://")) {
            serverAddr.trimEnd('/')
        } else {
            "http://${serverAddr.trimEnd('/')}"
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        private val LOG = Logger.getInstance(NacosApiClient::class.java)
    }
}
