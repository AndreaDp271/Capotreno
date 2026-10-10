package io.github.andreadp.trenolibero.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Treno in viaggio disegnato sulla mappa: posizione dell'ultimo rilevamento e tratto fino alla prossima fermata. */
data class TrainOnMap(
    val title: String,
    val number: String,
    val originId: String,
    val dateMs: Long,
    val destination: String,
    val delay: Int?,
    val detection: LocatedDetection,
    val nextStop: String,
    /** Tratto sui binari dal punto di rilevamento alla prossima fermata. */
    val segment: List<LatLon>,
)

/**
 * Posizione dei treni in transito sulle linee indicate: per ogni linea occupata si leggono i treni
 * (dettagliTratta), poi l'andamento di ciascuno per sapere dove è stato rilevato e dove si fermerà.
 */
object TrainsOnMap {
    const val MAX_LINES = 30
    const val MAX_TRAINS = 40

    suspend fun load(lines: List<Pair<Int, Tratta>>, index: Map<String, StationGeo>, rail: RailNetwork?): List<TrainOnMap> =
        coroutineScope {
            val sem = Semaphore(4)
            val trains = lines.filter { it.second.occupata }.take(MAX_LINES).map { (region, t) ->
                async { sem.withPermit { runCatching { ViaggiaTrenoApi.lineTrains(region, t.trattaAB, t.trattaBA) }.getOrDefault(emptyList()) } }
            }.awaitAll()
                .flatten().flatMap { it.treni }
                .filter { it.codOrigine != null && it.dataPartenzaTreno != null && it.nonPartito != true && it.provvedimento != 1 }
                .distinctBy { "${it.numeroTreno}/${it.codOrigine}" }
                .take(MAX_TRAINS)
            val all = index.values
            trains.map { tr ->
                async { sem.withPermit { runCatching { locate(tr, index, all, rail) }.getOrNull() } }
            }.awaitAll().filterNotNull()
        }

    private suspend fun locate(
        tr: TrenoTratta,
        index: Map<String, StationGeo>,
        all: Collection<StationGeo>,
        rail: RailNetwork?,
    ): TrainOnMap? {
        val a = ViaggiaTrenoApi.trainStatus(tr.codOrigine!!, tr.numeroTreno.toString(), tr.dataPartenzaTreno!!) ?: return null
        if (a.arrivato == true || a.nonPartito == true) return null
        val name = a.stazioneUltimoRilevamento?.takeIf { it.isNotBlank() && it != "--" } ?: return null
        val time = a.oraUltimoRilevamento ?: return null
        val stops = a.fermate.map { it to index[it.id] }
        val det = DetectionLocator.locate(Detection(name, time), a, stops, all) ?: return null
        val lastReached = a.fermate.indexOfLast { it.reached && !it.suppressed }
        val next = a.fermate.drop(lastReached + 1).firstOrNull { !it.suppressed && index[it.id] != null } ?: return null
        val nextGeo = index[next.id]!!
        val from = LatLon(det.lat, det.lon)
        val to = LatLon(nextGeo.lat, nextGeo.lon)
        val profile = when (a.title.substringBefore(' ').uppercase()) {
            "FR", "FA", "ITA", "AV", "ES", "ES*" -> RailNetwork.Profile.HIGH_SPEED
            else -> RailNetwork.Profile.REGIONAL
        }
        // Tratta sui binari dalla fermata precedente alla prossima: il pallino va proiettato lì sopra,
        // perché le coordinate di stazioni e posti tecnici non cadono esattamente sui binari
        val prev = a.fermate.take(lastReached + 1).lastOrNull { !it.suppressed && index[it.id] != null }
        val leg = prev?.let { pf ->
            val pg = index[pf.id]!!
            rail?.path(pf.id, LatLon(pg.lat, pg.lon), next.id, to, profile)
        }
        val projected = leg?.let { projectOnPolyline(it, from) }?.takeIf { (_, q) ->
            DetectionLocator.distanceKm(q.lat, q.lon, from.lat, from.lon) < 15
        }
        val seg = if (leg != null && projected != null) {
            listOf(projected.second) + leg.drop(projected.first + 1)
        } else {
            val fromCode = if (det.precision == Precision.EXACT) all.firstOrNull { DetectionLocator.normalize(it.name) == DetectionLocator.normalize(name) }?.codiceStazione else null
            rail?.path(fromCode, from, next.id, to, profile) ?: listOf(from, to)
        }
        return TrainOnMap(
            title = a.title,
            number = tr.numeroTreno.toString(),
            originId = tr.codOrigine,
            dateMs = tr.dataPartenzaTreno,
            destination = a.destinazione.orEmpty(),
            delay = a.ritardo,
            detection = det,
            nextStop = next.stazione,
            segment = seg,
        )
    }
}
