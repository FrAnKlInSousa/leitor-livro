package br.com.leitor

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.ZipFile

private val Ink = Color(0xFF171713)
private val Paper = Color(0xFFF5F1E8)
private val Yellow = Color(0xFFF2C230)
private val Muted = Color(0xFF827E73)

private data class ReaderDocument(
    val title: String,
    val html: String? = null,
    val baseUrl: String? = null,
    val assetRoot: File? = null,
    val chapters: List<BookChapter> = emptyList(),
    val pdfPages: List<Bitmap> = emptyList(),
    val error: String? = null,
)

private data class BookChapter(val title: String, val anchor: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ReaderApp(::loadDocument) }
    }

    private fun loadDocument(uri: Uri, onResult: (ReaderDocument) -> Unit) {
        val title = displayName(uri)
        val mime = contentResolver.getType(uri).orEmpty()
        val ext = title.substringAfterLast('.', "").lowercase()
        when {
            mime == "application/pdf" || ext == "pdf" -> openPdf(uri, title, onResult)
            mime == "text/plain" || ext == "txt" -> runCatching {
                contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            }.onSuccess { raw -> onResult(ReaderDocument(title, textHtml(raw), "file:///android_asset/")) }
                .onFailure { onResult(ReaderDocument(title, error = "Não foi possível abrir este arquivo de texto.")) }
            mime == "text/html" || ext in setOf("html", "htm") -> runCatching {
                contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            }.onSuccess { onResult(ReaderDocument(title, Jsoup.parse(it).html(), "file:///android_asset/")) }
                .onFailure { onResult(ReaderDocument(title, error = "Não foi possível abrir este arquivo HTML.")) }
            mime == "application/epub+zip" || ext == "epub" || mime.contains("zip") -> openEpub(uri, title, onResult)
            else -> onResult(ReaderDocument(title, error = "Formato não reconhecido. Abra um EPUB, PDF, TXT ou HTML."))
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.let { return it }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Livro"
    }

    private fun openPdf(uri: Uri, title: String, onResult: (ReaderDocument) -> Unit) {
        runCatching {
            contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    (0 until renderer.pageCount).map { index ->
                        renderer.openPage(index).use { page ->
                            val width = 1000
                            Bitmap.createBitmap(width, width * page.height / page.width, Bitmap.Config.ARGB_8888).also {
                                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            }
        }.onSuccess { pages -> onResult(ReaderDocument(title, pdfPages = pages)) }
            .onFailure { onResult(ReaderDocument(title, error = "Não foi possível abrir este PDF. Tente selecionar o arquivo novamente.")) }
    }

    private fun openEpub(uri: Uri, title: String, onResult: (ReaderDocument) -> Unit) {
        runCatching {
            val root = File(cacheDir, "books/${UUID.randomUUID()}").apply { mkdirs() }
            val archive = File(root, "book.epub")
            contentResolver.openInputStream(uri)!!.use { input -> archive.outputStream().use { output -> input.copyTo(output) } }
            ZipFile(archive).use { zip ->
                val rootPath = root.canonicalPath + File.separator
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val out = File(root, entry.name).canonicalFile
                    require(out.path.startsWith(rootPath)) { "Invalid EPUB path" }
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input -> out.outputStream().use(input::copyTo) }
                    }
                }

                val container = File(root, "META-INF/container.xml")
                val containerDoc = Jsoup.parse(container, "UTF-8", "", Parser.xmlParser())
                val packagePath = containerDoc.getElementsByTag("rootfile").first()?.attr("full-path")
                    ?: error("EPUB sem arquivo de pacote")
                val packageFile = File(root, packagePath)
                val packageDoc = Jsoup.parse(packageFile, "UTF-8", "", Parser.xmlParser())
                val packageDir = packagePath.substringBeforeLast('/', "")
                val manifest = packageDoc.getElementsByTag("item").associate { item ->
                    item.attr("id") to resolvePath(packageDir, item.attr("href"))
                }
                val spinePaths = packageDoc.getElementsByTag("itemref")
                    .mapNotNull { manifest[it.attr("idref")] }
                    .filter { File(root, it).isFile }
                require(spinePaths.isNotEmpty()) { "EPUB sem capítulos no spine" }
                val chapterIdByPath = spinePaths.withIndex().associate { it.value to "chapter-${it.index}" }
                val navPath = packageDoc.getElementsByTag("item").firstOrNull {
                    "nav" in it.attr("properties").split(Regex("\\s+"))
                }?.let { manifest[it.attr("id")] }
                val chapters = navPath?.let { path ->
                    val navDoc = Jsoup.parse(File(root, path), "UTF-8", "", Parser.xmlParser())
                    val toc = navDoc.getElementsByTag("nav").firstOrNull {
                        it.attr("epub:type") == "toc" || it.attr("role") == "doc-toc" || it.id() == "toc"
                    } ?: navDoc.getElementsByTag("nav").firstOrNull()
                    toc?.select("a[href]")?.mapNotNull { link ->
                        val target = runCatching { URI(link.attr("href")) }.getOrNull() ?: return@mapNotNull null
                        val targetPath = resolvePath(path.substringBeforeLast('/', ""), target.path.orEmpty())
                        val chapterId = chapterIdByPath[targetPath] ?: return@mapNotNull null
                        val anchor = target.fragment?.let { "$chapterId--$it" } ?: chapterId
                        link.text().trim().takeIf(String::isNotBlank)?.let { BookChapter(it, anchor) }
                    }.orEmpty()
                }.orEmpty().ifEmpty {
                    spinePaths.mapIndexedNotNull { index, path ->
                        val doc = Jsoup.parse(File(root, path), "UTF-8", "", Parser.htmlParser())
                        doc.selectFirst("h1")?.text()?.trim()?.takeIf(String::isNotBlank)
                            ?.let { BookChapter(it, "chapter-$index") }
                    }
                }
                val sections = StringBuilder()
                val stylesheets = linkedSetOf<String>()
                val inlineStyles = mutableListOf<String>()

                spinePaths.forEachIndexed { index, path ->
                    val chapterFile = File(root, path)
                    val doc = Jsoup.parse(chapterFile, "UTF-8", chapterFile.toURI().toString())
                    doc.select("link[rel~=(?i)stylesheet][href]").forEach { link ->
                        val cssPath = resolvePath(path.substringBeforeLast('/', ""), link.attr("href").substringBefore('#'))
                        val cssFile = File(root, cssPath)
                        if (cssFile.isFile) stylesheets += assetUrl(cssPath)
                    }
                    doc.select("style").forEach { style ->
                        inlineStyles += rebaseCss(style.data(), path, root)
                    }
                    val chapterId = "chapter-$index"
                    val body = doc.body()
                    body.select("[id], [name]").forEach { node ->
                        node.attr("id")?.takeIf(String::isNotBlank)?.let { node.attr("id", "$chapterId--$it") }
                        node.attr("name")?.takeIf(String::isNotBlank)?.let { node.attr("name", "$chapterId--$it") }
                    }
                    body.select("img[src], source[src], audio[src], video[src], video[poster], image, use").forEach { node ->
                        val attr = when {
                            node.hasAttr("src") -> "src"
                            node.hasAttr("poster") -> "poster"
                            node.hasAttr("xlink:href") -> "xlink:href"
                            else -> "href"
                        }
                        node.attr(attr, resolveResource(path, node.attr(attr), root))
                        if (node.normalName() == "img") {
                            node.attr("loading", "lazy")
                            node.attr("decoding", "async")
                        }
                    }
                    body.select("[style]").forEach { node -> node.attr("style", rebaseCss(node.attr("style"), path, root)) }
                    body.select("a[href]").forEach { link ->
                        val href = link.attr("href")
                        if (href.startsWith('#')) {
                            link.attr("href", "#$chapterId--${href.drop(1)}")
                        } else {
                            runCatching { URI(href) }.getOrNull()?.takeIf { !it.isAbsolute }?.let { target ->
                                val resolved = resolvePath(path.substringBeforeLast('/', ""), target.path.orEmpty())
                                chapterIdByPath[resolved]?.let { targetChapter ->
                                    val anchor = target.fragment?.let { "--$it" }.orEmpty()
                                    link.attr("href", "#$targetChapter$anchor")
                                }
                            }
                        }
                    }
                    sections.append("<section id=\"").append(chapterId).append("\">")
                        .append(body.html()).append("</section>")
                }
                val headLinks = stylesheets.joinToString("") { "<link rel=\"stylesheet\" href=\"$it\">" } +
                    inlineStyles.joinToString("") { "<style>$it</style>" }
                ReaderDocument(
                    title,
                    html = htmlPage(sections.toString(), headLinks),
                    baseUrl = "https://epub.local/epub/",
                    assetRoot = root,
                    chapters = chapters,
                )
            }
        }.onSuccess(onResult).onFailure {
            onResult(ReaderDocument(title, error = "Não foi possível ler este EPUB. Verifique se o arquivo não está protegido ou corrompido."))
        }
    }

    private fun resolvePath(baseDirectory: String, reference: String): String {
        val resolved = URI(if (baseDirectory.isBlank()) "" else "$baseDirectory/").resolve(reference).normalize()
        return resolved.path.orEmpty().removePrefix("/")
    }

    private fun resolveResource(chapterPath: String, reference: String, root: File): String {
        if (reference.startsWith("data:") || reference.startsWith("http:") || reference.startsWith("https:") || reference.startsWith("file:")) return reference
        val path = resolvePath(chapterPath.substringBeforeLast('/', ""), reference.substringBefore('#').substringBefore('?'))
        val file = File(root, path)
        return if (file.isFile) assetUrl(path) else reference
    }

    private fun assetUrl(path: String): String = "https://epub.local/epub/${Uri.encode(path, "/") }"

    private fun rebaseCss(css: String, chapterPath: String, root: File): String =
        Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE).replace(css) { match ->
            val reference = match.groupValues[2].trim()
            "url(\"${resolveResource(chapterPath, reference, root)}\")"
        }

    private fun textHtml(text: String): String {
        val paragraphs = text.trim().split(Regex("\\n\\s*\\n"))
            .filter(String::isNotBlank).joinToString("") { "<p>${org.jsoup.nodes.TextNode(it.trim()).outerHtml().replace("\n", "<br>")}</p>" }
        return htmlPage(paragraphs)
    }
}

