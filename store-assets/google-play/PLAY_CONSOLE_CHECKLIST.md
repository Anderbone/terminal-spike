# Google Play listing checklist

## Prepared here

- Default `en-US` title, short description, and full description.
- Six additional store-listing translations, each kept in its own Play locale folder.
- 512 × 512 app icon copied from the project-owned icon master.
- 1024 × 500 opaque feature graphic derived from the project-owned visual system.
- Four or more actual in-app screenshots for phone, 7-inch tablet, and 10-inch tablet.
- Unique English alt text for every screenshot.
- Automated validation for dimensions, formats, file sizes, and metadata character limits.

## Console values that still need an owner decision

- Confirm `Terminal Spike` as the permanent public product name.
- Category: recommended `Tools`.
- Tags: choose only terms offered by Play Console that accurately describe SSH, terminal, or
  remote administration functionality.
- Required support email, optional support website, and optional phone number.
- Countries/regions, pricing, target audience, content rating, and app access declarations.
- Privacy-policy URL and Data safety answers.
- Foreground-service declaration and demonstration video where requested by Play Console.
- Whether the separately distributed Mosh-compatible extension will be published. It is
  deliberately omitted from this main-app listing pending the licensing and trademark decision in
  `docs/PUBLISHING.md`.

## Upload order

1. Add the `en-US` listing and upload `graphics/app-icon-512.png` plus
   `graphics/feature-graphic-1024x500.png`.
2. Upload the numbered images from each screenshot folder in filename order.
3. Add each approved locale from `metadata/` and paste its three text files.
4. If Play Console exposes screenshot alt-text fields, paste the matching entry from
   `SCREENSHOT_ALT_TEXT.md`.
5. Preview phone and tablet pages before saving the draft.

No preview video is included. A video is optional, and a truthful one requires a public or unlisted
YouTube upload with ads disabled; that external publication is outside this local asset package.
