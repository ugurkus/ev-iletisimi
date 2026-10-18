# WebRTC yerel koddan (JNI) sınıflarını adıyla çağırır; küçültücü dokunmasın
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
