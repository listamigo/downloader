# ARCHITECTURE - elimd downloader

> Documento actualizado el **2026-10-03**.
> La versión anterior de este archivo describía un motor `yt-dlp` invocado por
> `ProcessBuilder` desde Android. **Eso es técnicamente imposible** y nunca
> funcionó. Ver `DECISIONS_LOG.md` ADR-005 (sustituido) y ADR-011.
> Para el estado real de la implementación, ver `ESTADO_PROYECTO.md`.

## Overview
Aplicación Android para descarga de audio y vídeo, con búsqueda, selector de
calidad, descarga directa desde el dispositivo, y apertura en reproductores
externos.

**Tecnologías**: Kotlin, Jetpack Compose (Material 3), Clean Architecture, Hilt,
Room, DataStore, NewPipeExtractor + OkHttp, Media3 (Transformer).

## Arquitectura por Capas

### 1. Presentation Layer (`feature`)
- **home**: búsqueda con paginación real, filtros, y la **hoja de calidad** que
  se abre al pulsar Descargar.
- **downloads**: lista de descargas desde Room, con estados y acciones.
- **settings**: configuración de tema, notificaciones y datos.
- **main**: `MainActivity`, `App.kt` (tema, navegación, Snackbar), `MainViewModel`.

**Patrón**: MVVM con `StateFlow` y flujo unidireccional de datos.

> `feature/player/` **no existe**. La reproducción delega en el reproductor
> externo del sistema vía `Intent.ACTION_VIEW` + `FileProvider`. La capa de
> dominio y el modelo de datos **sí** soportan reproducción (playback), pero no
> hay reproductor embebido.

### 2. Domain Layer (`domain`)
- **model**: `YouTubeVideo`, `Download`, `DownloadQuality`, `DownloadType`,
  `DownloadStatus`, `AppSettings`, `AppTheme`, `SearchResult`.
- **repository**: contratos `YouTubeSearchRepository`, `DownloadRepository`,
  `SettingsRepository`.
- **usecase**: 32 casos de uso de búsqueda, descarga, ajustes y eliminación.

**Reglas**: los use cases son los únicos que acceden a repositorios. El dominio
no depende de `data` ni de `core`. Este directorio es Java/Kotlin puro, sin
dependencias de Android, lo que permite testearlo en la JVM.

> `domain/repository/` se creó en la fase de reparación. Sin él, los `@Binds`
> de Hilt apuntaban a tipos inexistentes y kapt fallaba.

### 3. Data Layer (`data`)
- **repository**: implementaciones de los puertos de dominio (`RepositoriesImpl`).
- **source**: interfaces de fuentes de datos.

### 4. Core Layer (`core`)
- **extract**: la frontera de resolución de medios (§ siguiente) y la política
  de selección de calidad.
- **download**: `HttpFileDownloader` (transferencia con `Range`), `MediaMuxer`
  (une las pistas de video y audio) y `DownloadEngineImpl` (jobs, progreso,
  pausa, reanudación, cancelación).
- **database**: Room (`AppDatabase`, `DownloadEntity`, `DownloadDao`, mappers).
- **datastore**: `DataStoreSettingsDataSource`.
- **network**: adaptador delgado sobre el `MediaResolver`.
- **di**: módulos de Hilt (`AppModule` agrupa base de datos, DataStore, red y
  repositorios).

> `core/youtubedl/` (`YoutubeDlExecutor`) fue **eliminado**. No puede existir en
> Android: no hay shell ni permiso `exec`, no hay build de yt-dlp para Android, y
> los binarios de PyInstaller requieren glibc cuando Android usa bionic.

## La frontera `MediaResolver` (decisión arquitectónica clave)

El punto de extensibilidad del que todo depende:

```
MediaResolver (interfaz, en core/extract)
├── NewPipeMediaResolver   <- implementación actual, sin servidor
└── ServerMediaResolver    <- plan B: servidor propio con yt-dlp
```

