package io.github.andreadp.trenolibero.data

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.Calendar
import java.util.UUID

@Serializable
data class TicketTrain(val number: String, val category: String = "")

@Serializable
data class Ticket(
    val id: String,
    val importedAt: Long,
    val trains: List<TicketTrain> = emptyList(),
    /** Data del viaggio "dd/MM/yyyy" (vuota se non riconosciuta). */
    val date: String = "",
    val from: String = "",
    val to: String = "",
    val departure: String = "",
    val arrival: String = "",
    val coach: String = "",
    val seat: String = "",
    val pnr: String = "",
    val passenger: String = "",
    val hasPdf: Boolean = true,
    /** "pdf" oppure "image" (screenshot o foto del biglietto). */
    val kind: String = "pdf",
    /** Codice a barre trovato (QR_CODE, AZTEC…), vuoto se non trovato. */
    val codeFormat: String = "",
    val codeText: String = "",
) {
    val isImage: Boolean get() = kind == "image"
    val isScan: Boolean get() = kind == "scan"

    val title: String
        get() = when {
            from.isNotEmpty() || to.isNotEmpty() -> "$from → $to"
            trains.isNotEmpty() -> trains.joinToString(" + ") { "${it.category} ${it.number}".trim() }
            else -> "Biglietto"
        }

    /** Mezzanotte (ora di Roma) del giorno del viaggio, come richiesto da andamentoTreno. */
    val dateMs: Long?
        get() {
            val m = Regex("(\\d{1,2})/(\\d{1,2})/(\\d{4})").matchEntire(date) ?: return null
            return Calendar.getInstance(ViaggiaTrenoApi.ROME).apply {
                clear()
                set(m.groupValues[3].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[1].toInt(), 0, 0, 0)
            }.timeInMillis
        }
}

/** Biglietti importati: PDF copiati nello spazio privato dell'app + dati riconosciuti in tickets.json. */
class TicketStore(private val context: Context) {
    private val dir = File(context.filesDir, "tickets").apply { mkdirs() }
    private val index = File(dir, "tickets.json")
    private val ser = ListSerializer(Ticket.serializer())
    private val _tickets = MutableStateFlow(read())
    val tickets: StateFlow<List<Ticket>> = _tickets

    private fun read(): List<Ticket> =
        runCatching { ViaggiaTrenoApi.json.decodeFromString(ser, index.readText()) }.getOrDefault(emptyList())

    private fun write(list: List<Ticket>) {
        val sorted = list.sortedByDescending { it.dateMs ?: it.importedAt }
        index.writeText(ViaggiaTrenoApi.json.encodeToString(ser, sorted))
        _tickets.value = sorted
    }

    fun pdf(id: String) = File(dir, "$id.pdf")
    fun image(id: String) = File(dir, "$id.img")
    fun code(id: String) = File(dir, "${id}_code.png")
    fun get(id: String) = _tickets.value.firstOrNull { it.id == id }

    fun save(t: Ticket) = write(_tickets.value.filterNot { it.id == t.id } + t)

    fun delete(id: String) {
        pdf(id).delete()
        image(id).delete()
        code(id).delete()
        write(_tickets.value.filterNot { it.id == id })
    }

    /**
     * Importa un PDF o un'immagine: copia il file, cerca il codice a barre da mostrare al controllore
     * e (per i PDF) estrae il testo per riconoscere i dati del viaggio.
     */
    suspend fun import(uri: Uri, stations: StationIndex): Ticket = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val mime = context.contentResolver.getType(uri).orEmpty()
        val head = context.contentResolver.openInputStream(uri)?.use { it.readNBytesCompat(5) }
            ?: throw ApiException("Impossibile leggere il file")
        val isPdf = mime == "application/pdf" || String(head, Charsets.ISO_8859_1) == "%PDF-"
        val file = if (isPdf) pdf(id) else image(id)
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: throw ApiException("Impossibile leggere il file")
        val names = runCatching { stations.all().map { it.name } }.getOrDefault(emptyList())

