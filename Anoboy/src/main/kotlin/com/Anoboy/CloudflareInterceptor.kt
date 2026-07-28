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
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@SuppressLint("SetJavaScriptEnabled")
class CloudflareInterceptor : Interceptor {
    private var cachedCookie: String? = null
    private var cachedHost: String? = null
    private var cachedAt = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code != 403 && response.code != 503) return response

        val ctx = currentApplicationContext() ?: return response
        val host = request.url.host

        val cookie = cachedCookie.takeIf { host == cachedHost && System.currentTimeMillis() - cachedAt < 15 * 60_000 }
            ?: run {
                response.close()
                val url = request.url.toString()
                (solveSilently(ctx, url) ?: solveInteractively(url))?.also {
                    cachedCookie = it
                    cachedHost = host
                    cachedAt = System.currentTimeMillis()
                }
            }
            ?: return response

        val newRequest = request.newBuilder().header("Cookie", cookie).build()
        return chain.proceed(newRequest)
    }

    // Silent JS challenge: hidden WebView, no user interaction.
    private fun solveSilently(ctx: Context, url: String): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        var webViewRef: WebView? = null

        Handler(Looper.getMainLooper()).post {
            val webView = WebView(ctx)
            webViewRef = webView
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    val cookies = CookieManager.getInstance().getCookie(url)
                    if (cookies != null && cookies.contains("cf_clearance")) {
                        result = cookies
                        latch.countDown()
                    }
                }
            }
            webView.loadUrl(url)
        }

        latch.await(12, TimeUnit.SECONDS)

        Handler(Looper.getMainLooper()).post {
            webViewRef?.stopLoading()
            webViewRef?.destroy()
        }

        return result
    }

    // Interactive challenge (e.g. Turnstile checkbox): show a real WebView on top of the
    // current screen so the user can solve it by hand, then capture the resulting cookie.
    private fun solveInteractively(url: String): String? {
        val activity = currentActivity() ?: return null
        val latch = CountDownLatch(1)
        var result: String? = null
        var dialogRef: Dialog? = null

        Handler(Looper.getMainLooper()).post {
            val webView = WebView(activity)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true

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

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    val cookies = CookieManager.getInstance().getCookie(url)
                    if (cookies != null && cookies.contains("cf_clearance")) {
                        result = cookies
                        dialog.dismiss()
                    }
                }
            }

            webView.loadUrl(url)
            dialog.show()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        latch.await(3, TimeUnit.MINUTES)

        Handler(Looper.getMainLooper()).post {
            dialogRef?.takeIf { it.isShowing }?.dismiss()
        }

        return result
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
