use rusqlite::{params, Connection};
use std::path::Path;
use std::sync::{Arc, Mutex};
use vibe_core::error::{Result, VibeError};
use vibe_core::model::Track;

#[derive(Clone)]
pub struct Database {
    conn: Arc<Mutex<Connection>>,
}

impl Database {
    pub fn open<P: AsRef<Path>>(path: P) -> Result<Self> {
        let conn = Connection::open(path).map_err(|e| VibeError::Database(e.to_string()))?;
        let db = Self {
            conn: Arc::new(Mutex::new(conn)),
        };
        db.init_schema()?;
        Ok(db)
    }

    pub fn in_memory() -> Result<Self> {
        let conn = Connection::open_in_memory().map_err(|e| VibeError::Database(e.to_string()))?;
        let db = Self {
            conn: Arc::new(Mutex::new(conn)),
        };
        db.init_schema()?;
        Ok(db)
    }

    fn init_schema(&self) -> Result<()> {
        let conn = self.conn.lock().map_err(|_| VibeError::Database("Lock poisoned".into()))?;
        conn.execute_batch(
            r#"
            CREATE TABLE IF NOT EXISTS favorites (
                id TEXT PRIMARY KEY,
                track_json TEXT NOT NULL,
                added_at INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                track_id TEXT NOT NULL,
                track_json TEXT NOT NULL,
                played_at INTEGER NOT NULL,
                duration_listened_secs REAL NOT NULL
            );

            CREATE TABLE IF NOT EXISTS track_features (
                track_id TEXT PRIMARY KEY,
                bpm REAL,
                key TEXT,
                energy REAL,
                loudness REAL,
                analyzed_at INTEGER NOT NULL
            );
            "#,
        )
        .map_err(|e| VibeError::Database(e.to_string()))?;
        Ok(())
    }

    pub fn save_favorite(&self, track: &Track) -> Result<()> {
        let conn = self.conn.lock().map_err(|_| VibeError::Database("Lock poisoned".into()))?;
        let json = serde_json::to_string(track).map_err(|e| VibeError::Database(e.to_string()))?;
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs() as i64;

        conn.execute(
            "INSERT OR REPLACE INTO favorites (id, track_json, added_at) VALUES (?1, ?2, ?3)",
            params![track.id, json, now],
        )
        .map_err(|e| VibeError::Database(e.to_string()))?;
        Ok(())
    }

    pub fn remove_favorite(&self, track_id: &str) -> Result<()> {
        let conn = self.conn.lock().map_err(|_| VibeError::Database("Lock poisoned".into()))?;
        conn.execute("DELETE FROM favorites WHERE id = ?1", params![track_id])
            .map_err(|e| VibeError::Database(e.to_string()))?;
        Ok(())
    }

    pub fn is_favorite(&self, track_id: &str) -> Result<bool> {
        let conn = self.conn.lock().map_err(|_| VibeError::Database("Lock poisoned".into()))?;
        let count: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM favorites WHERE id = ?1",
                params![track_id],
                |row| row.get(0),
            )
            .map_err(|e| VibeError::Database(e.to_string()))?;
        Ok(count > 0)
    }

    pub fn get_favorites(&self) -> Result<Vec<Track>> {
        let conn = self.conn.lock().map_err(|_| VibeError::Database("Lock poisoned".into()))?;
        let mut stmt = conn
            .prepare("SELECT track_json FROM favorites ORDER BY added_at DESC")
            .map_err(|e| VibeError::Database(e.to_string()))?;

        let rows = stmt
            .query_map([], |row| {
                let json: String = row.get(0)?;
                Ok(json)
            })
            .map_err(|e| VibeError::Database(e.to_string()))?;

        let mut tracks = Vec::new();
        for row in rows {
            let json = row.map_err(|e| VibeError::Database(e.to_string()))?;
            let track: Track =
                serde_json::from_str(&json).map_err(|e| VibeError::Database(e.to_string()))?;
            tracks.push(track);
        }
        Ok(tracks)
    }

    pub fn log_history(&self, track: &Track, duration_listened_secs: f64) -> Result<()> {
        let conn = self.conn.lock().map_err(|_| VibeError::Database("Lock poisoned".into()))?;
        let json = serde_json::to_string(track).map_err(|e| VibeError::Database(e.to_string()))?;
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs() as i64;

        conn.execute(
            "INSERT INTO history (track_id, track_json, played_at, duration_listened_secs) VALUES (?1, ?2, ?3, ?4)",
            params![track.id, json, now, duration_listened_secs],
        )
        .map_err(|e| VibeError::Database(e.to_string()))?;
        Ok(())
    }

    pub fn get_recent_history(&self, limit: usize) -> Result<Vec<Track>> {
        let conn = self.conn.lock().map_err(|_| VibeError::Database("Lock poisoned".into()))?;
        let mut stmt = conn
            .prepare("SELECT track_json FROM history ORDER BY played_at DESC LIMIT ?1")
            .map_err(|e| VibeError::Database(e.to_string()))?;

        let rows = stmt
            .query_map(params![limit as i64], |row| {
                let json: String = row.get(0)?;
                Ok(json)
            })
            .map_err(|e| VibeError::Database(e.to_string()))?;

        let mut tracks = Vec::new();
        for row in rows {
            let json = row.map_err(|e| VibeError::Database(e.to_string()))?;
            let track: Track =
                serde_json::from_str(&json).map_err(|e| VibeError::Database(e.to_string()))?;
            tracks.push(track);
        }
        Ok(tracks)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use vibe_core::model::{Artist, ProviderType};

    #[test]
    fn test_favorites_crud() {
        let db = Database::in_memory().expect("In memory db");
        let track = Track {
            id: "track-1".into(),
            title: "Song 1".into(),
            artist: "Artist 1".into(),
            artists: vec![Artist {
                id: "art-1".into(),
                name: "Artist 1".into(),
            }],
            album: None,
            duration_secs: 200,
            thumbnail_url: None,
            provider: ProviderType::LocalLibrary,
            uri: None,
        };

        assert!(!db.is_favorite("track-1").unwrap());
        db.save_favorite(&track).unwrap();
        assert!(db.is_favorite("track-1").unwrap());

        let favs = db.get_favorites().unwrap();
        assert_eq!(favs.len(), 1);
        assert_eq!(favs[0].title, "Song 1");

        db.remove_favorite("track-1").unwrap();
        assert!(!db.is_favorite("track-1").unwrap());
    }

    #[test]
    fn test_history_logging() {
        let db = Database::in_memory().expect("In memory db");
        let track = Track {
            id: "track-hist".into(),
            title: "Recent Play".into(),
            artist: "Artist".into(),
            artists: vec![],
            album: None,
            duration_secs: 150,
            thumbnail_url: None,
            provider: ProviderType::YouTubeMusic,
            uri: None,
        };

        db.log_history(&track, 120.0).unwrap();
        let history = db.get_recent_history(10).unwrap();
        assert_eq!(history.len(), 1);
        assert_eq!(history[0].id, "track-hist");
    }
}
