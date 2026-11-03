package no.grokbot.stedslogger

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast

/**
 * Copies one list entry to the clipboard (1.47, long-press). Never logged: the text only goes to the
 * clipboard. [sensitive] sets ClipDescription.EXTRA_IS_SENSITIVE (message text), so Android 13+ hides it in
 * the clipboard preview. Android 13+ shows its own "copied" confirmation, so the toast is only shown below 13.
 * [haptic] is a view to vibrate on when the copy did not come from a long-press (which vibrates by itself).
 */
object Clip {
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun copy(ctx: Context, label: String, text: String, sensitive: Boolean = false, haptic: View? = null) {
        try {
            val clip = ClipData.newPlainText(label, text)
            if (sensitive && Build.VERSION.SDK_INT >= 24) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else EXTRA_IS_SENSITIVE, true)
                }
            }
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            haptic?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Kopiert til utklippstavlen", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Kunne ikke kopiere: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }
}
