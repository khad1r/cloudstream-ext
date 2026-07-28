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
        response.close()

        val ctx = currentApplicationContext() ?: return chain.proceed(request)
        val host = request.url.host

        val cookie = getOrSolveCookie(ctx, host, request.url.toString()) ?: return chain.proceed(request)

        val newRequest = request.newBuilder().header("Cookie", cookie).build()
        return chain.proceed(newRequest)
    }

    // Synchronized so concurrent requests (e.g. the main page's parallel section loads)
    // wait for a single in-flight solve instead of each opening their own WebView/dialog.
    @Synchronized
    private fun getOrSolveCookie(ctx: Context, host: String, url: String): String? {
        cachedCookie.takeIf { host == cachedHost && System.currentTimeMillis() - cachedAt < 15 * 60_000 }
            ?.let { return it }

        toast(ctx, "Anoboy: bypassing Cloudflare…")
        val solved = solveSilently(ctx, url) ?: solveInteractively(ctx, url)
        toast(ctx, if (solved != null) "Anoboy: Cloudflare bypass succeeded" else "Anoboy: Cloudflare bypass failed")

        if (solved != null) {
            cachedCookie = solved
            cachedHost = host
            cachedAt = System.currentTimeMillis()
        }
        return solved
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
    private fun solveInteractively(ctx: Context, url: String): String? {
        val activity = currentActivity() ?: return null
        val latch = CountDownLatch(1)
        var result: String? = null
        var dialogRef: Dialog? = null

        toast(ctx, "Anoboy: please complete the verification shown")

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
