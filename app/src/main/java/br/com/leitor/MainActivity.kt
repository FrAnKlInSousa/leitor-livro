package br.com.leitor

import android.net.Uri
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import java.util.zip.ZipInputStream

private val Ink = Color(0xFF171713)
private val Paper = Color(0xFFF5F1E8)
private val Yellow = Color(0xFFF2C230)
private val Muted = Color(0xFF827E73)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ReaderApp { uri, name, onResult -> loadDocument(uri, name, onResult) } }
    }

    private fun loadDocument(uri: Uri, name: String, onResult: (String?, List<Bitmap>?, String?) -> Unit) {
        if (name.endsWith(".pdf", true)) {
            runCatching {
                contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        (0 until renderer.pageCount).map { index ->
                            renderer.openPage(index).use { page ->
                                val width = 900
                                Bitmap.createBitmap(width, width * page.height / page.width, Bitmap.Config.ARGB_8888).also {
                                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            }
                        }
                    }
                }
            }.onSuccess { onResult(null, it, null) }.onFailure { onResult(null, null, "Não foi possível abrir este PDF.") }
            return
        }
        if (name.endsWith(".txt", true) || name.endsWith(".html", true) || name.endsWith(".htm", true)) {
            runCatching {
                contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            }.onSuccess { raw ->
                val text = if (name.endsWith(".html", true) || name.endsWith(".htm", true)) Jsoup.parse(raw).text() else raw
                onResult(text.ifBlank { null }, null, if (text.isBlank()) "Este arquivo não tem texto para exibir." else null)
            }.onFailure { onResult(null, null, "Não foi possível abrir este arquivo de texto.") }
            return
        }
        runCatching {
            contentResolver.openInputStream(uri)!!.use { input ->
                ZipInputStream(input).use { zip ->
                    buildString {
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (!entry.isDirectory && entry.name.endsWith(".html", true).or(entry.name.endsWith(".xhtml", true))) {
                                val html = zip.readBytes().toString(Charsets.UTF_8)
                                val text = Jsoup.parse(html).text()
                                if (text.isNotBlank()) append("\n\n").append(text)
                            }
                        }
                    }
                }
            }
        }.onSuccess { text -> onResult(text.ifBlank { null }, null, if (text.isBlank()) "Não encontrei capítulos legíveis neste EPUB." else null) }
            .onFailure { onResult(null, null, "Não foi possível abrir este arquivo. Tente um EPUB ou PDF válido.") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderApp(open: (Uri, String, (String?, List<Bitmap>?, String?) -> Unit) -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("O livro começa aqui") }
    var book by remember { mutableStateOf("") }
    var pdfPages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var speed by remember { mutableFloatStateOf(0f) }
    var running by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Livro"
            title = name.substringAfterLast(':').substringAfterLast('/')
            open(uri, title) { text, pages, error ->
                book = text.orEmpty(); pdfPages = pages.orEmpty(); notice = error
                scope.launch { scroll.scrollTo(0) }
            }
        }
    }
    LaunchedEffect(running, speed) {
        while (running && speed > 0f && isActive && (book.isNotBlank() || pdfPages.isNotEmpty())) {
            delay(16)
            scroll.scrollBy(34f * speed / 20f * 0.016f)
            if (scroll.value >= scroll.maxValue) running = false
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
                    IconButton(onClick = { scroll.animateScrollTo(0); running = false }) { Icon(Icons.Default.VerticalAlignTop, "Início", tint = Paper) }
                }
                HorizontalDivider(color = Color(0xFF393831))
                if (book.isBlank()) {
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
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                        if (pdfPages.isNotEmpty()) {
                            pdfPages.forEachIndexed { index, bitmap ->
                                Image(bitmap.asImageBitmap(), "Página ${index + 1}", Modifier.fillMaxWidth().padding(vertical = 5.dp), contentScale = ContentScale.FillWidth)
                            }
                        } else {
                            Column(Modifier.padding(horizontal = 26.dp, vertical = 30.dp)) {
                                Text(title.substringBeforeLast('.'), color = Yellow, fontSize = 12.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(24.dp))
                                Text(book, color = Paper, fontFamily = FontFamily.Serif, fontSize = 20.sp, lineHeight = 34.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
