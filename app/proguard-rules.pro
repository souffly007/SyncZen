# Règles ProGuard/R8 spécifiques à SyncZen.
# JCIFS-NG et OkHttp fonctionnent bien avec les règles par défaut d'Android Gradle Plugin.
# Ajouter ici des -keep si des classes sont supprimées par erreur en build release.

# Conserver les classes JCIFS-NG
-keep class jcifs.** { *; }
-dontwarn jcifs.**

# Conserver les classes Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Conserver les classes Room
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.Database
-keep class * implements androidx.room.Dao
-keep class * implements androidx.room.Entity

# Conserver les classes WorkManager
-keep class androidx.work.** { *; }

# Conserver les classes Kotlin
-keep class kotlin.Metadata { *; }
-keep class kotlin.** { *; }

# Conserver les classes de l'application
-keep class fr.bonobo.synczen.** { *; }

# Conserver les noms des méthodes des interfaces pour Room
-keep interface androidx.room.** { *; }

# Conserver les annotations
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# Optimisations pour les coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Optimisations pour OkHttp
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# Conserver les noms des classes pour la sérialisation
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}
