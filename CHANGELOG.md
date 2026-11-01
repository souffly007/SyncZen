# Changelog

Toutes les modifications notables de SyncZen seront documentées dans ce fichier.

Le format est basé sur [Keep a Changelog](https://keepachangelog.com/fr-FR/1.0.0/),
et ce projet respecte [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Non publié]

### ✨ Ajouté
- Notifications foreground pendant la synchronisation avec progression
- Filtrage par SSID Wi-Fi (optionnel) pour restreindre la synchronisation à un réseau spécifique
- Stratégie de résolution des conflits configurables :
  - **SKIP** (par défaut) : Ignorer les fichiers existants
  - **OVERWRITE** : Écraser les fichiers existants
  - **RENAME** : Renommer les nouveaux fichiers avec un timestamp
- Champ SSID dans la configuration pour le filtrage réseau

### 🔒 Sécurité
- Activation de la minification (ProGuard/R8) pour les builds de production
- Configuration améliorée de ProGuard avec conservation des classes essentielles
- Chiffrement des credentials déjà implémenté (AES-256-GCM avec Android Keystore)

### 🛠️ Modifié
- Version de la base de données passée à v4 pour supporter la stratégie de résolution des conflits
- Migration automatique de la base de données existante
- Amélioration des messages de notification

### 📝 Documentation
- Création de ce fichier CHANGELOG.md
- Mise à jour du README.md avec les nouvelles fonctionnalités

### 🧪 Tests
- Tests existants conservés et compatibles avec les nouvelles fonctionnalités

## [0.2.5] - 2024-09-23

### ✨ Ajouté
- Chiffrement AES-256-GCM des identifiants NAS avec Android Keystore
- Migration automatique des configurations existantes (v1 → v2)
- Stockage sécurisé avec nettoyage après migration
- Journal des synchronisations (50 dernières entrées)
- Interface utilisateur rafraîchie avec palette bleu indigo
- Nouvelle icône serveur et illustration de fond

### 🛠️ Modifié
- Sélection de dossiers via Storage Access Framework (SAF)
- Compatibilité avec le stockage scopé (API 29+)
- Remplacement de `usesCleartextTraffic` par `network_security_config.xml`
- Exclusion de la base de données des sauvegardes cloud/transfert d'appareil

### 📦 Dépendances
- OkHttp 4.12.0 pour WebDAV
- JCIFS-NG 2.1.10 pour SMB
- Room 2.6.1 pour la base de données
- WorkManager 2.9.1 pour les tâches en arrière-plan

### 📄 Licence
- Licence GPL v3

## [0.1.0] - 2024-09-04

### ✨ Première version
- Synchronisation automatique vers NAS/serveurs locaux
- Support SMB (JCIFS-NG) et WebDAV (OkHttp)
- Architecture MVVM avec Jetpack Compose
- Sélection de dossiers locaux
- Configuration du serveur (adresse, identifiants)
- Synchronisation périodique via WorkManager
- Synchronisation manuelle
- Journalisation des opérations

---

*Les versions avant 0.1.0 étaient des prototypes internes.*
