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
| GET | `/api/cars?q=&make=&minYear=&maxYear=&limit=&offset=&sortBy=&order=` | - | Araç listesi (skor + topluluk skoru ile) |
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

### Filtre — `GET /api/cars?make=`

`q` (marka/model üzerinde substring arama) ile karışmasın diye ayrı bir parametre:
`make` **tam eşleşme** (case-insensitive) yapıyor — `q` gibi "içeren" değil, seçilen
markanın tamamını istiyor. Virgülle birden fazla marka verilebilir (`make=Renault,BMW`)
— içeride `IN` sorgusuna dönüşüyor. `q` ile birlikte kullanılabilir, ikisi `AND`'lenir
(`make=Renault&q=clio` → sadece Renault Clio'lar, başka markadaki "clio" benzeri bir
şey varsa bile hariç). Eşleşme yoksa hata değil, boş dizi döner.

**`minYear`/`maxYear`** — jenerasyonun kendi `[yearStart, yearEnd]` aralığı verilen
aralıkla **kesişiyorsa** eşleşir (`yearEnd >= minYear` ve/veya `yearStart <= maxYear`),
sadece o yılda başlayanlar değil — ör. `minYear=2015` verirsen 2012-2018 arası üretilmiş
bir jenerasyon da eşleşir, çünkü 2015'te hâlâ satılıyordu. İkisi de opsiyonel (açık uçlu
aralık için tek biri verilebilir), `minYear > maxYear` ise 400 döner. `make`/`q` ile
serbestçe kombinlenebilir.

### Sıralama — `GET /api/cars?sortBy=&order=`

`sortBy` verilmezse liste marka+model'e göre alfabetik sıralanır (DB seviyesinde
`ORDER BY` + `LIMIT`/`OFFSET`, katalog boyutundan bağımsız ucuz). `sortBy` değerleri:
`score`, `ncapStars`, `communityScore`, `fuelConsumption`. Bu dördü verildiğinde eşleşen
**tüm** satırlar çekilip bellekte sıralanıp öyle sayfalanıyor
([CarRepository.kt](src/main/kotlin/com/arabaskor360/cars/CarRepository.kt)) —
`communityScore`/`fuelConsumption` SQL kolonu değil, sırasıyla `user_car_review` ve
`vca_fuel_consumption`'dan agregasyon, DB seviyesinde `ORDER BY`'a sokmak için sorguyu
yeniden yapılandırmak gerekirdi; katalog şu an birkaç yüz satır olduğu için bellekte
sıralamak sorun değil, katalog büyüklüğü gerçek bir darboğaz haline gelirse yeniden
değerlendirilmeli.

`order` verilmezse her `sortBy` kendi "en iyi önce" yönünü kullanır: `score`/`ncapStars`/
`communityScore` için azalan (yüksek=iyi), `fuelConsumption` için **artan** (düşük
L/100km=iyi) — `order=asc`/`desc` ile bu varsayılan ezilebilir. Puanı/veri noktası olmayan
araçlar (`score`/`ncapRating`/`communityScore`/`bestFuelConsumptionL100km` null) her iki
yönde de **her zaman en sona** düşer — "veri yok" ne "en iyi" ne "en kötü" demek değil.