- **`NewPipeMediaResolver`**: NewPipeExtractor (Java puro, el mismo que usa la
  app NewPipe en producción) + OkHttp. Descifra las URLs firmadas de YouTube con
  Rhino como motor JavaScript. No requiere servidor ni binario nativo. Fuerza el
  cliente **iOS** de InnerTube, que expone muchos más formatos que el de Android.
- **`ServerMediaResolver`**: delega en un servidor propio con yt-dlp, útil
  porque yt-dlp se actualiza antes y usa clientes de InnerTube distintos, con
  menor probabilidad de bloqueo.

Añadir el plan B es **un único cambio de binding en `AppModule`**: ni dominio
ni presentación se ven afectados.

**Limitación conocida**: sin cookies de sesión, YouTube bloquea con
`SignInConfirmNotBotException` la mayoría de vídeos. La causa no es el cliente de
InnerTube (se probaron 7, ninguno pasa) sino que **falta una sesión
autenticada**. Se resuelve con un `cookies.txt` del usuario. Ver
`ESTADO_PROYECTO.md` §5.

## Resolución y política de calidad

Dentro de `core/extract` hay una separación deliberada (ADR-015):

```
NewPipeMediaResolver          traduce   NewPipeExtractor <-> StreamCandidate
StreamSelection (puro)        decide    qué stream sirve para cada petición
```

- **`StreamSelection`** no sabe nada de YouTube ni del extractor: recibe listas
  de `StreamCandidate` y devuelve la elección. Se prueba en la JVM sin red.
- **`NewPipeMediaResolver`** es el único punto que conoce las clases de
  `NewPipeExtractor` ligadas a streams, y traduce a ese modelo neutro.

Reglas de la política:

- Sin altura fijada ("Mejor") gana la **resolución más alta** disponible.
- A igualdad de resolución se prefiere **H.264**, que se puede multiplexar sin
  recodificar.
- Para audio + video hasta 720p basta un **progresivo**: un solo fichero, sin
  unión. Por encima, dos ficheros y unión.

## Descarga y multiplexado

```
MediaResolver.resolveStream()  ->  ResolvedMedia (videoUrl + audioUrl)
                                     |
             requiresMuxing == false  ->  HttpFileDownloader  ->  fichero
             requiresMuxing == true   ->  HttpFileDownloader x2 (temporales)
                                          ->  MediaMuxer       ->  MP4
```

- `MediaMuxer` tiene **dos rutas** y elige entre ellas con una función pura
  (`canRemuxWithoutReencoding`), que sí se prueba en la JVM:
  1. **`Mp4Remuxer` (la normal)**: si el vídeo es H.264 en MP4 y el audio es AAC
     en MP4, copia las muestras con `MediaExtractor` + `MediaMuxer` del sistema.
     Sin codificar: el archivo pesa lo mismo que las partes y el perfil no cambia.
     Es el caso habitual porque el selector ya prioriza MP4.
  2. **`media3-transformer` (el respaldo)**: solo cuando hay que convertir de
     verdad (VP9/AV1, Opus, WebM). Fuerza la salida a H.264 + AAC, al bitrate de
     origen y con `enableHighQualityTargeting` desactivado.
- El orden importa: **convertir siempre es el error**, porque el encoder elige
  sus propios parámetros y el archivo pesa varias veces más. Ver ADR-018.
- Si la ruta rápida no puede hacerse, `Mp4Remuxer` lanza `RemuxNotPossible` y se
  cae a la de conversión. Nunca se entrega un MP4 al que le falte una pista.
- También pasa por el muxer el **video suelto sin audio** cuando no viene en
  MP4: la pista de "solo video" por encima de 720p suele ser WebM, y guardarla
  con extensión `.mp4` daría un fichero que muchos reproductores no abren.
- Los temporales se llaman siempre igual (`<nombre>.video.tmp`,
  `<nombre>.audio.tmp`): al reanudar, `HttpFileDownloader` los continúa con
  `Range` en vez de empezar de cero.
