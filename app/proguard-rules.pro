# Règles ProGuard/R8 spécifiques à SyncZen.
# JCIFS-NG et OkHttp fonctionnent bien avec les règles par défaut d'Android Gradle Plugin.
# Ajouter ici des -keep si des classes sont supprimées par erreur en build release.
-keep class jcifs.** { *; }
-dontwarn jcifs.**
