package io.github.jameskbb.hearth.system

import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/** LocaleList arrived in API 24. Preserve the configured locale on older TVs too. */
internal fun Configuration.primaryLocale(): Locale =
    if (Build.VERSION.SDK_INT >= 24) {
        locales[0] ?: Locale.getDefault()
    } else {
        @Suppress("DEPRECATION")
        locale ?: Locale.getDefault()
    }
