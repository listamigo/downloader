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
- **yt-dlp**: **no se necesita.** Ver ADR-005.

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
adb push cookies.txt /sdcard/Android/data/com.elimd.downloader/files/cookies.txt
adb shell am force-stop com.elimd.downloader
adb logcat -s YouTubeSession:*
```

Se buscan en este orden, y se usa el primero que exista:

1. `getExternalFilesDir(null)/cookies.txt` — para `adb push`.
2. `filesDir/cookies.txt`
3. `filesDir/session/cookies.txt`

**Nunca** se comitean: ya están en `.gitignore`.

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
- **NewPipeMediaResolver**: implementación actual sin servidor. Fuerza el
  cliente iOS de InnerTube.
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
`version = 1` y `exportSchema = false`. Si añades columnas, **activa
`exportSchema` y escribe la migración primero**, o perderás los datos del
usuario. Ver `ESTADO_PROYECTO.md` P7.

## Próximos Pasos
Ver `ESTADO_PROYECTO.md` §8 para el detalle.

- [ ] **P1 (Alta)**: El bloqueo es de IP y ningún cliente de InnerTube lo evita
      (§5.1). Solo dos vías: **plan B** (`ServerMediaResolver` + yt-dlp en un
      servidor en otra red) o **PO tokens**. El fork del extractor para forzar
      iOS ya no tiene sentido.
- [ ] **P3 (Media)**: Reproductor propio con Media3 (`feature/player`).
- [ ] **P4 (Media)**: Tests de `HttpFileDownloader` (Range/pausa) con
      `MockWebServer`, y de use cases con MockK.
- [ ] **P5 (Media)**: Conectar `DownloadService` al motor para descargas en
      segundo plano. Ahora importa más: unir pistas tarda, y sin servicio una
      descarga larga se corta al salir de la app.
- [ ] **P6 (Baja)**: Fondo de pantalla (`WallpaperManager`).
- [ ] **P7 (Baja)**: Migraciones de Room y velocidad real.
- [ ] **P8 (Baja)**: Reducir dependencia de JitPack.

> Redundancia conocida: el ajuste "Calidad de descarga" de `SettingsScreen` ya
> no influye en nada, porque la calidad se elige por descarga en el selector.
> Conviene borrarlo o conectarlo de verdad.
