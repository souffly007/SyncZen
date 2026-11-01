package fr.bonobo.synczen.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration de la version 3 à 4 : ajout du champ conflictResolutionStrategy.
 * Valeur par défaut : SKIP (comportement existant)
 */
class ConflictResolutionMigration : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Ajouter la nouvelle colonne avec une valeur par défaut
        db.execSQL("""
            ALTER TABLE nas_config 
            ADD COLUMN conflictResolutionStrategy TEXT NOT NULL DEFAULT 'SKIP'
        """)
    }
}
