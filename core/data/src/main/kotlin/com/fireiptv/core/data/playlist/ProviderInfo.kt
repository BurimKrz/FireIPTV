package com.fireiptv.core.data.playlist

/**
 * Providers add an "Account Information" category and channel: a video of the account's details.
 * It isn't TV, so imports leave it out; Settings → Account shows the same details. Matches the
 * misspelling seen in the wild too ("Accout Information").
 */
internal fun isProviderAccountInfo(name: String?): Boolean = name != null && ACCOUNT_INFO.containsMatchIn(name)

private val ACCOUNT_INFO = Regex("""^\W*acc?oun?t\s+info""", RegexOption.IGNORE_CASE)
