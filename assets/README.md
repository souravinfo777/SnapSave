# assets/

`apikeys.txt` is intentionally **not** committed (see `.gitignore`).

To run the app against the FastSaver backend either:

1. create `assets/apikeys.txt` here with one FastSaver API key per line, e.g.

   ```
   fs_sk_your_key_here
   fs_sk_second_account_key
   ```

   (multiple keys are rotated automatically), or
2. paste the key(s) once into the app's **Settings → API keys** screen.

Without a key the app still works, but only through the built-in on-device extractors —
`Resolvers` falls back to TikTok's public resolver and generic page scraping.