        val (parsed, found) = if (isPdf) {
            val text = try {
                PDFBoxResourceLoader.init(context.applicationContext)
                PDDocument.load(file).use { PDFTextStripper().getText(it) }
            } catch (e: Exception) {
                file.delete()
                throw ApiException("Il file non è un PDF leggibile")
            }
            TicketParser.parse(text, names) to findPdfCode(file)
        } else {
            val bmp = decodeImage(file) ?: run {
                file.delete(); throw ApiException("Formato non supportato: usa un PDF o un'immagine (JPG, PNG…)")
            }
            val found = TicketCode.find(bmp)
            // Senza OCR i dati si inseriscono a mano; se il codice contiene testo leggibile lo si analizza
            TicketParser.parse(found?.text.orEmpty(), names) to found
        }
        found?.let { f -> code(id).outputStream().use { f.image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        val t = parsed.copy(
            id = id,
            importedAt = System.currentTimeMillis(),
            hasPdf = isPdf,
            kind = if (isPdf) "pdf" else "image",
            codeFormat = found?.format.orEmpty(),
            codeText = found?.text.orEmpty(),
        )
        save(t)
        t
    }

    /** Codice del biglietto PDF: prima tra le immagini incorporate, poi sulle pagine renderizzate. */
    private fun findPdfCode(file: File): FoundCode? {
        runCatching {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(file).use { TicketCode.fromPdfImages(it) }
        }.getOrNull()?.let { return it }
        val pages = runCatching { TicketCode.renderPdf(file, 2000, maxPages = 3) }.getOrDefault(emptyList())
        return TicketCode.find(pages)
    }

    /**
     * Biglietto da scansione con la fotocamera: il codice viene ridisegnato nitido dal contenuto letto
     * (stessi dati dell'originale) e il testo, se leggibile, viene analizzato come quello di un PDF.
     */
    suspend fun addScanned(format: String, text: String, stations: StationIndex): Ticket = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val names = runCatching { stations.all().map { it.name } }.getOrDefault(emptyList())
        TicketCode.render(format, text)?.let { bmp ->
            code(id).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        val t = TicketParser.parse(text, names).copy(
            id = id, importedAt = System.currentTimeMillis(), hasPdf = false, kind = "scan",
            codeFormat = format, codeText = text,
        )
        save(t)
        t
    }

    /** Rilegge i dati dal PDF (es. dopo un aggiornamento del riconoscimento), mantenendo il codice. */
    suspend fun reparse(id: String, stations: StationIndex): Ticket? = withContext(Dispatchers.IO) {
        val t = get(id) ?: return@withContext null
        if (t.kind != "pdf") return@withContext t
        val names = runCatching { stations.all().map { it.name } }.getOrDefault(emptyList())
        PDFBoxResourceLoader.init(context.applicationContext)
        val text = PDDocument.load(pdf(id)).use { PDFTextStripper().getText(it) }
        val p = TicketParser.parse(text, names)
        p.copy(id = t.id, importedAt = t.importedAt, kind = t.kind, codeFormat = t.codeFormat, codeText = t.codeText)
            .also { save(it) }
    }

    /** Cerca (di nuovo) il codice di un biglietto già importato e lo salva. */
    fun findCode(id: String): android.graphics.Bitmap? {
        val t = get(id) ?: return null
        val found = when {
            t.isScan -> return TicketCode.render(t.codeFormat, t.codeText)
            t.isImage -> decodeImage(image(id))?.let { TicketCode.find(it) }
            else -> findPdfCode(pdf(id))
        }
        found ?: return null
        code(id).outputStream().use { found.image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        save(t.copy(codeFormat = found.format, codeText = found.text))
        return found.image
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = read(buf, read, n - read)
            if (r < 0) break
            read += r
        }
        return buf.copyOf(read)
    }

    /** Decodifica l'immagine ridotta a ~3000 px sul lato lungo, orientata secondo l'EXIF. */
    private fun decodeImage(file: File): android.graphics.Bitmap? {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, opts)
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 3000) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeFile(
            file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val rotation = runCatching {
            when (androidx.exifinterface.media.ExifInterface(file.path).getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1,
            )) {
                6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation == 0f) return bmp
        val m = android.graphics.Matrix().apply { postRotate(rotation) }
        return android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}

/** Riconoscimento euristico dei campi di un biglietto (Trenitalia, Italo, regionali…). */
object TicketParser {
    private val categories =
        "Frecciarossa(?: 1000)?|Frecciargento|Frecciabianca|Intercity(?: Notte)?|Regionale(?: Veloce)?|EuroCity|EuroNight|" +
            "Italo|FR|FA|FB|ICN|IC|EC|EN|ES|REG|RV|RE|ITA|Treno|Train|Tr\\."
    private val trainRx = Regex("\\b($categories)\\s*(?:n\\.?\\s*|N°\\s*)?(\\d{3,5})\\b", RegexOption.IGNORE_CASE)
    private val dateRx = Regex("\\b(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4}|\\d{2})\\b")
    private val timeRx = Regex("\\b([01]?\\d|2[0-3])[:.]([0-5]\\d)\\b")
    private val coachRx = Regex("(?:Carrozza|Coach|Carr\\.|Vettura)[^0-9\\n]{0,40}?(\\d{1,2})\\b", RegexOption.IGNORE_CASE)
    private val seatRx = Regex(
        "(?:Posti|Posto|Seats?|Post\\.)[^0-9\\n]{0,40}?(\\d{1,3}[A-F]?(?:\\s*[,-]\\s*\\d{1,3}[A-F]?)*)",
        RegexOption.IGNORE_CASE,
    )
    private val pnrRx = Regex(
        "(?:PNR|Codice prenotazione|Booking code|Codice biglietto|Ticket code|Codice)\\s*[:/]?\\s*(?:[A-Za-z ]{0,20}[:/]\\s*)?([A-Z0-9]{5,10})\\b"
    )
    private val passengerRx = Regex("(?:Passeggero|Passenger|Nominativo|Titolare)\\s*[:/]?\\s*(?:[A-Za-z ]{0,15}[:/]\\s*)?([A-Z][A-Za-z'’ ]{3,40})")

