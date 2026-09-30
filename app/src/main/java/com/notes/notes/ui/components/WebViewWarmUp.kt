package com.notes.notes.ui.components

import android.content.Context
import android.util.Log
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import java.util.concurrent.Executors

object WebViewWarmUp {

    @Volatile
    private var requested =
        false

    fun start(
        context: Context,
    ) {
        if (requested) {
            return
        }

        synchronized(this) {
            if (requested) {
                return
            }

            requested =
                true
        }

        val executor =
            Executors
                .newSingleThreadExecutor()

        val configuration =
            WebViewStartUpConfig
                .Builder(executor)
                .build()

        runCatching {
            WebViewCompat.startUpWebView(
                context.applicationContext,
                configuration,
                object :
                    WebViewOutcomeReceiver<
                            WebViewStartUpResult,
                            WebViewStartupException,
                            > {

                    override fun onResult(
                        result:
                        WebViewStartUpResult,
                    ) {
                        executor.shutdown()
                    }

                    override fun onError(
                        error:
                        WebViewStartupException,
                    ) {
                        Log.w(
                            "WebViewWarmUp",
                            "WebView async startup failed",
                            error,
                        )

                        executor.shutdown()
                    }
                },
            )
        }.onFailure { error ->
            executor.shutdown()

            Log.w(
                "WebViewWarmUp",
                "Unable to request WebView startup",
                error,
            )
        }
    }
}