# Araba Skor 360 — Server

Kotlin + Javalin backend. Önceden hesaplanmış araç skorlarını (`model_variant` /
`model_variant_score`) sunar; kullanıcıların araçlara 1-100 arası skor ve serbest
metin yorum girmesine izin verir. Kimlik doğrulama tamamen
[user-platform-service](http://localhost:8089) üzerinden yapılır — bu proje Firebase
token doğrulamayı kendisi yapmaz, gelen `Authorization` header'ını olduğu gibi forward eder.

## Kurulum

```bash
sdk env install   # veya: sdk use java 21.0.5-tem
cp .env.example .env
# .env içindeki ADMIN_SECRET'i doldur (sadece setup script'i için gerekli)
```

`user_car_review` tablosu uygulama ilk çalıştığında Flyway ile otomatik oluşturulur.
`model_variant` / `model_variant_score` tablolarına dokunulmaz (sadece okunur).

`araba-skor` platformunu user-platform-service'te bir kere kaydetmek için:

```bash
./scripts/setup-platform.sh
```

## Çalıştırma

```bash
./gradlew run
```

Sunucu `PORT` (varsayılan 7070) üzerinde ayağa kalkar.

## API

| Method | Path | Auth | Açıklama |
|---|---|---|---|
| GET | `/api/cars?q=&limit=&offset=` | - | Araç listesi (skor + topluluk skoru ile) |
| GET | `/api/cars/{id}` | - | Araç detayı |
| GET | `/api/cars/{id}/cost-of-ownership?registrationYear=&annualKm=&trValueTl=` | - | Yıllık yürütme maliyeti (MTV + yakıt), 0-100 skordan ayrı |
| GET | `/api/cars/{id}/reviews` | - | Araca ait kullanıcı skor/yorumları |
| POST | `/api/cars/{id}/reviews` | Firebase | Skor (1-100) + yorum gönder/güncelle |
| GET | `/api/cars/{id}/reviews/me` | Firebase | Kendi review'ım |
| DELETE | `/api/cars/{id}/reviews/me` | Firebase | Kendi review'ımı sil |

Korumalı uçlar `Authorization: Bearer <firebase_id_token>` header'ı bekler; bu header
user-platform-service'in `/v1/users/me/context/araba-skor` uç noktasına iletilir.

`POST /api/cars/{id}/reviews`, user-platform-service'in `daily_reviews` kullanım
metriğini tüketir (`/usage/daily_reviews/consume`). Kota aşılırsa 429 döner; auth
servisi kota kontrolü sırasında erişilemezse istek yine de kabul edilir (fail-open),
ama kimlik doğrulamanın kendisi başarısız olursa istek reddedilir (fail-closed).

### Review kategorileri

Tek bir toplam skor yerine, `score` (genel memnuniyet) tek zorunlu alan; ayrıca dört
opsiyonel detay kategorisi var — kullanıcı doldurmak istemezse boş bırakabilir:

- `interiorQualityScore` — İç mekân kalitesi
- `powertrainHarmonyScore` — Motor-şanzıman uyumu
- `nvhScore` — Ses yalıtımı (rüzgar/yol/motor sesi tek eksende — NVH)
- `rideComfortScore` — Sürüş konforu

`communityScore` (araç kartlarında görünen) hâlâ sadece `score`'un (genel memnuniyet)
ortalaması — detay kategoriler şu an ayrı bir agregasyona dahil değil, sadece review
detayında görünüyor.

## Skorlama

Bir araç için API'de iki farklı skor döner: **Model Skoru** (`score`) ve **Topluluk Skoru**
(`communityScore`). İkisinin hesaplanma şekli tamamen farklı ve farklı yerlerde yaşıyor.

### Model Skoru (`score`, `confidence`, `sampleSize`, `nhtsaCovered`, `recallCount`)

Bu skor **bu repo'nun dışında**, ayrı bir veri pipeline'ı tarafından önceden hesaplanıp
`model_variant_score` tablosuna yazılıyor. araba-skor-360-server bu tabloyu **sadece okur**,
hesaplama mantığına hiç dokunmaz ve o pipeline'ın kod tabanı bu repo'da değil — yani tam
formülü burada dokümante edemiyorum, sadece DB'deki alanların anlamını (isimlerinden ve
verilerinden çıkarabildiğim kadarıyla) özetliyorum:

