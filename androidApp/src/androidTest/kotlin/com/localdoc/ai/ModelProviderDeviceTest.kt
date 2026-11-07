package com.localdoc.ai

import android.net.Uri
import android.provider.BaseColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.ai.model.ModelCatalog
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Реальный Android ContentProvider: список и чтение модели по id из строки списка. */
@RunWith(AndroidJUnit4::class)
class ModelProviderDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val spec = ModelCatalog.models.first()
    private val base = context.getExternalFilesDir(null) ?: context.filesDir
    private val modelFile = File(File(base, "models"), "${spec.id}.gguf")
    private var existed = false
    private lateinit var backup: ByteArray

    @Before
    fun setUp() {
        existed = modelFile.exists()
        if (existed) backup = modelFile.readBytes()
        modelFile.parentFile?.mkdirs()
        modelFile.writeBytes(GGUF)
    }

    @After
    fun tearDown() {
        if (existed) modelFile.writeBytes(backup) else modelFile.delete()
    }

    @Test
    fun listedModelCanBeOpenedByItsId() {
        val root = Uri.parse("content://${context.packageName}.models")
        val row = context.contentResolver.query(root, arrayOf(BaseColumns._ID, "name", "size"), null, null, null)
            ?.use { cursor ->
                generateSequence { if (cursor.moveToNext()) cursor else null }
                    .firstOrNull { it.getString(0) == spec.id }
                    ?.let { Triple(it.getString(0), it.getString(1), it.getLong(2)) }
            }
        assertEquals(Triple(spec.id, spec.name, GGUF.size.toLong()), row)

        context.contentResolver.openInputStream(Uri.withAppendedPath(root, spec.id))!!.use {
            assertTrue(it.readBytes().contentEquals(GGUF))
        }
    }

    private companion object {
        val GGUF = byteArrayOf(0x47, 0x47, 0x55, 0x46)
    }
}
