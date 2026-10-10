# TrenoLibero

Client Android libero e **non ufficiale** dei dati in tempo reale di
[ViaggiaTreno](http://www.viaggiatreno.it/infomobilita/index.jsp) (RFI/Trenitalia).

- **Andamento treno**: fermate, orari programmati/effettivi/previsti, ritardo, ultimo rilevamento, binari programmati ed effettivi
- **Tabellone di stazione**: partenze e arrivi, anche da un orario a scelta
- **Mappa** OpenStreetMap (osmdroid): tutte le stazioni e il percorso del treno
- **Infomobilità**: avvisi di circolazione, notizie in evidenza, treni in circolazione
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

## Licenza

GPL-3.0-or-later. Mappe © contributori OpenStreetMap.
Non affiliata a Trenitalia, RFI o Gruppo FS.
