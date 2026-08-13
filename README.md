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
