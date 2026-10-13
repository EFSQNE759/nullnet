# nullnet — Uçtan Uca Şifreli, Anonim Mesajlaşma

Telefon numarası **yok**, e-posta **yok**, hesap **yok**. Kimliğin tamamen cihazında
üretilen rastgele bir şifreleme anahtarıdır. Tüm mesajlar cihazdan cihaza
**uçtan uca şifrelenir**; aradaki sunucu (relay) yalnızca şifreli zarfları taşır ve
**hiçbir şeyi okuyamaz**.

Uygulama içinden **imzalı güncelleme** desteklenir: her sürüm Ed25519 imzası ve
APK sertifikası ile doğrulanır; sahte/oynanmış bir güncelleme kurulamaz.

---

## 1) Güvenlik Mimarisi

| Katman | Seçim | Not |
|---|---|---|
| Anahtar değişimi | **X25519** (Curve25519 ECDH) | Her kullanıcının cihazında üretilen statik anahtar çifti |
| Anahtar türetme | **HKDF-SHA256** | `shared(static) \|\| shared(ephemeral)` girişiyle; her mesajda taze geçici anahtar → ileri gizlilik |
| Şifreleme | **AES-256-GCM** | Kimlik doğrulamalı; kurcalanmış mesaj otomatik reddedilir |
| Güncelleme imzası | **Ed25519** | `update.json` açık anahtarla doğrulanır |
| Yerel depolama | **Android Keystore + AES-GCM** | Özel anahtar ve mesajlar cihazda şifreli; yedekleme **kapalı** |

**Anonimlik modeli**
- Kayıt yok. Kullanıcı kimliği = rastgele 16 bayt → taban32 kod (ör. `7K3Q...`).
- Kişiler, uygulama içinden paylaşılan davet kodu ile eklenir:
  `GIZLI1.<base64url(uid|pubkey|relay|ad)>`
- Relay yalnızca: alıcı kodu, zarfın boyutu/zamanı ve iki AÇIK anahtar görür.
  İçerik, adres defteri, IP'den kimlik korelasyonu sunucuda bulunmaz.
- Kurulur kurulmaz veritabanından "kişi/kimlik" bilgisi çıkaramazsınız.

**Otomatik güncelleme zinciri**
1. `update.json` indirilir.
2. İçindeki Ed25519 imzası, uygulamaya gömülü açık anahtarla doğrulanır.
3. APK indirilir, `sha256` özeti tutturulur.
4. APK imza sertifikası, kurulu uygulamanınkiyle birebir aynı mı kontrol edilir.
5. Ancak o zaman kurulum ekranı açılır.

---

## 2) Klasör Yapısı

```
GizliMesaj/
├─ app/                          # Android uygulaması (Kotlin + Jetpack Compose)
│  └─ src/main/java/com/gizli/messenger/
│     ├─ crypto/                 # X25519, HKDF, AES-GCM, Ed25519, keystore
│     ├─ data/                   # kimlik/kisi/mesaj/ayar (sifreli depo)
│     ├─ net/                    # relay istemcisi (HTTP)
│     ├─ update/                 # imzali guncelleme yoneticisi
│     ├─ service/                # arka plan yoklama (FCM kullanmaz)
│     └─ ui/                     # Compose arayuz
├─ server/relay.py               # aktarma sunucusu (Python, sifir bagimlilik)
├─ tools/
│  ├─ genkeys.py                 # guncelleme Ed25519 anahtar cifti uretir
│  ├─ make_update.py             # imzali update.json uretir
│  ├─ verify_update.py           # update.json dogrular
│  ├─ build_release.ps1          # yerel: APK bas + update.json
│  └─ update_key_public.b64      # uygulamaya gomulu acik anahtar
├─ release.keystore              # APK imza deposu (GIZLI — yedekle!)
├─ keystore.properties           # sifreler (GIZLI)
└─ .github/workflows/release.yml # otomatik imzali surum yayinlama
```

---

## 3) Yerel Derleme (APK üretimi)

Gereksinimler: JDK 17, Android SDK (platform 35), internet.

```powershell
# Debug APK:
.\gradlew.bat assembleDebug
#   -> app\build\outputs\apk\debug\app-debug.apk

# Release APK (imzali):
.\gradlew.bat assembleRelease
#   -> app\build\outputs\apk\release\app-release.apk
```

Release imzası `release.keystore` + `keystore.properties` ile yapılır (bu ikisi
zaten kurulmuştur; **yedeklerini alın** — kaybederseniz kurulu kullanıcılarınız
güncelleme alamaz).

### Uygulamaya gömülü ayarlar (`app/build.gradle.kts` içinde)

