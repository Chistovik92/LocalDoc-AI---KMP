package com.localdoc.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.localdoc.ai.platform.AndroidIntents
import com.localdoc.ai.platform.AppContextHolder
import com.localdoc.ai.ui.App

class MainActivity : ComponentActivity() {

    private val pdfPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            AndroidIntents.onPdfPicked(uri?.toString())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 35 и так рисует под системными панелями — делаем это явно на всех версиях,
        // отступы обрабатывает Scaffold
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppContextHolder.init(applicationContext)
        AndroidIntents.registerPdfPicker(pdfPickerLauncher)

        handleIntent(intent)

        setContent {
            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        AndroidIntents.handleIntent(intent)
    }
}
