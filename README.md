# TrenoLibero

Client Android libero e **non ufficiale** dei dati in tempo reale di
[ViaggiaTreno](http://www.viaggiatreno.it/infomobilita/index.jsp) (RFI/Trenitalia).

- **Andamento treno**: fermate, orari programmati/effettivi/previsti, ritardo, ultimo rilevamento, binari programmati ed effettivi
- **Tabellone di stazione**: partenze e arrivi, anche da un orario a scelta
- **Mappa** OpenStreetMap (osmdroid): stazioni e linee con i treni in transito (tocca una linea per l'elenco)
- **Linee e percorsi sui binari reali**: la rete ferroviaria OSM è inclusa nell'app (offline), i percorsi sono calcolati sul telefono
- **Ultimo rilevamento** anche in punti che non sono fermate (posti di comunicazione, bivi) e storico dei rilevamenti
- **Biglietti**: importazione di PDF e immagini (anche da "Condividi") o scansione con la fotocamera; il QR/Aztec viene estratto
  e mostrato nitido al controllore, treno/data/carrozza/posto riconosciuti dai PDF (ZXing, PdfBox, CameraX: tutto offline)
- **Cerca viaggio** da A a B: soluzioni anche con cambi e prezzi (servizio di vendita Trenitalia, lefrecce.it) e
  treni diretti di tutte le imprese presenti su ViaggiaTreno; filtri per tipo di treno e compagnia (Italo non disponibile)
- **Abbonamenti**: tratta, tipo, operatore e validità (con avviso di scadenza), QR allegato da mostrare al controllore,
  treni diretti compatibili in tempo reale nei due sensi
- **Notifiche** attivabili (controllo locale con WorkManager, nessun push esterno): promemoria e variazioni del treno
  del biglietto, ritardi/binari/soppressioni dei treni preferiti, nuovi scioperi, scadenza abbonamenti
- **Infomobilità**: avvisi divisi per circolazione, scioperi, lavori (per regione), altre info; treni in circolazione
- **Preferiti** salvati solo sul dispositivo
- Nessuna dipendenza proprietaria: niente Google Play Services, niente tracciamento

## Compilazione

Serve JDK 17 e l'Android SDK (platform 35).

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew assembleRelease      # firmata solo se esiste keystore.properties
./gradlew testDebugUnitTest    # test di parsing su risposte reali
LIVE_API=1 ./gradlew testDebugUnitTest   # anche test contro il server vero
```

`keystore.properties` (non versionato) per firmare la release in locale:

```
storeFile=/percorso/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## Rete ferroviaria OSM

`app/src/main/assets/rail_graph.bin` è generato da OpenStreetMap con `tools/build_rail_graph.py`
(binari `railway=rail|narrow_gauge`, esclusi quelli industriali/militari; scali e tronchini inclusi ma penalizzati;
piccole interruzioni della mappatura ricucite; stazioni ViaggiaTreno agganciate al binario di linea più vicino).
I percorsi sono calcolati sul telefono con A* sul grafo orientato, senza inversioni di marcia negli scambi. Per rigenerarlo:

```sh
pip install osmium
curl -LO https://download.geofabrik.de/europe/italy-latest.osm.pbf
python tools/build_rail_graph.py italy-latest.osm.pbf app/src/main/assets/rail_graph.bin
```

Dati © OpenStreetMap contributors, disponibili con licenza [ODbL](https://www.openstreetmap.org/copyright).

## Fonte dei dati

L'app usa l'API REST pubblica ma non documentata del sito ViaggiaTreno:
`http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/…`

| Endpoint | Uso |
|---|---|
| `cercaNumeroTrenoTrenoAutocomplete/{num}` | ricerca treno |
| `andamentoTreno/{origine}/{num}/{dataMs}` | andamento, fermate, binari |
| `autocompletaStazione/{testo}` | ricerca stazione |
| `partenze/{stazione}/{data}`, `arrivi/…` | tabellone |
| `elencoStazioni/{regione}`, `regione/{stazione}`, `dettaglioStazione/…` | coordinate per la mappa |
| `elencoTratte/{regione}/{zoom}/{categorie}/null/{ts}` | linee e stato "occupata" |
| `dettagliTratta/{regione}/{AB}/{BA}/{categorie}/null` | treni in transito su una linea |
| `statistiche/{ts}`, `infomobilitaTicker/`, `infomobilitaRSS/false` | infomobilità |

Note: il server risponde solo in HTTP (l'HTTPS fa redirect), quindi il traffico in chiaro
è abilitato esclusivamente per `viaggiatreno.it` in `network_security_config.xml`.
Il CDN rifiuta le richieste senza User-Agent da browser.

L'API può cambiare senza preavviso. Su F-Droid l'app porta l'anti-feature **NonFreeNet**.

## Pubblicazione su F-Droid

1. Pubblicare il repository su GitHub/GitLab e creare il tag `v1.0.0`.
2. Se l'utente non è `andreadp`, cambiare `applicationId`/`namespace` in `app/build.gradle.kts`
   (per convenzione `io.github.<utente>.trenolibero`).
3. Fare fork di [fdroiddata](https://gitlab.com/fdroid/fdroiddata), copiare
   `metadata/io.github.andreadp.trenolibero.yml` sostituendo `TUO_UTENTE`, verificare con
   `fdroid lint` / `fdroid build` e aprire una merge request.

Descrizioni e changelog per lo store sono in `fastlane/metadata/android/`.

## Permessi

- `INTERNET`: dati ViaggiaTreno e mappe OSM
- `ACCESS_FINE/COARSE_LOCATION` (facoltativi, chiesti solo all'uso): stazioni vicine, posizione sulle mappe,
  stazione di partenza suggerita; la posizione non lascia il dispositivo
- `POST_NOTIFICATIONS` (facoltativo, chiesto quando attivi una notifica)
- `CAMERA` (facoltativo, chiesto solo all'uso): scansione del codice del biglietto

## Licenza

GPL-3.0-or-later per il codice. Mappe e `rail_graph.bin` © contributori OpenStreetMap (ODbL).
Non affiliata a Trenitalia, RFI o Gruppo FS.
