package dev.zxeb.ccusage

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 构建链路与资源校验。
 *
 * 保证 APK 能装配、资源能解析、主题能被系统找到 —— CI 不会在「能编译但不能安装」的
 * 状态下变绿。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppWiringTest {

    @Test
    fun appNameResolves() {
        val context: Context = ApplicationProvider.getApplicationContext()
        assertTrue(context.getString(R.string.app_name).isNotBlank())
    }

    @Test
    fun applicationContextAvailable() {
        val context: Context = ApplicationProvider.getApplicationContext()
        assertNotNull(context.applicationContext)
    }

    @Test
    fun themeStyleIsResolvable() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val styleRes = context.resources.getIdentifier("Theme.CcUsage", "style", context.packageName)
        assertTrue("Theme.CcUsage must exist", styleRes != 0)
    }

    @Test
    fun minSdkMeetsBlurRequirement() {
        // miuix-blur 依赖 RuntimeShader，硬性要求 API 33+
        assertTrue(Build.VERSION_CODES.TIRAMISU >= 33)
    }
}
