package com.yugahashimoto.andcode.ui.lock

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** The device's own unlock: a fingerprint or face, falling back to its PIN, pattern or password. */
object DeviceUnlock {
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Whether the device has any lock to ask for. Without one, the setting cannot be turned on. */
    fun isAvailable(context: Context): Boolean =
        isSupported() && BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Android 9 and later. Before it, androidx.biometric shows its own fingerprint dialog, built
     * with AppCompat, which crashes under this app's theme; a lock that crashes on opening would
     * shut the user out of the app.
     */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** Shows the system prompt; [onResult] gets whether the user unlocked. */
    fun ask(
        activity: FragmentActivity,
        title: String,
        subtitle: String?,
        onResult: (Boolean) -> Unit,
    ) {
        val prompt =
            BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)

                    // A single failed fingerprint keeps the prompt open; only an error ends it.
                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) = onResult(false)
                },
            )
        val info =
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply { subtitle?.let(::setSubtitle) }
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
        prompt.authenticate(info)
    }
}
