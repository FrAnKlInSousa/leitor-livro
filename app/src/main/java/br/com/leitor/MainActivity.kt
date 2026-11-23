package br.com.leitor

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.net.URI
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
    val pdfPages: List<Bitmap> = emptyList(),
    val error: String? = null,
)

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
                val sections = StringBuilder()
                val stylesheets = linkedSetOf<String>()
                val inlineStyles = mutableListOf<String>()

                spinePaths.forEachIndexed { index, path ->
                    val chapterFile = File(root, path)
                    val doc = Jsoup.parse(chapterFile, "UTF-8", chapterFile.toURI().toString())
                    doc.select("link[rel~=(?i)stylesheet][href]").forEach { link ->
                        val cssPath = resolvePath(path.substringBeforeLast('/', ""), link.attr("href").substringBefore('#'))
                        val cssFile = File(root, cssPath)
                        if (cssFile.isFile) stylesheets += cssFile.toURI().toASCIIString()
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
                    body.select("img[src], source[src], audio[src], video[src], video[poster], image[href], use[href]").forEach { node ->
                        val attr = when {
                            node.hasAttr("src") -> "src"
                            node.hasAttr("poster") -> "poster"
                            else -> "href"
                        }
                        node.attr(attr, resolveResource(path, node.attr(attr), root))
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
                ReaderDocument(title, html = htmlPage(sections.toString(), headLinks), baseUrl = root.toURI().toASCIIString())
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
        return if (file.isFile) file.toURI().toASCIIString() else reference
    }

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
html { scroll-behavior: auto; } body { margin: 0; background: #f5f1e8; color: #25241f; font: 20px/1.72 Georgia, serif; }
#reader { box-sizing: border-box; max-width: 820px; margin: 0 auto; padding: 30px 26px 70px; overflow-wrap: anywhere; }
p { margin: 0 0 1.15em; text-indent: 1.2em; } h1,h2,h3,h4,h5,h6 { line-height: 1.25; margin: 1.4em 0 .7em; }
img,svg,video { max-width: 100%; height: auto; } table { max-width:100%; } a { color: #9a7300; }
blockquote { margin-left: 1em; padding-left: 1em; border-left: 3px solid #d1ad35; }
</style></head><body><main id="reader">$content</main></body></html>
"""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderApp(open: (Uri, (ReaderDocument) -> Unit) -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("O livro começa aqui") }
    var html by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("file:///android_asset/") }
    var pdfPages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var speed by remember { mutableFloatStateOf(0f) }
    var running by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) open(uri) { document ->
            title = document.title
            html = document.html.orEmpty()
            baseUrl = document.baseUrl ?: "file:///android_asset/"
            pdfPages = document.pdfPages
            notice = document.error
            running = false
            scope.launch { scroll.scrollTo(0) }
        }
    }
    LaunchedEffect(running, speed, html, pdfPages) {
        while (running && speed > 0f && isActive && (html.isNotBlank() || pdfPages.isNotEmpty())) {
            delay(16)
            val delta = 34f * speed / 20f * 0.016f
            if (pdfPages.isNotEmpty()) scroll.scrollBy(delta)
            else webView?.evaluateJavascript("(()=>{window.scrollBy(0,$delta);return window.scrollY+window.innerHeight>=document.documentElement.scrollHeight-1})()") { atEnd ->
                if (atEnd == "true") running = false
            }
            if (pdfPages.isNotEmpty() && scroll.value >= scroll.maxValue) running = false
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Yellow, background = Ink, surface = Ink, onSurface = Paper)) {
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
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                setBackgroundColor(AndroidColor.rgb(245, 241, 232))
                                settings.allowFileAccess = true
                                settings.allowContentAccess = true
                                settings.javaScriptEnabled = true
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        val target = request.url
                                        if (target.scheme in setOf("http", "https", "mailto")) {
                                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, target)) }
                                            return true
                                        }
                                        return false
                                    }
                                }
                                webView = this
                            }
                        },
                        update = { view ->
                            val marker = "${html.hashCode()}|$baseUrl"
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
