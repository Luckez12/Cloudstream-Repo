# AnimeXTV v1 — Sub only

Salin folder `AnimeXTV` ke root repo CloudStream yang sedia ada, kemudian commit dan jalankan workflow build repo. ZIP ini ialah source patch, bukan fail `.cs3`. Root settings repo asal mengesan modul secara automatik; tiada perubahan YAML diperlukan.

- Homepage, carian dan metadata AniList; fallback MAL menggunakan backend laman.
- Episod dan semua laluan player hanya Sub. Payload Dub ditolak.
- Megaplay mempunyai extractor native, dekripsi respons dan token mengikut player awam laman, serta sari kata.
- Nama pautan: `Megaplay · Sub · Auto (HLS)`. Jika master boleh dibaca, nama memaparkan julat resolusi sebenar. Resolusi tidak diteka.
- Vidnest AnimePahe, Vidnest, TryEmbed dan FrameXTV dicuba melalui extractor CloudStream. Sokongan keempat-empat mirror ini belum disahkan dan memerlukan extractor app yang sepadan.
- Log ringkas; tiada dump HTML, playlist atau URL token.

## Pengesahan

Kompilasi Kotlin JVM 11 dengan stub API CloudStream dan ujian fixture lulus: katalog/search/detail, fallback MAL, tajuk/poster null, jumlah episod, Sub-only, respons Megaplay sebenar yang dinyahenkripsi, token dan header, sari kata serta label master HLS.

Build Gradle Android/GitHub belum dijalankan. URL CDN tidak dapat memberikan playlist sah dalam persekitaran ini; master HLS dan playback app belum disahkan. Respons getSources Megaplay berjaya diambil dan dinyahenkripsi. Semak build GitHub dan cuba beberapa episod dalam app; eksport log ringkas jika gagal.