**`bestFuelConsumptionL100km`** — jenerasyonun sunduğu motorlar arasında **en düşük**
kombine L/100km (`vca_fuel_consumption`'dan `MIN`), farklı motorların ortalaması değil
(bkz. Kadjar'daki motor karıştırma hatası düzeltmesi — aynı prensip burada da geçerli:
gerçek olmayan bir "ortalama motor" üretmek yerine gerçekten var olan en verimli motoru
gösteriyoruz). Elektrikli-only jenerasyonlarda (`metric_combined_l_per_100km` kaynak
tabloda tamamen null) bu alan `null` kalır. Hem listede hem detayda dolu (score/ncapRating
gibi ucuz bir agregasyon, `costOfOwnership`'in aksine).

**Yürütme maliyetine göre sıralama bilerek sunulmuyor.** `costOfOwnership` liste
endpoint'inde hiç hesaplanmıyor (bkz. `CarResponse.costOfOwnership` doc comment) —
gerçek bir tescil yılı/yıllık km gerektiriyor ve her satır için ekstra birkaç DB
sorgusu (VCA yakıt, kasko_deger ILIKE, value history + döviz kuru join) demek; 50-200
satırlık bir listede bunu hesaplamak `/api/cars`'ı ciddi yavaşlatırdı. Kullanıcı önce
skor/NCAP/topluluk skoruna göre sırala, sonra detay sayfasında (`GET /api/cars/{id}`)
gerçek maliyeti görsün mantığı tercih edildi.

### Dil desteği — `?lang=tr|en`

Her uç, `?lang=en` query param'ı ile kullanıcıya açık metinleri (cost-of-ownership
`notes` dizisi, hata mesajları — `error` alanı, `confidence`) İngilizce döndürür.
`?lang` verilmezse ya da `tr`/tanınmayan bir değer verilirse Türkçe döner (varsayılan).
JSON **alan adları** (`id`, `make`, `confidence`, ...) her zaman İngilizce kalır — bu
API kontratının parçası, çevrilen kısım sadece metin *değerleri*
([Lang.kt](src/main/kotlin/com/arabaskor360/common/Lang.kt), basit `t(lang, tr, en)`
fonksiyonu; ayrı bir i18n/resource-bundle kütüphanesi yok çünkü çevrilen tüm metinler
statik ya da birkaç değişken içeren tek satırlık cümleler, veya `confidence` gibi
küçük/sabit bir enum).

**Çevrilmeyenler (bilinçli):** TSB gibi dış kaynakların kendi metinleri
(`kaskoDegerOptions[].trim`, `.make`), kullanıcıların kendi yorum metinleri, ve
`ncapRating.notes`/`ncapRating.source` — bunlar bu uygulamanın request anında ürettiği
metin değil, veri pipeline'ı tarafından bir kere yazılıp `ncap_rating` tablosunda
saklanan serbest metin (zaten İngilizce kaynaklara atıf yapıyor, ör. "euroncap.com
press release"). Güvenilir bir çevirisi için o tablonun kendisinin iki dilli
tutulması gerekir — bu depo tek başına çözemez, `araba-skor-360-loader` tarafında
ele alınmalı.

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

**`GET /api/cars` ve `GET /api/cars/{id}` artık tamamen bir bellek içi cache'ten
serviliyor** ([CarCache.kt](src/main/kotlin/com/arabaskor360/cars/CarCache.kt)) — sadece
`costOfOwnership` değil, `score`/`confidence`/`ncapRating`/`bestFuelConsumptionL100km`
dahil tüm response denormalize edilip `(araç id, dil)` anahtarlı tek bir Map'te tutuluyor.
İki uç nokta zaten aynı veriyi döndürüyordu (biri tekil eleman, biri filtrelenmiş/
sıralanmış/sayfalanmış liste) — artık ikisi de bu tek cache'i okuyor,
`CarRepository`'nin filtreleme/sıralama SQL'i kalktı, `list()` artık `q`/`make`/
`minYear`/`maxYear`/`sortBy`'ı zaten bellekte olan `CarResponse` nesneleri üzerinde
Kotlin'de uyguluyor.

Cache saat başı toptan yenileniyor — buradaki her alan zaten sadece loader'ın periyodik
ingest'leriyle ya da aylık bir cron'la değişiyor, bir saatlik bayatlık sorun değil.
**Tek istisna:** `communityScore`/`communityCategoryScores`/`communityReviewCount` —
bunlar bu uygulamanın kendi canlı review akışıyla (`POST`/`DELETE
/api/cars/{id}/reviews/me`) anında değişebiliyor. Saatlik toptan yenilemeyi beklemek
yerine, `ReviewController` her başarılı review yazımından hemen sonra
`CarCache.patchCommunityStats(carId)` çağırıp **sadece o aracın** topluluk alanlarını
cache'te güncelliyor (ucuz bir tek `GROUP BY` sorgusu + tek bir map girdisi değişimi) —
DB'ye yazma davranışı aynen duruyor, cache ayrıca senkron tutuluyor.

Cache `ConcurrentHashMap` değil, `@Volatile` bir referans arkasındaki **immutable Map**
(copy-on-write) — okuyucular hiç kilitlenmiyor, toptan yenileme de kısmi-boş bir ara
duruma düşmeden tek bir atomik referans değişimiyle oluyor (`FuelPriceCache`'le aynı
desen). Sadece `Lang.TR` (ana pazar) saat başı istekli olarak önceden hesaplanıyor;
`Lang.EN` ilk İngilizce istekte tembel dolduruluyor (hem tekil `get` hem de `list` için —
`list` kendi başına eksik dili doldurmadan sadece cache'i filtrelediği için, tam bu
amaçla ayrı bir `ensureLangPopulated` adımı var). Katalog canlı büyümeye devam ettiği
için yeni eklenen bir araç da aynı tembel-doldurma yoluyla ilk erişimde cache'e girer,
bir sonraki saatlik taramayı beklemez.

Uygulama başlarken cache arka planda bir thread'de ısıtılıyor (`Application.kt`) —
`/health` hemen cevap verir, Railway trafiği bekletmeden yönlendirir; ısınma sürerken
gelen gerçek istekler aynı kilide takılıp ısınmanın bitmesini bekler (aynı sweep'i
tekrar tekrar tetiklemez). Canlı ölçüldü: soğuk TR taraması ~60 araç için ~30-35 saniye
(sadece TR — hem TR hem EN önceden hesaplansaydı iki katına çıkardı, sayısal hesaplama
dilden bağımsız olduğu için bu boşa iş olurdu), ilk EN isteği de kendi tembel taramasını
tetikleyip benzer bir süre alıyor, ondan sonrası her iki dilde de ~0.1-0.3 saniye.
`GET /api/cars/{id}/cost-of-ownership` (caller'ın `registrationYear`/`annualKm`/
`trValueTl` geçebildiği uç nokta) bu cache'in **dışında** kalır — keyfi parametre
kombinasyonları cache alanını sınırsız büyütür, o yüzden hep canlı hesaplanır.

**Gruplama anahtarı `(fuelType, testingScheme, engineCapacityCc)` — motor hacmi dahil:**
Başlangıçta sadece `(fuelType, testingScheme)`'e göre gruplanıyordu; bu, aynı yakıt tipinde
birden fazla gerçek motoru (ör. Renault Kadjar'ın "Petrol" seçeneği altında hem 1197cc hem
1618cc TCe motoru var) tek bir fiktif "ortalama motor"a indirgiyordu. Sorun canlı DB'de
somut olarak yakalandı: ortalama ~1337cc, gerçekte var olmayan bir motor büyüklüğü, MTV'yi
yanlış vergi dilimine düşürüyordu — gerçek motorlar 2.238₺ (1197cc, 0-1300cc dilimi) ve
8.145₺ (1618cc, 1601-1800cc dilimi) öderken, fiktif ortalama 4.354₺ (1301-1600cc dilimi)
gösteriyordu, neredeyse 2 kat sapma. Düzeltme sonrası her gerçek motor kendi `CostOption`
satırında ayrı görünüyor; aynı motorun farklı jant/donanım varyantları (ör. 16/17" vs 19")
hâlâ birbiriyle ortalanıyor — bu meşru, çünkü fiziksel olarak aynı motor.

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
- **`estimatedKaskoAnnualTl`** — araç değerinin %2-5'i arası kaba bir aralık. Araç değeri önce
  `trValueTl` query param'ından (verilmişse), yoksa aşağıdaki `kaskoDegerOptions`'ın (TSB'den
  gelen gerçek değerler) ortalamasından alınır. İkisi de yoksa `null` döner — araç değeri hiç
  bilinmeden bir tahmin uydurmuyoruz.
- **`estimatedTrafficInsuranceAnnualTl`** — SEDDK'nin düzenlediği tavan fiyat sistemine dayalı,
  ama araç sınıfı/il/hasar basamağı bu uygulamada takip edilmediği için **tüm binek araçlar için
  aynı jenerik aralık** (2026 için ~8.500-16.000₺), model_variant'a özel değil.

İkisi de `notes` alanında "gerçek teklif değildir" diye açıkça işaretlenir.

**Toplam maliyet — `options[].totalWithInsuranceAnnualTl`:** Her `CostOption`, kendi
`totalAnnualTl`'ine (gerçek MTV+yakıt) `estimatedKaskoAnnualTl` + `estimatedTrafficInsuranceAnnualTl`
eklenerek "bu motorla bu aracı bir yıl sahiplenmenin toplam maliyeti" aralığını da taşır —
kullanıcının üç ayrı sayıyı elle toplamasına gerek kalmaz. Kasko/trafik sigortası sadece
araç değerine bağlı olduğu (motora bağlı değil) için tüm option'larda aynı aralık eklenir,
sadece MTV+yakıt kısmı motora göre değişir. `estimatedKaskoAnnualTl` `null` ise (TSB
eşleşmesi yoksa) `totalWithInsuranceAnnualTl` de `null` kalır — eksik bir gerçek maliyet
kalemini (kasko) sessizce atlayıp "toplam" diye yanıltıcı bir sayı göstermek yerine.

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

`trValueTl` verilmediğinde `estimatedKaskoAnnualTl`'in araç değeri girdisi olarak
**otomatik** kullanılıyor (eşleşen trim'lerin ortalaması — birden fazla trim eşleştiyse tek bir
trim seçmek yerine ortalama alınıyor, VCA yakıt/motor hacmi ortalamasıyla aynı mantık). MTV'nin
kendi değer bandı hesabına (`trValueTl` parametresi) hâlâ bağlı **değil** — bu ayrı, davranış
değiştiren bir karar, ileride konuşulabilir. TSB dosyası aylık güncellendiği için
`araba-skor-360-loader`'da `python -m carscore_ingest.kasko_deger_ingest` de aylık tekrar
çalıştırılmalı.

**Değer geçmişi — `valueHistory` (longitudinal, gerçek zaman serisi):** `araba-skor-360-loader`
artık `kasko_deger`'i tek aylık anlık görüntü değil, **her yıl Ağustos ayı için geriye dönük**
(2020-08-01'den 2026-08-01'e, 7 snapshot) ingest ediyor. `computeValueHistory`
([CostOfOwnershipRepository.kt](src/main/kotlin/com/arabaskor360/cost/CostOfOwnershipRepository.kt))
`kaskoDegerOptions`'da eşleşen her trim için `(marka_adi, tip_adi, model_year)` **üçü de sabit**
tutularak, sadece `snapshot_month` değişecek şekilde sorgu atıyor — yani "bu SABİT model
yılındaki araç, farklı takvim yıllarında TSB'ye göre ne kadar değerliydi" sorusuna cevap veriyor.
Bu, tek bir fiziksel aracın zaman içindeki gerçek değer serisi (longitudinal) — farklı model
yıllarını birbiriyle kıyaslayan kesitsel bir yaklaşım değil.

**Neden model yılını sabit tutuyoruz, kesitsel karşılaştırma yapmıyoruz:** İlk versiyonda aynı
anlık görüntü içinde farklı `model_year` satırları kıyaslanıyordu (`tip_kodu` üzerinden
eşleştirilerek). Bu yaklaşım iki nedenle terk edildi: (1) canlı DB'ye karşı doğrulanırken
`tip_kodu`'nun TSB'nin her `model_year` listesinde bağımsızca yeniden kullanılan bir sıra
numarası olduğu ortaya çıktı (ör. `tip_kodu=1087`, 2012'de bir Megane, 2016'da bir traktör,
2018'de bir Duster'a denk geliyor — aynı trim değil, tesadüfi çakışma); (2) `(marka_adi,
tip_adi)` metniyle düzeltilse bile, farklı model yıllarını aynı anda kıyaslamak gerçek yaşlanma
etkisini nesil/facelift farklarıyla karıştırıyordu ("elma armut" kıyası) — artık gerçek çok-yıllı
veri elimizde olduğuna göre buna gerek kalmadı.

**Nominal TL vs. USD — asıl gerçek değer kaybı/kazancı USD'de:** `nominalChangePct`/
`nominalChangePerYearPct` ham TL değişimi. Türkiye'de TL devalüasyonu genelde aracın fiziksel
yaşlanmadan kaynaklanan gerçek değer kaybından çok daha büyük olduğu için, bu sayılar sıklıkla
**artış** gösterir (ör. 2018 model bir Duster trim'i 2020→2026 arası nominal TL bazında ~%470
"değer kazanmış" görünüyor — bu ekonomik olarak bir kazanç değil, TL'nin kendi değer kaybı).

Bunu düzeltmek için `araba-skor-360-loader` artık aynı 7 anchor tarihi (2020-08-01..2026-08-01)
için `exchange_rate` tablosuna TCMB'nin (Türkiye Cumhuriyet Merkez Bankası) resmi günlük
USD/TRY döviz alış kurunu da çekiyor
([ExchangeRateTable.kt](src/main/kotlin/com/arabaskor360/db/tables/ExchangeRateTable.kt)).
`computeValueHistory` her `ValueHistoryPoint`'e `valueUsd = valueTl / o günün TCMB döviz alış
kuru` alanını ekliyor, ve trend seviyesinde `usdChangePct`/`usdChangePerYearPct` bunun üzerinden
hesaplanıyor — TRY'nin kendi devalüasyonundan arındırılmış, gerçeğe çok daha yakın bir sinyal.
Canlı örnek: aynı Duster trim'i USD bazında 2020→2026 arası **%16.5 değer KAYBETMİŞ**
(`usdChangePerYearPct: -2.76`) — nominal TL'nin gösterdiği "%470 artış" ile taban tabana zıt,
ama ekonomik olarak doğru olan bu. Seri içindeki herhangi bir nokta için o tarihte
`exchange_rate` satırı yoksa `valueUsd` (ve dolayısıyla `usdChangePct`) `null` döner — tam bir
enflasyon deflatörü değil (USD'nin kendi enflasyonu var), ama TRY'ninkinden kıyaslanamayacak
kadar küçük ve öngörülebilir.

Eşleşen trim'in TSB'de sadece tek snapshot'ı varsa (`points.size < 2`) o trim `valueHistory`'de
hiç görünmez; `notes`'a bu durum düşülür.

**Sabit ay çapası — karışık ingest cadence'ine dayanıklı:** Geçmiş backfill yıllık, sadece Ağustos
(2020-08-01 .. 2026-08-01). "Güncel kasko bedeli" tazeliği (`kaskoDegerOptions`/
`estimatedKaskoAnnualTl`, en yeni `snapshot_month`'u okur) için ileride daha sık ingest
edilmesi bekleniyor — ama `computeValueHistory` bu ek ingest'leri görmezden gelir: sadece
`VALUE_HISTORY_ANCHOR_MONTH` (Ağustos) ayına denk gelen satırları kullanır. Böylece Eylül gibi
ara aylarda tazelik amaçlı yeni bir snapshot ingest edilse bile `valueHistory` serisi hep tam
12 ay aralıklı, tutarlı kalır — o yılın "en güncel" değerini trend'e karıştırmaz. Trade-off:
yeni bir yılın verisi ancak bir sonraki Ağustos backfill'i yapılınca `valueHistory`'ye eklenir
(yıl içinde erken bir "bu yıla dair henüz" noktası vermez — bilinçli bir basitlik tercihi,
gerekirse ayrı bir "kısmi yıl" alanı olarak ileride eklenebilir).

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
