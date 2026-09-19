# PawfectAddons FAQ

## Account & Safety

**Does PA have access to my Minecraft account?**
No. PA never sends your session token to us or anyone else. When you link your account, the token is handed to *Mojang's* servers — the exact same thing that happens every time you join a Minecraft server. All our site ever receives is your username and a random one-time code, which we then ask Mojang to confirm.

**So what does PA actually know about me?**
Your UUID, your username, which cosmetics you own, and your cosmetic settings. That's it. No passwords, no tokens, no emails.

**Can I see the code that does this?**
Yes — yes, the whole mod is open source at https://github.com/pawliciously/pawfectaddons - the auth code is `CosmeticEnroll.kt` and `CosmeticLink.kt`. It's two files. If you'd rather check yourself, decompile the jar and search for `accessToken`; it only appears in those two places.

**Does PA connect to the internet?**
Yes, for cosmetics only. It downloads the public cosmetics list every 30 minutes, and verifies your account once so we know which cosmetics are yours. Nothing else is sent anywhere.

**Is PA a cheat? Will I get banned?**
No. PA doesn't automate anything, doesn't play for you, and doesn't send anything to Hypixel that vanilla wouldn't. Everything it does is drawing information you can already see.

## Setup

**What do I need?**
Minecraft **26.1.2** with **Fabric**, plus **Fabric API** and **Fabric Language Kotlin**. PA is client-side only — you don't need it on any server.

**How do I open the menu?**
Type `/pa` in chat.

**Does it work alongside SkyHanni / NEU / Skyblocker?**
Yes. If two mods draw the same overlay you'll want to turn one of them off, but nothing breaks.

**Where do I download it?**
https://pawfectaddons.net. Please don't use jars from anywhere else — if someone DMs you a "PA build", it isn't ours.

## Cosmetics

**How do I get cosmetics?**
Capes, custom names, trails and motes are donor perks. Badges are earned.

**How do I change my cosmetic settings?**
Go to **me.pawfectaddons.net** and link your account, or hit *Open Editor* in the Cosmetics tab of `/pa`.

**I was given a cosmetic but I can't see it.**
Give it up to 30 minutes, or restart your game to pull it immediately.

**Can people without PA see my cosmetics?**
No. Cosmetics are drawn by the mod, so only other PA users see them.

## Problems

**Something's broken / I found a bug.**
Post in the support channel with your Minecraft version, your PA version, and your `latest.log` (found in `.minecraft/logs`). The log is the important part.
