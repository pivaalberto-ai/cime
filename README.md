# Cime — riconoscimento delle montagne

Inquadri il panorama con la fotocamera e l'app sovrappone nome, quota e distanza delle cime visibili,
più la linea dell'orizzonte calcolata dal modello del terreno. Funziona completamente offline.

## Come funziona

1. **Pacchetto regione** (`.pkpack`): un file con il terreno (Copernicus DEM, ricampionato a ~90 m)
   e le cime con nome di OpenStreetMap. Si genera sul PC con `tools/build_pack.py` e si importa nell'app.
2. **Dove sei**: GPS → quota dell'occhio = terreno + 1,7 m (più affidabile della quota GPS).
3. **Cosa si vede**: per ogni cima entro 150 km si percorre la linea di vista sul DEM, tenendo conto di
   curvatura terrestre e rifrazione. Se un rilievo intermedio sta più in alto della cima, è nascosta.
   Si calcola anche il profilo dell'orizzonte (linea gialla), utile per verificare l'allineamento.
4. **Dove guardi**: sensore *rotation vector* + declinazione magnetica (`GeomagneticField`) →
   ogni cima viene proiettata sullo schermo con la focale reale della fotocamera.
5. **Calibrazione**: la bussola del telefono sbaglia spesso di qualche grado. Dal menu ⋮ → *Calibra*,
   trascina finché la linea gialla combacia con le creste vere; pizzica per correggere lo zoom.
   La correzione viene salvata.

## Struttura

```
app/src/main/java/it/cime/app/
  core/            ← puro Kotlin, senza Android: testato con JUnit
    Geo.kt           distanze, azimut, curvatura + rifrazione
    RegionPack.kt    lettura del .pkpack (memory-mapped) e interpolazione del DEM
    Viewshed.kt      visibilità delle cime e profilo dell'orizzonte
    Projector.kt     da (azimut, elevazione) a pixel dello schermo
  sensors/         orientamento (con filtro) e posizione
  PeakOverlayView  disegno di orizzonte ed etichette, gesti di calibrazione
  PackManager      import e scelta del pacchetto per la posizione corrente
  MainActivity     CameraX, permessi, menu
tools/build_pack.py  generatore dei pacchetti regione
```

## Tutto su GitHub (senza installare nulla)

- **App**: ogni push sul ramo `main` compila l'APK e lo pubblica tra le *Release* (`build-N`).
  Dal telefono apri la pagina Releases e tocca l'APK.
- **Pacchetti regione**: scheda *Actions* → *Crea pacchetto regione* → *Run workflow*,
  indica nome e riquadro. Dopo qualche minuto il `.pkpack` compare tra le Release (`pack-<nome>`).
  Rigenerando lo stesso nome, il file viene sostituito.

## Compilare l'app in locale

Apri la cartella `Cime` in Android Studio (Ladybug o successivo) e lascia che sincronizzi Gradle
(scarica Gradle 8.11.1 come indicato in `gradle/wrapper/gradle-wrapper.properties`).
Requisiti: minSdk 26, un telefono con giroscopio e magnetometro. Il test del motore:

```
./gradlew :app:testDebugUnitTest
```

## Creare un pacchetto regione

```
cd tools
pip install -r requirements.txt
python build_pack.py --bbox 45.4,8.5,46.7,11.0 --name "Lombardia" --out lombardia.pkpack
```

- `--bbox sud,ovest,nord,est`: la zona dove userai l'app. Il terreno viene esteso automaticamente di
  60 km (`--margin-km`) per riconoscere anche le cime lontane oltre il confine.
- `--step 3` (default) ≈ 90 m per cella: Lombardia + margine ≈ 28 MB. `--step 2` è più preciso ma 2,25× più grande.
- I tile del DEM vengono messi in cache in `dem_cache/`; Overpass può essere lento sulle zone grandi.

Copia il `.pkpack` sul telefono (cavo, Drive, email…) e importalo da ⋮ → *Importa pacchetto regione*.
Per provare a casa: ⋮ → *Posizione manuale* e inserisci le coordinate di un punto panoramico.

## Attribuzioni obbligatorie

Da mostrare nell'app pubblicata:
- © OpenStreetMap contributors (ODbL)
- Copernicus DEM © DLR e.V. 2010-2014 e © Airbus Defence and Space GmbH 2014-2018,
  fornito nell'ambito del programma Copernicus dell'Unione Europea

## Limiti noti e prossimi passi

- Solo verticale. La geometria regge qualsiasi rotazione, ma le etichette sono scritte in orizzontale.
- La precisione dipende soprattutto dalla bussola: senza calibrazione aspettati errori di 2–5°.
- Idee: riconoscimento automatico dell'allineamento confrontando il profilo calcolato con le creste
  rilevate nell'immagine (edge detection), tocco su un'etichetta per i dettagli, foto con etichette,
  download dei pacchetti dall'app.
