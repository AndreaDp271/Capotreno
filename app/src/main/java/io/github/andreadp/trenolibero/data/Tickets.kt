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
) {
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
    fun get(id: String) = _tickets.value.firstOrNull { it.id == id }

    fun save(t: Ticket) = write(_tickets.value.filterNot { it.id == t.id } + t)

    fun delete(id: String) {
        pdf(id).delete()
        write(_tickets.value.filterNot { it.id == id })
    }

    /** Copia il PDF, ne estrae il testo e prova a riconoscere i dati del viaggio. */
    suspend fun import(uri: Uri, stations: StationIndex): Ticket = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val file = pdf(id)
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: throw ApiException("Impossibile leggere il file")
        val text = try {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(file).use { PDFTextStripper().getText(it) }
        } catch (e: Exception) {
            file.delete()
            throw ApiException("Il file non è un PDF leggibile")
        }
        val names = runCatching { stations.all().map { it.name } }.getOrDefault(emptyList())
        val t = TicketParser.parse(text, names).copy(id = id, importedAt = System.currentTimeMillis())
        save(t)
        t
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

    fun parse(text: String, stationNames: List<String>): Ticket {
        val t = text.replace(' ', ' ')
        val date = dateRx.findAll(t).mapNotNull { m ->
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
            val y = m.groupValues[3].let { if (it.length == 2) "20$it" else it }.toInt()
            if (d in 1..31 && mo in 1..12 && y in 2000..2100) "%02d/%02d/%04d".format(d, mo, y) else null
        }.firstOrNull().orEmpty()
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

        // Orari: quelli che seguono la data, nell'ordine del testo
        val afterDate = if (date.isNotEmpty()) t.indexOf(date.take(2)).coerceAtLeast(0) else 0
        val times = timeRx.findAll(t, afterDate).map { "%02d:%s".format(it.groupValues[1].toInt(), it.groupValues[2]) }
            .distinct().toList()

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
            passenger = passengerRx.find(t)?.groupValues?.get(1)?.trim().orEmpty(),
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