    private val shortCategory = mapOf(
        "FRECCIAROSSA" to "FR", "FRECCIAROSSA 1000" to "FR", "FRECCIARGENTO" to "FA", "FRECCIABIANCA" to "FB",
        "INTERCITY" to "IC", "INTERCITY NOTTE" to "ICN", "REGIONALE" to "REG", "REGIONALE VELOCE" to "RV",
        "EUROCITY" to "EC", "EURONIGHT" to "EN", "ITALO" to "ITA", "TRENO" to "", "TRAIN" to "", "TR." to "",
    )

    private val tripRx = Regex(
        "\\bil\\s+(\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4})\\s+alle\\s+ore\\s+(\\d{1,2}[:.]\\d{2})", RegexOption.IGNORE_CASE,
    )
    private val oreDateRx = Regex(
        "\\bOre\\s+(\\d{1,2}[:.]\\d{2})\\s*-\\s*(\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4})", RegexOption.IGNORE_CASE,
    )
    private val issueContext = Regex("(emission|emess|acquist|stampa|pagament|scadenz|nato|nascita)", RegexOption.IGNORE_CASE)
    private val passengerBlockRx = Regex(
        "Nome\\s+Passeggero(?:\\s*\\([^)]*\\))?\\s*\\n\\s*([A-Za-zÀ-ÿ'’][A-Za-zÀ-ÿ'’ ]{2,40})",
        RegexOption.IGNORE_CASE,
    )

    private fun normDate(raw: String): String? {
        val m = dateRx.find(raw) ?: return null
        val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
        val y = m.groupValues[3].let { if (it.length == 2) "20$it" else it }.toInt()
        return if (d in 1..31 && mo in 1..12 && y in 2000..2100) "%02d/%02d/%04d".format(d, mo, y) else null
    }

