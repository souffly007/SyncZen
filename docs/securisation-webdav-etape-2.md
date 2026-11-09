# SyncZen — sécurisation HTTPS de WebDAV, étape 2

Validation locale du 9 octobre 2026. Aucun véritable NAS, aucun identifiant personnel,
aucune installation sur smartphone. Serveurs de test exclusivement liés à l'adresse
loopback 127.0.0.1. Aucun lancement de l'application ni de SyncWorker pendant les tests.

## Modifications de cette étape

- `app/src/main/java/fr/bonobo/synczen/data/WebDavSecurity.kt` (ajout) : URL HTTPS
  obligatoire ; rejet des identifiants intégrés à l'URL ; politique de redirections.
- `app/src/main/java/fr/bonobo/synczen/data/NasRepository.kt` : validation des URL
  WebDAV avant création de l'en-tête Basic et avant lecture du contenu à envoyer ;
  client OkHttp avec followRedirects=false et followSslRedirects=false ; refus explicite
  des réponses 3xx ; messages explicites en cas d'erreur de certificat/nom d'hôte.
  Constructeur interne avec injection du client pour les tests locaux TLS.
- `app/src/main/res/xml/network_security_config.xml` : cleartextTrafficPermitted=false
  globalement ; autorités système conservées, aucune exception de domaine ou de debug.
- `app/src/main/AndroidManifest.xml` : usesCleartextTraffic=false explicite.
- `app/build.gradle.kts` : MockWebServer et okhttp-tls ajoutés uniquement aux tests.
- `app/src/test/java/fr/bonobo/synczen/data/WebDavSecurityTest.kt` (ajout) : contrôles
  HTTP/HTTPS, Basic, redirections et validation TLS sur serveurs fictifs locaux.
- `app/src/test/java/fr/bonobo/synczen/data/CredentialMigrationTest.kt` : cas supplémentaire
  de configuration HTTP historique, conservée par migration mais bloquée à l'utilisation.
- `app/src/androidTest/java/fr/bonobo/synczen/data/AndroidNetworkSecurityTest.kt` (ajout) :
  vérification de la politique Android globale, sans connexion réseau.
- Ce compte rendu.

## Comportement

Les tests de connexion PROPFIND et les envois PUT exigent HTTPS. Une adresse HTTP
est refusée avant création du client et de l'en-tête Basic ; l'envoi refuse aussi de
lire le flux de fichier. L'adresse stockée n'est pas convertie, effacée ou réenregistrée.

Message d'une configuration HTTP :

> WebDAV bloqué : HTTPS obligatoire. Configuration conservée ; renseignez une adresse HTTPS pour utiliser cette destination.

Toutes les redirections 3xx sont refusées, y compris celles vers la même origine.
L'utilisateur doit renseigner directement l'adresse HTTPS finale. Le champ Location
n'est pas suivi et n'est pas reproduit dans le message d'erreur.

La production utilise les paramètres TLS normaux d'OkHttp et les autorités système
Android. Aucun TrustManager permissif, aucune désactivation du contrôle du nom d'hôte,
aucune autorité de test et aucune exception pour certificat autosigné ne sont ajoutés
à l'application. Les certificats et autorités fictives existent uniquement dans les tests.

La recherche dans les sources ne trouve comme usages applicatifs HTTP que WebDAV
(PROPFIND et PUT). SMB passe par les sockets JCIFS. Comparaison avec le fichier Git
initial : branches SMB, smbContext et normalizeSmbAddress identiques. Aucun test SMB
réseau réalisé. L'interdiction HTTP Android ne constitue pas une garantie de chiffrement SMB.

## Résultats

Commande avec JDK 17 :

```sh
JAVA_HOME=/var/home/Snoopy63/.jdks/ms-17.0.20 ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

BUILD SUCCESSFUL. Compilations Debug et Release réussies, contrôles lintVital Release
réussis, APK de tests Android compilé. Le fichier release reste non signé.

20 tests locaux réussis, aucun échec ou erreur :

- 3 tests AES-GCM de l'étape 1 ;
- 6 tests Room/Robolectric, dont les 5 tests de migration/protection de l'étape 1 et
  le nouveau contrôle de conservation d'une adresse HTTP historique ;
- 3 tests NasConfig existants ;
- 8 tests WebDAV : HTTP refusé sans requête ni création de client, PUT HTTP sans
  lecture du flux, succès HTTPS PROPFIND/PUT avec identifiants fictifs, redirections
  refusées, certificat non approuvé refusé, mauvais nom d'hôte refusé, URL invalides
  ou contenant des identifiants refusées, conservation des composants TLS standards.

Le test de redirections couvre 30 cas : 301/302/303/307/308 × même origine HTTPS,
autre origine HTTPS ou destination HTTP × PROPFIND/PUT. Seule la destination initiale
reçoit une requête. Les autres serveurs reçoivent zéro requête.

Contrôles supplémentaires : git diff --check sans erreur ; manifeste fusionné Debug
et Release avec usesCleartextTraffic=false et bonne référence XML ; XML réseau sans
exception de domaine/debug et avec confiance système uniquement ; code SMB inchangé.
Avertissement Gradle existant relatif à des fonctions dépréciées avant Gradle 10.

## Limites et suite

Les tests Android Keystore réel et NetworkSecurityPolicy sont compilés mais non exécutés,
faute d'émulateur avec image système dans cet environnement. Leur validation sur un
émulateur isolé reste à faire avant toute installation sur le smartphone. Aucune
validation d'un certificat ou d'une configuration du véritable NAS n'a été tentée.

Les configurations HTTP restent consultables et enregistrées, mais leurs opérations
WebDAV échouent jusqu'à saisie explicite d'une adresse HTTPS valide. Un serveur qui
impose une redirection devra être configuré avec son URL HTTPS finale.

Les corrections de chiffrement/migration de l'étape 1 sont préservées et leurs tests
repassent. Aucun travail sur collisions, écrasements, publication des fichiers ou
streaming : ces points restent pour les étapes suivantes.

Arrêt après l'étape 2 pour validation de l'utilisateur.