- Se borran al completar y al cancelar; **no al pausar**, para poder reanudar.

## Flujo de Datos (UDF)

1. **UI → ViewModel**: acción del usuario.
2. **ViewModel → Use Case**: se despacha el caso de uso.
3. **Use Case → Repository**: el caso de uso coordina.
4. **Repository → Data Source**: el repositorio elige fuente.
5. **Data Source → MediaResolver / Room / DataStore**: resolución, descarga o E/S.
6. **Data Source → ViewModel → UI**: el resultado sube como `StateFlow`.

## Dependencias de Capas
- `feature` → `domain`, `core`
- `domain` → nada (sin framework)
- `data` → `domain`, `core`
- `core` → framework Android, Hilt, Room, DataStore

## Reproducción
- **Actual**: `ACTION_VIEW` con `FileProvider`. El usuario elige reproductor.
  Funciona, pero pierde la integración con la app.
- **Futuro**: Media3 `ExoPlayer` embebido, en `feature/player`.

## Notificaciones
`DownloadService` es un foreground service con canales y
`FOREGROUND_SERVICE_TYPE`. **Pendiente**: el motor de descarga todavía no lo
arranca, así que hoy no hay descarga real en segundo plano. Ver
`ESTADO_PROYECTO.md` P5.

## Persistencia
- **Room**: descargas, estados y progreso.
- **DataStore**: configuración del usuario.

## Tema
`LIGHT`, `DARK`, `SYSTEM` y `AMOLED` (negro puro para pantallas OLED).

## Sesión y cookies

`YouTubeSession` (`core/network`) carga un `cookies.txt` exportado del
navegador y lo expone como cabecera `Cookie`. La inyecta
`OkHttpNewPipeDownloader`, que es el **único** punto por el que pasan todas las
peticiones de NewPipeExtractor.

Dos reglas, ambas cubiertas por tests:

- La cabecera **solo se envía a hosts de YouTube**. Nunca al CDN
  `googlevideo.com`, que es donde bajan los medios: mandar la credencial de la
  cuenta a un tercero sería una fuga sin motivo.
- Si el fichero no trae ninguna cookie de sesión real, se **descarta entero** y
  la app sigue en modo anónimo. Devolver una cabecera vacía haría creer que hay
  sesión activa y produciría el mismo `LOGIN_REQUIRED` sin explicación.

Las cookies nunca se registran en el log ni se comitean (`.gitignore`).

## Testing
- **Unitarios** (JVM, sin dispositivo): sanitización de respuestas, nombres de
  fichero, MIME, path traversal, **política de selección de calidad** y **decisión
  de ruta del muxer**. 118 tests en verde.
- **Instrumentados** (`androidTest`, necesitan dispositivo): `Mp4RemuxerTest`
  (6) y `MediaMuxerTest` (6), 12 tests. Lo importante no es que haya pistas, sino
  que **no se pierda nada ni se cambie el formato**: se comparan muestras y bytes
  de entrada y salida, y que el `csd-0` (la configuración del codec) sea el
  mismo, que es la prueba de que no hubo recodificación.
  Los medios de prueba son dos ficheros pequeños en `androidTest/assets`, y se
  leen con el contexto **de la instrumentación** (`InstrumentationRegistry`),
  no con el de la app: los assets de `androidTest` van en el APK de test.
- **Pendientes**: `HttpFileDownloader` con `MockWebServer`, use cases con MockK.
  `Mp4Remuxer` **no** es testeable en la JVM (depende de los codecs de Android),
  así que tiene su propia suite instrumentada; lo que sí se prueba en la JVM es
  la *decisión* de qué ruta tomar.
- Las dos suites son independientes: que los unitarios estén verdes no dice nada
  de que el sourceSet de instrumentación compile. Compilar los dos antes de
  fiarse de una cifra de tests.
- **Cobertura objetivo**: 80% de `domain` y `data`.
