# Obsidian Capture — Technical Overview

> **Keep this file up to date with every significant change to the project.**

## What This App Does

Android app that captures photos, sends them to a vision-capable LLM, and saves the AI-generated markdown note plus the original image to an Obsidian vault folder on-device (via Storage Access Framework).

## Architecture

Single-Activity Compose app with a ViewModel-driven navigation model (no Jetpack Navigation library).

```
MainActivity
  └── MainViewModel          ← all business logic, navigation state, settings
        ├── SettingsRepository ← DataStore persistence
        └── LlmClient          ← HTTP calls to AI providers
```

**Screens** (sealed class `MainViewModel.Screen`):
- `Camera` — live camera preview, capture button, zoom/lens controls, High/Med effort toggle
- `Settings` — provider/model/API key/output folder configuration
- `Processing` — progress indicator during the AI pipeline
- `ViewCapture` — read-only view of a saved capture record
- `Error` — shows API or file errors with retry/retake options

## Source Files

| File | Purpose |
|------|---------|
| `MainActivity.kt` | Entry point; wires ViewModel state to Composable screens |
| `MainViewModel.kt` | Capture workflow, location lookup, file saving, navigation, effort toggle state |
| `data/AppSettings.kt` | Settings data class |
| `data/SettingsRepository.kt` | DataStore-backed persistence for settings + capture history |
| `data/LlmClient.kt` | OkHttp-based OpenAI-compatible API client |
| `data/ProviderConfig.kt` | Provider/model catalogue (OpenAI, OpenRouter, Google, Custom) |
| `data/CaptureRecord.kt` | Capture history entry (serialised to/from JSON) |
| `ui/CameraScreen.kt` | Camera preview, pinch-to-zoom, lens selector, capture FAB, effort chips |
| `ui/SettingsScreen.kt` | Settings form with three model dropdowns + two system prompt fields |
| `ui/ProcessingScreen.kt` | Spinner with step label |
| `ui/ResultScreen.kt` / `HistorySheet.kt` / `ErrorScreen.kt` | Supporting screens |
| `ui/theme/Theme.kt` | Material3 theme |

## AI Provider Integration

All providers expose an **OpenAI-compatible `/chat/completions` endpoint**. The `LlmClient` always uses this format.

| Provider | Base URL | Notes |
|----------|----------|-------|
| OpenAI | `https://api.openai.com/v1` | |
| OpenRouter | `https://openrouter.ai/api/v1` | Adds `HTTP-Referer` + `X-Title` headers |
| Google | `https://generativelanguage.googleapis.com/v1beta/openai` | Gemini + Gemma models |
| Custom | User-specified | Any OpenAI-compatible endpoint (e.g. Ollama) |

### Dynamic Model Fetching

When the Settings screen opens (or the provider is changed), `LlmClient.fetchModels()` is called with the current base URL and API key. It hits the provider's `GET /models` endpoint (OpenAI-compatible). Results replace the static fallback list in all three model dropdowns. A ↻ refresh button re-triggers the fetch manually (useful after entering a new API key).

Filtering applied per provider:
- **OpenRouter** — removes free models (`pricing.prompt == 0 && pricing.completion == 0`)
- **OpenAI** — keeps only chat models (IDs starting with `gpt-`, `o1`, `o3`, `o4`, `chatgpt-`)
- **Google** — includes all models from the endpoint
- **Custom** — skips fetch, shows free-text model ID field

### Three-Model System

`AppSettings` holds three model IDs and two system prompts:

| Field | Purpose |
|-------|---------|
| `highEffortModel` | Detailed image analysis — used when camera toggle = **High** |
| `mediumEffortModel` | Quick image analysis — used when camera toggle = **Med** |
| `lowEffortModel` | Filename generation — always used for this task |
| `systemPrompt` | Full Obsidian-style prompt for high effort analysis |
| `mediumSystemPrompt` | Brief 4-line prompt for medium effort analysis |

The **High / Med toggle** lives in `MainViewModel.useHighEffort` (toggled by `toggleEffort()`). It selects which model and prompt are passed to `LlmClient.analyzeImage(settings, bytes, model, systemPrompt)` — the client takes them explicitly so it doesn't need to know about effort levels.

### Available Models (ProviderConfig.kt)

**OpenAI:** gpt-4o, gpt-4o-mini, gpt-4.1, gpt-4.1-mini  
**OpenRouter:** GPT-4o/4.1/4.1-mini, Claude Opus 4.7 / Sonnet 4.6 / Haiku 4.5, Gemini 2.5 Pro / 2.0 Flash, Gemma 4 27B, Llama 3.2 90B Vision  
**Google:** Gemini 2.5 Pro / 2.0 Flash / 1.5 Pro / 1.5 Flash, Gemma 4 27B

