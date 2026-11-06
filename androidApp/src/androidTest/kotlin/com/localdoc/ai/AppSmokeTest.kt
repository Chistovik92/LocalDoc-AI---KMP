package com.localdoc.ai

import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Запуск реального приложения на Android-устройстве, а не только JVM-тест бизнес-логики. */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @Test
    fun mainActivityShowsComposeContent() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("localdoc", 0)
            .edit().putBoolean("first_launch_permissions_asked", true).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                assertNotNull(root)
                assertTrue(root.childCount > 0)
                assertTrue(root.isAttachedToWindow)
            }
        }
    }
}