| BuildConfig | Varsayılan | Değiştirme |
|---|---|---|
| `DEFAULT_RELAY` | `http://10.0.2.2:8787` (emülatör hostu) | `-PrelayUrl=...` veya uygulama içi Ayarlar (en kolayı: uygulama içinde değiştirilir) |
| `UPDATE_MANIFEST_URL` | `https://github.com/EFSQNE759/nullnet/releases/latest/download/update.json` | GitHub reponuz: `-PupdateUrl=...` |
| `UPDATE_PUBLIC_KEY` | üretilen açık anahtar | `tools/update_key_public.b64` ile aynı kalmalı |

---

## 4) Relay Sunucuyu Kurma

Mesajlar çevrimiçi olmayan alıcıya ulaşsın diye küçük bir sunucu gerekir.
Sizin sunucunuzda veya ücretsiz bir bulutta çalışır; Python 3.8+ yeterli, başka
bir şey kurmaya gerek yok.

```bash
# 0.0.0.0:8787'de calistirir
python server/relay.py

# veya HTTPS ile dogrudan (gercek sertifika ile):
python server/relay.py --cert fullchain.pem --key privkey.pem
```

- `/send` : şifreli zarfı saklar
- `/pull?u=<kod>` : mesajları verir ve siler
- `/health` : sağlık kontrolü
- Hız sınırı (IP/fail), 2 MB zarf üst sınırı, 30 gün TTL dahildir.
- Mesajlar alıcı çekene kadar SQLite'te saklanır; çekilince silinir.

> **TLS zorunludur.** Ücretsiz, otomatik imzalı HTTPS için sunucunun önüne
> [Caddy](https://caddyserver.com) (1 komut) ya da nginx koyun:
> `caddy reverse-proxy --from https://relay.seninadresin.org --to :8787`

Test (aynı kod, süreç içi):
```powershell
python server\test_relay.py   # -> RELAY TEST: ALL PASS
```

Uygulamada **Ayarlar → Relay sunucu adresi** alanına HTTPS adresini yazın.

---

## 5) Uygulamayı Kullanma

1. APK'yı kurun (Android, "bilinmeyen kaynaklara izin ver"de ilk kurulum gerekir).
2. İlk ekranda **görünür ad** yazın → "Başla".
3. **Kişi ekle** → kendi davet kodunu **Kopyala/Paylaş**, arkadaşın da seninkini.
4. Arkadaşın kodu yapıştırın → "Kişiyi ekle". Karşı taraf ilk mesajda otomatik
   eklenir (güvenli el sıkışma).
5. Sohbet açın → uçtan uca şifreli yazışın.

Ayarlar:
- **Arka planda kontrol**: kapalıysa mesajlar yalnızca uygulama açıkken gelir.
  Açıksa ön plan servisi yoklar (pil harcar; FCM/Google kullanmadığımız için bu
  gizliliğin bedelidir).
- **Tüm verileri sil ve kimliği yenile**: telefon değişiminde/privacy sıfırlamada.

---


## 6) Sınırlamalar ve Dürüst Notlar

- **Abonelik yönlendirmesi (sealed sender) yapılmaz**: relay, kimin kime yazdığını
  (alıcı kodu + iki açık anahtar) görür. Bu, "mesaj içeriği + rehber" gizliliğini
  tam sağlar; ama metadata'ya karşı %100 koruma istiyorsanız **Tor onion routing**
  bir sonraki adımdır.
- **İleri gizlilik** mesaj başına taze geçici anahtarla sağlanır (X25519 ECDH).
  Tam Signal/Double Ratchet algoritması (sürekli türetme + önceden sızdırılan eski
  anahtarlara karşı koruma) sonraki sürüme planlanabilir; arayüz aynı kalır.
- **Bildirim** FCM/Google olmadan yalnızca arka plan yoklama ile gelir (pil etkisi).
  Android 15+, veri senkron ön plan servisini zamanla sınırlayabilir; o zaman
  uygulamayı açık tutmanız gerekir.
- Bu, **kendiniz için** kurduğunuz, bağımlılıksız bir MVP'dir. Yüz binlerce
  kullanıcıya ölçeklemek (sohbet geçmişi sunucuya akışı, grup, medya, aramalar,
  yedekleme) daha fazla iştir ve güvenlik denetimi öneririm.

---

## 7) Uygulama Adını/Adını Değiştirme

- Ad: `app/src/main/res/values/strings.xml` → `app_name`
- Paket adı: `app/build.gradle.kts` → `applicationId` + `namespace` (tüm
  `com.gizli.messenger.*` importları güncellenir)
- Simge: `res/drawable/ic_launcher_foreground.xml` + `res/values/colors.xml`

---

## 8) Doğrulama Yaptık, Gör

- Kripto çekirdeği BC ile JVM'de test edildi: X25519/HKDF/AES-GCM zararı
  dayanımı, kurcalamayı reddetme, Ed25519, Base32 — **ALL PASS**
- Çapraz dil: Python `make_update.py` imzası, Kotlin ile birebir aynı mantıkla
  JVM'de doğrulandı — **GECERLI**
- Relay: gönder/çek/sil/izolasyon/hız sınırı — **ALL PASS**
- Debug + imzalı (minified) Release APK derlendi.