> **Note on Gemma 4:** Model IDs follow the `gemma-4-27b-it` pattern. Verify exact IDs at [Google AI docs](https://ai.google.dev/gemini-api/docs/models) if they differ.

## Camera Implementation

Uses **CameraX** (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view` — all 1.6.0).

### Lens Detection (`CameraScreen.kt`)

On first compose, `detectLenses()` uses `android.hardware.camera2.CameraManager` to enumerate back-facing cameras, reads their minimum focal length, sorts them (shortest = widest), and assigns labels:
- 1 camera → "Main"
- Multiple cameras → "Wide" / "Main" / "Tele"

Zoom factors shown in the UI are relative to the widest lens (widest = 1x).

### Lens Switching

`buildCameraSelector(cameraId)` creates a `CameraSelector` using `Camera2CameraInfo` filter. The `LaunchedEffect` re-runs on `selectedLensIndex` change, unbinding and rebinding with the new selector. No `@OptIn` is needed — `ExperimentalCamera2Interop` is stable in CameraX 1.6.0.

### Zoom

- **Pinch gesture** on the preview via `detectTransformGestures` → `camera.cameraControl.setZoomRatio()`
- Current zoom ratio displayed bottom-left (white text)
- Range observed from `camera.cameraInfo.zoomState` LiveData

### Effort Toggle

Two `FilterChip` buttons (**High** / **Med**) sit on the right side of the FAB row. They display and toggle `MainViewModel.useHighEffort` via the `onToggleEffort` callback. Uses `androidx.lifecycle.compose.LocalLifecycleOwner` (from `lifecycle-runtime-compose:2.8.6`).

## Capture Workflow

1. `CameraScreen` → user selects effort level (High/Med) and taps FAB
2. CameraX captures JPEG to temp file in `cacheDir`
3. `MainViewModel.onImageCaptured(bytes)` launches coroutine:
   - `fixImageOrientation(bytes, quality)` — reads EXIF rotation tag, physically rotates the bitmap if needed, re-encodes to JPEG at the user's quality setting; corrected bytes are used for both the LLM and the saved file
   - Picks model + prompt based on `useHighEffort`
   - `LlmClient.analyzeImage(settings, correctedBytes, model, prompt)` → markdown
   - `LlmClient.generateFilename(settings, markdown)` → kebab-case name (uses `lowEffortModel`)
   - `getLastKnownLocation()` → GPS coordinates
   - `reverseGeocode()` → human-readable address via Nominatim
   - `saveFiles()` → writes image to `_resources/{date}-{name}/image.jpg` and `{date}-{name}.md` with YAML frontmatter
4. Record persisted to DataStore history (last 20 entries)

**File layout on disk:**
- `{output_folder}/{date}-{name}.md`
- `{output_folder}/_resources/{date}-{name}/image.jpg` (single capture)
- `{output_folder}/_resources/{date}-{name}/image-1.jpg`, `image-2.jpg`, … (multi-image capture)

## Persistence

**DataStore Preferences** keys (`SettingsRepository.kt`):
`provider_name`, `llm_url`, `api_key`, `high_effort_model`, `medium_effort_model`, `low_effort_model`, `system_prompt`, `medium_system_prompt`, `output_folder_uri`, `image_quality`, `history` (JSON array)

**YAML frontmatter fields** (when location is available):
`latitude`, `longitude`, `place`, `address`, `map` (Google Maps URL `https://www.google.com/maps?q=lat,lon`)

## Permissions

| Permission | Use |
|-----------|-----|
| `CAMERA` | Camera capture |
| `INTERNET` | LLM API calls + Nominatim geocoding |
| `ACCESS_FINE_LOCATION` | GPS coordinates for note frontmatter |
| `ACCESS_COARSE_LOCATION` | Fallback location |

## Key Dependencies

```
androidx.camera:camera-*:1.6.0
androidx.datastore:datastore-preferences:1.1.1
com.squareup.okhttp3:okhttp:4.12.0
androidx.compose:compose-bom:2026.03.00
androidx.lifecycle:lifecycle-runtime-compose:2.8.6
androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6
```

## Build

- Min SDK: 26 (Android 8.0)
- Target SDK: 36
- Portrait-only (`android:screenOrientation="portrait"`)
- Language: Kotlin 2.x + Jetpack Compose (Material3)
