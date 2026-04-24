-- Create a table
CREATE TABLE IF NOT EXISTS training_data (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    raw_sql TEXT UNIQUE,      -- Store the original SQL query(Shoud be unique, not repeated)
    feature_vector TEXT NOT NULL, -- Store the extracted features as a vector
    label INTEGER DEFAULT 0,    -- Label for classification (0: benign, 1: malicious)
    is_trained INTEGER DEFAULT 0, -- Flag to determine incremental training
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP -- Timestamp for record creation
);
-- construct index to accelerate incremental scanning
CREATE INDEX IF NOT EXISTS idx_is_trained ON training_data(is_trained);