| Alan | Anlamı |
|---|---|
| `score` | 0-100 arası, o araç modeli/varyantı için genel güvenilirlik skoru |
| `confidence` | `Low` / `Medium` / `High` — skorun kaç veri noktasına dayandığına bağlı güven seviyesi |
| `sampleSize` (`n`) | Skoru besleyen kayıt sayısı (muhtemelen MOT test kayıtları) |
| `sePoints` | Skorun standart hatası (istatistiksel belirsizlik payı) |
| `initPassRate` / `finalPassRate` | Muhtemelen aracın ilk / güncel MOT test geçme oranları |
| `passDelta` | `finalPassRate - initPassRate` gibi bir değişim değeri olabilir |
| `dangerousDelta` / `mechanicalDelta` | Tehlikeli/mekanik arıza kategorilerindeki değişim |
| `nhtsaCovered` | NHTSA (ABD) recall/complaint verisiyle eşleştirilebilmiş mi |
| `recallCount` | NHTSA recall sayısı |

Bu tablonun nasıl/kim tarafından dolduruldu bilgisi için o pipeline'ın kendi reposuna/ekibine
bakman gerekir — burada spekülasyon yapmaktan kaçınıyorum.

### Euro NCAP Puanı (`ncapRating`)

`model_variant_score` gibi bu da **repo dışında** önceden hesaplanıp `ncap_rating` tablosuna
yazılan, salt-okunur bir veri — ama farkı: bu elle araştırılmış (euroncap.com ve ikincil
kaynaklardan doğrulanmış), otomatik bir pipeline çıktısı değil. `source` alanı her satırın hangi
kaynaktan geldiğini izlenebilir tutuyor; bazı eski testlerde alt-skorlardan biri doğrulanamadıysa
tahmin üretmek yerine `null` bırakılmış (`notes`'ta açıklanıyor). Hem `GET /api/cars` (liste) hem
`GET /api/cars/{id}` cevabında dolu — `costOfOwnership`'in aksine ekstra sorgu maliyeti yok, sadece
bir `LEFT JOIN`.

### Topluluk Skoru (`communityScore`, `communityReviewCount`)

Bu skoru **bu backend hesaplıyor**, mantığı basit: o araca kullanıcıların girdiği tüm
`user_car_review.score` (1-100) değerlerinin **aritmetik ortalaması**, 2 ondalık basamağa
yuvarlanmış. Hesaplama [CarRepository.kt](src/main/kotlin/com/arabaskor360/cars/CarRepository.kt)
içinde SQL `AVG()` ile yapılıyor:

```sql
SELECT model_variant_id, AVG(score), COUNT(*)
FROM user_car_review
WHERE model_variant_id IN (...)
GROUP BY model_variant_id
```

Ağırlıklandırma, aykırı değer filtreleme vb. yok — kasıtlı olarak en basit hali. Hiç review
yoksa `communityScore: null`, `communityReviewCount: 0` döner.

**`communityCategoryScores`** aynı mantığı [Review kategorileri](#review-kategorileri)'ndeki 4
opsiyonel detay alanına da uyguluyor — `interiorQualityScore`, `powertrainHarmonyScore`,
`nvhScore`, `rideComfortScore`, her biri kendi `AVG()`/`COUNT()`'una sahip (tek bir gruplu SQL
sorgusunda, ekstra round-trip yok). Kategoriler opsiyonel olduğu için her birinin `count`'u
`communityReviewCount`'tan farklı (daha düşük) olabilir — client bunu ayrı ayrı göstermeli, aynı
örneklem büyüklüğü varsayılmamalı.

### Yürütme Maliyeti (MTV + Yakıt) — `score`'dan tamamen ayrı bir eksen

`GET /api/cars/{id}/cost-of-ownership`, 0-100'lük skorun **hiç parçası değil** — ayrı bir ₺/yıl
ekseni. Kaynağı da farklı: `mtv_tariff`, `vca_fuel_consumption`, `fuel_price` tabloları
[araba-skor-360-loader](../python/araba-skor-360-loader) tarafından besleniyor (aynı `carscore`
DB'sinde), ama bu tablolar `model_variant_score` gibi önceden hesaplanmış tek bir sayı değil — MTV
ve yakıt maliyeti aracın gerçek tescil yılına ve yıllık km'sine bağlı olduğu için canlı hesaplanıyor
([CostOfOwnershipRepository.kt](src/main/kotlin/com/arabaskor360/cost/CostOfOwnershipRepository.kt),
o projedeki `cost_of_ownership.py`'nin Kotlin portu).

Bir araç genelde birden fazla motor/yakıt seçeneği içerdiğinden (`options` listesi) her seçenek
kendi `testingScheme`'ini (`NEDC`/`WLTP`) taşır — bu ikisi asla harmanlanmaz, çünkü WLTP resmi
olarak NEDC'den ~%10-20 daha yüksek tüketim raporlar. Elektrikli araçlar için `mtv`/`fuel` şu an
`null` döner (ayrı kW bazlı MTV tarifesi henüz transcribe edilmedi) — sessizce yanlış bir sayı
üretmek yerine `notes` alanında açıklanır.

**Yakıt fiyatı — günlük bellek cache** ([FuelPriceCache.kt](src/main/kotlin/com/arabaskor360/cost/FuelPriceCache.kt)):
`fuel_price` artık `araba-skor-360-loader`'ın elle çalıştırdığı bir script tarafından değil, bu
servis tarafından günde bir kere (TR takvim gününe göre — `Europe/Istanbul`) otomatik
güncelleniyor. Bir istek geldiğinde: bellekteki fiyat bugüne aitse direkt onu kullan; değilse önce
DB'de bugüne ait bir satır var mı diye bak (başka bir instance ya da restart öncesi zaten
çekilmiş olabilir); o da yoksa `ucuzyakitbul.com.tr`'ın herkese açık API'sine git, sonucu
`fuel_price`'a yeni satır olarak ekle (üzerine yazmadan — geçmiş "hangi tarihte ne kadardı"
sorgulanabilir kalsın diye) ve belleğe al. Dış API çağrısı başarısız olursa istek patlamaz,
elde ne varsa (bayat olsa bile) onunla devam edilir — bu ikincil bir bağımlılık, güvenlik sınırı
değil.

**Kasko + trafik sigortası — kaba tahmin, gerçek teklif değil:** İkisi için de ücretsiz/herkese
açık bir fiyat API'si yok (araştırıldı — sadece web tabanlı teklif karşılaştırma siteleri var,
hepsi sürücü bazlı kişiselleştirilmiş fiyat veriyor). Bu yüzden:
- **`estimatedKaskoAnnualTl`** — aracın değerinin (`trValueTl` query param'ı, zaten MTV değer
  bandı için var olan parametre) %2-5'i arası kaba bir aralık. `trValueTl` verilmezse `null`
  döner — araç değeri bilinmeden bir tahmin uydurmuyoruz.
