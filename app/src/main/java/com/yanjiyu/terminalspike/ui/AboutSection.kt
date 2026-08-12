package com.yanjiyu.terminalspike.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.BuildConfig
import com.yanjiyu.terminalspike.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_NOTICE_CHARACTERS = 256 * 1_024

private sealed interface ThirdPartyNoticesState {
    data object Loading : ThirdPartyNoticesState
    data class Loaded(val text: String) : ThirdPartyNoticesState
    data object Failed : ThirdPartyNoticesState
}

@Composable
internal fun AboutSection() {
    val context = LocalContext.current
    val noticesState by produceState<ThirdPartyNoticesState>(
        initialValue = ThirdPartyNoticesState.Loading,
        key1 = context,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("THIRD_PARTY_NOTICES.md")
                    .bufferedReader(Charsets.UTF_8)
                    .use { reader -> reader.readText() }
                    .also { text -> require(text.length <= MAX_NOTICE_CHARACTERS) }
            }.fold(
                onSuccess = ThirdPartyNoticesState::Loaded,
                onFailure = { ThirdPartyNoticesState.Failed },
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.about_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        item {
            Text(
                text = stringResource(
                    R.string.about_version,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Text(
                text = stringResource(R.string.about_local_first_privacy),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item { HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp)) }
        item {
            Text(
                text = stringResource(R.string.about_third_party_notices),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        item {
            when (val state = noticesState) {
                ThirdPartyNoticesState.Loading -> Text(stringResource(R.string.about_notices_loading))
                ThirdPartyNoticesState.Failed -> Text(
                    text = stringResource(R.string.about_notices_failed),
                    color = MaterialTheme.colorScheme.error,
                )
                is ThirdPartyNoticesState.Loaded -> SelectionContainer {
                    Text(
                        text = state.text,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("third_party_notices_body"),
                    )
                }
            }
        }
    }
}
