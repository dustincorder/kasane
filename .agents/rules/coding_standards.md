# VibePlayer Code Quality & Style Guidelines

## Rust
- Zero warnings under `cargo clippy --workspace -- -D warnings`.
- Never use `.unwrap()` or `.expect()` in production error-handling code paths. Use `Result<T, VibeError>` with `?` or handle errors explicitly.
- Keep domain structs and traits decoupled from concrete playback backends and cloud APIs.
- Audio and heavy DSP processing must execute in separate threads/tasks off the UI event loop.

## Frontend (Svelte 5 & TypeScript)
- Use modern Svelte 5 runes (`$state`, `$derived`, `$effect`, `$props`).
- Strictly adhere to TypeScript typings; avoid `any`.
- Keep the visual identity clean, dark, and focused on typography and album artwork.
- Strictly avoid generic AI clichés: purple glows, gratuitous glassmorphism, or intrusive chatbot interfaces.
