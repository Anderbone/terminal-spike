# Translation review

The `en-US` source listing was refreshed on 2026-10-06. Other locale folders contain older drafts.
Update them against the English source and have a native speaker review them before publication.
The new phone artwork includes English headlines; localize those alongside the listing text.

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
