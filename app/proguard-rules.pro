# WebRTC yerel koddan (JNI) sınıflarını adıyla çağırır; küçültücü dokunmasın
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
# Görüşme kodu WebRTC'nin geri çağrılarını uyguluyor; adları korunsun
-keep class com.ev.iletisim.media.** { *; }
