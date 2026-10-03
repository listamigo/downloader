# elimd downloader

Aplicación Android para buscar y descargar vídeo/audio de YouTube **directamente
desde el dispositivo**, sin servidor local y sin binarios externos.

## Documentación

| Documento | Para qué |
|---|---|
| **[ESTADO_PROYECTO.md](ESTADO_PROYECTO.md)** | **Empieza aquí.** Estado verificado, bitácora de errores y pendientes. |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Arquitectura por capas y la frontera `MediaResolver`. |
| [docs/DECISIONS_LOG.md](docs/DECISIONS_LOG.md) | ADR, incluidas las decisiones descartadas y por qué. |
| [docs/MANUAL_DE_DESARROLLO.md](docs/MANUAL_DE_DESARROLLO.md) | Guía de desarrollo y comandos. |

## Estado

- Build: **verde** (`./gradlew :app:assembleDebug`)
- Tests: **118 unitarios + 12 instrumentados, en verde**
- APK debug: 23 MB
- Verificado en dispositivo real (Android 16): búsqueda, paginación, hoja de
  calidad hasta 2160p, descarga de 1080p completa y unión **sin recodificar**
  (archivo final 1,0006x las partes, perfil y bitrate intactos).
- El bloqueo de YouTube por acceso anónimo está **resuelto con cookies de
  sesión**; no hace falta servidor.

## Arranque rápido

```bash
cd "/home/elimdavid/a. donwloader/"
./gradlew :app:assembleDebug        # compilar
./gradlew :app:testDebugUnitTest    # tests
./gradlew :app:installDebug         # instalar por adb
```

## Cómo funciona

```
YouTube ──► NewPipeExtractor (Java puro) ──► OkHttp ──► fichero en el móvil
```

NewPipeExtractor es la misma librería que usa la app NewPipe en producción:
descifra las URLs firmadas de YouTube usando Rhino como motor JavaScript. No
necesita servidor, ni Python, ni root.

Se fuerza el cliente **iOS** de InnerTube en lugar del de Android: con Android,
un vídeo típico expone **1 formato** (360p); con iOS, **27**. Eso es lo que hace
que haya calidades que elegir.

## Calidades

YouTube solo entrega audio y video juntos hasta ~720p. Por encima hay **dos
pistas separadas**, así que la app las baja por separado y las une en un único
MP4 (`media3-transformer`) cuando el usuario elige una de esas calidades. Las
opciones que requieren esa unión se marcan en el selector, para que el coste
extra sea visible antes de confirmar.

> **Nota importante**: una versión anterior de este proyecto documentaba que el
> motor era `yt-dlp` ejecutado con `ProcessBuilder` desde Android. **Eso es
> imposible**: Android no tiene shell ni permiso `exec`, yt-dlp no publica build
> para Android, y sus binarios requieren glibc cuando Android usa bionic. Ver
> [ADR-005](docs/DECISIONS_LOG.md) para el análisis completo.

## Sesión (cookies)

Desde muchas IPs, YouTube no responde al cliente anónimo: devuelve
`SignInConfirmNotBotException` y la app no puede descargar nada. La causa **no es
la IP ni el cliente**, es que falta una sesión — el propio error de YouTube pide
iniciar sesión, y el de yt-dlp dice *"Use --cookies-from-browser or --cookies"*.

Por eso la app admite un `cookies.txt` exportado de tu navegador con la sesión
de YouTube iniciada:

```bash
adb push cookies.txt /sdcard/Android/data/com.elimd.downloader/files/cookies.txt
adb shell am force-stop com.elimd.downloader
adb logcat -s YouTubeSession:*   # debe decir "Sesion cargada ... N cookies"
```

**Detalles que importan:**

- `cookies.txt` está en `.gitignore`. **Nunca** lo comitees: es una credencial
  de tu cuenta.
- Las cookies **solo se envían a hosts de YouTube**, nunca al CDN
  `googlevideo.com` donde bajan los medios. Hay tests que lo comprueban.
- Si el fichero no trae cookies de sesión reales, la app lo ignora y sigue en
  modo anónimo, en vez de fingir que hay sesión.
- Caducan. Cuando el error vuelva a aparecer, renuévalas y repite el `push`.

## Pruebas

```bash
./gradlew :app:testDebugUnitTest        # 118 unitarios en la JVM (rápido)
./gradlew :app:assembleDebugAndroidTest # compilar los instrumentados
./gradlew :app:connectedDebugAndroidTest # 12 instrumentados, necesitan dispositivo
```

Conviene distinguir las dos suites: los unitarios no tocan los codecs de Android,
así que que `:app:testDebugUnitTest` salga verde **no dice nada** de que compile
el sourceSet de instrumentación. Compila los dos (`assembleDebugAndroidTest`)
antes de dar por buena cualquier cifra de tests.

Los instrumentados cubren `Mp4Remuxer` y `MediaMuxer`, que dependen de los
codecs de Android. Comprueban, entre otras cosas, que el remux **no pierde
muestras ni cambia el formato** (mismo `csd-0`), que es la garantía de que no se
recodifica. Si Gradle falla con *"Unable to find instrumentation target
package"*, es que desinstaló la app: instalar ambos APK a mano y lanzar
`am instrument`.

## Stack

Kotlin · Jetpack Compose (Material 3) · Clean Architecture · Hilt · Room ·
DataStore · NewPipeExtractor (JitPack) · OkHttp · Media3

`compileSdk 34` · `minSdk 21` · `targetSdk 34`