- **`estimatedTrafficInsuranceAnnualTl`** — SEDDK'nin düzenlediği tavan fiyat sistemine dayalı,
  ama araç sınıfı/il/hasar basamağı bu uygulamada takip edilmediği için **tüm binek araçlar için
  aynı jenerik aralık** (2026 için ~8.500-16.000₺), model_variant'a özel değil.

İkisi de `notes` alanında "gerçek teklif değildir" diye açıkça işaretlenir.

**Araç değeri — `kaskoDegerOptions` (gerçek veri, tahmin değil):** `araba-skor-360-loader`,
TSB'nin (Türkiye Sigorta Birliği — sigorta şirketlerinin bizzat kasko primi hesaplarken kullandığı
resmi kaynak) aylık yayımladığı kasko değer listesini tamamen indirip `kasko_deger` tablosuna
işliyor (~40 bin satır, scrape değil — TSB'nin kendi `GetLatestExcelFile` API'sinden). Bu tablo
`vca_fuel_consumption`'ın aksine `model_variant`'a ingest anında eşleştirilmiyor — dosya küçük
olduğu için TSB'nin takip ettiği her marka olduğu gibi saklanıyor, eşleştirme sorgu anında
`marka_adi`/`tip_adi` üzerinde ILIKE substring + `model_year` ile yapılıyor
(`CostOfOwnershipRepository.lookupKaskoDeger`, Python tarafındaki `get_kasko_deger`'in portu).
Bu araç modelinin `make`/`trName` alanları arama terimi olarak kullanılıyor; bir nesil birden çok
trim içerdiğinden `kaskoDegerOptions` boş, tek, ya da birden çok satır dönebilir.

Şu an bilerek **sadece bilgi amaçlı** — `estimatedKaskoAnnualTl`'e ya da MTV'nin değer bandı
hesabına (`trValueTl`) otomatik bağlanmıyor; bu, davranış değiştiren ayrı bir karar, ileride
konuşulabilir. TSB dosyası aylık güncellendiği için `araba-skor-360-loader`'da
`python -m carscore_ingest.kasko_deger_ingest` de aylık tekrar çalıştırılmalı.

## API Dokümantasyonu (Swagger)

Sunucu ayaktayken `/docs` üzerinden interaktif Swagger UI, `/openapi.yaml`
üzerinden ham OpenAPI 3.0 spec'i sunulur. Spec elle yazılır
(`src/main/resources/openapi.yaml`) — yeni endpoint eklerken onu da güncelle.

## Railway'e Deploy

Bu proje `Dockerfile` ile deploy edilir (`railway.toml` builder'ı `DOCKERFILE`
olarak sabitler, Nixpacks'in Gradle/Kotlin'i yanlış algılamasını engeller).

1. Yeni bir Railway projesi oluştur, bu repo'yu ve `user-platform-service`'i
   aynı projeye ekle (private networking için ikisi de aynı projede olmalı).
2. araba-skor-360-server servisinde şu env var'ları ayarla:
   - `DATABASE_URL` — mevcut carscore Postgres'inin **public proxy** adresi
     (`postgresql://carscore_app:...@yamabiko.proxy.rlwy.net:34835/carscore`).
     carscore Postgres'i ayrı bir Railway projesinde olduğu için private
     networking kullanılamaz, bu URL değişmeden kalır.
   - `USER_PLATFORM_SERVICE_URL` — user-platform-service'in Railway private
     networking adresi, ör. `http://user-platform-service.railway.internal:8089`
     (gerçek adres Railway dashboard'da o servisin "Networking" sekmesinde
     görünür; portu servisin kendi dinlediği port olmalı, `PORT` env var'ı ile
     karıştırma).
   - `PLATFORM_SLUG` — `araba-skor` (varsayılan zaten bu).
   - `CORS_ALLOWED_ORIGIN` — production'da `*` yerine gerçek client origin'i
     kullanmak daha güvenli.
   - `PORT` — **elle set etme**, Railway kendisi enjekte eder; `AppConfig`
     zaten `PORT` env var'ını okuyup ona bind olur.
3. `ADMIN_SECRET` gerekmez (sadece local `scripts/setup-platform.sh` için
   kullanılıyor, deploy edilen servis onu hiç okumaz).
4. İlk deploy'dan sonra `scripts/setup-platform.sh`'ı **local'den**,
   `USER_PLATFORM_SERVICE_URL`'i o an production'daki user-platform-service'in
   **public** domain'ine çevirerek bir kere çalıştır (platform/rol/plan
   kurulumu tek seferlik, admin secret gerektirir).

Local'de Docker imajını test etmek için:

```bash
docker build -t araba-skor-360-server .
docker run -p 8080:8080 \
  -e PORT=8080 \
  -e DATABASE_URL="postgresql://carscore_app:...@yamabiko.proxy.rlwy.net:34835/carscore" \
  -e USER_PLATFORM_SERVICE_URL="http://host.docker.internal:8089" \
  araba-skor-360-server
```
