package com.yanjiyu.terminalspike.ui.guide

import android.animation.ValueAnimator
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.yanjiyu.terminalspike.R
import kotlinx.coroutines.launch

internal val GuideInk = Color(0xFF0C1115)
internal val GuideMint = Color(0xFF82D5BE)
internal val GuideMuted = Color(0xFFADBABF)

/** One index and one continuous visual guide; first install uses its essential subset. */
@Composable
internal fun UserGuideIndex() {
    var openPage by rememberSaveable { mutableStateOf<Int?>(null) }
    var reference by rememberSaveable { mutableStateOf(false) }
    if (reference) {
        GuideReferenceScreen(onBack = { reference = false })
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("user-guide-index"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
        item {
            Text(stringResource(R.string.guide_title), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 8.dp).semantics { heading() })
            Button(onClick = { openPage = 0 }, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                .heightIn(min = 52.dp).testTag("guide-start")) {
                Text(stringResource(R.string.guide_start))
            }
        }
        itemsIndexed(GuideLesson.entries, key = { _, lesson -> lesson.name }) { index, lesson ->
            GuideThumbnailRow(lesson.visual, stringResource(lesson.title),
                onClick = { openPage = index }, tag = "guide-lesson-${lesson.name}")
        }
        item {
            TextButton(onClick = { reference = true }, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.guide_more_help))
            }
        }
    }
    openPage?.let { QuickStartGuide(initialPage = it, onDismiss = { openPage = null }) }
}

@Composable
private fun GuideThumbnailRow(visual: GuideVisual, title: String, onClick: () -> Unit, tag: String) {
    Column {
        Row(Modifier.fillMaxWidth().testTag(tag).clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(ImageBitmap.imageResource(visual.poster), contentDescription = null,
                modifier = Modifier.size(width = 108.dp, height = 88.dp).clip(RoundedCornerShape(10.dp))
                    .background(GuideInk), contentScale = ContentScale.Fit)
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
    }
}

/** All lessons share navigation; first install shows only the essentials. */
@Composable
internal fun QuickStartGuide(onDismiss: () -> Unit, initialPage: Int = 0, firstRun: Boolean = false) {
    val lessons = if (firstRun) onboardingGuideLessons else GuideLesson.entries
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(lessons.indices)) { lessons.size }
    val currentPage = pager.currentPage.coerceIn(lessons.indices)
    val scope = rememberCoroutineScope()
    var playing by rememberSaveable { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    GuideDialog(onDismiss) {
        Column(Modifier.fillMaxSize().testTag("quick-start-guide").windowInsetsPadding(WindowInsets.safeDrawing)) {
            GuideHeader(if (firstRun) R.string.guide_getting_started else R.string.guide_title)
            BoxWithConstraints(Modifier.weight(1f)) {
                val availableHeight = maxHeight
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().testTag("guide-pager"),
                    key = { lessons[it].name }) { index ->
                    val topic = lessons[index]
                    GuideVisualPage(topic.visual, stringResource(topic.title), stringResource(topic.caption),
                        playing = playing && pager.settledPage == index,
                        loadVideo = pager.settledPage == index,
                        onToggle = { playing = !playing }, availableHeight = availableHeight,
                        titleTag = "guide-title-$index")
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                val description = stringResource(R.string.guide_page_description, currentPage + 1,
                    lessons.size, stringResource(lessons[currentPage].title))
                Text(stringResource(R.string.guide_progress, currentPage + 1, lessons.size),
                    style = MaterialTheme.typography.labelSmall, color = GuideMuted,
                    modifier = Modifier.testTag("guide-progress").semantics { contentDescription = description })
                Row(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { scope.launch { pager.animateScrollToPage(currentPage - 1) } },
                        enabled = currentPage > 0, modifier = Modifier.testTag("guide-back")) {
                        Text(stringResource(R.string.guide_previous))
                    }
                    Button(onClick = {
                        if (currentPage == lessons.lastIndex) onDismiss()
                        else scope.launch { pager.animateScrollToPage(currentPage + 1) }
                    }, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("guide-next"),
                        colors = ButtonDefaults.buttonColors(containerColor = GuideMint, contentColor = GuideInk)) {
                        Text(stringResource(R.string.guide_next))
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideVisualPage(
    visual: GuideVisual, title: String, caption: String, playing: Boolean, loadVideo: Boolean,
    onToggle: () -> Unit, availableHeight: Dp, titleTag: String,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val mediaHeight = (minOf(maxWidth - 40.dp, 520.dp) / visual.aspectRatio)
            .coerceAtMost((availableHeight - 140.dp).coerceAtLeast(120.dp))
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            GuideDemo(visual, title, playing, Modifier.fillMaxWidth().height(mediaHeight), loadVideo, onToggle)
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(titleTag).semantics { heading() })
            Text(caption, style = MaterialTheme.typography.bodyLarge, color = GuideMuted)
        }
    }
}

@Composable
private fun GuideHeader(title: Int) {
    Text(stringResource(title), color = GuideMint, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp))
}

@Composable
private fun GuideDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            val statusLight = controller?.isAppearanceLightStatusBars
            val navigationLight = controller?.isAppearanceLightNavigationBars
            controller?.isAppearanceLightStatusBars = false
            controller?.isAppearanceLightNavigationBars = false
            onDispose {
                statusLight?.let { controller.isAppearanceLightStatusBars = it }
                navigationLight?.let { controller.isAppearanceLightNavigationBars = it }
            }
        }
        MaterialTheme(colorScheme = darkColorScheme(primary = GuideMint, onPrimary = GuideInk,
            background = GuideInk, surface = GuideInk, onSurface = Color(0xFFE9F0F1), onSurfaceVariant = GuideMuted)) {
            Surface(color = GuideInk, modifier = Modifier.fillMaxSize(), content = content)
        }
    }
}
