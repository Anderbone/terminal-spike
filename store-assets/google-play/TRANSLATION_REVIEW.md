# Translation review

The `en-US` listing is the source copy. The other locale folders are publication-ready drafts,
but they were not reviewed by a native-speaking legal or marketing reviewer. Google recommends
native-speaker review for store listings, and every translation is subject to the same metadata
policy as the default listing.

Before publishing a locale:

1. Have a native speaker review the title, short description, and full description in context.
2. Preserve the product name `Terminal Spike`, protocol names `SSH` and `SFTP`, and the product name
   `tmux`.
3. Re-run `./validate-store-assets.sh`; title, short-description, and full-description limits apply
   independently to every locale.
4. Do not add claims about price, ranking, awards, availability, or performance.
5. If the app UI is not localized for that language, confirm that using the default English UI
   screenshots is acceptable for the intended launch market.

Prepared locales:

- `en-US` — English (United States), default/source
- `es-ES` — Spanish (Spain)
- `de-DE` — German (Germany)
- `fr-FR` — French (France)
- `pt-BR` — Portuguese (Brazil)
- `ja-JP` — Japanese (Japan)
- `ko-KR` — Korean (South Korea)
