package com.aventure.connexion

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Enregistrement des fichiers demandés par les pages (pièces jointes Proton, fichiers de Claude).
 * Ils arrivent dans le dossier Téléchargements du téléphone : c'est la seule chose qui survit à la
 * fermeture de la session privée.
 */
object Downloads {
    private const val BRIDGE_NAME = "androidDownload"

    // Une page qui télécharge un blob crée un lien <a download="nom.zip"> puis le clique : le navigateur ne
    // transmet pas ce nom à l'appli. Ce script (lancé au tout début de chaque page) le retient au passage.
    private const val TRACK_SCRIPT = """
        (function () {
            if (window.__dlTrack) { return; }
            window.__dlTrack = true;
            function remember(a) {
                if (a && a.download) { window.__dlName = a.download; }
            }
            var originalClick = HTMLAnchorElement.prototype.click;
            HTMLAnchorElement.prototype.click = function () {
                remember(this);
                return originalClick.apply(this, arguments);
            };
            document.addEventListener('click', function (e) {
                var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
                remember(a);
            }, true);
        })();
    """

    // Les fichiers « blob: » n'existent que dans la page : un script les lit puis les renvoie à l'appli.
    // Ce canal n'est ouvert qu'à l'adresse exacte de chaque site (jamais à une autre page).
    private const val BLOB_SCRIPT = """
        (function () {
            var xhr = new XMLHttpRequest();
            xhr.open('GET', __URL__, true);
            xhr.responseType = 'blob';
            xhr.onload = function () {
                if (this.status !== 200) { return; }
                var reader = new FileReader();
                reader.onloadend = function () {
                    var name = window.__dlName || __NAME__;
                    window.__dlName = null;
                    androidDownload.postMessage(JSON.stringify({ name: name, data: reader.result }));
                };
                reader.readAsDataURL(this.response);
            };
            xhr.send();
        })();
    """

    /** Autorise le site d'origine donné à envoyer ses fichiers blob à l'appli. */
    fun installBridge(context: Context, view: WebView, origin: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, TRACK_SCRIPT, setOf(origin))
        }
        WebViewCompat.addWebMessageListener(view, BRIDGE_NAME, setOf(origin)) { _, message, _, _, _ ->
            val json = message.data ?: return@addWebMessageListener
            try {
                val item = JSONObject(json)
                saveDataUrl(context, item.getString("data"), item.getString("name"))
            } catch (e: Exception) {
                failed(context)
            }
        }
    }

    /** Appelé par la WebView quand une page demande un téléchargement. */
    fun start(
        context: Context,
        view: WebView,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        val name = cleanName(URLUtil.guessFileName(url, contentDisposition, mimeType))
        when {
            url.startsWith("blob:") -> {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    view.evaluateJavascript(
                        BLOB_SCRIPT
                            .replace("__URL__", JSONObject.quote(url))
                            .replace("__NAME__", JSONObject.quote(name)),
                        null,
                    )
                } else {
                    failed(context)
                }
            }
            url.startsWith("data:") -> saveDataUrl(context, url, name)
            url.startsWith("http://") || url.startsWith("https://") ->
                enqueue(context, url, userAgent, mimeType, name)
            else -> failed(context)
        }
    }

    private fun enqueue(context: Context, url: String, userAgent: String?, mimeType: String?, name: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            if (!mimeType.isNullOrEmpty()) request.setMimeType(mimeType)
            // Le téléchargement a lieu hors de la page : on lui transmet la session pour qu'il soit autorisé.
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            userAgent?.let { request.addRequestHeader("User-Agent", it) }
            context.getSystemService(DownloadManager::class.java).enqueue(request)
            saved(context, name)
        } catch (e: Exception) {
            failed(context)
        }
    }

    private fun saveDataUrl(context: Context, dataUrl: String, name: String) {
        val comma = dataUrl.indexOf(',')
        if (!dataUrl.startsWith("data:") || comma < 0) {
            failed(context)
            return
        }
        val header = dataUrl.substring(5, comma)
        val payload = dataUrl.substring(comma + 1)
        val bytes = try {
            if (header.endsWith(";base64")) {
                Base64.decode(payload, Base64.DEFAULT)
            } else {
                Uri.decode(payload).toByteArray()
            }
        } catch (e: IllegalArgumentException) {
            failed(context)
            return
        }
        val mime = header.substringBefore(';').ifEmpty { "application/octet-stream" }
        save(context, name, mime, bytes)
    }

    private fun save(context: Context, rawName: String, rawMime: String, bytes: ByteArray) {
        val name = finalName(rawName, bytes, rawMime)
        // Android ajoute « .bin » si le type annoncé ne correspond pas à l'extension : on le déduit du nom.
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: rawMime
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("insertion impossible")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IOException("écriture impossible")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                // Avant Android 10, le dossier public demande une autorisation : on utilise celui de l'appli.
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: throw IOException("dossier indisponible")
                File(dir, name).writeBytes(bytes)
            }
            saved(context, name)
        } catch (e: IOException) {
            failed(context)
        }
    }

    /**
     * Garde le nom de la page quand il a une vraie extension ; sinon (« .bin » ou rien) cherche la bonne
     * extension d'après le contenu du fichier, puis d'après son type.
     */
    private fun finalName(name: String, bytes: ByteArray, mime: String): String {
        val base = cleanName(name)
        val extension = base.substringAfterLast('.', "")
        if (base.contains('.') && extension.isNotEmpty() && !extension.equals("bin", ignoreCase = true)) return base
        val stem = base.substringBeforeLast('.', base)
        val guessed = sniffExtension(bytes)
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf { it != "bin" }
        return if (guessed != null) "$stem.$guessed" else base
    }

    /** Reconnaît les formats courants à leurs premiers octets. */
    private fun sniffExtension(bytes: ByteArray): String? {
        fun startsWith(vararg prefix: Int) =
            bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 0xFF) == prefix[it] }
        return when {
            startsWith(0x50, 0x4B, 0x03, 0x04) -> "zip"
            startsWith(0x25, 0x50, 0x44, 0x46) -> "pdf"
            startsWith(0x89, 0x50, 0x4E, 0x47) -> "png"
            startsWith(0xFF, 0xD8, 0xFF) -> "jpg"
            startsWith(0x47, 0x49, 0x46, 0x38) -> "gif"
            startsWith(0x1F, 0x8B) -> "gz"
            startsWith(0x37, 0x7A, 0xBC, 0xAF) -> "7z"
            else -> null
        }
    }

    private fun cleanName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "fichier" }

    private fun saved(context: Context, name: String) {
        Toast.makeText(context, context.getString(R.string.download_saved, name), Toast.LENGTH_LONG).show()
    }

    private fun failed(context: Context) {
        Toast.makeText(context, context.getString(R.string.download_failed), Toast.LENGTH_LONG).show()
    }
}
