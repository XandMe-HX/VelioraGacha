# VelioraGacha

Hologram crate memakai satu kelompok permanen untuk setiap crate. Setiap elemen di `pages[0].lines` menjadi satu baris TextDisplay dengan jarak yang dapat diatur melalui `height`. Halaman tidak berganti otomatis, sehingga hologram tidak menumpuk menjadi satu kotak besar dan tidak menggandakan entity.

Konfigurasi utama:

```text
plugins/VelioraGacha/config.yml
```

Untuk membangun ulang hologram setelah mengubah konfigurasi, gunakan command admin hologram VelioraGacha. Proses respawn juga membersihkan TextDisplay lama yang ditandai sebagai milik crate tersebut.