private fun htmlPage(content: String, headContent: String = ""): String = """
<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
$headContent
<style>
html { scroll-behavior: auto; background: #171713 !important; }
body { margin: 0; background: #171713 !important; color: #f5f1e8 !important; font: 20px/1.72 Georgia, serif; }
body * { color: #f5f1e8 !important; background-color: transparent !important; }
#reader { box-sizing: border-box; max-width: 820px; margin: 0 auto; padding: 30px 26px 70px; overflow-wrap: anywhere; }
p { margin: 0 0 1.15em; text-indent: 1.2em; } h1,h2,h3,h4,h5,h6 { line-height: 1.25; margin: 1.4em 0 .7em; }
img,svg,video { max-width: 100%; height: auto; } table { max-width:100%; } a, a * { color: #f2c230 !important; }
blockquote { margin-left: 1em; padding-left: 1em; border-left: 3px solid #d1ad35; }
</style></head><body><main id="reader">$content</main>
<script>
(() => {
  let speed = 0, previous = 0, frame = 0;
  const tick = now => {
    if (speed <= 0) { frame = 0; previous = 0; return; }
    if (previous) window.scrollBy(0, speed * (now - previous) / 1000);
    previous = now;
    if (window.scrollY + window.innerHeight >= document.documentElement.scrollHeight - 1) {
      speed = 0; frame = 0; return;
    }
    frame = requestAnimationFrame(tick);
  };
  window.setReaderSpeed = value => {
    speed = Math.max(0, Number(value) || 0);
    if (speed === 0) { cancelAnimationFrame(frame); frame = 0; previous = 0; }
    else if (!frame) { previous = 0; frame = requestAnimationFrame(tick); }
  };
})();
</script></body></html>
"""

