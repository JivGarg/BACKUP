package app.backupcircle.member.ui

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Applies the in-app language. AppCompat recreates the screen at once and remembers the
 * choice (Android 13+ stores it per app; older versions via AppLocalesMetadataHolderService).
 */
fun applyLanguage(code: String, onlyIfDifferent: Boolean = false) {
    if (onlyIfDifferent && AppCompatDelegate.getApplicationLocales().toLanguageTags() == code) return
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
}

fun clearLanguage() {
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
}

/**
 * A context in the chosen language, for text shown outside activities (the protection
 * notification). Older Android versions only localise activities automatically.
 */
fun localized(context: Context, code: String?): Context {
    if (code == null) return context
    val config = Configuration(context.resources.configuration)
    config.setLocales(LocaleList.forLanguageTags(code))
    return context.createConfigurationContext(config)
}
