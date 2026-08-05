package com.mywallet.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mywallet.R

/** The authenticators the app will take: a fingerprint, or the phone's PIN. */
private const val ALLOWED = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

/**
 * Whether this phone can be asked at all.
 *
 * False on a phone with no lock set, which is why turning the setting on has to
 * check first: an app that locked itself behind a credential that does not exist
 * would be an app that cannot be opened again.
 */
fun canLock(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS

/**
 * Asks the phone to prove who this is.
 *
 * The same prompt in both places it is needed, which is deliberate: the switch
 * that turns the lock *on* asks first, and only saves the setting once the
 * phone has said yes. Somebody who cannot get past the prompt must not be able
 * to put it in front of their own money — the app has no passcode of its own to
 * fall back on and no way to let them back in.
 *
 * [subtitleRes] is the one thing the two callers differ on, and they have to.
 * "Unlock to see your money" over the setting's own confirmation asks a
 * question the user did not ask — they can already see it — where what is
 * actually being checked is whether this phone's lock can be got past at all.
 */
fun askToUnlock(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onDone: () -> Unit = {},
    subtitleRes: Int = R.string.lock_subtitle,
) {
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onSuccess()
            onDone()
        }

        override fun onAuthenticationError(code: Int, message: CharSequence) {
            // Dismissed, or too many tries. Nothing happens — the app stays
            // shut, or the switch stays off.
            onDone()
        }
    }
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(activity.getString(R.string.lock_title))
        .setSubtitle(activity.getString(subtitleRes))
        .setAllowedAuthenticators(ALLOWED)
        .build()
    // Asking while the activity is not resumed throws rather than queueing —
    // the prompt is a fragment, and its transaction cannot run against a saved
    // state. Left uncaught it took the caller's "a prompt is up" flag down with
    // it, and the flag never came back: the Unlock button did nothing for the
    // rest of the process's life, and the only way in was to kill the app.
    runCatching { prompt.authenticate(info) }.onFailure { onDone() }
}

/** The activity behind a Compose context, for the two callers that need one. */
@Composable
fun lockHost(): FragmentActivity? = LocalContext.current.findActivity()

/**
 * What stands in front of the app while it is locked.
 *
 * The prompt is asked for immediately — nobody who set a lock wants to tap
 * "unlock" first — and this page is what is behind it: a closed padlock, the
 * app's name, and a way to ask again if the prompt was dismissed. Deliberately
 * says nothing else. It is drawn over everything, including in the recents
 * preview, so it must not show a single figure.
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    // Remembered, not a local: a recomposition while the system dialog is up
    // would otherwise reset the flag and stack a second prompt on the first.
    val asking = remember { booleanArrayOf(false) }

    fun prompt() {
        val host = activity ?: return
        if (asking[0]) return
        asking[0] = true
        askToUnlock(host, onSuccess = onUnlocked, onDone = { asking[0] = false })
    }

    // Asked on resume, not on appearing.
    //
    // This screen appears the moment the app *stops* — that is what locks it —
    // so a prompt fired as it composes is fired at an activity on its way to
    // the background, where it cannot be shown. Coming back put no prompt up
    // and left the page sitting there with an Unlock button. Waiting for
    // ON_RESUME asks at the only moment the phone can answer, and re-asks every
    // time the user comes back.
    //
    // The flag is deliberately not cleared here. Answering with the phone's PIN
    // takes the app through ON_STOP and ON_RESUME while the prompt is still
    // waiting, and clearing on the way out would put a second one on top of it.
    // Every way a prompt can end calls onDone, so the flag cannot stick.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) prompt()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.lock_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(28.dp))
            // A fingerprint rather than the word "Unlock": what the tap does is
            // put the phone's own prompt back up, and the sensor's own mark says
            // that in one glyph in every language the app is read in. Drawn big
            // enough to be the thing on the page a thumb goes for — a text button
            // in the middle of an otherwise empty screen read as a caption. The
            // word survives as what a screen reader says.
            FilledIconButton(
                onClick = { prompt() },
                modifier = Modifier.size(72.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Fingerprint,
                    contentDescription = stringResource(R.string.lock_unlock),
                    modifier = Modifier.size(40.dp),
                )
            }
        }
    }
}

/**
 * The activity behind a Compose context.
 *
 * `BiometricPrompt` needs the real one — it puts up a fragment — and the context
 * a composable sees may be a wrapper around it.
 */
private fun Context.findActivity(): FragmentActivity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is FragmentActivity) return context
        context = context.baseContext
    }
    return null
}
