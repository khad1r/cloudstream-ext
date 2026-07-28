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
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Fetches a page's HTML by letting a real WebView pass the Cloudflare challenge and then
// reading its rendered DOM directly, rather than extracting the cf_clearance cookie and
// replaying it through a separate OkHttp client. That cookie-replay approach looked like it
// worked (the toast said "succeeded") but the app still saw an empty page, because Cloudflare
// can bind cf_clearance to the TLS/browser fingerprint of the client that solved it — OkHttp
// presents a different fingerprint than the WebView, so the replayed cookie alone doesn't
// guarantee real content back. Reading the HTML straight out of the browser that solved the
// challenge sidesteps that entirely.
//
// Solving is coroutine-Mutex-guarded (not thread-blocking) so concurrent callers (e.g. the
// main page's parallel section loads) suspend and reuse a single in-flight result instead of
// each racing for a lock inside their own OkHttp call and getting cancelled by their own
// per-call timeout.
@SuppressLint("SetJavaScriptEnabled")
class CloudflareSolver {
    private val mutex = Mutex()
    private var cachedHtml: String? = null
    private var cachedUrl: String? = null
    private var cachedAt = 0L

    suspend fun fetchHtml(url: String, forceRefresh: Boolean = false): String? {
        if (!forceRefresh) {
            validCached(url)?.let { return it }
        }

        return mutex.withLock {
            // Another coroutine may have solved it while we were waiting for the lock.
            if (!forceRefresh) {
                validCached(url)?.let { return@withLock it }
            }

            val ctx = currentApplicationContext() ?: return@withLock null

            // cf_clearance is stored in the shared CookieManager, so once one page has cleared
            // the challenge for this host, later pages already carry it and load near-instantly.
            // Only announce the bypass when we're not already cleared, so this isn't a toast on
            // every single page fetch.
            val alreadyCleared = CookieManager.getInstance().getCookie(url)?.contains("cf_clearance") == true
            if (!alreadyCleared) toast(ctx, "Anoboy: bypassing Cloudflare…")

            val html = withContext(Dispatchers.IO) {
                solveSilently(ctx, url) ?: solveInteractively(ctx, url)
            }

            if (!alreadyCleared) {
                toast(ctx, if (html != null) "Anoboy: Cloudflare bypass succeeded" else "Anoboy: Cloudflare bypass failed")
            }

            if (html != null) {
                cachedHtml = html
                cachedUrl = url
                cachedAt = System.currentTimeMillis()
            }
            html
        }
    }

    private fun validCached(url: String): String? {
        return cachedHtml.takeIf { url == cachedUrl && System.currentTimeMillis() - cachedAt < 2 * 60_000 }
    }

    // Silent JS challenge: hidden WebView, no user interaction.
    private fun solveSilently(ctx: Context, url: String): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        var webViewRef: WebView? = null
        val handler = Handler(Looper.getMainLooper())

        val poller = object : Runnable {
            override fun run() {
                val webView = webViewRef
                val cookies = CookieManager.getInstance().getCookie(url)
                if (webView != null && cookies != null && cookies.contains("cf_clearance")) {
                    extractHtml(webView) { html ->
                        android.util.Log.d("AnoboyDebug", "solveSilently extractHtml htmlLen=${html?.length ?: -1}")
                        result = html
                        latch.countDown()
                    }
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

        latch.await(15, TimeUnit.SECONDS)
        android.util.Log.d("AnoboyDebug", "solveSilently timed out, count=${latch.count}")
        handler.removeCallbacks(poller)

        handler.post {
            webViewRef?.stopLoading()
            webViewRef?.destroy()
        }

        return result
    }

    // Interactive challenge (e.g. Turnstile checkbox): show a real WebView on top of the
    // current screen so the user can solve it by hand, then read the resulting page HTML.
    private fun solveInteractively(ctx: Context, url: String): String? {
        val activity = currentActivity() ?: return null
        val latch = CountDownLatch(1)
        var result: String? = null
        var webViewRef: WebView? = null
        var dialogRef: Dialog? = null
        val handler = Handler(Looper.getMainLooper())

        toast(ctx, "Anoboy: please complete the verification shown")

        val poller = object : Runnable {
            override fun run() {
                val webView = webViewRef
                val cookies = CookieManager.getInstance().getCookie(url)
                if (webView != null && cookies != null && cookies.contains("cf_clearance")) {
                    extractHtml(webView) { html ->
                        result = html
                        dialogRef?.dismiss()
                    }
                } else if (latch.count > 0) {
                    handler.postDelayed(this, 500)
                }
            }
        }

        handler.post {
            val webView = WebView(activity)
            webViewRef = webView
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

    private fun extractHtml(webView: WebView, onResult: (String?) -> Unit) {
        webView.evaluateJavascript("document.documentElement.outerHTML") { json ->
            val html = try {
                if (json == null || json == "null") null else JSONTokener(json).nextValue() as? String
            } catch (e: Exception) {
                null
            }
            onResult(html)
        }
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
