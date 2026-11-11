package dev.lexicon.ide.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.testTag
import dev.lexicon.domain.*
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

/** A normal first-party page in this app. No privileged JS bridge, hidden payload extraction or external browser launch. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun InAppArticlePage(article: Article, aiAllowed: Boolean, onBack: () -> Unit, onCapture: (Capture) -> Unit) {
    var page by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var focus by rememberSaveable(article.id) { mutableStateOf(false) }
    var currentUrl by rememberSaveable(article.id) { mutableStateOf(article.url) }
    var savedScrollY by rememberSaveable(article.id) { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    val surface = MaterialTheme.colorScheme.surface.toArgb()
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    val currentCapture by rememberUpdatedState(onCapture)
    fun back() { if (page?.canGoBack() == true) page?.goBack() else onBack() }
    PredictiveBackHandler(enabled = canGoBack) { progress ->
        try { progress.collect { }; page?.goBack() } catch (_: CancellationException) { }
    }
    fun updateFocus(view: WebView?, enabled: Boolean) {
        view?.evaluateJavascript(pageFocusScript(enabled, surface, foreground), null)
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().testTag("in_app_article_page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = ::back) { Text("← 返回") }
            TextButton(onClick = { focus = !focus; updateFocus(page, focus) }) { Text(if (focus) "退出聚焦" else "段落聚焦") }
            TextButton(onClick = { failed = false; page?.reload() }) { Text("重载") }
        }
        if (loading) Text("加载中…", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (failed) Text("页面未能加载，请重试。", Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
        AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { context ->
            object : WebView(context) {
                override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? {
                    val wrapped = object : ActionMode.Callback {
                        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                            val created = callback.onCreateActionMode(mode, menu)
                            menu.add(0, 0x1e710, 0, "查词 / 捕获")
                            return created
                        }
                        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = callback.onPrepareActionMode(mode, menu)
                        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                            if (item.itemId != 0x1e710) return callback.onActionItemClicked(mode, item)
                            val selectedUrl = url.orEmpty()
                            evaluateJavascript(SELECTION_SCRIPT) { raw ->
                                runCatching {
                                    val encoded = JSONTokener(raw).nextValue() as? String ?: return@runCatching
                                    val value = JSONObject(encoded)
                                    val text = value.optString("text").take(CaptureLimits.SELECTED_TEXT).trim()
                                    if (text.isBlank()) return@runCatching
                                    val sentence = value.optString("sentence").take(CaptureLimits.SENTENCE)
                                    val sameArticle = sameArticlePage(selectedUrl, article.url)
                                    currentCapture(Capture(UUID.randomUUID().toString(), articleId = article.id.takeIf { sameArticle },
                                        target = text.takeIf { it.length <= CaptureLimits.TARGET }.orEmpty(), selectedText = text,
                                        sentence = sentence, contextOrigin = if (sentence.isBlank()) ContextOrigin.MISSING else ContextOrigin.ORIGINAL,
                                        sourceRef = if (sameArticle) SourceRef(article.title, article.sourceName, article.url, article.publishedAt)
                                            else SourceRef(url = selectedUrl),
                                        createdAt = System.currentTimeMillis(), aiAllowed = aiAllowed && sameArticle))
                                }
                                mode.finish()
                            }
                            return true
                        }
                        override fun onDestroyActionMode(mode: ActionMode) { callback.onDestroyActionMode(mode) }
                    }
                    return super.startActionMode(wrapped, type)
                }
            }.apply {
                setBackgroundColor(surface)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setSupportMultipleWindows(false)
                settings.javaScriptCanOpenWindowsAutomatically = false
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                var restoring = savedScrollY > 0
                setOnScrollChangeListener { _, _, y, _, _ -> if (!restoring) savedScrollY = y }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.scheme !in listOf("https", "http")
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loading = true; failed = false; currentUrl = url ?: article.url }
                    override fun onPageFinished(view: WebView, url: String?) {
                        loading = false; canGoBack = view.canGoBack(); updateFocus(view, focus)
                        if (restoring) { view.scrollTo(0, savedScrollY); restoring = false }
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) { loading = false; failed = true }
                    }
                }
                page = this
                loadUrl(currentUrl)
            }
        }, onRelease = { it.stopLoading(); it.destroy(); page = null })
    }
}

private const val SELECTION_SCRIPT = """(function(){
 const s=window.getSelection(); if(!s||!s.rangeCount)return JSON.stringify({text:'',sentence:''});
 const r=s.getRangeAt(0), text=s.toString(); let n=r.commonAncestorContainer;
 if(n.nodeType!==1)n=n.parentElement;
 const p=n.closest('p,li,blockquote') || n;
 let sentence=(p && p.innerText || '');
 if(sentence.length>6000 || !sentence.includes(text))sentence='';
 return JSON.stringify({text:text.substring(0,12000),sentence:sentence});
})()"""

/** Focus changes only presentation of currently rendered paragraphs; website access and login remain intact. */
internal fun pageFocusScript(enabled: Boolean, background: Int, foreground: Int): String {
    fun rgb(value: Int) = "#%06x".format(value and 0xffffff)
    return """(function(){
      if(window.__lexiconFocusCleanup)window.__lexiconFocusCleanup();
      if(!${enabled})return;
      const root=document.querySelector('article,[itemprop=articleBody],main');if(!root)return;
      const all=Array.from(root.querySelectorAll('p,li,blockquote')).filter(p=>p.innerText.length>45&&p.getClientRects().length&&!p.querySelector('p,li,blockquote'));
      if(!all.length)return;
      const style=document.createElement('style'); style.textContent='.lx-dim{opacity:.48;transition:opacity 280ms ease,background-color 280ms ease;}.lx-active{opacity:1;background-color:rgba(128,128,128,.07);border-radius:6px;}@media(prefers-reduced-motion:reduce){.lx-dim{transition:none;}}';document.head.appendChild(style);
      all.forEach(p=>p.classList.add('lx-dim'));let active=null,timer=null;
      function update(){if(window.getSelection().toString())return;const y=innerHeight*.42;
        const visible=all.filter(p=>{const r=p.getBoundingClientRect();return r.bottom>0&&r.top<innerHeight;});
        let best=visible.reduce((a,p)=>{const r=p.getBoundingClientRect(),d=Math.max(r.top-y,y-r.bottom,0);return !a||d<a.d?{p,d}:a;},null);
        if(!best)return; if(active){const r=active.getBoundingClientRect();if(r.top-22<y&&r.bottom+22>y)return;active.classList.remove('lx-active');}
        active=best.p;active.classList.add('lx-active');}
      function scroll(){clearTimeout(timer);timer=setTimeout(update,75);}addEventListener('scroll',scroll,{passive:true});update();
      window.__lexiconFocusCleanup=()=>{removeEventListener('scroll',scroll);clearTimeout(timer);all.forEach(p=>p.classList.remove('lx-dim','lx-active'));style.remove();};
    })()"""
}

internal fun sameArticlePage(first: String, second: String): Boolean = runCatching {
    UrlCanonicalizer.canonicalize(first) == UrlCanonicalizer.canonicalize(second)
}.getOrDefault(false)
