# SyncZen — sécurisation des identifiants NAS, étape 1

Validation locale du 9 octobre 2026. Cette étape ne modifie ni le transport WebDAV/SMB,
ni les chemins distants, ni les transferts. Aucun NAS réel, identifiant personnel ou
smartphone utilisé. Aucun test ne lance SyncWorker ou NasRepository.

## Implémentation

- AES-256-GCM, tag de 128 bits, IV aléatoire de 12 octets par chiffrement.
- Clé Android Keystore : `synczen.nas.credentials.v1`. Aucune clé exportée ou stockée dans Room.
- Enveloppe `v1:` + Base64(IV + ciphertext + tag), authentifiée avec l'identifiant de ligne
  et le nom du champ. Les deux champs (utilisateur et mot de passe) sont chiffrés,
  y compris lorsqu'ils sont vides.
- Le déchiffrement ne crée jamais de clé manquante. Toute erreur est propagée ;
  les messages applicatifs ne contiennent pas les secrets ni les détails des exceptions.
- `NasConfig` reste un modèle en mémoire. `StoredNasConfig` est la seule entité de
  configuration Room ; `NasConfigRepository` constitue la frontière de chiffrement.
- Toute sauvegarde vérifie le déchiffrement de la configuration existante dans la
  même transaction. Une configuration illisible ne peut pas être remplacée par le
  formulaire vide. La dernière date de synchronisation stockée est conservée.
- L'interface affiche une erreur ; le worker s'arrête avant toute opération réseau
  si la lecture sécurisée échoue. Les mécanismes de synchronisation restent inchangés.

## Migration

Migration explicite Room 1 → 2, transactionnelle, sans fallback destructif.
La table de remplacement reçoit toutes les lignes et tous les champs existants.
Chaque paire d'identifiants est chiffrée puis relue avant remplacement de la table
ancienne. Une erreur annule la transaction ; la base version 1 reste réutilisable.
La table des journaux n'est pas modifiée.

`secure_delete` est activé pendant la migration. Un checkpoint SQLite avec troncature
WAL est exigé à l'ouverture avant livraison de la configuration. Un checkpoint occupé
provoque une erreur explicite sans effacement de données. Ces mesures ne garantissent
pas l'effacement forensique des anciennes copies, des anciennes pages libres ou des
blocs physiques d'un stockage flash.

Les exclusions de sauvegarde Android existantes pour `synczen.db`, WAL et SHM sont
conservées. La clé Keystore n'est pas portable vers une autre installation/appareil.

## Fichiers

Modifiés :

- `app/build.gradle.kts` : export du schéma Room et dépendances/tests locaux et Android.
- `app/src/main/java/fr/bonobo/synczen/data/AppDatabase.kt` : version 2, migration et nettoyage WAL.
- `app/src/main/java/fr/bonobo/synczen/data/NasConfig.kt` : modèle mémoire uniquement.
- `app/src/main/java/fr/bonobo/synczen/data/NasConfigDao.kt` : entité chiffrée.
- `app/src/main/java/fr/bonobo/synczen/ui/SyncViewModel.kt` : dépôt sécurisé et erreurs explicites.
- `app/src/main/java/fr/bonobo/synczen/ui/SyncScreen.kt` : conservation du SSID enregistré dans le formulaire.
- `app/src/main/java/fr/bonobo/synczen/worker/SyncWorker.kt` : lecture sécurisée et arrêt en cas d'erreur.

Ajoutés :

- `app/src/main/java/fr/bonobo/synczen/data/CredentialCipher.kt`
- `app/src/main/java/fr/bonobo/synczen/data/CredentialMigration.kt`
- `app/src/main/java/fr/bonobo/synczen/data/NasConfigRepository.kt`
- `app/src/main/java/fr/bonobo/synczen/data/StoredNasConfig.kt`
- `app/schemas/fr.bonobo.synczen.data.AppDatabase/2.json`
- `app/src/test/java/fr/bonobo/synczen/data/CredentialCipherTest.kt`
- `app/src/test/java/fr/bonobo/synczen/data/CredentialMigrationTest.kt`
- `app/src/androidTest/java/fr/bonobo/synczen/data/AndroidKeystoreCredentialsTest.kt`
- Ce compte rendu.

## Vérification locale

Commande finale (JDK 17) :

```sh
JAVA_HOME=/var/home/Snoopy63/.jdks/ms-17.0.20 ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --offline
```

Résultat : BUILD SUCCESSFUL. 11 tests réussis, aucun échec ni test ignoré.

- 3 tests AES-GCM : Unicode et valeurs vides, IV distincts, altération, mauvaise clé,
  mauvais champ/identifiant, enveloppes invalides, absence de création de clé au déchiffrement.
- 5 tests Room/Robolectric : base v1 synthétique avec le schéma et l'identité Room
  de l'ancien code généré ; migration et validation du schéma v2, conservation des
  paramètres/SAF/dates/journaux, lignes supplémentaires et champs nuls/vides,
  réouverture, rollback après un échec sur la deuxième ligne et reprise,
  refus de remplacement après clé manquante ou contenu corrompu,
  conservation d'une date plus récente, table de configuration initialement vide.
  Un contrôle des fichiers SQLite et WAL vérifie l'absence des identifiants fictifs
  en clair après migration.
- 3 tests NasConfig existants.

Le premier passage avait échoué sur l'appel du PRAGMA secure_delete avec execSQL.
L'appel utilise désormais query, et tous les tests ont été relancés avec succès.
Les avertissements de dépréciation Gradle et NetworkUtils sont indépendants de cette étape.
La compilation release inclut les contrôles lintVital et produit un APK non signé.

Le test Android Keystore est compilé et empaqueté, mais NON EXÉCUTÉ : aucun émulateur
avec image système n'est disponible. Les tests locaux utilisent une clé AES synthétique
JCE et le moteur SQLite natif de Robolectric, pas le Keystore Android réel.

## Avant une mise à jour du smartphone

1. Exécuter d'abord le test Android Keystore sur un émulateur isolé, puis une migration
   v1 → v2 utilisant le vrai Keystore avec des données entièrement fictives. Cette
   validation Android reste à faire avant de considérer la version prête à installer.
2. Préparer un APK signé avec le même certificat que l'installation existante,
   conserver l'applicationId et incrémenter le versionCode pour la version publiée.
   Ne pas utiliser l'APK debug pour remplacer une installation release.
3. Mettre à jour en place : ne pas désinstaller, ne pas effacer les données.
   Une désinstallation peut supprimer la configuration et la clé Keystore.
4. Effectuer le premier démarrage hors réseau (mode avion, Wi-Fi coupé) pour vérifier
   la migration et l'affichage des paramètres sans permettre aux tâches existantes
   d'accéder au NAS. Vérifier les dossiers SAF, paramètres, dates et journaux.
5. Conserver une possibilité indépendante de récupération des paramètres. La sauvegarde
   Android automatique exclut déjà cette base et ne constitue pas un retour arrière.
   Une base chiffrée seule ne suffit pas sans sa clé ; ne pas transmettre les identifiants.
6. Ne pas revenir simplement à l'ancienne application après migration : elle attend
   un schéma version 1 et ne sait pas lire la version 2. En cas d'erreur, conserver
   les données, ne pas réinitialiser et diagnostiquer avant toute intervention.

Les risques HTTPS, d'écrasement et de mémoire restent ceux identifiés à l'audit.
Arrêt après l'étape 1 ; aucune étape suivante engagée.
