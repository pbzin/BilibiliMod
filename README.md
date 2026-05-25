[![Repo Views](https://api.visitorbadge.io/api/visitors?path=https%3A%2F%2Fgithub.com%2Fpbzin%2FBilibiliMod&label=repo%20views&countColor=%230e75b6&style=flat)](https://visitorbadge.io/status?path=https%3A%2F%2Fgithub.com%2Fpbzin%2FBilibiliMod)

![Downloads](https://img.shields.io/github/downloads/pbzin/BilibiliMod/total?style=flat&color=0e75b6&label=downloads)

# BilibiliMod

LSPosed module for foreign Bilibili users (`tv.danmaku.bili`).

It forces Bilibili to treat the app as the CN version/region and unlocks hidden mobile video categories, similar to the category access available on PC.

## Features

- Forces Bilibili region/version behavior to China (`CN`) for foreign users.
- Restores access to hidden video categories in the mobile app, similar to Bilibili on PC.
- Opens the category menu through `bilibili://main/top_category`.
- Sets Portuguese as the preferred subtitle language when Bilibili does not provide a preferred language.
- Logs subtitle and translation diagnostics with the `BilibiliMod` logcat tag.
- Translates video titles in category and author-space pages through Bilibili's internal `TranslationMoss` endpoint.

## Usage

1. Install the module APK.
2. Enable the module in LSPosed.
3. Select only `tv.danmaku.bili` as the module scope.
4. Force close Bilibili and open it again.

> [!IMPORTANT]
> On the first Bilibili launch after installing/enabling this module, use a Chinese VPN. If the app is opened without the VPN first, Bilibili may hide the categories and they usually return only after clearing Bilibili app data.

## Notes

- Title translation on category pages runs through the render path used by that Compose screen, so the first category opening may have a short delay.
- The module does not store a persistent translation cache.
- Avoid scoping Bilibili into another module that hooks the same behavior, otherwise effects may be duplicated.

## Donate

I do not have a stable income and I build this module for fun in my free time. If it helps you, any support keeps the project alive and means a lot.

[![Donate](https://img.shields.io/badge/Donate-Support%20the%20project-ff69b4?style=for-the-badge)](https://github.com/pbzin)
