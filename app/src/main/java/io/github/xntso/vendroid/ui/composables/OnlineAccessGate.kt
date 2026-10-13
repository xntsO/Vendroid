package io.github.xntso.vendroid.ui.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.xntso.vendroid.R
import io.github.xntso.vendroid.online.OnlineAccess
import io.github.xntso.vendroid.online.OnlineAccessSession
import io.github.xntso.vendroid.online.OnlineEligibility

private sealed interface OnlinePrompt {
    data object Closed : OnlinePrompt
    data class AgeCheck(val action: () -> Unit) : OnlinePrompt
    data object Restricted : OnlinePrompt
}

@Composable
fun rememberOnlineAccessGate(
    session: OnlineAccessSession = OnlineAccess.session,
): (() -> Unit) -> Unit {
    var prompt by remember { mutableStateOf<OnlinePrompt>(OnlinePrompt.Closed) }
    when (val current = prompt) {
        OnlinePrompt.Closed -> Unit
        is OnlinePrompt.AgeCheck -> BirthDateDialog(
            session = session,
            onDismiss = { prompt = OnlinePrompt.Closed },
            onResult = { result ->
                when (result) {
                    OnlineEligibility.ELIGIBLE -> {
                        prompt = OnlinePrompt.Closed
                        current.action()
                    }
                    OnlineEligibility.RESTRICTED -> prompt = OnlinePrompt.Restricted
                    OnlineEligibility.UNKNOWN -> prompt = OnlinePrompt.Closed
                }
            },
        )
        OnlinePrompt.Restricted -> AlertDialog(
            onDismissRequest = { prompt = OnlinePrompt.Closed },
            title = { Text(stringResource(R.string.online_features_unavailable)) },
            text = {
                Text(
                    stringResource(R.string.online_features_offline_explanation),
                    modifier = Modifier.appiumTag("onlineAccessRestricted"),
                )
            },
            confirmButton = {
                TextButton(onClick = { prompt = OnlinePrompt.Closed }) {
                    Text(stringResource(R.string.continue_offline))
                }
            },
        )
    }
    return { action ->
        when (session.eligibility) {
            OnlineEligibility.ELIGIBLE -> action()
            OnlineEligibility.UNKNOWN -> prompt = OnlinePrompt.AgeCheck(action)
            OnlineEligibility.RESTRICTED -> prompt = OnlinePrompt.Restricted
        }
    }
}

@Composable
private fun BirthDateDialog(
    session: OnlineAccessSession,
    onDismiss: () -> Unit,
    onResult: (OnlineEligibility) -> Unit,
) {
    // Never save DOB fields across recreation or prefill an eligible date.
    var day by remember { mutableStateOf("") }
    var month by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.online_age_check)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.online_connection_disclosure))
                Text(stringResource(R.string.online_birth_date_explanation))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BirthDateField(day, { day = it; invalid = false }, R.string.birth_day, "onlineBirthDay", 2, Modifier.weight(1f))
                    BirthDateField(month, { month = it; invalid = false }, R.string.birth_month, "onlineBirthMonth", 2, Modifier.weight(1f))
                    BirthDateField(year, { year = it; invalid = false }, R.string.birth_year, "onlineBirthYear", 4, Modifier.weight(1.3f))
                }
                if (invalid) {
                    Text(stringResource(R.string.invalid_birth_date), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.appiumTag("onlineAgeContinue"),
                enabled = day.isNotBlank() && month.isNotBlank() && year.isNotBlank(),
                onClick = {
                    val result = session.submitBirthDate(day, month, year)
                    if (result == null) {
                        invalid = true
                    } else {
                        day = ""
                        month = ""
                        year = ""
                        onResult(result)
                    }
                },
            ) { Text(stringResource(R.string.continue_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.continue_offline)) }
        },
    )
}

@Composable
private fun BirthDateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: Int,
    tag: String,
    maxDigits: Int,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter { it in '0'..'9' }.take(maxDigits)) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.appiumTag(tag),
    )
}

@Composable
fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val policy = remember(context) {
        context.assets.open("privacy-policy.md").bufferedReader().use { it.readText() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.privacy_policy)) },
        text = {
            SelectionContainer {
                // Plain selectable text keeps the complete policy and URLs accessible offline.
                Text(
                    text = policy,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()).appiumTag("localPrivacyPolicy"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
