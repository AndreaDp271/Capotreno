package io.github.andreadp.trenolibero.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Risultato di ricerca treno per numero: "9600 - MILANO CENTRALE - 10/10/26|9600-S01700-1791583200000" */
data class TrainMatch(
    val label: String,
    val number: String,
    val originId: String,
    val dateMs: Long,
)

data class StationRef(val id: String, val name: String)

/** Una fermata dell'andamento treno. */
@Serializable
data class Fermata(
    val stazione: String = "",
    val id: String = "",
    val programmata: Long? = null,
    val effettiva: Long? = null,
    val ritardo: Int? = null,
    @SerialName("partenza_teorica") val partenzaTeorica: Long? = null,
    @SerialName("arrivo_teorico") val arrivoTeorico: Long? = null,
    val partenzaReale: Long? = null,
    val arrivoReale: Long? = null,
    val ritardoPartenza: Int? = null,
    val ritardoArrivo: Int? = null,
    val progressivo: Int? = null,
    val binarioProgrammatoArrivoDescrizione: String? = null,
    val binarioEffettivoArrivoDescrizione: String? = null,
    val binarioProgrammatoPartenzaDescrizione: String? = null,
    val binarioEffettivoPartenzaDescrizione: String? = null,
    /** P = partenza, F = fermata, A = arrivo */
    val tipoFermata: String? = null,
    /** 0 = non ancora raggiunta, 1 = raggiunta, 2 = straordinaria, 3 = soppressa */
    val actualFermataType: Int? = null,
) {
    val reached: Boolean get() = actualFermataType == 1 || arrivoReale != null || partenzaReale != null
    val suppressed: Boolean get() = actualFermataType == 3
}

/** Risposta di andamentoTreno. */
@Serializable
data class Andamento(
    val numeroTreno: Int? = null,
    val compNumeroTreno: String? = null,
    val categoria: String? = null,
    val origine: String? = null,
    val destinazione: String? = null,
    val idOrigine: String? = null,
    val idDestinazione: String? = null,
    val orarioPartenza: Long? = null,
    val orarioArrivo: Long? = null,
    val dataPartenzaTreno: Long? = null,
    val ritardo: Int? = null,
    val oraUltimoRilevamento: Long? = null,
    val stazioneUltimoRilevamento: String? = null,
    val arrivato: Boolean? = null,
    val nonPartito: Boolean? = null,
    val circolante: Boolean? = null,
    val provvedimento: Int? = null,
    /** PG = regolare, ST = soppresso, PP/SI/SF = parzialmente soppresso, DV = deviato */
    val tipoTreno: String? = null,
    val subTitle: String? = null,
    val motivoRitardoPrevalente: String? = null,
    val descOrientamento: List<String>? = null,
    val compDurata: String? = null,
    val fermate: List<Fermata> = emptyList(),
) {
    val title: String get() = compNumeroTreno?.trim()?.takeIf { it.isNotEmpty() } ?: "Treno $numeroTreno"
    val cancelled: Boolean get() = tipoTreno == "ST" || provvedimento == 1
    val partiallyCancelled: Boolean get() = tipoTreno in setOf("PP", "SI", "SF") || provvedimento == 2
    val deviated: Boolean get() = tipoTreno == "DV"
}

/** Riga del tabellone partenze/arrivi. */
@Serializable
data class BoardEntry(
    val numeroTreno: Int = 0,
    val codOrigine: String? = null,
    val dataPartenzaTreno: Long? = null,
    val compNumeroTreno: String? = null,
    val categoriaDescrizione: String? = null,
    val origine: String? = null,
    val destinazione: String? = null,
    val orarioPartenza: Long? = null,
    val orarioArrivo: Long? = null,
    val ritardo: Int? = null,
    val binarioProgrammatoPartenzaDescrizione: String? = null,
    val binarioEffettivoPartenzaDescrizione: String? = null,
    val binarioProgrammatoArrivoDescrizione: String? = null,
    val binarioEffettivoArrivoDescrizione: String? = null,
    val inStazione: Boolean? = null,
    val nonPartito: Boolean? = null,
    val provvedimento: Int? = null,
    val arrivato: Boolean? = null,
) {
    val title: String get() = compNumeroTreno?.trim()?.takeIf { it.isNotEmpty() } ?: "$numeroTreno"
}

@Serializable
data class Localita(val nomeLungo: String? = null, val nomeBreve: String? = null, val id: String? = null)

/** Stazione con coordinate (elencoStazioni / dettaglioStazione). */
@Serializable
data class StationGeo(
    val codiceStazione: String = "",
    val codReg: Int? = null,
    val tipoStazione: Int? = null,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val localita: Localita? = null,
) {
    val name: String get() = localita?.nomeLungo ?: codiceStazione
    val hasCoords: Boolean get() = lat != 0.0 && lon != 0.0
}

@Serializable
data class Statistiche(
    val treniGiorno: Int? = null,
    val treniCircolanti: Int? = null,
    val ultimoAggiornamento: Long? = null,
)

/** Avviso di infomobilità estratto dall'HTML del servizio. */
data class InfoNews(val title: String, val date: String, val html: String, val highlighted: Boolean)

/** Linea (tratta) della mappa: segmento tra due nodi, "occupata" se ci sono treni in transito. */
@Serializable
data class Tratta(
    val nodoA: String? = null,
    val nodoB: String? = null,
    val trattaAB: Int = 0,
    val trattaBA: Int = 0,
    val latitudineA: Double = 0.0,
    val longitudineA: Double = 0.0,
    val latitudineB: Double = 0.0,
    val longitudineB: Double = 0.0,
    val occupata: Boolean = false,
)

/** Treno in transito su una tratta (dettagliTratta: qui gli orari sono stringhe già formattate). */
@Serializable
data class TrenoTratta(
    val numeroTreno: Int = 0,
    val codOrigine: String? = null,
    val dataPartenzaTreno: Long? = null,
    val compNumeroTreno: String? = null,
    val origine: String? = null,
    val destinazione: String? = null,
    val compOrarioPartenza: String? = null,
    val compOrarioArrivo: String? = null,
    val ritardo: Int? = null,
    val nonPartito: Boolean? = null,
    val provvedimento: Int? = null,
) {
    val title: String get() = compNumeroTreno?.trim()?.takeIf { it.isNotEmpty() } ?: "$numeroTreno"
}

@Serializable
data class DirezioneTratta(val tratta: String = "", val treni: List<TrenoTratta> = emptyList())
