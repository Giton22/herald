package dev.hermeskotlin.ui.settings

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun rememberAppIcons(): AppIcons {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    return remember(context, version) { AndroidAppIcons(context.applicationContext, context.findActivity()) { version++ } }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Each icon is a launcher alias of the app's activity in its AndroidManifest.xml, exactly one enabled: Blue's is
 * MainActivity (the activity's old name, so home-screen icons from before stay), the others AppIcon<name>.
 */
private class AndroidAppIcons(
    private val context: Context,
    private val activity: Activity?,
    private val onChange: () -> Unit,
) : AppIcons {
    private val packages = context.packageManager

    // The launcher entry Android opens Herald from is the enabled alias: one lookup instead of one per icon.
    override val current: AppIcon = packages.getLaunchIntentForPackage(context.packageName)?.component?.className
        .let { entry -> AppIcon.entries.firstOrNull { it.aliasClass == entry } } ?: AppIcon.Blue

    override suspend fun picture(icon: AppIcon, sizePx: Int): ImageBitmap? = withContext(Dispatchers.Default) {
        runCatching {
            // A disabled alias is still found with this flag; its icon is drawn masked like the launcher's.
            packages.getActivityInfo(component(icon), PackageManager.MATCH_DISABLED_COMPONENTS)
                .loadIcon(packages)
                .toBitmap(sizePx, sizePx)
                .asImageBitmap()
        }.getOrNull()
    }

    override fun apply(icon: AppIcon) {
        if (icon == current) return
        // The new one is turned on before the others go off, so the launcher always has an entry for Herald.
        val changes = listOf(icon to PackageManager.COMPONENT_ENABLED_STATE_ENABLED) +
            AppIcon.entries.filter { it != icon }.map { it to PackageManager.COMPONENT_ENABLED_STATE_DISABLED }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.setComponentEnabledSettings(
                changes.map { (it, state) -> PackageManager.ComponentEnabledSetting(component(it), state, PackageManager.DONT_KILL_APP) },
            )
        } else {
            changes.forEach { (it, state) -> packages.setComponentEnabledSetting(component(it), state, PackageManager.DONT_KILL_APP) }
        }
        // Android closes the task the old icon opened once that alias is off, and Herald's activity is singleTask,
        // so it would reopen in that same task. Close it first, then open Herald in a new task from the new icon,
        // which Android leaves alone: a restart, not the app disappearing.
        AppIconRestart.pending = true
        activity?.finishAndRemoveTask()
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(component(icon))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        }
        onChange()
    }

    private fun component(icon: AppIcon) = ComponentName(context.packageName, icon.aliasClass)
}

/**
 * Set while Herald restarts itself from a new app icon, so the new activity doesn't count as a fresh open (App
 * lock asks again on those). Kept in the process, not on the intent: another app could put an extra there.
 */
object AppIconRestart {
    @Volatile
    var pending = false

    /** Whether this open is that restart; reading it clears it. */
    fun take(): Boolean = pending.also { pending = false }
}

/**
 * The icon's launcher alias in androidApp's AndroidManifest.xml (AppIconsManifestTest checks they match). Named in
 * the app module's namespace, not its application id, which Herald Dev's suffix changes.
 */
internal val AppIcon.aliasClass: String
    get() = "dev.hermeskotlin.android." + if (this == AppIcon.Blue) "MainActivity" else "AppIcon$name"
