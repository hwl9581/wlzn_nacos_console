package com.wlzn.nacos.service

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class NacosApiClientLiveTest {
    @Test
    fun `configured live environments support login list and detail reads`() {
        val configPath = System.getenv("WLZN_NACOS_SMOKE_CONFIG").orEmpty()
        assumeTrue("Live checks require an explicit local config path", configPath.isNotBlank())
        val environments = NacosConfigFileParser().parse(Files.readString(Path.of(configPath)))
        assertTrue("No Nacos environments found", environments.isNotEmpty())
        val profiles = System.getenv("WLZN_NACOS_SMOKE_PROFILES")?.split(',')?.map { it.trim() }
            ?: listOf(environments.first().profileName)
        for (profile in profiles) {
            val environment = environments.single { it.profileName == profile }
            val client = NacosApiClient()
            val token = client.login(environment.serverAddr, environment.username, environment.password).accessToken
            val configs = client.getConfigList(environment.serverAddr, environment.namespace, token)
            val item = configs.pageItems.firstOrNull()
            if (item != null) {
                client.getConfig(environment.serverAddr, item.dataId, item.group, environment.namespace, token)
            }
            // 只报告数量，不输出凭据、DataId 或配置正文；本测试不调用发布接口。
            println("Nacos live read check passed: profile=$profile, total=${configs.totalCount}, detailRead=${item != null}")
        }
    }
}
