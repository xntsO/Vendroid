package io.github.xntso.vendroid.online

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import java.io.IOException
import java.util.Calendar
import java.util.GregorianCalendar

enum class OnlineEligibility {
    UNKNOWN, RESTRICTED, ELIGIBLE,
}

class OnlineAccessRequiredException : IOException("Optional online features require a local eligibility check.")

fun requireOnlineEligibility(eligibility: OnlineEligibility) {
    when (eligibility) {
        OnlineEligibility.ELIGIBLE -> Unit
        OnlineEligibility.UNKNOWN, OnlineEligibility.RESTRICTED -> throw OnlineAccessRequiredException()
    }
}

/** Only the service eligibility band lives in memory; birth dates are never retained or backed up. */
class OnlineAccessSession {
    @Volatile
    var eligibility: OnlineEligibility = OnlineEligibility.UNKNOWN
        private set

    fun submitBirthDate(
        day: String,
        month: String,
        year: String,
        today: Calendar = Calendar.getInstance(),
    ): OnlineEligibility? {
        val result = eligibilityForBirthDate(day, month, year, today)
        eligibility = result ?: OnlineEligibility.UNKNOWN
        return result
    }
}

object OnlineAccess {
    val session = OnlineAccessSession()
}

internal fun eligibilityForBirthDate(
    day: String,
    month: String,
    year: String,
    today: Calendar,
): OnlineEligibility? {
    val birthDay = day.toIntOrNull() ?: return null
    val birthMonth = month.toIntOrNull() ?: return null
    val birthYear = year.toIntOrNull() ?: return null
    val currentYear = today.get(Calendar.YEAR)
    val currentMonth = today.get(Calendar.MONTH) + 1
    val currentDay = today.get(Calendar.DAY_OF_MONTH)
    if (birthYear !in 1..currentYear || birthMonth !in 1..12 || birthDay !in 1..31) return null
    val birthDate = GregorianCalendar(today.timeZone).apply {
        clear()
        isLenient = false
        set(birthYear, birthMonth - 1, birthDay)
    }
    try {
        birthDate.timeInMillis
    } catch (_: IllegalArgumentException) {
        return null
    }
    val birthday = birthMonth * 100 + birthDay
    val currentDate = currentMonth * 100 + currentDay
    if (birthYear == currentYear && birthday > currentDate) return null

    // GitHub's minimum service age is 13; this does not declare adulthood in every locale.
    val eligibleYear = currentYear - 13
    return if (birthYear < eligibleYear || (birthYear == eligibleYear && birthday <= currentDate)) {
        OnlineEligibility.ELIGIBLE
    } else {
        OnlineEligibility.RESTRICTED
    }
}

class ExternalProjectLinks(
    private val eligibility: () -> OnlineEligibility = { OnlineAccess.session.eligibility },
) {
    fun open(context: Context, url: String) {
        requireOnlineEligibility(eligibility())
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }
}
