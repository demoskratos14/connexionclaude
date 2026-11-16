package com.aventure.connexion

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/** Efface tout ce que les pages web ont pu laisser sur le téléphone (cookies, stockage local). */
object WebData {
    fun wipe() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()
    }
}

/**
 * Une session « privée » : deux WebView (Claude et Proton Mail) sans cache ni historique, dont tout
 * est effacé au démarrage et à la fermeture. Le même téléphone ne garde donc aucune connexion.
 */
class WebSession(
    context: Context,
    private val credentials: Credentials,
    /** Appelé quand un lien de connexion Claude reçu par mail est envoyé vers l'onglet Claude. */
    private val onClaudeLink: () -> Unit,
    /**
     * Appelé quand une page demande à choisir un fichier à envoyer (pièce jointe, image...).
     * Doit ouvrir le sélecteur de fichiers et renvoyer true, ou renvoyer false s'il ne peut pas.
     */
    private val onChooseFile: (ValueCallback<Array<Uri>>, Intent) -> Boolean,
) {
    private val chromeClient = object : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: WebChromeClient.FileChooserParams?,
        ): Boolean {
            if (filePathCallback == null || fileChooserParams == null) return false
            return onChooseFile(filePathCallback, fileChooserParams.createIntent())
        }
    }

    val claudeView: WebView = newWebView(context, ClaudeClient(), chromeClient)
    val mailView: WebView = newWebView(context, ProtonClient(), chromeClient)

    init {
        // Téléchargements : chaque site n'a le droit d'envoyer des fichiers à l'appli que depuis sa propre adresse.
        Downloads.installBridge(context, claudeView, "https://claude.ai")
        Downloads.installBridge(context, mailView, "https://mail.proton.me")
    }

    private var claudeFilled = false
    private var protonFilled = false

    private var mailLoaded = false

    fun start() {
        WebData.wipe()
        claudeView.loadUrl(CLAUDE_URL)
    }

    /**
     * Charge Proton Mail la première fois que son onglet est affiché (appel sans effet ensuite) :
     * chargée pendant qu'elle est masquée, la page prenait une taille nulle et sa liste de messages
     * restait blanche.
     */
    fun openMail() {
        if (mailLoaded) return
        mailLoaded = true
        mailView.loadUrl(MAIL_URL)
    }

    fun close() {
        for (view in listOf(claudeView, mailView)) {
            view.stopLoading()
            view.clearCache(true)
            view.clearHistory()
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        WebData.wipe()
    }

    private inner class ClaudeClient : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            val uri = Uri.parse(url ?: return)
            if (!claudeFilled && uri.host == "claude.ai" && uri.path.orEmpty().startsWith("/login")) {
                claudeFilled = true
                view?.evaluateJavascript(Scripts.claudeLogin(credentials.email), null)
            }
        }
    }

    private inner class ProtonClient : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            val uri = Uri.parse(url ?: return)
            if (!protonFilled && uri.host == "account.proton.me") {
                protonFilled = true
                view?.evaluateJavascript(
                    Scripts.protonLogin(credentials.email, credentials.password),
                    null,
                )
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            if (request == null || !request.isForMainFrame) return false
            val uri = request.url
            if (uri.scheme != "https" && uri.scheme != "http") return true
            val host = uri.host.orEmpty()
            return when {
                // Lien de connexion Claude cliqué dans le mail : on le charge dans l'onglet Claude.
                isHost(host, "claude.ai") -> {
                    claudeView.loadUrl(uri.toString())
                    onClaudeLink()
                    true
                }
                isHost(host, "proton.me") || isHost(host, "protonmail.com") -> false
                // Tout le reste est bloqué : l'onglet Proton Mail ne sort pas de Proton.
                else -> true
            }
        }
    }

    private fun isHost(host: String, domain: String) = host == domain || host.endsWith(".$domain")

    private companion object {
        const val CLAUDE_URL = "https://claude.ai/login"
        const val MAIL_URL = "https://mail.proton.me"

        @SuppressLint("SetJavaScriptEnabled")
        fun newWebView(
            context: Context,
            client: WebViewClient,
            chrome: WebChromeClient,
        ): WebView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = client
            webChromeClient = chrome
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                Downloads.start(context, this, url, userAgent, contentDisposition, mimeType)
            }
        }
    }
}

/** Scripts qui remplissent les formulaires de connexion (les pages sont chargées après le clic). */
object Scripts {
    /** Mettre à false pour remplir l'adresse sans valider : il restera à appuyer sur « Continuer ». */
    private const val AUTO_SUBMIT = true

    fun claudeLogin(email: String): String =
        CLAUDE_SCRIPT
            .replace("__EMAIL__", JSONObject.quote(email))
            .replace("__SUBMIT__", AUTO_SUBMIT.toString())

    fun protonLogin(email: String, password: String): String =
        PROTON_SCRIPT
            .replace("__EMAIL__", JSONObject.quote(email))
            .replace("__PASSWORD__", JSONObject.quote(password))
            .replace("__SUBMIT__", AUTO_SUBMIT.toString())

    // Les champs React ignorent un simple « value = ... » : on passe par le setter natif, puis on
    // déclenche les événements input/change. On attend que la page ait fini de s'afficher (30 s max).
    private const val SET_VALUE = """
        function setValue(el, value) {
            var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
            setter.call(el, value);
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
        }
    """

    private const val CLAUDE_SCRIPT = """
        (function () {
            $SET_VALUE
            var tries = 0;
            var timer = setInterval(function () {
                tries++;
                var field = document.querySelector('input[type="email"], input[name="email"]');
                if (field) {
                    clearInterval(timer);
                    field.focus();
                    setValue(field, __EMAIL__);
                    if (__SUBMIT__) {
                        setTimeout(function () {
                            try {
                                var form = field.form;
                                if (form && form.requestSubmit) {
                                    form.requestSubmit();
                                } else {
                                    var button = document.querySelector('button[type="submit"]');
                                    if (button) { button.click(); }
                                }
                            } catch (e) {}
                        }, 400);
                    }
                } else if (tries > 120) {
                    clearInterval(timer);
                }
            }, 250);
        })();
    """

    private const val PROTON_SCRIPT = """
        (function () {
            $SET_VALUE
            var password = __PASSWORD__;
            var userDone = false;
            var passDone = false;
            var tries = 0;
            function submit() {
                if (!__SUBMIT__) { return; }
                setTimeout(function () {
                    var button = document.querySelector('button[type="submit"]');
                    if (button) { button.click(); }
                }, 400);
            }
            var timer = setInterval(function () {
                tries++;
                var user = document.querySelector('input#username');
                var pass = document.querySelector('input#password');
                if (user && !userDone) {
                    userDone = true;
                    setValue(user, __EMAIL__);
                    if (pass && password.length > 0) {
                        passDone = true;
                        setValue(pass, password);
                    }
                    submit();
                } else if (pass && userDone && !passDone && password.length > 0) {
                    passDone = true;
                    setValue(pass, password);
                    submit();
                }
                if (tries > 120 || (userDone && passDone)) {
                    clearInterval(timer);
                }
            }, 250);
        })();
    """
}
