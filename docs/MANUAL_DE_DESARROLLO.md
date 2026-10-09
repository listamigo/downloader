# MANUAL DE DESARROLLO - elimd downloader

> Documento actualizado el **2026-10-03**.
> La versión anterior instruía a tener **yt-dlp instalado en el sistema** y a
> ejecutar `yt-dlp` por subprocess desde la app. **Nada de eso es necesario ni
> posible** en Android. Ver `DECISIONS_LOG.md` ADR-005 y ADR-011.
> Para el estado real y los errores ya resueltos, ver `ESTADO_PROYECTO.md`.

## Requisitos previos
- **Android Studio** (última versión estable).
- **JDK 17+** (probado con OpenJDK 21).
- **Android SDK** (API 21+). Ruta por defecto en este equipo:
  `/home/elimdavid/Android/Sdk`, declarada en `local.properties` (no versionado).
- **Gradle**: se gestiona con el wrapper. No hace falta instalarlo aparte.
- **yt-dlp**: **no se necesita en el dispositivo.** Ver ADR-005. El plan B lo
  usa **en el servidor** (`server/`), no en la app (ADR-022).

## Comandos de Terminal

### Compilación, pruebas e instalación
```bash
cd "/home/elimdavid/a. donwloader/"

./gradlew :app:assembleDebug        # compilar
./gradlew :app:testDebugUnitTest    # tests unitarios (JVM, sin dispositivo)
./gradlew :app:installDebug         # compilar e instalar por adb
./gradlew :app:connectedDebugAndroidTest  # tests instrumentados (necesita móvil)
./gradlew :app:clean                 # limpiar
./gradlew tasks                     # listar tareas
```

Se recomienda usar las tareas con scope `:app:` en lugar de `build`, que
ejecuta todos los módulos y variants.

### Backend del plan B (`server/`)

El servidor es un proyecto Python aparte; no participa del build de Gradle.

```bash
cd "/home/elimdavid/a. donwloader/server"
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
python3 -m unittest discover -s tests -t .   # 30 tests del núcleo puro
uvicorn app.main:app --reload                # servidor en http://127.0.0.1:8000
```

Sin PO token YouTube solo entrega 360p (itag 18); con él aparece la escalera
completa hasta 4K. Ver `server/README.md` para desplegar en Railway y aportar el
PO token (`YT_PO_TOKEN`, `YTDLP_POT_SCRIPT` o `YTDLP_POT_BASEURL`).

### Tests instrumentados

