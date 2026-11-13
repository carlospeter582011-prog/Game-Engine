package com.nexusengine.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import java.io.ByteArrayInputStream

class MainActivity : FlutterActivity() {
    private val origin = "https://appassets.androidplatform.net"
    private val editorUrl = "$origin/flutter/editor.html"
    private val playerUrl = "$origin/game/game.html"
    private var document: PendingDocument? = null
    private var iconPicker: MethodChannel.Result? = null
    private var picker: ValueCallback<Array<Uri>>? = null
    private var channel: MethodChannel? = null
    private var webView: WebView? = null
    private var immersive = false
    private val editor by lazy {
        packageManager.getActivityInfo(componentName, PackageManager.GET_META_DATA)
            .metaData.getBoolean("nexus.editor")
    }

    private data class PendingDocument(val bytes: ByteArray, val result: MethodChannel.Result)

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "nexus/native")
        channel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "save" -> {
                    val name = call.argument<String>("name")
                    val mime = call.argument<String>("mime")
                    val bytes = call.argument<ByteArray>("bytes")
                    if (name == null || mime == null || bytes == null || document != null) {
                        result.error("SAVE", "A save is already pending or the file is invalid.", null)
                    } else {
                        document = PendingDocument(bytes, result)
                        try {
                            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = mime
                                putExtra(Intent.EXTRA_TITLE, name)
                            }, 41)
                        } catch (error: Exception) {
                            document = null
                            result.error("SAVE", error.message, null)
                        }
                    }
                }
                "icon" -> {
                    if (iconPicker != null) {
                        result.error("ICON", "The icon picker is already open.", null)
                    } else {
                        iconPicker = result
                        try {
                            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "image/*"
                            }, 43)
                        } catch (error: Exception) {
                            iconPicker = null
                            result.error("ICON", error.message, null)
                        }
                    }
                }
                "fullscreen" -> {
                    immersive = if (editor) !immersive else true
                    applyFullscreen()
                    result.success(null)
                }
                "exit" -> { finish(); result.success(null) }
                "webBack" -> {
                    val view = webView
                    if (view == null || !editor) result.success(false)
                    else view.evaluateJavascript("window.nexusHandleBack?.() || false") {
                        result.success(it == "true")
                    }
                }
                "open" -> {
                    val url = Uri.parse(call.argument<String>("url") ?: "")
                    if (url.scheme == "https" && url.host == "github.com") {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, url))
                            result.success(null)
                        } catch (error: Exception) {
                            result.error("OPEN", error.message, null)
                        }
                    } else result.error("OPEN", "Invalid GitHub link", null)
                }
                else -> result.notImplemented()
            }
        }
        flutterEngine.platformViewsController.registry.registerViewFactory(
            "nexus/web", object : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
                override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
                    val view = makeWebView(context)
                    webView = view
                    return object : PlatformView {
                        override fun getView(): View = view
                        override fun dispose() {
                            if (webView === view) webView = null
                            view.destroy()
                        }
                    }
                }
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersive = !editor
        applyFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    @Suppress("DEPRECATION")
    private fun applyFullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            val controller = window.insetsController ?: return
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (immersive) controller.hide(WindowInsets.Type.systemBars())
            else controller.show(WindowInsets.Type.systemBars())
        } else {
            window.decorView.systemUiVisibility = if (immersive) {
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            } else 0
        }
        channel?.invokeMethod("immersive", immersive)
    }

    @Suppress("SetJavaScriptEnabled")
    private fun makeWebView(context: Context): WebView {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/flutter/") { path ->
                val allowed = setOf("editor.html", "python.js", "vendor/skulpt.min.js",
                    "vendor/skulpt-stdlib.js", "vendor/Skulpt-LICENSE.txt")
                if (!editor || path !in allowed) null
                else {
                    val mime = when {
                        path.endsWith(".html") -> "text/html"
                        path.endsWith(".js") -> "application/javascript"
                        else -> "text/plain"
                    }
                    WebResourceResponse(mime, "UTF-8", assets.open("flutter_assets/assets/web/$path"))
                }
            }
            .addPathHandler("/game/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        val page = if (editor) editorUrl else playerUrl
        return WebView(context).apply {
            setBackgroundColor(Color.rgb(13, 17, 23))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClientCompat() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.toString() != page

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    if (url.scheme in setOf("data", "blob")) return null
                    if (url.scheme == "https" && url.host == "appassets.androidplatform.net" &&
                        (if (editor) url.path?.startsWith("/flutter/") == true else url.path == "/game/game.html")) {
                        loader.shouldInterceptRequest(url)?.let { return it }
                    }
                    return WebResourceResponse("text/plain", "UTF-8", 404, "Blocked",
                        emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
            }
            if (editor) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.addWebMessageListener(this, "NexusNative", setOf(origin)) {
                        _: WebView, message, sourceOrigin, mainFrame, _: JavaScriptReplyProxy ->
                        if (mainFrame && sourceOrigin.toString().trimEnd('/') == origin && this.url == editorUrl) {
                            message.data?.let { channel?.invokeMethod("message", it) }
                        }
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
                    ): Boolean {
                        picker?.onReceiveValue(null)
                        picker = callback
                        val types = params.acceptTypes.flatMap { it.split(",") }.mapNotNull {
                            when (val value = it.trim()) {
                                ".zip" -> "application/zip"
                                ".png" -> "image/png"
                                else -> value.takeIf { type -> '/' in type }
                            }
                        }.distinct()
                        try {
                            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = if (types.size == 1) types.single() else "*/*"
                                if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                            }, 42)
                        } catch (_: Exception) {
                            picker = null
                            callback.onReceiveValue(null)
                        }
                        return true
                    }
                }
            }
            loadUrl(page)
        }
    }

    @Deprecated("FlutterActivity forwards activity results for the system document picker.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data?.takeIf { resultCode == Activity.RESULT_OK }
        if (requestCode == 42) {
            picker?.onReceiveValue(uri?.let { arrayOf(it) })
            picker = null
        }
        if (requestCode == 43) {
            val pending = iconPicker ?: return
            iconPicker = null
            if (uri == null) { pending.success(null); return }
            Thread {
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    require(bounds.outWidth in 1..16000 && bounds.outHeight in 1..16000) {
                        "Choose a valid PNG, JPEG, or WebP image."
                    }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
                    val source = contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    } ?: error("Could not decode the icon.")
                    val scale = 512f / maxOf(source.width, source.height)
                    val scaled = Bitmap.createScaledBitmap(source,
                        (source.width * scale).toInt().coerceAtLeast(1),
                        (source.height * scale).toInt().coerceAtLeast(1), true)
                    val icon = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                    android.graphics.Canvas(icon).drawBitmap(scaled,
                        (512 - scaled.width) / 2f, (512 - scaled.height) / 2f, null)
                    val bytes = ByteArrayOutputStream().use {
                        check(icon.compress(Bitmap.CompressFormat.PNG, 100, it))
                        it.toByteArray()
                    }
                    runOnUiThread { pending.success(bytes) }
                } catch (error: Exception) {
                    runOnUiThread { pending.error("ICON", error.message, null) }
                }
            }.start()
        }
        if (requestCode == 41) {
            val pending = document ?: return
            document = null
            if (uri == null) { pending.result.success(false); return }
            Thread {
                try {
                    val output = requireNotNull(contentResolver.openOutputStream(uri, "wt"))
                    output.use { it.write(pending.bytes) }
                    runOnUiThread { pending.result.success(true) }
                } catch (error: Exception) {
                    runOnUiThread { pending.result.error("SAVE", error.message, null) }
                }
            }.start()
        }
    }
}
