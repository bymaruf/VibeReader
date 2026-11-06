package com.example.vibereader.service

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

class QuoteWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = QuoteEngine()

    private inner class QuoteEngine : Engine() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 52f
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) drawWallpaper()
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            drawWallpaper()
        }

        private fun drawWallpaper() {
            val canvas = surfaceHolder.lockCanvas() ?: return
            try {
                drawQuote(canvas)
            } finally {
                surfaceHolder.unlockCanvasAndPost(canvas)
            }
        }

        private fun drawQuote(canvas: Canvas) {
            canvas.drawColor(Color.rgb(25, 28, 38))
            val centerX = canvas.width / 2f
            val centerY = canvas.height / 2f
            paint.textSize = 52f
            canvas.drawText("Your next favorite", centerX, centerY - 30f, paint)
            canvas.drawText("quote will live here.", centerX, centerY + 40f, paint)
            paint.textSize = 28f
            paint.alpha = 190
            canvas.drawText("VibeReader", centerX, centerY + 120f, paint)
            paint.alpha = 255
        }
    }
}
