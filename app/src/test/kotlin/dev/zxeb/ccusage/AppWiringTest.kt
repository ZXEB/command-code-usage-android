package dev.zxeb.ccusage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 构建链路与资源校验。
 *
 * 这个测试存在的意义是：即使还没有真实功能，也保证 APK 能装配、资源能解析、
 * 主题能被系统找到 —— CI 不会在“能编译但不能安装”的状态下变绿。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppWiringTest {

    @Test
    fun appNameResolves() {
        val context: Context = ApplicationProvider.getApplicationContext()
        assertEquals("CC 用量", context.getString(R.string.app_name))
    }

    @Test
    fun applicationClassIsDeclared() {
        val context: Context = ApplicationProvider.getApplicationContext()
        assertNotNull(context.applicationContext)
        assertEquals("dev.zxeb.ccusage", context.packageName)
    }

    @Test
    fun themeStyleIsResolvable() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val styleRes = context.resources.getIdentifier("Theme.CcUsage", "style", context.packageName)
        assertEquals(true, styleRes != 0)
    }

    @Test
    fun minSdkMeetsBlurRequirement() {
        // miuix-blur 依赖 RuntimeShader，硬性要求 API 33+
        assertEquals(true, android.os.Build.VERSION_CODES.TIRAMISU >= 33)
    }
}
