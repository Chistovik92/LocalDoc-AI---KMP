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
import com.localdoc.ai.platform.CreateDocumentContract
import com.localdoc.ai.ui.App

class MainActivity : ComponentActivity() {

    // Android 13+: разрешение на уведомление-плеер в шторке (без него озвучка работает, но без шторки)
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Голосовое управление чтением: микрофон запрашивается только при включении функции
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { AndroidIntents.onMicrophoneResult(it) }

    private val pdfPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            AndroidIntents.onPdfPicked(uri?.toString())
        }

    private val saveLauncher =
        registerForActivityResult(CreateDocumentContract()) { uri: Uri? -> AndroidIntents.onSaved(uri) }

    private val folderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> AndroidIntents.onFolderPicked(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 35 и так рисует под системными панелями — делаем это явно на всех версиях,
        // отступы обрабатывает Scaffold
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppContextHolder.init(applicationContext)
        AndroidIntents.registerPdfPicker(pdfPickerLauncher)
        AndroidIntents.registerSaver(saveLauncher)
        AndroidIntents.registerMicrophone(microphonePermission)
        AndroidIntents.registerFolderPicker(folderLauncher)

        handleIntent(intent)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

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
