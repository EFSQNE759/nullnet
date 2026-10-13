# Bouncy Castle: kripto saglayici
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# Eclipse Paho MQTT istemcisi
-keep class org.eclipse.paho.** { *; }
-dontwarn org.eclipse.paho.**

# Uygulama model siniflari (reflection/JSON icin)
-keep class com.gizli.messenger.data.** { *; }