`Mp4Remuxer` y `MediaMuxer` dependen de los codecs de Android, así que solo se
pueden probar en el dispositivo. Compila el sourceSet antes de fiarse de la
cifra: `./gradlew :app:assembleDebugAndroidTest`. `./gradlew :app:connectedDebugAndroidTest` a veces falla con
*"Unable to find instrumentation target package"*: es que Gradle desinstaló la
app. Se puede lanzar a mano, que además es más rápido:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.elimd.downloader.test/androidx.test.runner.AndroidJUnitRunner
```

> `androidx.test.ext:espresso-core:3.6.1` **no existe** en los repositorios (404).
> Se eliminó de `libs.versions.toml` porque no se usaba. Es el mismo tipo de
> error que `media3-player`.

### Depuración en el dispositivo
```bash
adb logcat -s DownloadEngine:* MainViewModel:* NewPipeMediaResolver:E MediaMuxer:* Mp4Remuxer:* YouTubeSession:*
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.elimd.downloader/.feature.main.MainActivity
```

`YouTubeSession` es la etiqueta clave cuando algo falla con
`LOGIN_REQUIRED`: dice si se cargó la sesión y cuántas cookies encontró.
`MediaMuxer` lo es cuando falla la unión de pistas de un >720p.

### Sesión de YouTube (cookies)

La app necesita cookies de una cuenta con sesión iniciada; sin ellas YouTube
devuelve `LOGIN_REQUIRED` a cualquier cliente. Ver `ESTADO_PROYECTO.md` §5.

```bash
# Exportar cookies.txt del navegador (extensión "Get cookies.txt LOCALLY")
# y guardarlo FUERA del repo: es una credencial. Esta copia de trabajo vive en
# ~/cookies-a-donwloader.txt (movida fuera del árbol el 2026-10-09).
adb push ~/cookies-a-donwloader.txt /sdcard/Android/data/com.elimd.downloader/files/cookies.txt
adb shell am force-stop com.elimd.downloader
adb logcat -s YouTubeSession:*
```

Se buscan en este orden, y se usa el primero que exista:

1. `getExternalFilesDir(null)/cookies.txt` — para `adb push`.
2. `filesDir/cookies.txt`
3. `filesDir/session/cookies.txt`

**Nunca** se comitean: ya están en `.gitignore`, y la copia de trabajo se
mantiene fuera del árbol de trabajo para que ni un `cp -r` ni un backup la
arrastren.

### NewPipeExtractor (opcional, para depurar en la JVM)
Al ser Java puro, se puede ejercitar fuera de Android:

```bash
./gradlew :app:dependencies --configuration debugRuntimeClasspath
```

Para reproducir un fallo concreto, un test JVM contra YouTube real es más
rápido que iterar sobre el dispositivo.

## Estructura del Proyecto

```
app/src/main/java/com/elimd/downloader/
├── core/
│   ├── extract/          # MediaResolver, NewPipeMediaResolver, StreamSelection
│   ├── download/         # HttpFileDownloader, MediaMuxer, DownloadEngineImpl
│   ├── database/         # Room (AppDatabase, DownloadEntity, DownloadDao)
│   ├── datastore/        # DataStore (configuración)
│   ├── network/          # YouTubeSearchDataSource (adaptador delgado)
│   └── di/               # AppModule (Hilt)
├── data/
│   ├── repository/       # RepositoriesImpl
│   └── source/           # interfaces de fuentes de datos
├── domain/
│   ├── model/            # YouTubeVideo, Download, AppSettings, etc.
│   ├── repository/       # contratos (creados en la reparación)
│   └── usecase/          # casos de uso
└── feature/
    ├── home/             # búsqueda + hoja de calidad
    ├── downloads/        # lista de descargas
    ├── settings/         # configuración
    └── main/             # MainActivity, App, MainViewModel