private fun missingAsset() = WebResourceResponse(
    "application/octet-stream",
    null,
    ByteArrayInputStream(ByteArray(0)),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderApp(open: (Uri, (ReaderDocument) -> Unit) -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("O livro começa aqui") }
    var html by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("file:///android_asset/") }
    var assetRoot by remember { mutableStateOf<File?>(null) }
    var chapters by remember { mutableStateOf<List<BookChapter>>(emptyList()) }
    var pdfPages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var speed by remember { mutableFloatStateOf(0f) }
    var running by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canNavigateBack by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) open(uri) { document ->
            title = document.title
            html = document.html.orEmpty()
            baseUrl = document.baseUrl ?: "file:///android_asset/"
            assetRoot = document.assetRoot
            chapters = document.chapters
            pdfPages = document.pdfPages
            notice = document.error
            running = false
            canNavigateBack = false
            scope.launch { scroll.scrollTo(0) }
        }
    }
    LaunchedEffect(running, speed, html, pdfPages, webView) {
        if (html.isNotBlank()) {
            val pixelsPerSecond = if (running) 34f * speed / 20f else 0f
            webView?.evaluateJavascript("window.setReaderSpeed?.($pixelsPerSecond)", null)
        } else while (running && speed > 0f && isActive && pdfPages.isNotEmpty()) {
            delay(16)
            val delta = 34f * speed / 20f * 0.016f
            if (pdfPages.isNotEmpty()) scroll.scrollBy(delta)
            if (pdfPages.isNotEmpty() && scroll.value >= scroll.maxValue) running = false
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Yellow, background = Ink, surface = Ink, onSurface = Paper)) {
      ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Color(0xFF22221D), modifier = Modifier.width(320.dp)) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp)) {
                    Text("ENTRELINHAS", color = Yellow, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(title, color = Paper, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("CAPÍTULOS", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
                }
                HorizontalDivider(color = Color(0xFF393831))
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    itemsIndexed(chapters) { _, chapter ->
                        NavigationDrawerItem(
                            label = { Text(chapter.title, color = Paper, fontSize = 14.sp) },
                            selected = false,
                            onClick = {
                                scope.launch {
                                    drawerState.close()
                                    val destination = JSONObject.quote("#${chapter.anchor}")
                                    webView?.evaluateJavascript("window.location.hash=$destination", null)
                                }
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent),
                        )
                    }
                }
            }
        },
      ) {
        Scaffold(containerColor = Ink, bottomBar = {
            Column(Modifier.fillMaxWidth().background(Color(0xFF22221D)).padding(horizontal = 22.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { running = !running; if (speed == 0f) speed = 20f }) {
                        Icon(if (running) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Yellow)
                    }
                    Text(if (speed == 0f) "Rolagem automática desligada" else "Velocidade", color = Paper, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(if (speed == 0f) "OFF" else "${(speed / 20f).let { "%.1f".format(it) }}×", color = Yellow, fontWeight = FontWeight.Bold)
                }
                Slider(value = speed, onValueChange = { speed = it; if (it == 0f) running = false }, valueRange = 0f..120f, colors = SliderDefaults.colors(thumbColor = Yellow, activeTrackColor = Yellow, inactiveTrackColor = Color(0xFF48473E)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Toque em play para começar", color = Muted, fontSize = 11.sp)
                    Text("${(speed / 20f).let { "%.1f".format(it) }} linhas/s", color = Muted, fontSize = 11.sp)
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ENTRELINHAS", color = Yellow, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text(title, color = Paper, fontSize = 13.sp, maxLines = 1)
                    }
                    IconButton(enabled = chapters.isNotEmpty(), onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, "Capítulos", tint = if (chapters.isNotEmpty()) Paper else Muted)
                    }
                    IconButton(enabled = canNavigateBack, onClick = { webView?.goBack() }) {
                        Icon(Icons.Default.ArrowBack, "Voltar ao ponto anterior", tint = if (canNavigateBack) Yellow else Muted)
                    }
                    IconButton(onClick = { picker.launch(arrayOf("application/epub+zip", "application/pdf", "text/plain", "text/html", "*/*")) }) {
                        Icon(Icons.Default.FolderOpen, "Abrir livro", tint = Paper)
                    }
                    IconButton(onClick = {
                        running = false
                        if (pdfPages.isNotEmpty()) scope.launch { scroll.animateScrollTo(0) }
                        else webView?.evaluateJavascript("window.scrollTo({top:0,behavior:'smooth'})", null)
                    }) { Icon(Icons.Default.VerticalAlignTop, "Início", tint = Paper) }
                }
                HorizontalDivider(color = Color(0xFF393831))
                if (html.isBlank() && pdfPages.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
                        Text("LEITURA SEM PRESSA", color = Yellow, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                        Spacer(Modifier.height(18.dp))
                        Text("Seu próximo\nlivro, em fluxo.", color = Paper, fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(14.dp))
                        Text("Importe um EPUB ou PDF para ler em fluxo contínuo. Ajuste a rolagem automática no seu ritmo.", color = Muted, fontSize = 15.sp, lineHeight = 23.sp)
                        Spacer(Modifier.height(28.dp))
                        Button(onClick = { picker.launch(arrayOf("application/epub+zip", "application/pdf", "text/plain", "text/html", "*/*")) }, colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = Ink)) {
                            Icon(Icons.Default.MenuBook, null); Spacer(Modifier.width(8.dp)); Text("Abrir um livro", fontWeight = FontWeight.Bold)
                        }
                        notice?.let { Spacer(Modifier.height(14.dp)); Text(it, color = Yellow, fontSize = 13.sp) }
                        Spacer(Modifier.height(38.dp))
                        Text("EPUB  ·  PDF  ·  TXT  ·  HTML", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
                    }
                } else if (pdfPages.isNotEmpty()) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                        pdfPages.forEachIndexed { index, bitmap ->
                            Image(bitmap.asImageBitmap(), "Página ${index + 1}", Modifier.fillMaxWidth().padding(vertical = 5.dp), contentScale = ContentScale.FillWidth)
                        }
                    }
                } else {
                    val context = LocalContext.current
                    val readerClient = remember(assetRoot) {
                        object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val target = request.url
                                if (target.scheme in setOf("http", "https", "mailto") && target.host != "epub.local") {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, target)) }
                                    return true
                                }
                                return false
                            }

                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                if (request.url.host != "epub.local") {
                                    return if (request.url.scheme == "http" || request.url.scheme == "https") missingAsset()
                                    else super.shouldInterceptRequest(view, request)
                                }
                                val root = assetRoot ?: return missingAsset()
                                return runCatching {
                                    val relative = Uri.decode(request.url.encodedPath.orEmpty()).removePrefix("/epub/")
                                    val rootPath = root.canonicalPath + File.separator
                                    val file = File(root, relative).canonicalFile
                                    if (!file.path.startsWith(rootPath) || !file.isFile) return@runCatching missingAsset()
                                    val extension = file.extension.lowercase()
                                    val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                                        ?: when (extension) {
                                            "xhtml" -> "application/xhtml+xml"
                                            "opf" -> "application/oebps-package+xml"
                                            "ncx" -> "application/x-dtbncx+xml"
                                            else -> "application/octet-stream"
                                        }
                                    val encoding = if (type.startsWith("text/") || type.contains("xml") || type.contains("javascript")) "UTF-8" else null
                                    WebResourceResponse(type, encoding, file.inputStream())
                                }.getOrElse { missingAsset() }
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                canNavigateBack = view.canGoBack()
                            }
                        }
                    }
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                setBackgroundColor(AndroidColor.rgb(23, 23, 19))
                                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.javaScriptEnabled = true
                                settings.loadsImagesAutomatically = true
                                webViewClient = readerClient
                                webView = this
                            }
                        },
                        update = { view ->
                            view.webViewClient = readerClient
                            val marker = "${html.hashCode()}|$baseUrl|${assetRoot?.absolutePath}"
                            if (view.tag != marker) {
                                view.tag = marker
                                view.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
                            }
                        },
                    )
                }
            }
        }
      }
    }
}
