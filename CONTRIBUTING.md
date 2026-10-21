# Contributing to Acrylterm

Thanks for helping out! Issues and pull requests are welcome in Japanese, English or Chinese.
日本語・英語・中文のどれで書いても大丈夫です。

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Make your change and check it builds:
   ```sh
   ./gradlew :app:assembleDebug :app:testDebugUnitTest
   ```
   Gradle needs JDK 17 or newer (Android Studio's bundled JDK works). `app/libs/tsbridge.aar` is optional;
   without it the app builds with Tailscale and Mosh compiled out. See the README for building it.
3. Open a pull request against `main`. CI runs the same build, unit tests and lint.

If you change dependencies (`app/build.gradle.kts` or `tsbridge/go.mod`), run `scripts/gen-go-licenses.sh` and commit
the changes under `app/config/`. If you don't have Go, mention it in the PR and the maintainer will regenerate them.

## UI text

Don't hardcode user-visible text in Kotlin. Add it to both files of the matching area, with the same key:

- `app/src/main/res/values/strings_<area>.xml` — English (the default)
- `app/src/main/res/values-ja/strings_<area>.xml` — Japanese

Areas: `term` (terminal screen), `herdrui` (tabs / panes / workspaces), `ext` (Lua extensions), `settings`, `core`
(connections, notifications, errors). Use `stringResource(...)` in composables. Commands sent to the host and prompts
sent to AI CLIs are not UI text and stay untranslated.

## Translations

To add a language, e.g. Simplified Chinese:

1. Copy the five `app/src/main/res/values/strings_*.xml` files to `app/src/main/res/values-zh-rCN/` and translate the
   values. A partial translation is fine; untranslated strings fall back to English.
2. Keep the keys (`name="..."`) unchanged, and keep placeholders such as `%1$s` and `%2$d`. You may reorder them to fit
   the sentence.
3. In `<plurals>`, include the quantities your language uses (Chinese and Japanese only need `other`).
4. Don't translate product names: Acrylterm, herdr, Tailscale, Mosh, SSH, Lua.
5. Escape `'` as `\'` and `&` as `&amp;`.
6. Add the locale to `app/src/main/res/xml/locales_config.xml`, e.g. `<locale android:name="zh-CN" />`. On Android 13+
   it then appears under Settings > Apps > Acrylterm > Language (also reachable from the app's Settings > Language).
7. Please check on a device that labels aren't cut off, especially in narrow places such as the extra keys bar and the
   tab strip. Screenshots in the PR help a lot.

Terminology suggestions (what to call a pane, tab, workspace, worktree…) are very welcome in the PR description.

## License

By contributing you agree that your contribution is licensed under the [Apache License 2.0](LICENSE).
