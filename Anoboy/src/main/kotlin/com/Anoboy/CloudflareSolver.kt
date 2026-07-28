package com.anoboy

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Resolves a Cloudflare cf_clearance cookie for a host, proactively and off the OkHttp
// call path. Solving used to live inside an okhttp Interceptor, but that meant every one
// of the main page's parallel section requests raced for the same lock *inside* its own
// OkHttp call, and requests that lost the race got cancelled by their own per-call timeout
// before the winner ever finished solving. A coroutine Mutex has no such timeout: losers
// just suspend until the winner is done, then read the now-populated cache.
@SuppressLint("SetJavaScriptEnabled")
class CloudflareSolver {
    private val mutex = Mutex()
    private var cachedCookie: String? = null
    private var cachedHost: String? = null
    private var cachedAt = 0L

    suspend fun getCookie(url: String, forceRefresh: Boolean = false): String? {
        val host = url.toHttpUrl().host

        if (!forceRefresh) {
            validCachedCookie(host)?.let { return it }
        }

        return mutex.withLock {
            // Another coroutine may have solved it while we were waiting for the lock.
            if (!forceRefresh) {
                validCachedCookie(host)?.let { return@withLock it }
            }

            val ctx = currentApplicationContext() ?: return@withLock null
            toast(ctx, "Anoboy: bypassing Cloudflare…")

            val solved = withContext(Dispatchers.IO) {
                solveSilently(ctx, url) ?: solveInteractively(ctx, url)
            }

            toast(ctx, if (solved != null) "Anoboy: Cloudflare bypass succeeded" else "Anoboy: Cloudflare bypass failed")

            if (solved != null) {
                cachedCookie = solved
                cachedHost = host
                cachedAt = System.currentTimeMillis()
            }
            solved
        }
    }

    private fun validCachedCookie(host: String): String? {
        return cachedCookie.takeIf { host == cachedHost && System.currentTimeMillis() - cachedAt < 15 * 60_000 }
    }

    // Silent JS challenge: hidden WebView, no user interaction.
    // Cookie is polled continuously rather than only checked in onPageFinished, since
    // Cloudflare can set cf_clearance via a background reload/XHR after that event fires.
    private fun solveSilently(ctx: Context, url: String): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        var webViewRef: WebView? = null
        val handler = Handler(Looper.getMainLooper())

        val poller = object : Runnable {
            override fun run() {
                val cookies = CookieManager.getInstance().getCookie(url)
                if (cookies != null && cookies.contains("cf_clearance")) {
                    result = cookies
                    latch.countDown()
                } else if (latch.count > 0) {
                    handler.postDelayed(this, 500)
                }
            }
        }

        handler.post {
            val webView = WebView(ctx)
            webViewRef = webView
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.webViewClient = WebViewClient()
            webView.loadUrl(url)
            handler.postDelayed(poller, 500)
        }

        latch.await(12, TimeUnit.SECONDS)
        handler.removeCallbacks(poller)

        handler.post {
            webViewRef?.stopLoading()
            webViewRef?.destroy()
        }

        return result
    }

    // Interactive challenge (e.g. Turnstile checkbox): show a real WebView on top of the
    // current screen so the user can solve it by hand, then capture the resulting cookie.
    private fun solveInteractively(ctx: Context, url: String): String? {
        val activity = currentActivity() ?: return null
        val latch = CountDownLatch(1)
        var result: String? = null
        var dialogRef: Dialog? = null
        val handler = Handler(Looper.getMainLooper())

        toast(ctx, "Anoboy: please complete the verification shown")

        val poller = object : Runnable {
            override fun run() {
                val cookies = CookieManager.getInstance().getCookie(url)
                if (cookies != null && cookies.contains("cf_clearance")) {
                    result = cookies
                    dialogRef?.dismiss()
                } else if (latch.count > 0) {
                    handler.postDelayed(this, 500)
                }
            }
        }

        handler.post {
            val webView = WebView(activity)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.webViewClient = WebViewClient()

            val dialog = Dialog(activity)
            dialogRef = dialog
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(
                webView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            dialog.setCancelable(true)
            dialog.setOnDismissListener {
                webView.stopLoading()
                webView.destroy()
                latch.countDown()
            }

            webView.loadUrl(url)
            dialog.show()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            handler.postDelayed(poller, 500)
        }

        latch.await(3, TimeUnit.MINUTES)
        handler.removeCallbacks(poller)

        handler.post {
            dialogRef?.takeIf { it.isShowing }?.dismiss()
        }

        return result
    }

    private fun toast(ctx: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun currentApplicationContext(): Context? {
        return try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        } catch (e: Exception) {
            null
        }
    }

    private fun currentActivity(): Activity? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = activityThreadClass.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            val activities = activitiesField.get(activityThread) as? Map<*, *> ?: return null
            for (record in activities.values) {
                val recordClass = record!!.javaClass
                val pausedField = recordClass.getDeclaredField("paused")
                pausedField.isAccessible = true
                if (!pausedField.getBoolean(record)) {
                    val activityField = recordClass.getDeclaredField("activity")
                    activityField.isAccessible = true
                    return activityField.get(record) as? Activity
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
