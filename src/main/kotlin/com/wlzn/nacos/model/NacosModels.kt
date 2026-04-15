package com.wlzn.nacos.model

class NacosEnvironment(
    val profileName: String,
    val serverAddr: String,
    val namespace: String,
    val username: String,
    val password: String
) {
    override fun toString(): String = "$profileName ($serverAddr)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NacosEnvironment) return false
        return profileName == other.profileName && namespace == other.namespace
    }

    override fun hashCode(): Int = 31 * profileName.hashCode() + namespace.hashCode()
}

data class NacosConfigItem(
    val id: Long = 0,
    val dataId: String = "",
    val group: String = "",
    val content: String = "",
    val type: String = "",
    val tenant: String = "",
    val md5: String = "",
    val appName: String = ""
)

data class LoginResponse(
    val accessToken: String = "",
    val tokenTtl: Long = 0,
    val globalAdmin: Boolean = false
) {
    override fun toString(): String = "LoginResponse(tokenTtl=$tokenTtl, globalAdmin=$globalAdmin)"
}

data class ConfigListResponse(
    val totalCount: Int = 0,
    val pageNumber: Int = 0,
    val pagesAvailable: Int = 0,
    val pageItems: List<NacosConfigItem> = emptyList()
)
