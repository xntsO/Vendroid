package io.github.xntso.vendroid.ui.composables

import android.content.res.AssetManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.xntso.vendroid.R

data class LicenseDocument(val title: String, val assetPath: String)

/** The notices and license texts that the app carries as assets. */
object OpenSourceNotices {
    const val NOTICE = "licenses/NOTICE.txt"
    const val VENTOY_LICENSES = "ventoy_licenses/License"

    private val fixedDocuments = listOf(
        LicenseDocument("Vendroid notice and credits", NOTICE),
        LicenseDocument("GNU General Public License, version 3", "licenses/GPL-3.0.txt"),
        LicenseDocument("GNU Lesser General Public License, version 2.1", "licenses/LGPL-2.1.txt"),
        LicenseDocument("Apache License, version 2.0", "licenses/Apache-2.0.txt"),
        LicenseDocument("Ventoy notice and source", "ventoy_licenses/NOTICE.txt"),
    )

    /** The fixed documents first, then every file of Ventoy's License folder. */
    fun documents(assets: AssetManager): List<LicenseDocument> = fixedDocuments +
        assets.list(VENTOY_LICENSES).orEmpty().sorted().map { LicenseDocument("Ventoy: $it", "$VENTOY_LICENSES/$it") }

    fun read(assets: AssetManager, path: String): String =
        assets.open(path).bufferedReader().use { it.readText() }
}

@Composable
fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    val assets = LocalContext.current.assets
    val documents = remember(assets) { OpenSourceNotices.documents(assets) }
    var openPath by rememberSaveable { mutableStateOf<String?>(null) }
    val open = documents.firstOrNull { it.assetPath == openPath }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(open?.title ?: stringResource(R.string.open_source_licenses)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.licenses_language_note))
                if (open == null) {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        documents.forEachIndexed { index, document ->
                            TextButton(
                                modifier = Modifier.appiumTag("licenseDocument$index"),
                                onClick = { openPath = document.assetPath },
                            ) { Text(document.title) }
                        }
                    }
                } else {
                    val text = remember(open.assetPath) { OpenSourceNotices.read(assets, open.assetPath) }
                    SelectionContainer {
                        // Plain selectable text keeps every notice and URL readable offline.
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()).appiumTag("licenseText"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.appiumTag("closeLicenses"), onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
        dismissButton = if (open != null) {
            {
                TextButton(modifier = Modifier.appiumTag("licensesBack"), onClick = { openPath = null }) {
                    Text(stringResource(R.string.licenses_back))
                }
            }
        } else null,
    )
}
