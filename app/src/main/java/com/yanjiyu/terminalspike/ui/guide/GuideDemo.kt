package com.yanjiyu.terminalspike.ui.guide

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RawRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanjiyu.terminalspike.R

@Composable
internal fun GuideDemo(
    visual: GuideVisual,
    description: String,
    playing: Boolean,
    modifier: Modifier = Modifier,
    loadVideo: Boolean = true,
    onToggle: () -> Unit,
) = key(visual) {
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val poster = ImageBitmap.imageResource(visual.poster)
    val painter = remember(poster) { BitmapPainter(poster) }
    var ready by remember(loadVideo) { mutableStateOf(false) }
    Box(modifier.clip(RoundedCornerShape(14.dp))
        .background(Color.Black).testTag(if (ready) "guide-recording-ready" else "guide-recording-poster")) {
        Image(painter, description, Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit)
        if (loadVideo && visual.video != null) {
            AndroidView(
                factory = { RecordedGuideView(context, visual.video) { ready = true } },
                modifier = Modifier.fillMaxSize(),
                update = { it.setPlaying(playing && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) },
                onRelease = { it.release() },
            )
        }
        if (loadVideo && visual.video != null) {
            val playbackDescription = stringResource(if (playing) R.string.guide_pause else R.string.guide_play)
            IconButton(onClick = onToggle, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                .size(48.dp).background(GuideInk.copy(alpha = .9f), RoundedCornerShape(24.dp))
                .testTag("guide-animation-toggle").semantics { contentDescription = playbackDescription }) {
                Text(if (playing) "Ⅱ" else "▶", color = Color.White)
            }
        }
    }
}

/** Platform decoder, with no player dependency, network use or terminal input sink. */
private class RecordedGuideView(
    context: Context,
    @RawRes private val resource: Int,
    private val onFirstFrame: () -> Unit,
) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var videoSurface: Surface? = null
    private var prepared = false
    private var shouldPlay = false

    init {
        isOpaque = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        surfaceTextureListener = this
    }

    fun setPlaying(value: Boolean) {
        shouldPlay = value
        if (!prepared) return
        player?.let { if (value) it.start() else if (it.isPlaying) it.pause() }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        videoSurface = Surface(texture)
        player = MediaPlayer().apply {
            setSurface(videoSurface)
            setDataSource(context, Uri.parse("android.resource://${context.packageName}/$resource"))
            setVolume(0f, 0f)
            isLooping = true
            setOnPreparedListener {
                prepared = true
                updateFraming()
                if (shouldPlay) start() else seekTo(0L, MediaPlayer.SEEK_CLOSEST)
            }
            setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onFirstFrame()
                false
            }
            // Keep the still frame readable if a device cannot decode the bundled clip.
            setOnErrorListener { _, _, _ -> this@RecordedGuideView.release(); true }
            prepareAsync()
        }
    }

    private fun updateFraming() {
        val current = player ?: return
        val videoWidth = current.videoWidth.toFloat()
        val videoHeight = current.videoHeight.toFloat()
        if (videoWidth <= 0 || videoHeight <= 0 || width == 0 || height == 0) return
        val scale = minOf(width / videoWidth, height / videoHeight)
        setTransform(Matrix().apply {
            setScale(scale * videoWidth / width, scale * videoHeight / height)
            postTranslate((width - videoWidth * scale) / 2f,
                (height - videoHeight * scale) / 2f)
        })
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = updateFraming()
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        return true
    }

    fun release() {
        prepared = false
        player?.release()
        player = null
        videoSurface?.release()
        videoSurface = null
    }
}
