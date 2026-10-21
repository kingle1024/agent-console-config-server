package com.kingle.configserver.customer

import com.kingle.configserver.menuadmin.MenuAdminRepository
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.lang.reflect.Proxy
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

// 열람 잠금 ON/OFF — 저장소는 Proxy 로 필요한 메서드만 흉내낸다(JpaRepository 는 메서드가 많아 직접 구현하지 않는다).
class CustomerLockToggleTest {
    private val settingRows = HashMap<String, CustomerSetting>()
    private val logs = ArrayList<CustomerAccessLog>()
    private val adminIds = HashSet<String>()

    private inline fun <reified T> fake(crossinline handler: (String, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, args ->
            handler(m.name, args ?: emptyArray())
        } as T

    private fun controller(): CustomerController {
        val settings = fake<CustomerSettingRepository> { name, args ->
            when (name) {
                "findById" -> Optional.ofNullable(settingRows[args[0] as String])
                "save" -> (args[0] as CustomerSetting).also { settingRows[it.settingKey] = it }
                else -> throw UnsupportedOperationException(name)
            }
        }
        val accessLogs = fake<CustomerAccessLogRepository> { name, args ->
            if (name == "save") (args[0] as CustomerAccessLog).also { logs.add(it) } else throw UnsupportedOperationException(name)
        }
        val menuAdmins = fake<MenuAdminRepository> { name, args ->
            if (name == "existsByMenuKeyAndLoginId") args[1] in adminIds else throw UnsupportedOperationException(name)
        }
        val repo = fake<CustomerInfoRepository> { name, _ -> throw UnsupportedOperationException(name) }
        return CustomerController(
            repo,
            accessLogs,
            AmaranthLoginVerifier("https://example.invalid", "douzone"),
            CustomerUnlockSessions(),
            CustomerCipher("test-key"),
            menuAdmins,
            settings,
        )
    }

    @Test
    fun `설정이 없으면 잠금 ON — 비밀번호 없이는 열람 불가`() {
        val c = controller()
        adminIds.add("ejy10241")
        assertEquals(true, c.lockSetting()["enabled"])
        val e = assertFailsWith<ResponseStatusException> { c.unlock(CustomerUnlockReq("ejy10241", "")) }
        assertEquals(400, e.statusCode.value())
    }

    @Test
    fun `잠금 OFF 면 비밀번호 없이 토큰을 주고 기록을 남긴다`() {
        val c = controller()
        adminIds.add("*")
        c.saveLockSetting(CustomerLockReq(enabled = false, updatedBy = "EJY10241"))
        assertEquals(false, c.lockSetting()["enabled"])
        assertEquals("ejy10241", c.lockSetting()["updatedBy"])
        val r = c.unlock(CustomerUnlockReq("Someone", null))
        assertNotNull(r["token"])
        assertEquals("someone", r["userId"])
        assertEquals(listOf("lock_off", "unlock_nopw"), logs.map { it.action })
    }

    @Test
    fun `잠금 OFF 여도 권한 목록에 없으면 거부`() {
        val c = controller()
        c.saveLockSetting(CustomerLockReq(enabled = false))
        val e = assertFailsWith<ResponseStatusException> { c.unlock(CustomerUnlockReq("nobody", null)) }
        assertEquals(403, e.statusCode.value())
    }

    @Test
    fun `다시 켜면 비밀번호 확인으로 돌아간다`() {
        val c = controller()
        adminIds.add("*")
        c.saveLockSetting(CustomerLockReq(enabled = false))
        val token = c.unlock(CustomerUnlockReq("ejy10241", null))["token"] as String
        c.saveLockSetting(CustomerLockReq(enabled = true))
        assertEquals(true, c.lockSetting()["enabled"])
        // 비밀번호 없이 열린 세션은 다시 켜는 순간 닫힌다
        val e = assertFailsWith<ResponseStatusException> { c.logs(token, null) }
        assertEquals(401, e.statusCode.value())
        assertFailsWith<ResponseStatusException> { c.unlock(CustomerUnlockReq("ejy10241", "")) }
    }
}
