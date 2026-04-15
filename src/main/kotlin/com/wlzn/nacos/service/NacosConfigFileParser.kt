package com.wlzn.nacos.service

import com.wlzn.nacos.model.NacosEnvironment
import org.yaml.snakeyaml.Yaml

class NacosConfigFileParser {

    fun parse(content: String): List<NacosEnvironment> {
        val yaml = Yaml()
        val documents = yaml.loadAll(content).filterNotNull().toList()
        if (documents.isEmpty()) return emptyList()

        val baseDoc = asMap(documents[0]) ?: return emptyList()

        val baseServerAddr = getNestedString(baseDoc, "nacos", "server-addr") ?: ""
        val baseNamespace = getNestedString(baseDoc, "nacos", "namespace") ?: ""
        val baseUsername = getNestedString(baseDoc, "nacos", "username") ?: "nacos"
        val basePassword = getNestedString(baseDoc, "nacos", "password") ?: ""
        val activeProfile = getNestedString(baseDoc, "spring", "profiles", "active") ?: "default"

        val environments = mutableListOf<NacosEnvironment>()

        environments.add(
            NacosEnvironment(
                profileName = activeProfile,
                serverAddr = baseServerAddr,
                namespace = baseNamespace,
                username = baseUsername,
                password = basePassword
            )
        )

        for (i in 1 until documents.size) {
            val doc = asMap(documents[i]) ?: continue
            val profileName = getNestedString(doc, "spring", "config", "activate", "on-profile") ?: continue

            val serverAddr = getNestedString(doc, "nacos", "server-addr") ?: baseServerAddr
            val namespace = getNestedString(doc, "nacos", "namespace") ?: baseNamespace
            val username = getNestedString(doc, "nacos", "username") ?: baseUsername
            val password = getNestedString(doc, "nacos", "password") ?: basePassword

            if (profileName == activeProfile) {
                environments[0] = NacosEnvironment(
                    profileName = profileName,
                    serverAddr = serverAddr,
                    namespace = namespace,
                    username = username,
                    password = password
                )
            } else {
                environments.add(
                    NacosEnvironment(
                        profileName = profileName,
                        serverAddr = serverAddr,
                        namespace = namespace,
                        username = username,
                        password = password
                    )
                )
            }
        }

        return environments
    }

    @Suppress("UNCHECKED_CAST")
    private fun asMap(obj: Any?): Map<String, Any>? = obj as? Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    private fun getNestedString(map: Map<String, Any>, vararg keys: String): String? {
        var current: Any? = map
        for (key in keys) {
            current = (current as? Map<String, Any>)?.get(key) ?: return null
        }
        return current?.toString()
    }
}
