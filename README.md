# AIChat

A simple, fully offline desktop chat for local LLMs.

Built with **Java 25** and **JavaFX 25**, powered by **[Ollama](https://ollama.com)**
through **[LangChain4j](https://github.com/langchain4j/langchain4j)**.

## Why

Most chat apps send your data to the cloud. This one doesn't.
Everything runs locally — no internet, no telemetry, no leaks.
Perfect for experimenting with LLMs on confidential data.
*(Note: An internet connection is required only for the initial download of Ollama models).*

## Features

- **Fully offline** — runs against a local Ollama instance.
- **Multiple Servers** — configure and switch between named Ollama servers on the fly.
- **Multiple Prompts** — manage named system prompts and switch them from the toolbar or settings.
- **Model switching** — change the active model directly from the UI.
- **Search** — in-transcript search field with match highlighting and navigation.
- **Edit & Delete** — edit the last sent message or delete history from a specific point using the hover panel or right-click context menu.
- **Regenerate & Copy** — re-run the last user message, or copy the latest response (with Markdown stripped).
- **Attachments** — attach text/code files to your prompt (size limited by config).
- **Export** — save the current conversation as Markdown, JSON, HTML, or PDF.
- **Cancellation** — abort the current generation with the "Stop" button.
- **Themes** — Nord Light/Dark, Primer Light/Dark.
- **Usage Metrics** — displays response time and generation speed (tokens/second).

## Requirements

- **Java 25** or newer — [Download](https://jdk.java.net/25/)
- **Maven 3.9+** — [Download](https://maven.apache.org/download.cgi)
- **Ollama** — see below

## Keyboard shortcuts

| Shortcut | Action |
| :--- | :--- |
| `Enter` | Send message |
| `Ctrl + L` | Clear chat history |
| `Ctrl + K` | Focus model selector |
| `Esc` | Focus input field / Close context menu |
| `Enter` (in search) | Jump to next match |
| `Shift + Enter` (in search)| Jump to previous match |

## Configuration

The application uses a two-tier configuration system:
1. **Bundled defaults** (`application.properties` inside the jar): Fallback values for all settings.
2. **User overrides** (`~/.aichat/config.properties`): Created automatically when you change settings in the UI. Survives application restarts.

The following settings are persisted in the user config:
- **Ollama Servers:** The `local` server is mandatory. Additional servers can be added. The active server URL is derived from this list.
- **System Prompts:** The `default` prompt is mandatory. Additional prompts can be added.
- **LLM Parameters:** Temperature, request timeout, history limit (max messages sent to the model).
- **UI Settings:** Active theme, selected model, attachment size limit.

*(Note: You can manually edit `~/.aichat/config.properties`, but it's recommended to use the Settings dialog to avoid syntax errors).*

---

## Installing Ollama

Ollama runs the LLM locally on your machine and exposes a small HTTP API
on `http://127.0.0.1:11434`. The app connects to that API.

### Windows

1. Download the installer from [ollama.com/download](https://ollama.com/download).
2. Run `OllamaSetup.exe` and follow the wizard.
3. Ollama starts in the background and adds the `ollama` command to `PATH`.
4. Open a **new** terminal and verify:

```bash
ollama --version
```

### macOS

1. Download `Ollama-darwin.zip` from [ollama.com/download](https://ollama.com/download).
2. Unzip and drag `Ollama.app` into `Applications`.
3. Launch it once — the Ollama icon appears in the menu bar.
4. Verify in a terminal:

```bash
ollama --version
```

### Linux

Run the official install script:

```bash
curl -fsSL https://ollama.com/install.sh | sh
```

Then verify:

```bash
ollama --version
```

To start the server manually (if not already running as a service):

```bash
ollama serve
```

### Downloading models
Models are pulled separately from the Ollama engine. You need at least one.

### Recommended models

| Model | Size | Notes |
| :--- | :--- | :--- |
| `qwen2.5:7b` | ~4.7 GB | Best balance for Russian and English |
| `llama3.1:8b` | ~4.9 GB | Strong general-purpose model |
| `gemma2:2b` | ~1.6 GB | Very small, good for low-RAM machines |
| `gemma2:9b` | ~5.4 GB | Larger, slower, better quality |

### Pull a model

```bash
ollama pull qwen2.5:7b
```

This downloads the model into Ollama's local storage. After that, the app
can use it even without an internet connection.

### List installed models

```bash
ollama list
```

Example output:

```text
NAME              ID              SIZE      MODIFIED
qwen2.5:7b        xxxxxxxxxxxx    4.7 GB    2 minutes ago
llama3.1:8b       xxxxxxxxxxxx    4.9 GB    1 hour ago
```

### Remove a model (optional)

```bash
ollama rm qwen2.5:7b
```

---

## Running the app

From the project root:

```bash
mvn clean javafx:run
```
Or, in IntelliJ IDEA, run the `Launcher` class directly.

### VM options

Two JVM flags are recommended when running the app:

- `--enable-native-access=javafx.graphics,javafx.web` — required by JDK 24+ for JavaFX's native calls. Without it, the JVM prints a "restricted method" warning on startup.
- `-Dorg.slf4j.simpleLogger.defaultLogLevel=debug` — enables debug logs. Optional; useful when troubleshooting.

Optional, to print logs to stdout instead of stderr:
- `-Dorg.slf4j.simpleLogger.logFile=System.out` — makes INFO logs appear in white instead of red in IntelliJ's console. Purely cosmetic.

#### IntelliJ IDEA

**Run → Edit Configurations → VM options**, paste:

```text
--enable-native-access=javafx.graphics,javafx.web
```

### First request is slow
The first time you send a message, Ollama loads the model into memory.
This can take 10–30 seconds depending on your hardware. Subsequent
requests are much faster.