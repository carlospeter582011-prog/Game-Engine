package com.nexusengine.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import android.net.Uri
import android.view.View
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
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class MainActivity : FlutterActivity() {
    private val origin = "https://appassets.androidplatform.net"
    private val editorUrl = "$origin/flutter/editor.html"
    private var document: PendingDocument? = null
    private var keyPicker: MethodChannel.Result? = null
    private var iconPicker: MethodChannel.Result? = null
    private var picker: ValueCallback<Array<Uri>>? = null
    private var channel: MethodChannel? = null
    private var webView: WebView? = null
    private var buildingApk = false

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
                "chooseSigningKey" -> {
                    if (keyPicker != null) result.error("BUSY", "A key picker is already open.", null)
                    else {
                        keyPicker = result
                        try {
                            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }, 44)
                        } catch (error: Exception) {
                            keyPicker = null
                            result.error("KEY", error.message, null)
                        }
                    }
                }
                "buildApk" -> buildApk(call, result)
                "exit" -> { finish(); result.success(null) }
                "webBack" -> {
                    val view = webView
                    if (view == null) result.success(false)
                    else view.evaluateJavascript("window.nexusHandleBack?.() || false") {
                        result.success(it == "true")
                    }
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

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    @Suppress("SetJavaScriptEnabled")
    private fun makeWebView(context: Context): WebView {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/flutter/") { path ->
                val allowed = setOf("editor.html", "python.js", "apk.js", "player-template.json", "vendor/skulpt.min.js",
                    "vendor/skulpt-stdlib.js", "vendor/Skulpt-LICENSE.txt")
                if (path !in allowed) null
                else {
                    val mime = when {
                        path.endsWith(".html") -> "text/html"
                        path.endsWith(".js") -> "application/javascript"
                        path.endsWith(".json") -> "application/json"
                        else -> "text/plain"
                    }
                    WebResourceResponse(mime, "UTF-8", assets.open("flutter_assets/assets/web/$path"))
                }
            }
            .build()
        return WebView(context).apply {
            setBackgroundColor(Color.rgb(13, 17, 23))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClientCompat() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.toString() != editorUrl

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    if (url.scheme in setOf("data", "blob")) return null
                    if (url.scheme == "https" && url.host == "appassets.androidplatform.net" &&
                        url.path?.startsWith("/flutter/") == true) {
                        loader.shouldInterceptRequest(url)?.let { return it }
                    }
                    return WebResourceResponse("text/plain", "UTF-8", 404, "Blocked",
                        emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
            }
            run {
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
                                ".json" -> "application/json"
                                else -> value.takeIf { type -> '/' in type }
                            }
                        }.distinct()
                        try {
                            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = if (types.size == 1) types.single() else "*/*"
                                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
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
            loadUrl(editorUrl)
        }
    }

    private fun buildApk(call: io.flutter.plugin.common.MethodCall, result: MethodChannel.Result) {
        if (buildingApk) {
            result.error("BUSY", "Another APK export is already running.", null)
            return
        }
        val html = call.argument<String>("html")
        val packageId = call.argument<String>("package")
        val label = call.argument<String>("name")
        val version = call.argument<String>("version")
        val number = call.argument<Int>("number")
        val orientation = call.argument<String>("orientation")
        val icon = call.argument<ByteArray>("icon")
        val keystore = call.argument<ByteArray>("keystore")
        val password = call.argument<String>("keyPassword")
        val alias = call.argument<String>("keyAlias")
        if (html == null || packageId == null || label == null || version == null ||
            number == null || orientation == null) {
            result.error("METADATA", "Fill in the game’s APK settings.", null)
            return
        }
        buildingApk = true
        Thread {
            try {
                val work = File(cacheDir, "game-export").apply { mkdirs() }
                val unsigned = File.createTempFile("unsigned-", ".apk", work)
                try {
                    val signed = File.createTempFile("signed-", ".apk", work)
                    try {
                        val key = if (keystore == null) gameSigningKey()
                        else portableKey(keystore, password.orEmpty(), alias.orEmpty())
                        assets.open("nexus-player.apk").use { template ->
                            GameApk.build(template, unsigned, signed, html.toByteArray(Charsets.UTF_8),
                                icon, packageId, label, version, number, orientation,
                                key.privateKey, key.certificate as X509Certificate)
                        }
                        val bytes = signed.readBytes()
                        runOnUiThread { buildingApk = false; result.success(bytes) }
                    } finally {
                        signed.delete()
                    }
                } finally {
                    unsigned.delete()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    buildingApk = false
                    result.error("APK", error.message ?: "Could not package or sign the game.", null)
                }
            }
        }.start()
    }

    private fun portableKey(bytes: ByteArray, password: String, alias: String): KeyStore.PrivateKeyEntry {
        require(bytes.size in 1..524288) { "Select a PKCS#12 key file up to 512 KB." }
        val chars = password.toCharArray()
        try {
            val store = KeyStore.getInstance("PKCS12")
            store.load(ByteArrayInputStream(bytes), chars)
            val candidates = store.aliases().toList().filter { store.isKeyEntry(it) }
            val selected = alias.ifBlank {
                require(candidates.size == 1) { "Enter the alias of the private key in this file." }
                candidates.single()
            }
            return store.getEntry(selected, KeyStore.PasswordProtection(chars)) as? KeyStore.PrivateKeyEntry
                ?: error("That alias has no private key.")
        } finally { chars.fill('\u0000') }
    }

    private fun gameSigningKey(): KeyStore.PrivateKeyEntry {
        val alias = "nexus-game-signing-v1"
        synchronized(signingLock) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(alias)) {
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
                generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=Nexus Games"))
                    .build())
                generator.generateKeyPair()
            }
            return store.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        }
    }

    private companion object {
        val signingLock = Any()
    }

    @Deprecated("FlutterActivity forwards activity results for the system document picker.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data?.takeIf { resultCode == Activity.RESULT_OK }
        if (requestCode == 42) {
            val clip = data?.clipData
            val files = if (resultCode == Activity.RESULT_OK && clip != null)
                Array(clip.itemCount) { clip.getItemAt(it).uri }
            else uri?.let { arrayOf(it) }
            picker?.onReceiveValue(files)
            picker = null
        }
        if (requestCode == 44) {
            val pending = keyPicker ?: return
            keyPicker = null
            if (uri == null) { pending.success(null); return }
            Thread {
                try {
                    val bytes = requireNotNull(contentResolver.openInputStream(uri)).use {
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = it.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 524288) { "Select a key file up to 512 KB." }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    runOnUiThread { pending.success(bytes) }
                } catch (error: Exception) {
                    runOnUiThread { pending.error("KEY", error.message, null) }
                }
            }.start()
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
