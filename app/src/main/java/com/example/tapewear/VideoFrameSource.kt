package com.example.tapewear

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever

class VideoFrameSource(context: Context, assetName: String) {

    private val retriever = MediaMetadataRetriever()
    private val durationMs: Long

    init {
        val afd = context.assets.openFd(assetName)
        retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        durationMs = durStr?.toLongOrNull() ?: 0L
        afd.close()
    }

    fun frameAt(ms: Long): Bitmap? {
        if (durationMs <= 0L) return null
        // loop video when we go past the end
        val safeMs = ms % durationMs
        return retriever.getFrameAtTime(safeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    }

    fun close() = retriever.release()
}
