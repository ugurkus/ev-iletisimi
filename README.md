# Ev İletişimi (1. ve 2. aşama)

Ev Wi‑Fi'ında, internetsiz, merkezsiz metin mesajlaşma. Her cihaz diğerlerini mDNS ile bulur, şifreli doğrudan bağlantı kurar. Kapalı cihaza giden mesaj gönderende bekler, alıcı açılınca gider.

## Kurulum sırası
1. Yetişkin telefonda "Yeni aile kur" seçilir.
2. Yetişkin telefonda "Yeni cihaz ekle" ile ad ve rol (Çocuk/Yetişkin) seçilip QR gösterilir.
3. Diğer cihazda "Aileye katıl (QR okut)" ile QR okutulur.
4. Her cihazda "Pil kısıtlamasını kaldır" düğmesine basılır.

## Görüntülü görüşme
Sohbet ekranındaki "Görüntülü ara" ile başlar. Aranan cihazda zil çalar, kilit ekranında da görünür. Görüntü ve ses WebRTC ile cihazdan cihaza şifreli akar. Kural: 21:00–07:00 arasında iki çocuk cihazı birbirini arayamaz, yetişkinlerle her zaman görüşebilir. Kural hem arayan hem aranan cihazda uygulanır. Meşgul, reddedildi ve 30 saniyede cevap yok durumları ekranda yazar.

## Kod
- `core/`: el sıkışma (aile sırrıyla HMAC), AES‑GCM şifreleme, QR içeriği, giden kutusu mantığı. Android'den bağımsız.
- `net/PeerManager.kt`: bağlantılar, yeniden deneme, çift bağlantı kuralı. `net/Discovery.kt`: Android NSD.
- `core/CallManager.kt`, `core/CallPolicy.kt`: zil, kabul, ret ve gece kuralı. `media/WebRtcCall.kt`: kamera, mikrofon ve WebRTC bağlantısı. `ui/CallActivity.kt`: arama ekranı.
- `EvService.kt`: arka planda çalışan servis ve bildirimler. `ui/`: ekranlar (AndroidX'siz, sade View'lar).
- Testler: `app/src/test` (şifreleme, yabancı cihazın reddi, mesaj iletimi, giden kutusu, arama akışı ve gece kuralı).

## Bilinen sınırlar
- QR'ı gören herkes aileye katılabilir; QR sadece eşleştirme sırasında gösterilmeli.
- Sessiz saatler sabittir; izinli kişiler listesi ve ayarlanabilir saatler 4. aşamada.
- Rolü cihaz kendisi bildirir; yönetici imzalı roller 4. aşamada.
- Kod Android 15 ve WebRTC kitaplıklarına karşı derlendi ve 13 test geçti. APK bu ortamda üretilemedi; görüntülü görüşme gerçek cihazda henüz denenmedi.
