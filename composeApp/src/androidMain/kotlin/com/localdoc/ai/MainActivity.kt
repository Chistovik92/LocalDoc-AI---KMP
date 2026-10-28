package com.localdoc.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.localdoc.ai.platform.AndroidIntents
import com.localdoc.ai.platform.AppContextHolder
import com.localdoc.ai.platform.CreateDocumentContract
import com.localdoc.ai.platform.CurrentActivity
import com.localdoc.ai.platform.VolumeKeys
import com.localdoc.ai.ui.App

class MainActivity : ComponentActivity() {

    // Язык интерфейса, выбранный в настройках, не зависит от системного
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(com.localdoc.ai.platform.AppLocale.wrap(newBase))

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
        CurrentActivity.set(this)
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

    override fun onDestroy() {
        CurrentActivity.clear(this)
        super.onDestroy()
    }

    // Читалка может отдать кнопки громкости на листание
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val forward = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if ((forward || keyCode == KeyEvent.KEYCODE_VOLUME_UP) && VolumeKeys.onKey(forward, event.repeatCount > 0)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) && VolumeKeys.handler != null) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        AndroidIntents.handleIntent(intent)
    }
}