    private fun normTime(raw: String) = raw.replace('.', ':').let { if (it.length == 4) "0$it" else it }

    /** Testo che precede la posizione [i] sulla stessa riga (per scartare data/ora di emissione). */
    private fun lineBefore(t: String, i: Int): String = t.substring(t.lastIndexOf('\n', i - 1) + 1, i)

    fun parse(text: String, stationNames: List<String>): Ticket {
        val t = text.replace(' ', ' ')
        val trip = tripRx.find(t)
        val oreDates = oreDateRx.findAll(t).toList()
        val date = trip?.let { normDate(it.groupValues[1]) }
            ?: oreDates.firstNotNullOfOrNull { normDate(it.groupValues[2]) }
            ?: dateRx.findAll(t).filterNot { issueContext.containsMatchIn(lineBefore(t, it.range.first)) }
                .firstNotNullOfOrNull { normDate(it.value) }
            ?: ""
        val year = date.takeLast(4)

        val trains = trainRx.findAll(t)
            .map { m ->
                val cat = m.groupValues[1].uppercase()
                TicketTrain(m.groupValues[2], shortCategory[cat] ?: cat)
            }
            .filter { it.number != year }
            .distinctBy { it.number }
            .toList()
            // se una stessa corsa compare sia come "Treno 9600" che come "FR 9600" si tiene la forma con categoria
            .sortedByDescending { it.category.isNotEmpty() }
            .distinctBy { it.number }

        // Orari: "Ore 17:30 - 07/10/2026" (partenza, arrivo), altrimenti quelli non legati all'emissione
        val times = if (oreDates.size >= 2) {
            oreDates.map { normTime(it.groupValues[1]) }
        } else {
            listOfNotNull(trip?.let { normTime(it.groupValues[2]) }) +
                timeRx.findAll(t)
                    .filterNot { issueContext.containsMatchIn(lineBefore(t, it.range.first)) }
                    .map { "%02d:%s".format(it.groupValues[1].toInt(), it.groupValues[2]) }
        }.distinct()

        val (from, to) = findStations(t, stationNames)
        return Ticket(
            id = "",
            importedAt = 0,
            trains = trains,
            date = date,
            from = from,
            to = to,
            departure = times.getOrNull(0).orEmpty(),
            arrival = times.getOrNull(1).orEmpty(),
            coach = coachRx.find(t)?.groupValues?.get(1).orEmpty(),
            seat = seatRx.find(t)?.groupValues?.get(1)?.trim().orEmpty(),
            pnr = pnrRx.find(t)?.groupValues?.get(1).orEmpty(),
            passenger = (passengerBlockRx.find(t) ?: passengerRx.find(t))?.groupValues?.get(1)?.trim().orEmpty(),
        )
    }

    /** Prima e ultima stazione note citate nel testo (preferendo i nomi più lunghi, es. "MILANO CENTRALE"). */
    fun findStations(text: String, names: List<String>): Pair<String, String> {
        val norm = " " + DetectionLocator.normalize(text) + " "
        val hits = names.asSequence()
            .map { it to DetectionLocator.normalize(it) }
            .filter { (_, n) -> n.length >= 4 }
            .mapNotNull { (name, n) -> norm.indexOf(" $n ").takeIf { it >= 0 }?.let { Triple(name, n, it) } }
            .sortedWith(compareBy<Triple<String, String, Int>> { it.third }.thenByDescending { it.second.length })
            .toList()
        // scarta i nomi contenuti in un nome più lungo trovato nella stessa posizione
        val kept = mutableListOf<Triple<String, String, Int>>()
        for (h in hits) {
            if (kept.none { k -> h.third >= k.third && h.third + h.second.length <= k.third + k.second.length }) kept.add(h)
        }
        val distinct = kept.distinctBy { it.second }
        val pretty = { s: String -> s.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } }
        return when {
            distinct.isEmpty() -> "" to ""
            distinct.size == 1 -> pretty(distinct[0].first) to ""
            else -> pretty(distinct.first().first) to pretty(distinct[1].first)
        }
    }
}
