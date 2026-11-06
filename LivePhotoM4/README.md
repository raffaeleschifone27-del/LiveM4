# Live Photo M4

App Android per POCO M4 Pro 4G. Allo scatto registra un clip di ~3 secondi e una foto
a meta' clip. Salva JPEG in `Pictures/LivePhotoM4` e MP4 in `Movies/LivePhotoM4`
con lo stesso nome.

Si avvia solo su POCO M4 Pro 4G (codename `fleur`, modello 2201117PG / 2201117PI).

## Ottenere l'APK

### Con GitHub (senza installare nulla)
1. Crea un repository su github.com e carica tutto il contenuto di questa cartella.
2. Vai nella scheda **Actions**, apri l'ultima esecuzione di "Build APK".
3. Scarica l'artifact **LivePhotoM4-apk** (zip con `app-debug.apk`).

### Con Android Studio
Apri la cartella, attendi la sincronizzazione Gradle, poi **Build > Build APK(s)**.

## Installazione
Copia `app-debug.apk` sul telefono, aprilo e consenti l'installazione da fonti sconosciute.