app/src/test/              # SearchResponseSanitizer, StreamSelection, nombres
docs/                     # Documentación viva
gradle/                   # libs.versions.toml
```

> `core/youtubedl/` y `feature/player/` **no existen**. No los busques.

## Convención de Dependencias

`domain` no depende de nada (ni de Android, ni de `data`, ni de `core`). Esto
permite testearlo en la JVM sin dispositivo. `feature` depende de `domain` y
`core`; `data` depende de `domain` y `core`; `core` depende del framework.

## Componentes Construidos

### Domain Layer
- **Models**: YouTubeVideo, DownloadQuality, Download, AppSettings, AppTheme,
  DownloadType, DownloadStatus, SearchResult.
- **Repository**: YouTubeSearchRepository, DownloadRepository, SettingsRepository.
- **Use Cases**: 32 casos de uso de búsqueda, descarga, ajustes y eliminación.

### Core Layer
- **MediaResolver** (`core/extract`): interfaz, la frontera del motor de
  descarga. Punto de extensión para el plan B.
- **SwitchingMediaResolver**: binding activo de `MediaResolver`. Lee el ajuste
  `useRemoteServer` en cada llamada y enruta al backend local o remoto (ADR-022).
- **NewPipeMediaResolver**: extractor local sin servidor. Fuerza el cliente iOS
  de InnerTube. Inyectado como `@LocalResolver`.
- **ServerMediaResolver**: plan B contra el backend `server/` con yt-dlp.
  Devuelve un único archivo ya muxeado; inyectado como `@RemoteResolver`.
- **StreamSelection**: la política de qué stream sirve para cada petición.
  Sin dependencias del extractor, se prueba en la JVM (ADR-015).
- **OkHttpNewPipeDownloader**: puente HTTP que el extractor necesita.
- **HttpFileDownloader**: transferencia de bytes con soporte de `Range`.
- **Mp4Remuxer**: une la pista de video y la de audio en un MP4 **copiando las
  muestras** (`MediaExtractor` + `MediaMuxer`). Sin recodificar: el archivo pesa
  lo mismo que las partes. Es el camino habitual por encima de 720p.
- **MediaMuxer**: decide la ruta. Usa `Mp4Remuxer` cuando las pistas ya caben en
  un MP4 (H.264 + AAC) y cae a `media3-transformer` cuando hay que convertir de
  verdad (VP9/AV1, Opus, WebM). Ver ADR-018.
- **DownloadEngineImpl**: jobs, progreso, pausa, reanudación y cancelación.
- **Room**: AppDatabase, DownloadEntity, DownloadDao.
- **DataStore**: DataStoreSettingsDataSource.

### Presentation Layer
- **MainActivity**: actividad principal con Compose.
- **ElimdDownloaderApp** (`App.kt`): root composable con tema (LIGHT, DARK,
  SYSTEM, AMOLED), navegación y Snackbar de errores.
- **MainViewModel**: estado global, búsqueda, selector de calidad, descargas y
  ajustes.
- **HomeScreen**: búsqueda con paginación y hoja de calidad.
- **DownloadsScreen**: lista con estados y acciones.
- **SettingsScreen**: configuración.

## Buenas Prácticas

### Coroutines y Flow
- Usar `viewModelScope` para operaciones ligadas a la UI.
- Usar `Dispatchers.IO` para red y E/S de disco.
- Evitar `runBlocking` en producción.
- El progreso se expone como `StateFlow` (ver ADR-013), no `SharedFlow`.

### SOLID
- **Single Responsibility**: cada clase tiene una sola razón de cambiar.
- **Dependency Inversion**: depender de interfaces (en `domain`), no de
  implementaciones.

### Al añadir dependencias
- Preferir Maven Central. NewPipeExtractor requiere **JitPack**:
  `maven { url = uri("https://jitpack.io") }`.
- Comprobar el nombre real del artefacto en Google Maven: por ejemplo
  `media3-exoplayer` existe, `media3-player` **no** (devuelve 404).

### Antes de tocar el esquema de Room
`version = 2` (con `MIGRATION_1_2` registrada en `AppModule`) y
`exportSchema = false`. Si añades columnas, **activa `exportSchema` y escribe
la migración primero**, o perderás los datos del usuario y Room no podrá
validarla. Ver `ESTADO_PROYECTO.md` P7.

## Bugs Resueltos y Lecciones Aprendidas

### 2026-10-09 — Superficie de seguridad (C4 de la auditoría)
- `cookies.txt` con la sesión real vivía en la **raíz del repo**. No estaba en
  git (ya lo excluía `.gitignore`), pero seguía en el árbol de trabajo: un
  `cp -r`, un backup o una captura lo habrían filtrado. Se **movió fuera del
  árbol** a `~/cookies-a-donwloader.txt`.
- `DEBUG_PATHS = true` estaba fijo en `OkHttpNewPipeDownloader`: en release
  seguía escribiendo en logcat el `playabilityStatus` de cada petición. Ahora es
  `BuildConfig.DEBUG`, y la decisión la toma el compilador.
- `startActivity(ACTION_VIEW)` en `DownloadsScreen` iba sin red: sin
  reproductor instalado, **crash**. Ahora `catch (ActivityNotFoundException)`
  con log y aviso.
- El `videoId` que llega de la red se usaba tal cual como nombre de fichero de
  la miniatura. Era seguro "por accidente" (un regex aguas arriba), así que se
  añadió `sanitizeVideoId` (`core/common/VideoId.kt`): solo pasa
  `[A-Za-z0-9_-]{1,64}`; cualquier otra forma se descarta antes de tocar el
  sistema de ficheros o la URL. 8 tests en `VideoIdTest`.
- **Lección**: "está en `.gitignore`" no es lo mismo que "no está en el árbol".
  El `.gitignore` protege el commit, no el `cp -r`. Una credencial en el
  directorio de trabajo está a un backup de distancia de un incidente; sacarla
  del árbol es la única garantía real. Y una entrada de red nunca se convierte
  en ruta o URL sin validar su forma.

### 2026-10-07 — Ajustes que no hacían nada (C3 de la auditoría)
- "Descargas concurrentes máximas" se guardaba en Room y el motor **nunca lo
  leía**: con el límite en 1 seguían yendo 4 descargas en paralelo. Ahora
  cada job espera su hueco (`DownloadSlots`, contador con CAS) antes de
  resolver la URL, releyendo el límite en cada intento; mientras espera su
  estado es `QUEUED` y la notificación no miente.
- Tres `onClick = { }` vacíos en `SettingsScreen`: "Ubicación de descarga"
  se **eliminó** (nadie consumía `downloadLocation` y no había selector: la
  fila prometía lo que no ocurre); "Versión" y "Acerca de" son ahora filas
  informativas no pulsables, y la versión sale de `BuildConfig.VERSION_NAME`
  (habilitado `buildFeatures.buildConfig`) en vez de un `"1.0.0"` hardcodeado
  que habría quedado mentiroso con la primera actualización.
- **Lección**: un ajuste que no lee nadie es una mentira en la UI, igual que
  una excepción silenciada: si aún no se implementa, no se muestra — o se
  marca como pendiente. Y no hardcodear la versión en la UI: es el dato que
  más garantía de desincronización tiene.

### 2026-10-07 — Excepciones silenciadas (C2 de la auditoría)
- `MainViewModel.observeDownloads` tenía `.catch { }` vacío: si el Flow de
  Room fallaba, la lista de descargas se congelaba para siempre sin log.
  Ahora: `Log.e` + snackbar. Igual el catch de `observeSettings`, que quitaba
  el indicador de carga sin registrar nada.
- `NewPipeMediaResolver.getRelatedVideos` devolvía `emptyList()` vía
  `runCatching` en cualquier fallo: "0 relacionados" parecía un resultado.
  Ahora log + rethrow, como `getVideoInfo` en el mismo fichero.
- `YouTubeSearchDataSourceImpl.downloadThumbnail` usaba `runCatching`, que se
  traga **cualquier `Throwable`** (incluido `OutOfMemoryError` de
  `body.bytes()`) sin log. Ahora `catch (e: Exception)` con `Log.w`.
- **Lección**: `runCatching` + `getOrDefault/getOrNull` es el formato favorito
  de las excepciones silenciadas: compila limpio y no molesta hasta que el
  usuario ve un dato falso. `r3.md` lo prohíbe; si el fallo es cosmético
  (miniatura), se registra y se devuelve `null`; si cambia el significado del
  dato (relacionados, descargas), se propaga.

## Próximos Pasos
Ver `ESTADO_PROYECTO.md` §8 para el detalle.

- [ ] **P1 (Alta)**: El bloqueo es de IP y ningún cliente de InnerTube lo evita
      (§5.1). Solo dos vías: **plan B** (`ServerMediaResolver` + yt-dlp en un
      servidor en otra red) o **PO tokens**. El fork del extractor para forzar
      iOS ya no tiene sentido.
- [ ] **P3 (Media)**: Reproductor propio con Media3 (`feature/player`).
- [ ] **P4 (Media)**: Tests de `HttpFileDownloader` (Range/pausa) con
      `MockWebServer`, y de use cases con MockK.
- [x] **P5 (Media)**: Conectar `DownloadService` al motor para descargas en
      segundo plano (hecho 2026-10-07, ADR-020: el motor lo arranca en
      `launchDownload` y el servicio se cierra cuando no queda nada activo).
- [ ] **P6 (Baja)**: Fondo de pantalla (`WallpaperManager`).
- [ ] **P7 (Baja)**: Migraciones de Room y velocidad real.
- [ ] **P8 (Baja)**: Reducir dependencia de JitPack.

> Redundancia conocida: el ajuste "Calidad de descarga" de `SettingsScreen` ya
> no influye en nada, porque la calidad se elige por descarga en el selector.
> Conviene borrarlo o conectarlo de verdad.
