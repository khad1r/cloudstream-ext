# Cloudstream extensions

This repository contains a collection of extensions for [Cloudstream3](https://github.com/recloudstream/cloudstream)

## ⚙️ Cara Menggunakan

1. Buka aplikasi CloudStream.  
   Jika belum punya, download [DISINI](https://github.com/recloudstream/cloudstream/releases)
2. Masuk ke menu **"Pengaturan"** > **"Ekstensi"**
3. Klik tombol **"Tambahkan Repositori"**
4. Masukkan URL repositori:
   - `https://raw.githubusercontent.com/khad1r/cloudstream-ext/main/repo.json`
5. Klik **"Tambahkan"**
6. Ekstensi yang tersedia akan muncul di daftar
7. Pilih ekstensi yang ingin diinstal dan klik **"Instal"** ✅

---

## 🛠️ Update Constants (Anoboy & Gofile)

Situs streaming **Anoboy** sering mengubah domainnya untuk menghindari pemblokiran, dan **Gofile** secara berkala memperbarui token salt enkripsinya (`X-Website-Token`).

Untuk mempermudah pemeliharaan, repositori ini dilengkapi dengan script otomatisasi Node.js (`update-constants.js`) yang secara otomatis mendeteksi domain Anoboy yang aktif, memecahkan enkripsi token Gofile terbaru, dan memperbarui file source code Kotlin secara langsung.

### ⚡ Jalankan Otomatis via GitHub Actions (Rekomendasi)

Script ini sudah dikonfigurasi untuk berjalan **secara otomatis setiap kali workflow build dijalankan**. Workflow akan mendeteksi domain & salt baru, memperbarui file Kotlin, dan melakukan commit/push kembali perubahan source code secara otomatis ke branch utama Anda dengan flag `[skip ci]` (untuk mencegah perulangan tak terbatas).

### 💻 Jalankan Secara Lokal (Manual)

Jika Anda ingin memperbarui file secara lokal di komputer Anda sebelum melakukan commit manual, ikuti langkah-langkah berikut:

1. Pastikan Anda sudah menginstal **Node.js (v18 ke atas)**.
2. Buka terminal di folder root project utama dan jalankan perintah:
   ```bash
   node Anoboy/update-constants.js
   ```
3. Script akan langsung mendeteksi domain dan salt terbaru, serta memperbarui URL domain di `Anoboy.kt` dan salt di `Extractor.kt` secara otomatis.
4. Anda tinggal melakukan commit dan push perubahan tersebut.
