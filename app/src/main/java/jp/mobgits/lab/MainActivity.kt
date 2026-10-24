package jp.mobgits.lab

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.*
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.biometric.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.os.Build
import jp.mobgits.lab.feature.auth.*
import jp.mobgits.lab.feature.collaboration.*
import jp.mobgits.lab.feature.inbox.DeepLinks
import jp.mobgits.lab.feature.settings.*

class MainActivity : FragmentActivity() {
    private val vm by viewModels<AppViewModel>()
    private var unlocked by mutableStateOf(false)
    private var lockError by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(); handle(intent)
        setContent {
            val state = vm.state
            val dark = when (state.settings.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            val colors = if (state.settings.dynamic && Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                if (state.settings.biometric && state.directory.activeAccount != null && !unlocked) {
                    LockScreen(lockError, ::unlock)
                } else MobGitsShell(vm, ::browse) { enabled ->
                    val available = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
                    if (!enabled || available) { unlocked = true; vm.flag("biometric", enabled) }
                    else vm.showError("端末の生体認証・画面ロックを設定してください")
                }
            }
        }
    }
    override fun onStop() { super.onStop(); unlocked = false }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }
    private fun browse(value: String) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))) }
    private fun handle(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "mobgits" && data.host == "oauth" && data.path == "/callback") vm.callback(data.toString())
        else DeepLinks.parse(data.toString())?.let { vm.deepLink(it.first, it.second) }
    }
    private fun unlock() {
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlocked = true; lockError = null }
            override fun onAuthenticationError(code: Int, message: CharSequence) { lockError = message.toString() }
        }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("MobGitsをロック解除")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())
    }
}
