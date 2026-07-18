package io.github.xntso.vendroid.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import io.github.xntso.vendroid.AppSettings
import io.github.xntso.vendroid.BuildConfig
import io.github.xntso.vendroid.R
import io.github.xntso.vendroid.plugins.reviews.WriteReviewHelper
import io.github.xntso.vendroid.ui.composables.MainView
import io.github.xntso.vendroid.ui.composables.ScreenSizeLayoutSelector
import io.github.xntso.vendroid.utils.ktexts.activity

class AboutActivity : ActivityBase() {
    private val mViewModel: ThemeViewModel by viewModels()
    private lateinit var mSettings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mSettings = AppSettings(this).apply {
            addListener(mViewModel)
            mViewModel.refreshSettings(this)
        }

        setContent {
            MainView(viewModel = mViewModel) {
                AboutView()
            }
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AboutViewLayout(
    modifier: Modifier = Modifier,
    appTitle: @Composable () -> Unit,
    versionInfo: @Composable () -> Unit,
    logo: @Composable () -> Unit,
    contributorsInfo: @Composable () -> Unit,
    actionButtons: @Composable () -> Unit,
    content: @Composable () -> Unit = {},
) {
    ScreenSizeLayoutSelector(
        modifier = modifier,
        normal = {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(48.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    appTitle()
                    versionInfo()
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    logo()
                }

                contributorsInfo()

                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        8.dp,
                        Alignment.CenterHorizontally
                    )
                ) {
                    actionButtons()
                }

            }
        },
        compact = {
            Box(
                Modifier
                    .sizeIn(minWidth = 550.dp)
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .horizontalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(
                        48.dp,
                        Alignment.CenterHorizontally
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.padding(32.dp)) {
                        logo()
                    }
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        appTitle()
                        versionInfo()
                        contributorsInfo()

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(
                                8.dp,
                                Alignment.CenterHorizontally
                            )
                        ) {
                            actionButtons()
                        }

                    }
                }
            }
        }
    )

    content()
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AboutView() {
    AboutViewLayout(
        modifier = Modifier.fillMaxSize(),
        appTitle = {
            Text(
                text = "${stringResource(R.string.app_name)} v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 28.sp),
                textAlign = TextAlign.Center,
            )
        },
        versionInfo = {
            SelectionContainer {
                Text(
                    text = "${BuildConfig.APPLICATION_ID}\n v${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}, ${BuildConfig.FLAVOR}+${BuildConfig.BUILD_TYPE}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 16.sp,
                        fontFamily = FontFamily(Typeface.MONOSPACE)
                    ),
                    textAlign = TextAlign.Center,
                )
            }
        },
        logo = {
            VendroidLogo(
                modifier = Modifier.size(128.dp),
                contentDescription = stringResource(R.string.app_name),
            )
        },
        contributorsInfo = {
            val developerName = stringResource(R.string.developer_name)
            val github = "GitHub"
            val ventoy = "Ventoy"
            val etchDroid = "EtchDroid"
            val developerLine = stringResource(R.string.developer_credit, developerName)
            val sourceLine = stringResource(R.string.project_source, github)
            val upstreamLine = stringResource(R.string.upstream_projects, ventoy, etchDroid)
            val linkStyle = SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
            )
            val annotatedText = buildAnnotatedString {
                append(stringResource(R.string.about_project_summary))
                append("\n\n")
                appendLinkedText(developerLine, developerName, "https://github.com/xntso", linkStyle)
                append("\n")
                appendLinkedText(
                    sourceLine,
                    github,
                    "https://github.com/xntsO/Vendroid",
                    linkStyle,
                )
                append("\n")
                appendLinkedText(upstreamLine, ventoy, "https://www.ventoy.net", linkStyle)
                val etchDroidStart = upstreamLine.indexOf(etchDroid)
                if (etchDroidStart >= 0) {
                    val absoluteStart = length - upstreamLine.length + etchDroidStart
                    addStyle(linkStyle, absoluteStart, absoluteStart + etchDroid.length)
                    addLink(
                        LinkAnnotation.Url("https://github.com/EtchDroid/EtchDroid"),
                        absoluteStart,
                        absoluteStart + etchDroid.length,
                    )
                }
                append("\n")
                append(stringResource(R.string.gpl_license_notice))

            }

            Text(
                modifier = Modifier
                    .fillMaxWidth(),
                text = annotatedText,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )
            )

        },
        actionButtons = {
            val activity = LocalContext.current.activity
            OutlinedButton(
                onClick = {
                    activity?.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            "https://github.com/xntsO/Vendroid".toUri()
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.project_on_github))
            }
            OutlinedButton(
                onClick = {
                    activity?.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            "https://www.ventoy.net".toUri()
                        )
                    )
                }
            ) {
                Text("Ventoy")
            }
            val reviewHelper = remember { activity?.let { WriteReviewHelper(it) } }
            if (reviewHelper != null) {
                OutlinedButton(onClick = { reviewHelper.launchReviewFlow() }) {
                    Text(
                        text = if (reviewHelper.isGPlayFlavor) stringResource(
                            R.string.write_a_review
                        )
                        else stringResource(R.string.star_on_github)
                    )
                }
            }
        },
    )
}


@PreviewScreenSizes
@Composable
fun AboutViewPreview() {
    val viewModel = remember { ThemeViewModel() }
    MainView(viewModel) {
        AboutView()
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendLinkedText(
    text: String,
    linkedPart: String,
    url: String,
    style: SpanStyle,
) {
    val lineStart = length
    append(text)
    val relativeStart = text.indexOf(linkedPart)
    if (relativeStart < 0) return

    val start = lineStart + relativeStart
    val end = start + linkedPart.length
    addStyle(style, start, end)
    addLink(LinkAnnotation.Url(url), start, end)
}
