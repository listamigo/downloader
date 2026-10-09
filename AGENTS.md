# Estándares de código — a. donwloader

App Android (Kotlin + Jetpack Compose M3) de descarga de medios. Arquitectura
por capas: `domain` → `data` → `core` → `feature`. Build y tests deben quedar
**en verde** antes de commitear (`./gradlew :app:assembleDebug` y los tests).

## Arquitectura y capas

- `domain/`: modelos y casos de uso puros. **Sin imports de Android** (ni
  Context, ni Room, ni Compose).
- `data/`: implementaciones de repositorios y sources. Implementa interfaces
  de `domain`, nada de lógica de UI.
- `core/`: infraestructura (database, download, extract, network, di).
- `feature/`: pantallas Compose. Solo UI + estado; la lógica vive en
  domain/data. Un screen no habla directo con Room ni con OkHttp.
- DI manual en `core/di` (sin Hilt/Koin): si agregás una dependencia,
  reglala ahí, no la instancies desde la UI.

## Kotlin

- Preferir `val`, inmutabilidad y funciones puras donde se pueda.
- Sin `!!`: manejar nullabilidad explícita.
- Sin excepciones silenciadas: o se propagan o se registran/se mapean a
  errores de dominio (`ExtractionErrors` es el patrón existente).
- Coroutines para I/O; nada de bloquear el hilo principal.
- Números mágicos y strings de usuario: constantes o recursos.

## Descarga y multimedia (dominio crítico)

- El multiplexado **nunca recodifica**: `MediaMuxer`/`Mp4Remuxer` solo
  unen streams. No introducir transcodificación.
- La selección de calidad debe respetar el patrón existente de
  `StreamSelection`/`SegmentPlanner`.
- Cookies de sesión: nunca hardcodear; van por el flujo de `YouTubeSession`.

## Compose

- Material 3 (`darkColorScheme`/`lightColorScheme` ya definidos): no
  colores hardcodeados en composables.
- Extraer estado a ViewModel/casos de uso; los composable reciben estado y
  emiten eventos.

## Tests

- Toda lógica nueva de dominio/core lleva unit test (el proyecto ya tiene
  123 — mantener esa barra).
- Regresiones de descarga/multiplexado: test instrumentado cuando aplique.

## Documentación

- Si cambia el estado real del proyecto, actualizar `ESTADO_PROYECTO.md`
  (solo con verificación de build/ejecución, sin declarar sin probar).
