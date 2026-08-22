package com.arabaskor360.common

import io.javalin.http.Context

/** Response language for user-facing text (notes, error messages). Defaults to Turkish — this
 *  app's primary market — for a missing or unrecognized `?lang` value; only `en` switches it. */
enum class Lang {
    TR,
    EN,
}

fun Context.resolveLang(): Lang =
    if (this.queryParam("lang")?.trim()?.lowercase() == "en") Lang.EN else Lang.TR

/** Single point where every bilingual user-facing string in this app is chosen — picks [tr] or
 *  [en] based on [lang]. Kept as a plain function (not a resource bundle) since every string
 *  here is either static or interpolates a handful of already-localized values (numbers, TSB's
 *  own Turkish trim names, etc.) — a full i18n framework would be overkill for this surface. */
fun t(lang: Lang, tr: String, en: String): String = if (lang == Lang.EN) en else tr
